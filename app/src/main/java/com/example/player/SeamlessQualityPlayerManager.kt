package com.example.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface QualitySwitchState {
    data object Idle : QualitySwitchState
    data class PreBuffering(val targetQuality: String) : QualitySwitchState
    data class Swapped(val activeQuality: String) : QualitySwitchState
}

/**
 * Dual-player hot-swap manager for zero-interruption quality switching,
 * DASH TrackSelector live switching, automatic error failover,
 * and a 9-second stall watchdog (alternate codec -> re-extract -> progressive 360p fallback).
 */
@OptIn(UnstableApi::class)
class SeamlessQualityPlayerManager(
    private val playerEngine: StreamPlayerEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _activePlayer = MutableStateFlow(playerEngine.createFastPlayer(repeatOne = false))
    val activePlayer: StateFlow<ExoPlayer> = _activePlayer.asStateFlow()

    private val _switchState = MutableStateFlow<QualitySwitchState>(QualitySwitchState.Idle)
    val switchState: StateFlow<QualitySwitchState> = _switchState.asStateFlow()

    var onRequestReExtract: ((videoId: String, resumePositionMs: Long) -> Unit)? = null

    private var standbyPlayer: ExoPlayer? = null
    private var switchJob: Job? = null
    private var watchdogJob: Job? = null

    private var currentVideoId: String = ""
    private var currentCandidates: List<MediaSource> = emptyList()
    private var currentCandidateIndex: Int = 0
    private var lastPlaybackSpeed: Float = 1.0f
    private var isDashManifestActive: Boolean = false
    private var isPlayingOffline: Boolean = false
    private var hasAttemptedReExtract: Boolean = false

    private val errorFallbackListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player error on candidate #$currentCandidateIndex: ${error.errorCodeName}")
            tryNextCandidateOrReExtract()
        }
    }

    init {
        _activePlayer.value.addListener(errorFallbackListener)
        startStallWatchdog()
    }

    fun playInstant360p(
        formatInfo: MediaItemFormatInfo,
        speed: Float = 1.0f,
        startPositionMs: Long = 0L,
        isReExtracted: Boolean = false
    ) {
        cancelPendingSwitch()
        _switchState.value = QualitySwitchState.Idle
        isPlayingOffline = false
        currentVideoId = formatInfo.videoId
        lastPlaybackSpeed = speed
        if (!isReExtracted) {
            hasAttemptedReExtract = false
        }

        playerEngine.activePlaybackVideoId = formatInfo.videoId
        currentCandidates = playerEngine.buildCandidateMediaSources(formatInfo, "360p")
        currentCandidateIndex = 0
        val source = currentCandidates.firstOrNull() ?: return
        isDashManifestActive = source.javaClass.simpleName.contains("Dash", ignoreCase = true)

        val player = _activePlayer.value
        if (isDashManifestActive) {
            playerEngine.applyTrackSelectorQuality(player, "360p")
        } else {
            playerEngine.applyTrackSelectorQuality(player, "Auto")
        }
        player.setMediaSource(source)
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.playbackParameters = PlaybackParameters(speed)
        player.playWhenReady = true
        player.prepare()
    }

    fun playOfflineFile(
        localPath: String,
        speed: Float = 1.0f
    ) {
        cancelPendingSwitch()
        _switchState.value = QualitySwitchState.Idle
        isPlayingOffline = true
        isDashManifestActive = false
        currentVideoId = ""
        lastPlaybackSpeed = speed
        currentCandidates = emptyList()
        currentCandidateIndex = 0
        playerEngine.prepareLocalFile(
            player = _activePlayer.value,
            localUri = localPath,
            startPositionMs = 0L,
            speed = speed
        )
    }

    fun switchQualitySeamlessly(
        formatInfo: MediaItemFormatInfo,
        targetQuality: String,
        speed: Float
    ) {
        val currentPlayer = _activePlayer.value
        lastPlaybackSpeed = speed

        // If DASH MPD is currently active and contains the requested resolution, switch seamlessly via TrackSelector
        if (isDashManifestActive && playerEngine.applyTrackSelectorQuality(currentPlayer, targetQuality)) {
            scope.launch {
                _switchState.value = QualitySwitchState.Swapped(targetQuality)
                delay(1600L)
                if (_switchState.value is QualitySwitchState.Swapped) {
                    _switchState.value = QualitySwitchState.Idle
                }
            }
            return
        }

        val candidates = playerEngine.buildCandidateMediaSources(formatInfo, targetQuality)
        val firstSource = candidates.firstOrNull() ?: return

        cancelPendingSwitch()

        var standbyCandidateIndex = 0
        var standbyExhausted = false
        val candidate = playerEngine.createFastPlayer(repeatOne = false).apply {
            volume = 0f
            playWhenReady = false
        }
        val standbyErrorListener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val next = standbyCandidateIndex + 1
                if (next < candidates.size) {
                    standbyCandidateIndex = next
                    val pos = currentPlayer.currentPosition.coerceAtLeast(0L)
                    candidate.setMediaSource(candidates[next])
                    candidate.seekTo(pos)
                    candidate.prepare()
                } else {
                    standbyExhausted = true
                }
            }
        }
        candidate.addListener(standbyErrorListener)
        standbyPlayer = candidate

        val initialAnchorMs = currentPlayer.currentPosition.coerceAtLeast(0L)
        candidate.setMediaSource(firstSource)
        if (initialAnchorMs > 0L) {
            candidate.seekTo(initialAnchorMs)
        }
        candidate.prepare()

        switchJob = scope.launch {
            _switchState.value = QualitySwitchState.PreBuffering(targetQuality)
            val startWaitMs = System.currentTimeMillis()
            var swapped = false

            while (System.currentTimeMillis() - startWaitMs <= MAX_PREBUFFER_WAIT_MS) {
                if (standbyExhausted) break
                val ready = candidate.playbackState == Player.STATE_READY
                val bufferedAheadMs = (candidate.bufferedPosition - candidate.currentPosition).coerceAtLeast(0L)
                val elapsedMs = System.currentTimeMillis() - startWaitMs

                if (ready && bufferedAheadMs >= MIN_BUFFER_AHEAD_MS && elapsedMs >= 250L) {
                    candidate.removeListener(standbyErrorListener)
                    performAtomicHotSwap(
                        oldPlayer = currentPlayer,
                        newPlayer = candidate,
                        newCandidates = candidates,
                        candidateIndex = standbyCandidateIndex,
                        targetQuality = targetQuality,
                        speed = speed
                    )
                    swapped = true
                    break
                }
                delay(100L)
            }

            if (!swapped) {
                candidate.removeListener(standbyErrorListener)
                if (candidate.playbackState == Player.STATE_READY) {
                    performAtomicHotSwap(
                        oldPlayer = currentPlayer,
                        newPlayer = candidate,
                        newCandidates = candidates,
                        candidateIndex = standbyCandidateIndex,
                        targetQuality = targetQuality,
                        speed = speed
                    )
                } else {
                    candidate.release()
                    standbyPlayer = null
                    val applyIdx = if (!standbyExhausted) standbyCandidateIndex else 0
                    val chosen = candidates.getOrNull(applyIdx) ?: firstSource
                    val exactPos = currentPlayer.currentPosition.coerceAtLeast(0L)
                    val shouldPlay = currentPlayer.playWhenReady || currentPlayer.isPlaying
                    currentCandidates = candidates
                    currentCandidateIndex = applyIdx
                    isDashManifestActive = chosen.javaClass.simpleName.contains("Dash", ignoreCase = true)
                    if (isDashManifestActive) {
                        playerEngine.applyTrackSelectorQuality(currentPlayer, targetQuality)
                    }
                    currentPlayer.setMediaSource(chosen)
                    currentPlayer.seekTo(exactPos)
                    currentPlayer.playbackParameters = PlaybackParameters(speed)
                    currentPlayer.playWhenReady = shouldPlay
                    currentPlayer.prepare()
                    _switchState.value = QualitySwitchState.Swapped(targetQuality)
                    delay(1200L)
                    if (_switchState.value is QualitySwitchState.Swapped) {
                        _switchState.value = QualitySwitchState.Idle
                    }
                }
            }
        }
    }

    private suspend fun performAtomicHotSwap(
        oldPlayer: ExoPlayer,
        newPlayer: ExoPlayer,
        newCandidates: List<MediaSource>,
        candidateIndex: Int,
        targetQuality: String,
        speed: Float
    ) {
        val oldPos = oldPlayer.currentPosition.coerceAtLeast(0L)
        val shouldPlay = oldPlayer.playWhenReady || oldPlayer.isPlaying

        oldPlayer.removeListener(errorFallbackListener)
        newPlayer.addListener(errorFallbackListener)
        currentCandidates = newCandidates
        currentCandidateIndex = candidateIndex
        isDashManifestActive = newCandidates.getOrNull(candidateIndex)?.javaClass?.simpleName?.contains("Dash", ignoreCase = true) == true

        // Never seek backwards and discard already-buffered media; only catch up forward if needed
        if (oldPos > newPlayer.currentPosition + 800L) {
            newPlayer.seekTo(oldPos)
        }
        newPlayer.playbackParameters = PlaybackParameters(speed)
        newPlayer.volume = 1f
        newPlayer.playWhenReady = shouldPlay

        oldPlayer.clearVideoSurface()
        _activePlayer.value = newPlayer
        standbyPlayer = null
        _switchState.value = QualitySwitchState.Swapped(targetQuality)

        delay(120L)
        oldPlayer.pause()
        oldPlayer.release()

        delay(1200L)
        if (_switchState.value is QualitySwitchState.Swapped) {
            _switchState.value = QualitySwitchState.Idle
        }
    }

    /**
     * 9-Second Stall Watchdog:
     * If playback remains stuck in STATE_BUFFERING with zero position progress for 9s,
     * automatically switches to alternate codec candidate -> re-extracts stream -> progressive 360p fallback.
     */
    private fun startStallWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var lastPositionMs = -1L
            var stalledSinceMs = 0L

            while (isActive) {
                delay(500L)
                val player = _activePlayer.value
                if (isPlayingOffline || currentVideoId.isBlank() || !player.playWhenReady) {
                    stalledSinceMs = 0L
                    lastPositionMs = player.currentPosition
                    continue
                }

                val curPos = player.currentPosition
                val isStalledBuffering = player.playbackState == Player.STATE_BUFFERING && curPos == lastPositionMs

                if (isStalledBuffering) {
                    if (stalledSinceMs == 0L) {
                        stalledSinceMs = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - stalledSinceMs >= STALL_WATCHDOG_MS) {
                        Log.w(TAG, "9s stall watchdog triggered at ${curPos}ms for $currentVideoId")
                        stalledSinceMs = 0L
                        tryNextCandidateOrReExtract()
                    }
                } else {
                    stalledSinceMs = 0L
                    lastPositionMs = curPos
                }
            }
        }
    }

    private fun tryNextCandidateOrReExtract() {
        val player = _activePlayer.value
        val resumePos = player.currentPosition.coerceAtLeast(0L)
        val nextIdx = currentCandidateIndex + 1

        if (nextIdx < currentCandidates.size) {
            currentCandidateIndex = nextIdx
            val nextSource = currentCandidates[nextIdx]
            isDashManifestActive = nextSource.javaClass.simpleName.contains("Dash", ignoreCase = true)
            Log.w(TAG, "Fallback to candidate #$nextIdx (${nextSource.javaClass.simpleName}) at ${resumePos}ms")
            player.setMediaSource(nextSource)
            if (resumePos > 0L) player.seekTo(resumePos)
            player.playbackParameters = PlaybackParameters(lastPlaybackSpeed)
            player.playWhenReady = true
            player.prepare()
            return
        }

        if (!hasAttemptedReExtract && currentVideoId.isNotBlank()) {
            hasAttemptedReExtract = true
            Log.w(TAG, "Exhausted candidates for $currentVideoId; triggering stream re-extraction")
            onRequestReExtract?.invoke(currentVideoId, resumePos)
        }
    }

    fun cancelPendingSwitch() {
        switchJob?.cancel()
        switchJob = null
        standbyPlayer?.release()
        standbyPlayer = null
    }

    fun releaseAll() {
        watchdogJob?.cancel()
        cancelPendingSwitch()
        _activePlayer.value.removeListener(errorFallbackListener)
        _activePlayer.value.release()
    }

    companion object {
        private const val TAG = "SeamlessQualityMgr"
        private const val STALL_WATCHDOG_MS = 9_000L
        private const val PRE_BUFFER_LEAD_MS = 1_500L
        private const val MIN_BUFFER_AHEAD_MS = 1_000L
        private const val MAX_PREBUFFER_WAIT_MS = 10_000L
    }
}
