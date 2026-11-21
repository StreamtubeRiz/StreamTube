package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import com.example.player.StreamPlayerEngine
import com.example.ui.UiState
import com.example.ui.components.ChannelAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen VerticalPager Shorts player implementing all 7 instant-playback optimizations:
 * 1. +1/+2 background prefetch into 25-min cache with duplicate guard
 * 2. Single progressive MP4 stream priority (1 HTTP socket)
 * 3. Single shared ExoPlayer + REPEAT_MODE_ONE + beyondViewportPageCount = 1 + stop/clearMediaItems
 * 4. Speed-adaptive LoadControl (0.8s fast / 1.0s slow start) + matching UA
 * 5. Thumbnail first (hidden onRenderedFirstFrame) + PlayerView RESIZE_MODE_ZOOM
 * 6. Pre-pagination at size - 5 + strict <=65s non-live Shorts filter
 * 7. Zero-crash auto-recovery: lowest progressive -> staggered silent re-extract (0/250/650ms) -> ShortWebEmbed
 */
@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(
    shortsState: UiState<List<MediaItem>>,
    activeSearchQuery: String?,
    isSearchingShorts: Boolean,
    playerEngine: StreamPlayerEngine,
    resolveShortFormatInfo: suspend (String, Boolean) -> MediaItemFormatInfo?,
    onPreWarmUpcomingShort: (String) -> Unit,
    onShortPageChanged: (Int) -> Unit,
    onShortViewed: (String) -> Unit,
    onRefresh: () -> Unit,
    onSearchShorts: (String) -> Unit,
    onClearShortsSearch: () -> Unit,
    onChannelClick: (channelId: String, name: String, avatar: String) -> Unit,
    onDownloadClick: (MediaItem, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    var showInlineSearchBar by rememberSaveable { mutableStateOf(false) }
    var shortsQuery by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler(enabled = showInlineSearchBar || activeSearchQuery != null) {
        if (showInlineSearchBar) {
            showInlineSearchBar = false
        } else if (activeSearchQuery != null) {
            onClearShortsSearch()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (shortsState) {
            is UiState.Loading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading Shorts…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }

            is UiState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = shortsState.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Shorts")
                    }
                }
            }

            is UiState.Success -> {
                val shorts = shortsState.data
                val pagerState = rememberPagerState(pageCount = { shorts.size })
                val player = remember { playerEngine.createShortsPlayer() }
                val recoveryScope = rememberCoroutineScope()

                var isBuffering by remember { mutableStateOf(true) }
                var hasRenderedFirstFrame by remember { mutableStateOf(false) }
                var useWebEmbedFallback by remember { mutableStateOf(false) }
                var hasTriedSilentReExtract by remember { mutableStateOf(false) }
                var retryNonce by remember { mutableIntStateOf(0) }
                var isPaused by remember { mutableStateOf(false) }
                var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
                var activeCandidates by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
                var candidateIndex by remember { mutableIntStateOf(0) }
                var activeShortId by remember { mutableStateOf("") }

                // When an inline search swaps the feed, jump straight to page 0 in the same container
                LaunchedEffect(activeSearchQuery, shorts.firstOrNull()?.id) {
                    if (activeSearchQuery != null && shorts.isNotEmpty() && pagerState.currentPage != 0) {
                        pagerState.scrollToPage(0)
                    }
                }

                fun triggerSilentReExtractOrWebEmbed(shortId: String) {
                    if (shortId.isBlank() || shortId != activeShortId) return
                    if (!hasTriedSilentReExtract) {
                        hasTriedSilentReExtract = true
                        isBuffering = true
                        recoveryScope.launch {
                            val fresh = resolveShortFormatInfo(shortId, true)
                            if (shortId != activeShortId) return@launch
                            val freshCandidates = fresh?.let {
                                playerEngine.buildShortsCandidateMediaSources(it)
                            }.orEmpty()
                            if (freshCandidates.isNotEmpty()) {
                                activeCandidates = freshCandidates
                                candidateIndex = 0
                                player.stop()
                                player.clearMediaItems()
                                player.setMediaSource(freshCandidates.first())
                                player.playbackParameters = PlaybackParameters(playbackSpeed)
                                player.playWhenReady = true
                                player.prepare()
                            } else {
                                isBuffering = false
                                useWebEmbedFallback = true
                            }
                        }
                    } else {
                        isBuffering = false
                        useWebEmbedFallback = true
                    }
                }

                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            hasRenderedFirstFrame = true
                            isBuffering = false
                            useWebEmbedFallback = false
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> isBuffering = true
                                Player.STATE_READY -> isBuffering = false
                                Player.STATE_ENDED -> isBuffering = false
                                Player.STATE_IDLE -> Unit
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            isPaused = !isPlaying && player.playbackState == Player.STATE_READY
                            if (isPlaying) {
                                isBuffering = false
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val next = candidateIndex + 1
                            if (next < activeCandidates.size) {
                                candidateIndex = next
                                player.setMediaSource(activeCandidates[next])
                                player.playbackParameters = PlaybackParameters(playbackSpeed)
                                player.playWhenReady = true
                                player.prepare()
                            } else {
                                triggerSilentReExtractOrWebEmbed(activeShortId)
                            }
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                        player.release()
                    }
                }

                LifecycleStartEffect(player) {
                    if (!isPaused && !useWebEmbedFallback) player.play()
                    onStopOrDispose {
                        player.pause()
                    }
                }

                // 9-second Shorts stall watchdog (alternate candidate -> silent re-extract -> WebEmbed)
                LaunchedEffect(player, activeShortId) {
                    var lastPos = -1L
                    var stalledSince = 0L
                    while (true) {
                        delay(500L)
                        if (isPaused || useWebEmbedFallback || activeShortId.isBlank()) {
                            stalledSince = 0L
                            lastPos = player.currentPosition
                            continue
                        }
                        val cur = player.currentPosition
                        val stalled = player.playbackState == Player.STATE_BUFFERING && cur == lastPos
                        if (stalled) {
                            if (stalledSince == 0L) {
                                stalledSince = System.currentTimeMillis()
                            } else if (System.currentTimeMillis() - stalledSince >= 9_000L) {
                                stalledSince = 0L
                                val next = candidateIndex + 1
                                if (next < activeCandidates.size) {
                                    candidateIndex = next
                                    player.setMediaSource(activeCandidates[next])
                                    player.playbackParameters = PlaybackParameters(playbackSpeed)
                                    player.playWhenReady = true
                                    player.prepare()
                                } else {
                                    triggerSilentReExtractOrWebEmbed(activeShortId)
                                }
                            }
                        } else {
                            stalledSince = 0L
                            lastPos = cur
                        }
                    }
                }

                val currentShortId = shorts.getOrNull(pagerState.currentPage)?.id.orEmpty()
                LaunchedEffect(pagerState.currentPage, currentShortId, retryNonce) {
                    onShortPageChanged(pagerState.currentPage)
                    val current = shorts.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
                    activeShortId = current.id
                    playerEngine.activePlaybackVideoId = current.id
                    onShortViewed(current.id)
                    isBuffering = true
                    hasRenderedFirstFrame = false
                    useWebEmbedFallback = false
                    hasTriedSilentReExtract = false
                    isPaused = false

                    // Optimization #3: Stop & clearMediaItems before loading next Short on single shared ExoPlayer
                    player.stop()
                    player.clearMediaItems()

                    // Optimization #7: Staggered silent re-extract delays (0ms, 250ms, 650ms)
                    val delays = longArrayOf(0L, 250L, 650L)
                    var resolvedCandidates: List<MediaSource> = emptyList()
                    for ((attemptIdx, waitMs) in delays.withIndex()) {
                        if (waitMs > 0L) delay(waitMs)
                        val force = retryNonce > 0 || attemptIdx > 0
                        val info = resolveShortFormatInfo(current.id, force)
                        val list = info?.let { playerEngine.buildShortsCandidateMediaSources(it) }.orEmpty()
                        if (list.isNotEmpty()) {
                            resolvedCandidates = list
                            break
                        }
                    }

                    if (resolvedCandidates.isNotEmpty()) {
                        activeCandidates = resolvedCandidates
                        candidateIndex = 0
                        player.setMediaSource(resolvedCandidates.first())
                        player.playbackParameters = PlaybackParameters(playbackSpeed)
                        player.playWhenReady = true
                        player.prepare()
                    } else {
                        isBuffering = false
                        useWebEmbedFallback = true
                    }

                    // Optimization #1: Also trigger head-byte pre-warm for +1 and +2 Shorts
                    (1..2).forEach { offset ->
                        shorts.getOrNull(pagerState.currentPage + offset)?.let { upcoming ->
                            onPreWarmUpcomingShort(upcoming.id)
                        }
                    }
                }

                VerticalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    key = { page -> shorts[page].id },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("shorts_vertical_pager")
                ) { page ->
                    val item = shorts[page]
                    val isCurrent = page == pagerState.currentPage
                    var liked by remember(item.id) { mutableStateOf(false) }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 520.dp)
                                .clickable {
                                    if (!useWebEmbedFallback) {
                                        if (player.isPlaying) {
                                            player.pause()
                                            isPaused = true
                                        } else {
                                            player.play()
                                            isPaused = false
                                        }
                                    }
                                }
                        ) {
                            // Optimization #5: Thumbnail layered behind PlayerView (zero blank screen)
                            AsyncImage(
                                model = item.thumbnailUrl,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (isCurrent && !useWebEmbedFallback) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                                            layoutParams = FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    update = { view ->
                                        if (view.player !== player) {
                                            view.player = player
                                        }
                                    },
                                    onRelease = { view ->
                                        view.player = null
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (!hasRenderedFirstFrame) {
                                    AsyncImage(
                                        model = item.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            } else if (isCurrent && useWebEmbedFallback) {
                                // Optimization #7: Zero-crash ShortWebEmbed WebView backup player
                                ShortWebEmbed(
                                    videoId = item.id,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.0f to Color.Black.copy(alpha = 0.25f),
                                            0.55f to Color.Transparent,
                                            1.0f to Color.Black.copy(alpha = 0.85f)
                                        )
                                    )
                            )

                            // Top Bar Inside Same Container: Active Search Pill / Inline Search Bar + Speed & Search Icon
                            Column(
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (activeSearchQuery != null && !showInlineSearchBar) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .clickable {
                                                    shortsQuery = ""
                                                    onClearShortsSearch()
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "\"$activeSearchQuery\"",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Back to Shorts feed",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(1.dp))
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = Color.Black.copy(alpha = 0.55f),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .clickable {
                                                    playbackSpeed = when (playbackSpeed) {
                                                        1.0f -> 1.5f
                                                        1.5f -> 2.0f
                                                        else -> 1.0f
                                                    }
                                                    player.playbackParameters = PlaybackParameters(playbackSpeed)
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Speed,
                                                    contentDescription = "Change playback speed",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${playbackSpeed}x",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.White
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Surface(
                                            shape = CircleShape,
                                            color = if (showInlineSearchBar) MaterialTheme.colorScheme.primary
                                            else Color.Black.copy(alpha = 0.55f)
                                        ) {
                                            IconButton(
                                                onClick = { showInlineSearchBar = !showInlineSearchBar },
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .testTag("shorts_search_button")
                                            ) {
                                                Icon(
                                                    imageVector = if (showInlineSearchBar) Icons.Rounded.Close else Icons.Rounded.Search,
                                                    contentDescription = "Search Shorts in same player",
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                    }
                                }

                                // Compact inline search bar over the SAME playing Shorts container
                                AnimatedVisibility(
                                    visible = showInlineSearchBar,
                                    enter = fadeIn(),
                                    exit = fadeOut()
                                ) {
                                    OutlinedTextField(
                                        value = shortsQuery,
                                        onValueChange = { shortsQuery = it },
                                        placeholder = { Text("Search Shorts to play here…", color = Color.White.copy(alpha = 0.72f)) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(50),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.Black.copy(alpha = 0.78f),
                                            unfocusedContainerColor = Color.Black.copy(alpha = 0.72f),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.35f)
                                        ),
                                        trailingIcon = {
                                            if (isSearchingShorts) {
                                                CircularProgressIndicator(
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .padding(end = 12.dp)
                                                        .size(18.dp)
                                                )
                                            } else {
                                                IconButton(
                                                    onClick = {
                                                        keyboard?.hide()
                                                        if (shortsQuery.isNotBlank()) {
                                                            onSearchShorts(shortsQuery)
                                                            showInlineSearchBar = false
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Search,
                                                        contentDescription = "Play matching Shorts",
                                                        tint = Color.White
                                                    )
                                                }
                                            }
                                        },
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(
                                            onSearch = {
                                                keyboard?.hide()
                                                if (shortsQuery.isNotBlank()) {
                                                    onSearchShorts(shortsQuery)
                                                    showInlineSearchBar = false
                                                }
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                            .testTag("shorts_search_input")
                                    )
                                }
                            }

                            if (isCurrent && !useWebEmbedFallback && (isBuffering || isPaused || isSearchingShorts)) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .size(60.dp)
                                        .align(Alignment.Center)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isBuffering || isSearchingShorts) {
                                            CircularProgressIndicator(
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                                contentDescription = "Play/Pause",
                                                tint = Color.White,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Bottom-Right Action Rail (Like + 1-Click Download)
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 14.dp, bottom = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clickable { liked = !liked }
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (liked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f),
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.ThumbUp,
                                                contentDescription = "Like Short",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (liked) "Liked" else "Like",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }

                                var downloadButtonCenter by remember(item.id) { mutableStateOf(Offset.Zero) }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .onGloballyPositioned { coords ->
                                            val pos = coords.positionInRoot()
                                            downloadButtonCenter = Offset(
                                                x = pos.x + coords.size.width / 2f,
                                                y = pos.y + coords.size.height / 2f
                                            )
                                        }
                                        .clickable { onDownloadClick(item, downloadButtonCenter) }
                                        .testTag("shorts_download_${item.id}")
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Download,
                                                contentDescription = "Download Short",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Download",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Bottom-Left Corner Channel & Title Info
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(0.76f)
                                    .padding(start = 16.dp, bottom = 18.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        onChannelClick(item.channelId, item.channelName, item.channelAvatarUrl)
                                    }
                                ) {
                                    ChannelAvatar(
                                        avatarUrl = item.channelAvatarUrl,
                                        channelName = item.channelName,
                                        size = 36.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = item.channelName.ifBlank { "Shorts Creator" },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (item.viewCountText.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.Visibility,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = item.viewCountText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White.copy(alpha = 0.85f)
                                            )
                                        }
                                    }
                                    if (item.displayDateTime.isNotBlank()) {
                                        Text(
                                            text = item.displayDateTime,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.75f)
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ShortWebEmbed(
    videoId: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadUrl(
                    "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&loop=1&playlist=$videoId&controls=1&modestbranding=1"
                )
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        },
        modifier = modifier
    )
}
