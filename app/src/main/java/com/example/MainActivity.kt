package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import coil.compose.AsyncImage
import com.example.data.local.DownloadedVideoEntity
import com.example.mediaserviceinterfaces.MediaItem
import com.example.ui.StreamTubeViewModel
import com.example.ui.components.DownloadFlightOverlay
import com.example.ui.components.DownloadFormatSheet
import com.example.ui.components.SplashOverlay
import com.example.ui.components.StreamTubeTopBar
import com.example.ui.components.UpdateBanner
import com.example.ui.components.shouldShowTopBanner
import com.example.ui.screens.ChannelScreen
import com.example.ui.screens.ChannelsHubScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.ShortsScreen
import com.example.ui.screens.WatchScreen
import com.example.ui.theme.StreamTubeTheme
import com.example.update.AppUpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AppDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME("Home", Icons.Rounded.Home, Icons.Outlined.Home, "nav_home"),
    SHORTS("Shorts", Icons.Rounded.VideoLibrary, Icons.Outlined.VideoLibrary, "nav_shorts"),
    CHANNELS("Channels", Icons.Rounded.Subscriptions, Icons.Outlined.Subscriptions, "nav_channels"),
    DOWNLOADS("Downloads", Icons.Rounded.Download, Icons.Outlined.Download, "nav_downloads")
}

