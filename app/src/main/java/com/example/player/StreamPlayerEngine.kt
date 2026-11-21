package com.example.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.mediaserviceinterfaces.MediaFormatOption
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import com.example.youtubeapi.InnertubeParser
import com.example.youtubeapi.NetworkStackProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * High-speed Media3 ExoPlayer engine backed by Cronet + matching User-Agent DataSource,
 * speed-adaptive LoadControl (0.8s startup + aggressive forward buffering),
 * hardware-friendly codec priority (avc1 > vp9 > av01), local static DASH MPD generation,
 * and container-matched MergingMediaSource.
 */
@OptIn(UnstableApi::class)
class StreamPlayerEngine(
    context: Context,
    private val networkStack: NetworkStackProvider
) {
    private val appContext = context.applicationContext

    private val databaseProvider by lazy {
        StandaloneDatabaseProvider(appContext)
    }

    val mediaCache: SimpleCache by lazy {
        val cacheDir = File(appContext.cacheDir, "streamtube_media_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(250L * 1024L * 1024L),
            databaseProvider
        )
    }

    private fun createCacheDataSourceFactory(customUserAgent: String?): CacheDataSource.Factory {
        val upstreamFactory = DefaultDataSource.Factory(
            appContext,
            networkStack.createMediaDataSourceFactory(customUserAgent)
        )
        return CacheDataSource.Factory()
            .setCache(mediaCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun createMediaSourceFactory(customUserAgent: String?): DefaultMediaSourceFactory {
        return DefaultMediaSourceFactory(createCacheDataSourceFactory(customUserAgent))
    }

    private val preWarmedVideoIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var activePlaybackVideoId: String = ""

    /**
     * Fast-start LoadControl tuned for instant 1-click playback (500ms startup)
     * without saturating mobile bandwidth with oversized buffer targets.
     */
    fun createSpeedAdaptiveLoadControl(isShort: Boolean = false): DefaultLoadControl {
        val isFastNetwork = isFastUnmeteredConnection()
        val minBuffer = when {
            isShort -> 10_000
            isFastNetwork -> 18_000
            else -> 12_000
        }
        val maxBuffer = when {
            isShort -> 25_000
            isFastNetwork -> 50_000
            else -> 32_000
        }
        val startBuffer = if (isFastNetwork) 500 else 750
        val rebufferMs = if (isFastNetwork) 1_100 else 1_500

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ minBuffer,
                /* maxBufferMs = */ maxBuffer,
                /* bufferForPlaybackMs = */ startBuffer,
                /* bufferForPlaybackAfterRebufferMs = */ rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(if (isShort) 4_000 else 10_000, true)
            .build()
    }

    private fun isFastUnmeteredConnection(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                caps.linkDownstreamBandwidthKbps >= 12_000
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Downloads only a lightweight head slice into SimpleCache so tapping/swiping starts fast
     * without competing with active Watch playback.
     */
    fun preWarmStreamHead(info: MediaItemFormatInfo, isShort: Boolean = false) {
        if (info.videoId.isBlank() || info.videoId == activePlaybackVideoId) return
        // Do not compete for bandwidth on slow mobile links while a long-form video is actively open
        if (!isShort && activePlaybackVideoId.isNotBlank()) return
        if (!preWarmedVideoIds.add(info.videoId)) return

        val target = if (isShort) {
            info.formats.firstOrNull {
                it.isMuxed && !it.isHls && it.isH264Compatible &&
                    InnertubeParser.extractResolutionHeight(it.qualityLabel) in 320..480
            } ?: info.formats.firstOrNull {
                it.isMuxed && !it.isHls && it.url.isNotBlank()
            } ?: info.formats.firstOrNull {
                it.hasVideo && !it.isHls && (it.isMuxed || !it.audioPairUrl.isNullOrBlank()) &&
                    InnertubeParser.extractResolutionHeight(it.qualityLabel) in 320..480
            } ?: info.formats.firstOrNull { it.hasVideo && !it.isHls && it.url.isNotBlank() }
        } else {
            info.formats.firstOrNull { it.isMuxed && !it.isHls && it.isH264Compatible && it.qualityLabel.contains("360") }
                ?: info.formats.firstOrNull { !it.isHls && it.hasVideo && it.isH264Compatible && (it.isMuxed || !it.audioPairUrl.isNullOrBlank()) }
                ?: info.formats.firstOrNull { !it.isHls && it.hasVideo && it.url.isNotBlank() }
        } ?: return

        val cacheDataSource = createCacheDataSourceFactory(info.requestUserAgent).createDataSourceForDownloading()
        val headLength = if (isShort) 128L * 1024L else 96L * 1024L

        runCatching {
            if (!mediaCache.isCached(target.url, 0L, 4096L) && info.videoId != activePlaybackVideoId) {
                val videoSpec = DataSpec.Builder()
                    .setUri(target.url)
                    .setPosition(0L)
                    .setLength(headLength)
                    .build()
                CacheWriter(cacheDataSource, videoSpec, null, null).cache()
            }

            if (!target.isMuxed && !target.audioPairUrl.isNullOrBlank() &&
                !mediaCache.isCached(target.audioPairUrl, 0L, 4096L) &&
                info.videoId != activePlaybackVideoId
            ) {
                val audioSpec = DataSpec.Builder()
                    .setUri(target.audioPairUrl)
                    .setPosition(0L)
                    .setLength(48L * 1024L)
                    .build()
                CacheWriter(cacheDataSource, audioSpec, null, null).cache()
            }
        }
    }

    fun createFastPlayer(repeatOne: Boolean = false): ExoPlayer {
        return buildConfiguredPlayer(createSpeedAdaptiveLoadControl(isShort = false), repeatOne)
    }

    fun createShortsPlayer(): ExoPlayer {
        return buildConfiguredPlayer(createSpeedAdaptiveLoadControl(isShort = true), repeatOne = true)
    }

    private fun buildConfiguredPlayer(loadControl: DefaultLoadControl, repeatOne: Boolean): ExoPlayer {
        val trackSelector = DefaultTrackSelector(appContext).apply {
            parameters = buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .build()
        }

        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(createMediaSourceFactory(null))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                if (repeatOne) {
                    repeatMode = Player.REPEAT_MODE_ONE
                }
            }
    }

    fun prepareLocalFile(
        player: ExoPlayer,
        localUri: String,
        startPositionMs: Long = 0L,
        speed: Float = 1.0f
    ) {
        val uri = if (localUri.startsWith("content://") || localUri.startsWith("file://")) {
            Uri.parse(localUri)
        } else {
            Uri.fromFile(File(localUri))
        }
        player.setMediaItem(Media3MediaItem.fromUri(uri))
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.playbackParameters = PlaybackParameters(speed)
        player.playWhenReady = true
        player.prepare()
    }

    /**
     * Checks if the active DASH tracks contain the requested resolution tier (supporting 16:9,
     * 9:16, and 21:9 aspect ratios), and applies a clean TrackSelector constraint in-place.
     */
    fun applyTrackSelectorQuality(player: ExoPlayer, targetQuality: String): Boolean {
        val selector = player.trackSelector as? DefaultTrackSelector ?: return false
        val height = InnertubeParser.extractResolutionHeight(targetQuality)
        val paramsBuilder = selector.buildUponParameters()
        if (height <= 0) {
            paramsBuilder
                .clearVideoSizeConstraints()
                .setForceHighestSupportedBitrate(false)
            selector.parameters = paramsBuilder.build()
            return true
        }

        val maxW = when {
            height >= 2160 -> 3840
            height >= 1440 -> 2560
            height >= 1080 -> 1920
            height >= 720 -> 1280
            height >= 480 -> 854
            height >= 360 -> 640
            else -> 426
        }

        val hasPreparedTracks = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        if (hasPreparedTracks) {
            val hasMatchingDashTrack = player.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { idx ->
                    if (!group.isTrackSupported(idx)) return@any false
                    val fmt = group.getTrackFormat(idx)
                    val shortEdge = minOf(fmt.width, fmt.height).takeIf { it > 0 } ?: fmt.height
                    val longEdge = maxOf(fmt.width, fmt.height)
                    shortEdge in (height - 80)..(height + 80) ||
                        longEdge in (maxW - 180)..(maxW + 180)
                }
            }
            if (!hasMatchingDashTrack) return false
        }

        paramsBuilder
            .setMinVideoSize((maxW * 0.60).toInt(), (height * 0.45).toInt())
            .setMaxVideoSize(maxW + 16, height + 16)
            .setForceHighestSupportedBitrate(true)
        selector.parameters = paramsBuilder.build()
        return true
    }

    /**
     * Shorts Optimization #2 & #7:
     * 1. Single progressive MP4 (<= 480p / 360p) first (1 HTTP socket = instant start).
     * 2. Any progressive muxed stream <= 480p.
     * 3. Paired adaptive 480p/360p (avc1 > vp9).
     * 4. Lowest progressive fallback (144p/240p) for error recovery.
     * 5. HLS fallback.
     */
    fun buildShortsCandidateMediaSources(info: MediaItemFormatInfo): List<MediaSource> {
        val cacheFactory = createCacheDataSourceFactory(info.requestUserAgent)
        val factory = DefaultMediaSourceFactory(cacheFactory)
        val candidates = mutableListOf<MediaSource>()
        val usedKeys = hashSetOf<String>()

        fun addFormat(opt: MediaFormatOption?) {
            if (opt == null || opt.url.isBlank()) return
            if (!opt.isMuxed && opt.audioPairUrl.isNullOrBlank()) return
            val key = "${opt.itag}_${opt.url.take(64)}"
            if (usedKeys.add(key)) {
                candidates.add(createSourceFromFormat(factory, opt))
            }
        }

        val playable = info.formats.filter {
            it.hasVideo && !it.isHls && it.url.isNotBlank() &&
                (it.isMuxed || !it.audioPairUrl.isNullOrBlank())
        }

        // #2 Priority 1: Single progressive MP4 (<= 480p, preferring 360p/480p itag 18) -> 1 HTTP socket!
        playable.filter { it.isMuxed && it.isH264Compatible && InnertubeParser.extractResolutionHeight(it.qualityLabel) in 240..480 }
            .sortedByDescending { InnertubeParser.extractResolutionHeight(it.qualityLabel) }
            .forEach(::addFormat)

        // Any other muxed progressive stream <= 480p
        playable.filter { it.isMuxed && InnertubeParser.extractResolutionHeight(it.qualityLabel) <= 480 }
            .sortedByDescending { InnertubeParser.extractResolutionHeight(it.qualityLabel) }
            .forEach(::addFormat)

        // Paired adaptive 360p/480p (MergingMediaSource) ranked avc1 > vp9 > av01
        playable.filter { InnertubeParser.extractResolutionHeight(it.qualityLabel) in 240..480 }
            .sortedWith(
                compareByDescending<MediaFormatOption> { it.codecPriority }
                    .thenByDescending { InnertubeParser.extractResolutionHeight(it.qualityLabel) }
            )
            .forEach(::addFormat)

        // #7 Lowest progressive fallback (144p/240p)
        playable.filter { it.isMuxed }
            .sortedBy { InnertubeParser.extractResolutionHeight(it.qualityLabel).takeIf { h -> h > 0 } ?: 9999 }
            .forEach(::addFormat)

        // HLS manifest fallback
        info.hlsManifestUrl?.takeIf { it.isNotBlank() }?.let { hls ->
            if (usedKeys.add("hls_$hls")) {
                candidates.add(factory.createMediaSource(Media3MediaItem.fromUri(hls)))
            }
        }

        // Last resort any playable stream
        playable.sortedWith(
            compareByDescending<MediaFormatOption> { it.codecPriority }
                .thenBy { InnertubeParser.extractResolutionHeight(it.qualityLabel).takeIf { h -> h > 0 } ?: 9999 }
        ).forEach(::addFormat)

        return candidates
    }

    /**
     * Ordered candidate MediaSources for long-form playback.
     * Supports local static DASH MPD XML generation for seamless TrackSelector adaptation,
     * container-matched MergingMediaSource, hardware codec ranking (avc1 > vp9 > av01),
     * and progressive 360p fallback.
     */
    fun buildCandidateMediaSources(
        info: MediaItemFormatInfo,
        preferredQuality: String
    ): List<MediaSource> {
        val cacheFactory = createCacheDataSourceFactory(info.requestUserAgent)
        val factory = DefaultMediaSourceFactory(cacheFactory)
        val candidates = mutableListOf<MediaSource>()
        val usedKeys = hashSetOf<String>()

        fun addFormat(opt: MediaFormatOption?) {
            if (opt == null || opt.url.isBlank()) return
            val key = "${opt.itag}_${opt.url.take(64)}"
            if (usedKeys.add(key)) {
                candidates.add(createSourceFromFormat(factory, opt))
            }
        }

        fun addLocalOrRemoteDashManifest() {
            val localMpdUri = DashMpdGenerator.buildMpdUri(appContext, info)
            if (localMpdUri != null && usedKeys.add("local_dash_${info.videoId}")) {
                candidates.add(
                    DashMediaSource.Factory(cacheFactory)
                        .createMediaSource(Media3MediaItem.fromUri(localMpdUri))
                )
            }
            val remoteDash = info.dashManifestUrl?.takeIf { it.isNotBlank() }
            if (remoteDash != null && usedKeys.add("dash_$remoteDash")) {
                candidates.add(
                    DashMediaSource.Factory(cacheFactory)
                        .createMediaSource(Media3MediaItem.fromUri(remoteDash))
                )
            }
        }

        fun addHlsManifest() {
            val hls = info.hlsManifestUrl?.takeIf { it.isNotBlank() } ?: return
            if (usedKeys.add("hls_$hls")) {
                candidates.add(factory.createMediaSource(Media3MediaItem.fromUri(hls)))
            }
        }

        val withAudio = info.formats.filter {
            it.hasVideo && !it.isHls && it.url.isNotBlank() &&
                (it.isMuxed || !it.audioPairUrl.isNullOrBlank())
        }

        if (preferredQuality == "360p" || preferredQuality == "Fast 360p") {
            // Prefer local static DASH MPD first so initial 360p starts fast AND all quality switches
            // happen instantaneously in-place via TrackSelector without player swaps or re-buffering!
            addLocalOrRemoteDashManifest()
            addFormat(withAudio.firstOrNull { it.isMuxed && it.isH264Compatible && it.qualityLabel.contains("360") })
            addFormat(withAudio.firstOrNull { it.isH264Compatible && it.qualityLabel.contains("360") })
            addFormat(withAudio.firstOrNull {
                it.isMuxed && InnertubeParser.extractResolutionHeight(it.qualityLabel) in 240..480
            })
            addFormat(withAudio.firstOrNull {
                it.isVp9 && InnertubeParser.extractResolutionHeight(it.qualityLabel) in 240..480
            })
            withAudio.filter { it.isMuxed }.forEach(::addFormat)
            withAudio.filter { !it.isAv1 && InnertubeParser.extractResolutionHeight(it.qualityLabel) in 1..480 }
                .sortedByDescending { it.codecPriority }
                .forEach(::addFormat)
            addHlsManifest()
            withAudio.sortedWith(
                compareByDescending<MediaFormatOption> { it.codecPriority }
                    .thenBy { InnertubeParser.extractResolutionHeight(it.qualityLabel).takeIf { h -> h > 0 } ?: 9999 }
            ).forEach(::addFormat)
            return candidates
        }

        if (preferredQuality == "Auto") {
            addLocalOrRemoteDashManifest()
            addHlsManifest()
            withAudio.sortedByDescending { it.codecPriority }.forEach(::addFormat)
            return candidates
        }

        val targetHeight = InnertubeParser.extractResolutionHeight(preferredQuality)
        // Exact resolution ranked by hardware codec: avc1 > vp9 > av01
        withAudio.filter {
            it.qualityLabel.contains(preferredQuality, ignoreCase = true) ||
                (targetHeight > 0 && InnertubeParser.extractResolutionHeight(it.qualityLabel) == targetHeight)
        }
            .sortedWith(
                compareByDescending<MediaFormatOption> { it.isMuxed }
                    .thenByDescending { it.codecPriority }
                    .thenByDescending { it.bitrate }
            )
            .forEach(::addFormat)

        if (targetHeight > 0) {
            withAudio.filter { InnertubeParser.extractResolutionHeight(it.qualityLabel) in 1..targetHeight }
                .sortedWith(
                    compareByDescending<MediaFormatOption> { it.codecPriority }
                        .thenByDescending { InnertubeParser.extractResolutionHeight(it.qualityLabel) }
                )
                .forEach(::addFormat)
        }

        addLocalOrRemoteDashManifest()
        withAudio.filter { it.isMuxed }.forEach(::addFormat)
        addHlsManifest()
        return candidates
    }

    fun buildMediaSource(
        info: MediaItemFormatInfo,
        preferredQuality: String
    ): MediaSource? = buildCandidateMediaSources(info, preferredQuality).firstOrNull()

    private fun createSourceFromFormat(
        factory: DefaultMediaSourceFactory,
        chosen: MediaFormatOption
    ): MediaSource {
        return if (!chosen.isMuxed && !chosen.audioPairUrl.isNullOrBlank()) {
            val videoSource = factory.createMediaSource(Media3MediaItem.fromUri(chosen.url))
            val audioSource = factory.createMediaSource(Media3MediaItem.fromUri(chosen.audioPairUrl))
            MergingMediaSource(
                /* adjustPeriodTimeOffsets = */ true,
                /* clipDurations = */ true,
                videoSource,
                audioSource
            )
        } else {
            factory.createMediaSource(Media3MediaItem.fromUri(chosen.url))
        }
    }
}
