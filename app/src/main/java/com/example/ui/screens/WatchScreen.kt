package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import coil.compose.AsyncImage
import com.example.data.local.DownloadedVideoEntity
import com.example.mediaserviceinterfaces.ChannelInfo
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import com.example.player.QualitySwitchState
import com.example.ui.UiState
import com.example.ui.components.ChannelAvatar
import com.example.ui.components.VideoCard
import com.example.ui.theme.EmeraldSuccess
import com.example.youtubeapi.InnertubeParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Adaptive WatchScreen featuring:
 * - Quality & Speed selector menus directly INSIDE the player overlay (never below the video)
 * - Slim compact BufferedSeekBar with no "buffered" text clutter
 * - Drag / swipe down on the player to turn into the floating Mini-PiP player
 * - Dedicated 1-Click Download button directly beneath every video's player
 */
@OptIn(UnstableApi::class)
@Composable
fun WatchScreen(
    video: MediaItem,
    offlineLocalPath: String?,
    formatState: UiState<MediaItemFormatInfo>,
    relatedVideos: List<MediaItem>,
    videoDownloads: List<DownloadedVideoEntity>,
    isSubscribed: Boolean,
    playbackSpeed: Float,
    selectedQuality: String,
    qualitySwitchState: QualitySwitchState,
    player: ExoPlayer,
    onMinimize: () -> Unit,
    onRetryStream: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onQualityChange: (String) -> Unit,
    onChannelClick: (channelId: String, name: String, avatar: String) -> Unit,
    onToggleSubscribe: (ChannelInfo) -> Unit,
    onDownloadClick: (MediaItem, Offset) -> Unit,
    onRetryDownload: (DownloadedVideoEntity) -> Unit,
    onCancelDownload: (DownloadedVideoEntity) -> Unit,
    onRelatedVideoClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    downloadsByVideoId: Map<String, DownloadedVideoEntity> = emptyMap(),
    isLiked: Boolean = false,
    isSaved: Boolean = false,
    onToggleLike: (MediaItem) -> Unit = {},
    onToggleSave: (MediaItem) -> Unit = {},
    isRelatedLoading: Boolean = false
) {
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var isFullscreenZoomFill by rememberSaveable { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember { mutableLongStateOf(0L) }
    var downloadButtonCenter by remember { mutableStateOf(Offset.Zero) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var selectedRelatedId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }

    // True Landscape Fullscreen + Display Cutout Short Edges + Immersive Status/Nav Bar Auto-Hide
    DisposableEffect(isFullscreen, activity) {
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, view) else null
        val originalCutoutMode = if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode
        } else 0

        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            if (window != null && Build.VERSION_CODES.P <= Build.VERSION.SDK_INT) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null && Build.VERSION_CODES.P <= Build.VERSION.SDK_INT) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = originalCutoutMode
                }
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null && Build.VERSION_CODES.P <= Build.VERSION.SDK_INT) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = originalCutoutMode
                }
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            onMinimize()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (player.duration > 0L) durationMs = player.duration
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        isPlaying = player.isPlaying || player.playWhenReady
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        if (player.duration > 0L) durationMs = player.duration
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(currentPositionMs)
            if (player.duration > 0L) {
                durationMs = player.duration
            }
            delay(200L)
        }
    }

    LaunchedEffect(showControls, isPlaying, showQualityMenu, showSpeedMenu, isFullscreen) {
        if (showControls && isPlaying && !showQualityMenu && !showSpeedMenu) {
            delay(3000L)
            showControls = false
        }
        if (isFullscreen && !showControls) {
            val window = activity?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    LaunchedEffect(playbackSpeed, player) {
        player.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    LifecycleStartEffect(player) {
        if (isPlaying) player.play()
        onStopOrDispose {
            player.pause()
        }
    }

    val playedFraction = if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val bufferedFraction = if (durationMs > 0L) {
        (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val playerSurfaceBlock: @Composable (Modifier) -> Unit = { boxModifier ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = boxModifier
                .graphicsLayer {
                    translationY = dragOffsetY
                    val shrink = (1f - (dragOffsetY / 1100f)).coerceIn(0.84f, 1f)
                    scaleX = shrink
                    scaleY = shrink
                }
                .background(Color.Black)
                .pointerInput(isFullscreen) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY > 150f) {
                                if (isFullscreen) isFullscreen = false else onMinimize()
                            }
                            dragOffsetY = 0f
                        },
                        onDragCancel = { dragOffsetY = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            val next = (dragOffsetY + dragAmount).coerceIn(0f, 450f)
                            dragOffsetY = next
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (showQualityMenu || showSpeedMenu) {
                                showQualityMenu = false
                                showSpeedMenu = false
                            } else {
                                showControls = !showControls
                            }
                        },
                        onDoubleTap = { offset ->
                            val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                            val target = (player.currentPosition + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                            player.seekTo(target)
                            showControls = true
                        }
                    )
                }
        ) {
            val presentationState = rememberPresentationState(player)
            val activeContentScale = if (isFullscreen && isFullscreenZoomFill) {
                ContentScale.Crop
            } else {
                ContentScale.Fit
            }
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(0.dp))
                    .resizeWithContentScale(activeContentScale, presentationState.videoSizeDp)
            )
            if (presentationState.coverSurface) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = video.thumbnailUrl,
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                    )
                }
            }

            if ((isBuffering || (formatState is UiState.Loading && offlineLocalPath.isNullOrBlank())) &&
                qualitySwitchState is QualitySwitchState.Idle
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (formatState is UiState.Error && offlineLocalPath.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = onRetryStream) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }

            // Silent background quality switch (no on-screen "Preparing..." / "Ready" pill clutter)

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.6f),
                                    Color.Black.copy(alpha = 0.15f),
                                    Color.Black.copy(alpha = 0.78f)
                                )
                            )
                        )
                ) {
                    // Top Row Inside Player: Minimize on Left + Quality & Speed buttons on Right
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (isFullscreen) isFullscreen = false else onMinimize()
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Minimize to mini player",
                                    tint = Color.White
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isFullscreen) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (isFullscreenZoomFill) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.58f),
                                        modifier = Modifier
                                            .clickable { isFullscreenZoomFill = !isFullscreenZoomFill }
                                            .testTag("player_fullscreen_fit_toggle")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AspectRatio,
                                                contentDescription = "Toggle Fill or Fit",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isFullscreenZoomFill) "Fill" else "Fit",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                // Speed Selector Button Inside Player
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (showSpeedMenu) MaterialTheme.colorScheme.primary
                                    else Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier
                                        .clickable {
                                            showSpeedMenu = !showSpeedMenu
                                            showQualityMenu = false
                                        }
                                        .testTag("player_speed_toggle")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Speed,
                                            contentDescription = "Playback speed",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${playbackSpeed}x",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                // Quality Selector Button Inside Player
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (showQualityMenu) MaterialTheme.colorScheme.primary
                                    else Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier
                                        .clickable {
                                            showQualityMenu = !showQualityMenu
                                            showSpeedMenu = false
                                        }
                                        .testTag("player_quality_toggle")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Tune,
                                            contentDescription = "Video quality",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = selectedQuality,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        // Inline Quality Strip Inside Player
                        val availableQualities = remember(formatState) {
                            if (formatState is UiState.Success) {
                                InnertubeParser.extractAvailablePlayerQualities(formatState.data.formats)
                            } else {
                                listOf("360p", "480p", "720p", "1080p", "Auto")
                            }
                        }
                        AnimatedVisibility(visible = showQualityMenu) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                items(availableQualities) { q ->
                                    val active = selectedQuality == q
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (active) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.72f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                        modifier = Modifier
                                            .clickable {
                                                onQualityChange(q)
                                                showQualityMenu = false
                                            }
                                            .testTag("quality_chip_$q")
                                    ) {
                                        Text(
                                            text = q,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Inline Speed Strip Inside Player
                        AnimatedVisibility(visible = showSpeedMenu) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                items(listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)) { spd ->
                                    val active = playbackSpeed == spd
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (active) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.72f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                        modifier = Modifier.clickable {
                                            onSpeedChange(spd)
                                            showSpeedMenu = false
                                        }
                                    ) {
                                        Text(
                                            text = "${spd}x",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Center Playback Buttons
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                            modifier = Modifier
                                .size(50.dp)
                                .clickable {
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                                .testTag("watch_play_pause")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val target = (player.currentPosition + 10_000L).coerceAtMost(durationMs.coerceAtLeast(0L))
                                player.seekTo(target)
                            },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Forward10,
                                contentDescription = "Forward 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Slim Bottom Seek Bar & Clean Timestamp (NO "buffered" text)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${InnertubeParser.formatSeconds(currentPositionMs / 1000L).ifBlank { "0:00" }} / ${
                                    InnertubeParser.formatSeconds(durationMs / 1000L).ifBlank { video.durationText.ifBlank { "0:00" } }
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                            IconButton(
                                onClick = { isFullscreen = !isFullscreen },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                    contentDescription = "Toggle fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        BufferedSeekBar(
                            playedFraction = playedFraction,
                            bufferedFraction = bufferedFraction,
                            onSeekToFraction = { fraction ->
                                if (durationMs > 0L) {
                                    player.seekTo((fraction * durationMs).toLong())
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .testTag("buffered_seek_bar")
                        )
                    }
                }
            }
        }
    }

    if (isFullscreen) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            playerSurfaceBlock(Modifier.fillMaxSize())
        }
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTabletWide = maxWidth >= 820.dp

            if (isTabletWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.64f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item {
                            playerSurfaceBlock(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                            )
                        }
                        item {
                            WatchMetadataSection(
                                video = video,
                                videoDownloads = videoDownloads,
                                isSubscribed = isSubscribed,
                                isLiked = isLiked,
                                isSaved = isSaved,
                                onToggleLike = onToggleLike,
                                onToggleSave = onToggleSave,
                                onChannelClick = onChannelClick,
                                onToggleSubscribe = onToggleSubscribe,
                                onDownloadButtonPositioned = { downloadButtonCenter = it },
                                onDownloadClick = { item -> onDownloadClick(item, downloadButtonCenter) },
                                onRetryDownload = onRetryDownload,
                                onCancelDownload = onCancelDownload
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(0.36f)
                            .fillMaxHeight()
                            .padding(horizontal = 14.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "Up Next",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        if (isRelatedLoading) {
                            item {
                                RelatedLoadingHeader()
                            }
                            items(4) {
                                RelatedShimmerCard()
                            }
                        } else {
                            items(relatedVideos.take(10), key = { it.id }) { related ->
                                val isSwitchingToThis = selectedRelatedId == related.id
                                val itemScale by animateFloatAsState(
                                    targetValue = if (isSwitchingToThis) 0.96f else 1f,
                                    animationSpec = tween(150),
                                    label = "related_tap_scale_tablet"
                                )
                                Box(modifier = Modifier.scale(itemScale)) {
                                    VideoCard(
                                        item = related,
                                        onVideoClick = { clicked ->
                                            coroutineScope.launch {
                                                selectedRelatedId = clicked.id
                                                delay(150L)
                                                onRelatedVideoClick(clicked)
                                                selectedRelatedId = null
                                            }
                                        },
                                        onChannelClick = onChannelClick,
                                        downloadEntity = downloadsByVideoId[related.id],
                                        onRetryDownload = onRetryDownload
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    playerSurfaceBlock(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            WatchMetadataSection(
                                video = video,
                                videoDownloads = videoDownloads,
                                isSubscribed = isSubscribed,
                                isLiked = isLiked,
                                isSaved = isSaved,
                                onToggleLike = onToggleLike,
                                onToggleSave = onToggleSave,
                                onChannelClick = onChannelClick,
                                onToggleSubscribe = onToggleSubscribe,
                                onDownloadButtonPositioned = { downloadButtonCenter = it },
                                onDownloadClick = { item -> onDownloadClick(item, downloadButtonCenter) },
                                onRetryDownload = onRetryDownload,
                                onCancelDownload = onCancelDownload
                            )
                        }
                        if (isRelatedLoading) {
                            item {
                                Text(
                                    text = "Up Next",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            item {
                                RelatedLoadingHeader(
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            items(4) {
                                RelatedShimmerCard(
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        } else if (relatedVideos.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Up Next",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                            items(relatedVideos.take(10), key = { it.id }) { related ->
                                val isSwitchingToThis = selectedRelatedId == related.id
                                val itemScale by animateFloatAsState(
                                    targetValue = if (isSwitchingToThis) 0.96f else 1f,
                                    animationSpec = tween(150),
                                    label = "related_tap_scale"
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .scale(itemScale)
                                ) {
                                    VideoCard(
                                        item = related,
                                        onVideoClick = { clicked ->
                                            coroutineScope.launch {
                                                selectedRelatedId = clicked.id
                                                delay(150L)
                                                onRelatedVideoClick(clicked)
                                                selectedRelatedId = null
                                            }
                                        },
                                        onChannelClick = onChannelClick,
                                        downloadEntity = downloadsByVideoId[related.id],
                                        onRetryDownload = onRetryDownload
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedLoadingHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "Loading related videos…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RelatedShimmerCard(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "related_shimmer")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "related_shimmer_alpha"
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .alpha(pulseAlpha)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
        }
    }
}

/**
 * Slim, sleek seek bar showing played progress and forward bufferedPosition without oversized height.
 */
@Composable
private fun BufferedSeekBar(
    playedFraction: Float,
    bufferedFraction: Float,
    onSeekToFraction: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val bufferedColor = Color.White.copy(alpha = 0.45f)
    val trackBgColor = Color.White.copy(alpha = 0.22f)

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeekToFraction(frac)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeekToFraction(frac)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val trackHeight = 2.5.dp.toPx()
            val centerY = size.height / 2f
            val trackTop = centerY - trackHeight / 2f

            drawRoundRect(
                color = trackBgColor,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f)
            )

            val bufWidth = (size.width * bufferedFraction.coerceIn(0f, 1f))
            if (bufWidth > 0f) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, trackTop),
                    size = Size(bufWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f)
                )
            }

            val playedWidth = (size.width * playedFraction.coerceIn(0f, 1f))
            if (playedWidth > 0f) {
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(0f, trackTop),
                    size = Size(playedWidth, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f)
                )
            }

            drawCircle(
                color = primaryColor,
                radius = 5.dp.toPx(),
                center = Offset(playedWidth.coerceIn(0f, size.width), centerY)
            )
        }
    }
}

@Composable
private fun WatchMetadataSection(
    video: MediaItem,
    videoDownloads: List<DownloadedVideoEntity>,
    isSubscribed: Boolean,
    isLiked: Boolean,
    isSaved: Boolean,
    onToggleLike: (MediaItem) -> Unit,
    onToggleSave: (MediaItem) -> Unit,
    onChannelClick: (channelId: String, name: String, avatar: String) -> Unit,
    onToggleSubscribe: (ChannelInfo) -> Unit,
    onDownloadButtonPositioned: (Offset) -> Unit,
    onDownloadClick: (MediaItem) -> Unit,
    onRetryDownload: (DownloadedVideoEntity) -> Unit,
    onCancelDownload: (DownloadedVideoEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = video.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (video.viewCountText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = video.viewCountText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (video.displayDateTime.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = video.displayDateTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Like & Save quick actions directly beneath the video player
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isLiked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleLike(video) }
                    .testTag("watch_like_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = if (isLiked) "Unlike video" else "Like video",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLiked) "Liked" else "Like",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isLiked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSaved) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleSave(video) }
                    .testTag("watch_save_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (isSaved) "Remove saved video" else "Save video",
                        tint = if (isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isSaved) "Saved" else "Save",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSaved) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Dedicated 1-Click Download button directly below every video's player
        Button(
            onClick = { onDownloadClick(video) },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    onDownloadButtonPositioned(
                        Offset(
                            x = pos.x + coords.size.width / 2f,
                            y = pos.y + coords.size.height / 2f
                        )
                    )
                }
                .testTag("watch_download_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = "Download video formats",
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Download (Choose Format)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // Live Download Progress Bar directly beneath player/Download button
        videoDownloads.forEach { dl ->
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("watch_download_progress_${dl.formatItag}")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isDone = dl.status == DownloadedVideoEntity.STATUS_COMPLETED
                        val isFail = dl.status == DownloadedVideoEntity.STATUS_FAILED
                        val isQueued = dl.status == DownloadedVideoEntity.STATUS_QUEUED
                        val isMerging = dl.status == DownloadedVideoEntity.STATUS_MERGING
                        val dlMb = dl.downloadedBytes / (1024.0 * 1024.0)
                        val totMb = dl.totalBytes / (1024.0 * 1024.0)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            when {
                                isDone -> Icon(
                                    imageVector = Icons.Rounded.DownloadDone,
                                    contentDescription = null,
                                    tint = EmeraldSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                                isFail -> Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                else -> Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            val isPreparing = isQueued ||
                                (dl.status == DownloadedVideoEntity.STATUS_DOWNLOADING && dl.downloadedBytes <= 0L)
                            val label = when {
                                isDone -> String.format("Saved %s (%s) • %.1f MB", dl.qualityLabel, dl.container, totMb)
                                isFail -> "Failed ${dl.qualityLabel}"
                                isPreparing -> "Preparing ${dl.qualityLabel} download…"
                                isMerging -> "Merging ${dl.qualityLabel} video & audio…"
                                dl.totalBytes > 0L -> String.format(
                                    "Downloading %s • %d%% (%.1f / %.1f MB)",
                                    dl.qualityLabel,
                                    (dl.progressFraction * 100).toInt(),
                                    dlMb,
                                    totMb
                                )
                                else -> "Downloading ${dl.qualityLabel}…"
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    isDone -> EmeraldSuccess
                                    isFail -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isFail) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier
                                        .clickable { onRetryDownload(dl) }
                                        .testTag("watch_retry_download_${dl.formatItag}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "Retry download",
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Retry",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                            if (!isDone) {
                                IconButton(
                                    onClick = { onCancelDownload(dl) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Cancel download",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    val isPreparingBar = dl.status == DownloadedVideoEntity.STATUS_QUEUED ||
                        dl.status == DownloadedVideoEntity.STATUS_MERGING ||
                        (dl.status == DownloadedVideoEntity.STATUS_DOWNLOADING && dl.downloadedBytes <= 0L)
                    if (isPreparingBar) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else if (dl.status == DownloadedVideoEntity.STATUS_DOWNLOADING) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { dl.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onChannelClick(video.channelId, video.channelName, video.channelAvatarUrl)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChannelAvatar(
                        avatarUrl = video.channelAvatarUrl,
                        channelName = video.channelName,
                        size = 42.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = video.channelName.ifBlank { "Creator" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = "Tap to open channel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = {
                        onToggleSubscribe(
                            ChannelInfo(
                                channelId = video.channelId.ifBlank { video.channelName },
                                name = video.channelName,
                                avatarUrl = video.channelAvatarUrl
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSubscribed) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        contentColor = if (isSubscribed) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = if (isSubscribed) "Subscribed" else "Subscribe",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