class MainActivity : ComponentActivity() {
    private val viewModel: StreamTubeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreamTubeTheme(darkTheme = true) {
                StreamTubeRoot(viewModel = viewModel)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun StreamTubeRoot(viewModel: StreamTubeViewModel) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var activeChannelRequest by rememberSaveable { mutableStateOf<Triple<String, String, String>?>(null) }

    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val homeFeedState by viewModel.homeFeedState.collectAsStateWithLifecycle()
    val isLoadingMoreHome by viewModel.isLoadingMoreHome.collectAsStateWithLifecycle()
    val shortsState by viewModel.shortsState.collectAsStateWithLifecycle()
    val featuredChannelsState by viewModel.featuredChannelsState.collectAsStateWithLifecycle()
    val channelDetailState by viewModel.channelDetailState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val shortsSearchQuery by viewModel.shortsSearchQuery.collectAsStateWithLifecycle()
    val isSearchingShorts by viewModel.isSearchingShorts.collectAsStateWithLifecycle()
    val searchFilter by viewModel.searchFilter.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val isUpdateBannerDismissed by viewModel.isUpdateBannerDismissed.collectAsStateWithLifecycle()

    val activeVideo by viewModel.activeVideo.collectAsStateWithLifecycle()
    val activeOfflinePath by viewModel.activeOfflinePath.collectAsStateWithLifecycle()
    val isWatchExpanded by viewModel.isWatchExpanded.collectAsStateWithLifecycle()
    val watchFormatState by viewModel.watchFormatState.collectAsStateWithLifecycle()
    val relatedVideos by viewModel.relatedVideos.collectAsStateWithLifecycle()
    val isRelatedLoading by viewModel.isRelatedLoading.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val selectedQuality by viewModel.selectedQuality.collectAsStateWithLifecycle()
    val qualitySwitchState by viewModel.qualitySwitchState.collectAsStateWithLifecycle()
    val watchPlayer by viewModel.seamlessPlayerManager.activePlayer.collectAsStateWithLifecycle()

    val downloadSheetItem by viewModel.downloadSheetItem.collectAsStateWithLifecycle()
    val downloadSheetFormatState by viewModel.downloadSheetFormatState.collectAsStateWithLifecycle()
    val flyingDownloadEvent by viewModel.flyingDownloadEvent.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val likedVideos by viewModel.likedVideos.collectAsStateWithLifecycle()
    val savedVideos by viewModel.savedVideos.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var downloadsIconTargetOffset by remember { mutableStateOf(Offset(850f, 1950f)) }
    val downloadsIconScale = remember { Animatable(1f) }
    var notificationPromptShownForTag by rememberSaveable { mutableStateOf<String?>(null) }
    var showSplash by rememberSaveable { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkForUpdatesIfStale()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        if (showSplash) {
            delay(2500L)
            showSplash = false
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.notifyPendingUpdateIfAllowed()
        }
    }

    LaunchedEffect(appUpdateState, isUpdateBannerDismissed) {
        if (isUpdateBannerDismissed) return@LaunchedEffect
        val pendingRelease = (appUpdateState as? AppUpdateState.UpdateAvailable)?.release
            ?: (appUpdateState as? AppUpdateState.ReadyToInstall)?.release
        if (pendingRelease != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionState == PackageManager.PERMISSION_GRANTED) {
                viewModel.notifyPendingUpdateIfAllowed()
            } else if (notificationPromptShownForTag != pendingRelease.tagName) {
                notificationPromptShownForTag = pendingRelease.tagName
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeToast()
        }
    }

    val activeDownloadCount = remember(downloads) {
        downloads.count {
            it.status == DownloadedVideoEntity.STATUS_QUEUED ||
                it.status == DownloadedVideoEntity.STATUS_DOWNLOADING ||
                it.status == DownloadedVideoEntity.STATUS_MERGING
        }
    }
    val latestDownloadsByVideoId = remember(downloads) {
        downloads.groupBy { it.videoId }.mapValues { (_, list) -> list.first() }
    }
    val subscribedIds = remember(subscriptions) {
        subscriptions.map { it.channelId }.toSet()
    }
    val likedVideoIds = remember(likedVideos) {
        likedVideos.map { it.videoId }.toSet()
    }
    val savedVideoIds = remember(savedVideos) {
        savedVideos.map { it.videoId }.toSet()
    }

    var miniPlayerIsPlaying by remember { mutableStateOf(false) }

    DisposableEffect(watchPlayer) {
        miniPlayerIsPlaying = watchPlayer.isPlaying
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                miniPlayerIsPlaying = isPlaying
            }
        }
        watchPlayer.addListener(listener)
        onDispose {
            watchPlayer.removeListener(listener)
        }
    }

    val openChannelHandler: (String, String, String) -> Unit = { channelId, name, avatar ->
        activeChannelRequest = Triple(channelId, name, avatar)
        viewModel.minimizePlayer()
        isSearchOpen = false
        viewModel.openChannel(channelId, name, avatar)
    }

    val openVideoHandler: (MediaItem, String?) -> Unit = { item, offlinePath ->
        activeChannelRequest = null
        isSearchOpen = false
        viewModel.openVideo(item, offlinePath)
    }

    val openShortHandler: (MediaItem) -> Unit = { shortItem ->
        activeChannelRequest = null
        isSearchOpen = false
        viewModel.minimizePlayer()
        watchPlayer.pause()
        viewModel.focusShortInFeed(shortItem)
        currentDestination = AppDestination.SHORTS
    }

    val navItemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            navigationSuiteColors = NavigationSuiteDefaults.colors(
                navigationBarContainerColor = MaterialTheme.colorScheme.surface,
                navigationRailContainerColor = MaterialTheme.colorScheme.surface,
                navigationDrawerContainerColor = MaterialTheme.colorScheme.surface
            ),
            navigationSuiteItems = {
                AppDestination.entries.forEach { dest ->
                    item(
                        selected = currentDestination == dest,
                        onClick = {
                            activeChannelRequest = null
                            isSearchOpen = false
                            currentDestination = dest
                            if (dest == AppDestination.SHORTS) {
                                viewModel.minimizePlayer()
                                watchPlayer.pause()
                            }
                        },
                        icon = {
                            val iconVec = if (currentDestination == dest) dest.selectedIcon else dest.unselectedIcon
                            if (dest == AppDestination.DOWNLOADS) {
                                Box(
                                    modifier = Modifier
                                        .scale(downloadsIconScale.value)
                                        .onGloballyPositioned { coords ->
                                            val pos = coords.positionInRoot()
                                            downloadsIconTargetOffset = Offset(
                                                x = pos.x + coords.size.width / 2f,
                                                y = pos.y + coords.size.height / 2f
                                            )
                                        }
                                ) {
                                    if (activeDownloadCount > 0) {
                                        BadgedBox(
                                            badge = {
                                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                    Text(activeDownloadCount.toString())
                                                }
                                            }
                                        ) {
                                            Icon(iconVec, contentDescription = dest.label)
                                        }
                                    } else {
                                        Icon(iconVec, contentDescription = dest.label)
                                    }
                                }
                            } else {
                                Icon(iconVec, contentDescription = dest.label)
                            }
                        },
                        label = { Text(dest.label) },
                        colors = navItemColors,
                        modifier = Modifier.testTag(dest.testTag)
                    )
                }
            }
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.navigationBarsPadding()
                    )
                },
                topBar = {
                    val showUpdateBanner = appUpdateState.shouldShowTopBanner(isUpdateBannerDismissed)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        UpdateBanner(
                            state = appUpdateState,
                            isDismissed = isUpdateBannerDismissed,
                            onStartUpdate = viewModel::startAppUpdate,
                            onInstallUpdate = viewModel::installDownloadedUpdate,
                            onDismiss = viewModel::dismissUpdateBanner,
                            modifier = Modifier.statusBarsPadding()
                        )
                        if (currentDestination != AppDestination.SHORTS) {
                            StreamTubeTopBar(
                                onSearchClick = { isSearchOpen = true },
                                applyStatusBarsPadding = !showUpdateBanner
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentDestination) {
                        AppDestination.HOME -> HomeScreen(
                            selectedCategory = selectedCategory,
                            feedState = homeFeedState,
                            isLoadingMore = isLoadingMoreHome,
                            onSelectCategory = viewModel::selectCategory,
                            onRefresh = { viewModel.loadHomeFeed(selectedCategory, forceRefresh = true) },
                            onLoadNextBatch = viewModel::loadNextHomeBatch,
                            onVideoClick = { item -> openVideoHandler(item, null) },
                            onChannelClick = openChannelHandler,
                            downloadsByVideoId = latestDownloadsByVideoId,
                            onRetryDownload = viewModel::retryDownload
                        )

                        AppDestination.SHORTS -> ShortsScreen(
                            shortsState = shortsState,
                            activeSearchQuery = shortsSearchQuery,
                            isSearchingShorts = isSearchingShorts,
                            playerEngine = viewModel.playerEngine,
                            resolveShortFormatInfo = viewModel::resolveShortFormatInfo,
                            onPreWarmUpcomingShort = viewModel::preWarmUpcomingShort,
                            onShortPageChanged = viewModel::onShortPageChanged,
                            onShortViewed = viewModel::markShortViewed,
                            onRefresh = { viewModel.loadInitialShortsBatch(forceRefresh = true) },
                            onSearchShorts = viewModel::searchShortsInPlace,
                            onClearShortsSearch = viewModel::clearShortsSearch,
                            onChannelClick = openChannelHandler,
                            onDownloadClick = viewModel::openDownloadSheet
                        )

                        AppDestination.CHANNELS -> ChannelsHubScreen(
                            subscriptions = subscriptions,
                            featuredChannelsState = featuredChannelsState,
                            onOpenChannel = openChannelHandler,
                            onRefreshFeatured = viewModel::loadFeaturedChannels
                        )

                        AppDestination.DOWNLOADS -> DownloadsScreen(
                            downloads = downloads,
                            onPlayOffline = { item, path -> openVideoHandler(item, path) },
                            onRetryDownload = viewModel::retryDownload,
                            onDeleteDownload = viewModel::deleteDownload,
                            savedVideos = savedVideos,
                            likedVideos = likedVideos,
                            onPlayLibraryVideo = { item -> openVideoHandler(item, null) },
                            onRemoveSaved = viewModel::unsaveVideo,
                            onRemoveLiked = viewModel::unlikeVideo
                        )
                    }

                    // Floating Mini-PiP Player when swiped down / minimized
                    val currentVideo = activeVideo
                    if (currentVideo != null && !isWatchExpanded && currentDestination != AppDestination.SHORTS) {
                        MiniPipPlayerBar(
                            video = currentVideo,
                            player = watchPlayer,
                            isPlaying = miniPlayerIsPlaying,
                            onExpand = viewModel::expandPlayer,
                            onPlayPause = {
                                if (watchPlayer.isPlaying) watchPlayer.pause() else watchPlayer.play()
                            },
                            onClose = {
                                watchPlayer.stop()
                                viewModel.closePlayer()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = activeChannelRequest != null,
            enter = slideInVertically { it / 6 },
            exit = slideOutVertically { it }
        ) {
            ChannelScreen(
                channelState = channelDetailState,
                subscribedChannelIds = subscribedIds,
                onBack = { activeChannelRequest = null },
                onRetry = {
                    activeChannelRequest?.let { (id, name, avatar) ->
                        viewModel.openChannel(id, name, avatar)
                    }
                },
                onToggleSubscribe = viewModel::toggleSubscribe,
                onVideoClick = { item -> openVideoHandler(item, null) },
                downloadsByVideoId = latestDownloadsByVideoId,
                onRetryDownload = viewModel::retryDownload
            )
        }

        AnimatedVisibility(
            visible = isSearchOpen,
            enter = slideInVertically { it / 6 },
            exit = slideOutVertically { it }
        ) {
            SearchScreen(
                searchState = searchState,
                selectedFilter = searchFilter,
                onBack = { isSearchOpen = false },
                onSelectFilter = viewModel::selectSearchFilter,
                onSearch = viewModel::performSearch,
                onVideoClick = { item -> openVideoHandler(item, null) },
                onShortClick = openShortHandler,
                onChannelClick = openChannelHandler,
                downloadsByVideoId = latestDownloadsByVideoId,
                onRetryDownload = viewModel::retryDownload
            )
        }

        val watching = activeVideo
        AnimatedVisibility(
            visible = watching != null && isWatchExpanded,
            enter = slideInVertically { it / 3 },
            exit = slideOutVertically { it }
        ) {
            if (watching != null) {
                val videoDownloads = remember(downloads, watching.id) {
                    downloads.filter { it.videoId == watching.id }
                }
                WatchScreen(
                    video = watching,
                    offlineLocalPath = activeOfflinePath,
                    formatState = watchFormatState,
                    relatedVideos = relatedVideos,
                    videoDownloads = videoDownloads,
                    isSubscribed = subscribedIds.contains(watching.channelId),
                    playbackSpeed = playbackSpeed,
                    selectedQuality = selectedQuality,
                    qualitySwitchState = qualitySwitchState,
                    player = watchPlayer,
                    onMinimize = viewModel::minimizePlayer,
                    onRetryStream = { viewModel.openVideo(watching, activeOfflinePath) },
                    onSpeedChange = viewModel::setPlaybackSpeed,
                    onQualityChange = viewModel::switchQualitySeamlessly,
                    onChannelClick = openChannelHandler,
                    onToggleSubscribe = viewModel::toggleSubscribe,
                    onDownloadClick = viewModel::openDownloadSheet,
                    onRetryDownload = viewModel::retryDownload,
                    onCancelDownload = viewModel::deleteDownload,
                    onRelatedVideoClick = { rel -> openVideoHandler(rel, null) },
                    downloadsByVideoId = latestDownloadsByVideoId,
                    isLiked = likedVideoIds.contains(watching.id),
                    isSaved = savedVideoIds.contains(watching.id),
                    onToggleLike = viewModel::toggleLike,
                    onToggleSave = viewModel::toggleSave,
                    isRelatedLoading = isRelatedLoading
                )
            }
        }

        val sheetTarget = downloadSheetItem
        if (sheetTarget != null) {
            DownloadFormatSheet(
                item = sheetTarget,
                formatState = downloadSheetFormatState,
                onRetry = { viewModel.openDownloadSheet(sheetTarget) },
                onSelectFormat = { format -> viewModel.startOneClickDownload(sheetTarget, format) },
                onDismiss = viewModel::dismissDownloadSheet
            )
        }

        DownloadFlightOverlay(
            event = flyingDownloadEvent,
            targetOffset = downloadsIconTargetOffset,
            onArrived = {
                coroutineScope.launch {
                    downloadsIconScale.animateTo(
                        1.35f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                    )
                    downloadsIconScale.animateTo(
                        1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
            },
            onAnimationFinished = viewModel::clearFlyingDownloadEvent
        )

        SplashOverlay(visible = showSplash)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MiniPipPlayerBar(
    video: MediaItem,
    player: ExoPlayer,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .testTag("mini_player_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(88.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                val presentationState = rememberPresentationState(player)
                PlayerSurface(
                    player = player,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    modifier = Modifier
                        .fillMaxSize()
                        .resizeWithContentScale(ContentScale.Crop, presentationState.videoSizeDp)
                )
                if (presentationState.coverSurface) {
                    AsyncImage(
                        model = video.thumbnailUrl,
                        contentDescription = video.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = video.channelName.ifBlank { "Playing" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close mini player"
                )
            }
        }
    }
}
