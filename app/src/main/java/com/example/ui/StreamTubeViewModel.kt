package com.example.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.StreamTubeApp
import com.example.ads.AdPlacementHelper
import com.example.data.local.DownloadedVideoEntity
import com.example.data.local.LikedVideoEntity
import com.example.data.local.SavedVideoEntity
import com.example.data.local.SubscribedChannelEntity
import com.example.data.local.WatchHistoryEntity
import com.example.mediaserviceinterfaces.ChannelInfo
import com.example.mediaserviceinterfaces.FeedCategory
import com.example.mediaserviceinterfaces.MediaFormatOption
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import com.example.mediaserviceinterfaces.SearchFilter
import com.example.mediaserviceinterfaces.SearchResultBundle
import com.example.player.QualitySwitchState
import com.example.player.SeamlessQualityPlayerManager
import com.example.update.AppUpdateState
import com.example.update.GitHubReleaseInfo
import com.example.ui.components.FlyingDownloadEvent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

class StreamTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StreamTubeApp
    private val repository = app.streamRepository
    private val downloadManager = app.downloadManager
    private val updateManager = app.appUpdateManager
    private val downloadDao = app.database.downloadDao()
    private val subscriptionDao = app.database.subscriptionDao()
    private val historyDao = app.database.watchHistoryDao()
    private val likedDao = app.database.likedVideoDao()
    private val savedDao = app.database.savedVideoDao()

    val playerEngine = app.playerEngine
    val unityAdsManager = app.unityAdsManager
    val seamlessPlayerManager = SeamlessQualityPlayerManager(playerEngine)
    val qualitySwitchState: StateFlow<QualitySwitchState> = seamlessPlayerManager.switchState
    val appUpdateState: StateFlow<AppUpdateState> = updateManager.updateState
    val isUpdateBannerDismissed: StateFlow<Boolean> = updateManager.isBannerDismissed

    private val _selectedCategory = MutableStateFlow(FeedCategory.HOME)
    val selectedCategory: StateFlow<FeedCategory> = _selectedCategory.asStateFlow()

    // 20-video Home feed batching state
    private val _homeFeedState = MutableStateFlow<UiState<List<MediaItem>>>(UiState.Loading)
    val homeFeedState: StateFlow<UiState<List<MediaItem>>> = _homeFeedState.asStateFlow()
    private val _isRefreshingHome = MutableStateFlow(false)
    val isRefreshingHome: StateFlow<Boolean> = _isRefreshingHome.asStateFlow()
    private val _isLoadingMoreHome = MutableStateFlow(false)
    val isLoadingMoreHome: StateFlow<Boolean> = _isLoadingMoreHome.asStateFlow()
    private var homeBatchIndex = 0
    private var homeContinuationToken: String? = null

    // 20-item Shorts batching state
    private val _shortsState = MutableStateFlow<UiState<List<MediaItem>>>(UiState.Loading)
    val shortsState: StateFlow<UiState<List<MediaItem>>> = _shortsState.asStateFlow()
    private var currentShortsBatchIndex = 0
    private var shortsContinuationToken: String? = null
    private var isLoadingMoreShorts = false

    private val _featuredChannelsState = MutableStateFlow<UiState<List<ChannelInfo>>>(UiState.Loading)
    val featuredChannelsState: StateFlow<UiState<List<ChannelInfo>>> = _featuredChannelsState.asStateFlow()

    private val _searchFilter = MutableStateFlow(SearchFilter.VIDEOS)
    val searchFilter: StateFlow<SearchFilter> = _searchFilter.asStateFlow()

    private val _channelDetailState = MutableStateFlow<UiState<ChannelInfo>>(UiState.Loading)
    val channelDetailState: StateFlow<UiState<ChannelInfo>> = _channelDetailState.asStateFlow()

    private val _searchState = MutableStateFlow<UiState<SearchResultBundle>?>(null)
    val searchState: StateFlow<UiState<SearchResultBundle>?> = _searchState.asStateFlow()

    private val searchPrefs = app.getSharedPreferences("streamtube_search_prefs", Context.MODE_PRIVATE)
    private val _recentSearches = MutableStateFlow(loadRecentSearchesFromPrefs())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()
    private var suggestionJob: Job? = null

    private val _shortsSearchQuery = MutableStateFlow<String?>(null)
    val shortsSearchQuery: StateFlow<String?> = _shortsSearchQuery.asStateFlow()
    private val _isSearchingShorts = MutableStateFlow(false)
    val isSearchingShorts: StateFlow<Boolean> = _isSearchingShorts.asStateFlow()
    private var mainShortsBackup: List<MediaItem> = emptyList()

    private val _activeVideo = MutableStateFlow<MediaItem?>(null)
    val activeVideo: StateFlow<MediaItem?> = _activeVideo.asStateFlow()

    private val _activeOfflinePath = MutableStateFlow<String?>(null)
    val activeOfflinePath: StateFlow<String?> = _activeOfflinePath.asStateFlow()

    private val _isWatchExpanded = MutableStateFlow(false)
    val isWatchExpanded: StateFlow<Boolean> = _isWatchExpanded.asStateFlow()

    private val _watchFormatState = MutableStateFlow<UiState<MediaItemFormatInfo>>(UiState.Loading)
    val watchFormatState: StateFlow<UiState<MediaItemFormatInfo>> = _watchFormatState.asStateFlow()

    private val _relatedVideos = MutableStateFlow<List<MediaItem>>(emptyList())
    val relatedVideos: StateFlow<List<MediaItem>> = _relatedVideos.asStateFlow()

    private val _isRelatedLoading = MutableStateFlow(false)
    val isRelatedLoading: StateFlow<Boolean> = _isRelatedLoading.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _selectedQuality = MutableStateFlow("360p")
    val selectedQuality: StateFlow<String> = _selectedQuality.asStateFlow()

    private val _downloadSheetItem = MutableStateFlow<MediaItem?>(null)
    val downloadSheetItem: StateFlow<MediaItem?> = _downloadSheetItem.asStateFlow()
    private var pendingDownloadOrigin: Offset = Offset(500f, 800f)

    private val _downloadSheetFormatState = MutableStateFlow<UiState<MediaItemFormatInfo>>(UiState.Loading)
    val downloadSheetFormatState: StateFlow<UiState<MediaItemFormatInfo>> = _downloadSheetFormatState.asStateFlow()

    private val _rewardedLoadingFormatItag = MutableStateFlow<Int?>(null)
    val rewardedLoadingFormatItag: StateFlow<Int?> = _rewardedLoadingFormatItag.asStateFlow()

    private val _flyingDownloadEvent = MutableStateFlow<FlyingDownloadEvent?>(null)
    val flyingDownloadEvent: StateFlow<FlyingDownloadEvent?> = _flyingDownloadEvent.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    val downloads: StateFlow<List<DownloadedVideoEntity>> = downloadManager.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<SubscribedChannelEntity>> = subscriptionDao.observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchHistory: StateFlow<List<WatchHistoryEntity>> = historyDao.observeRecentHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val likedVideos: StateFlow<List<LikedVideoEntity>> = likedDao.observeLikedVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedVideos: StateFlow<List<SavedVideoEntity>> = savedDao.observeSavedVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        seamlessPlayerManager.onRequestReExtract = { videoId, resumePositionMs ->
            reExtractAndResume(videoId, resumePositionMs)
        }
        // Prioritize Home feed immediately on startup; defer non-critical background fetches slightly
        loadHomeFeed(FeedCategory.HOME)
        viewModelScope.launch {
            delay(350L)
            loadInitialShortsBatch()
            delay(850L)
            loadFeaturedChannels()
        }
        updateManager.checkForUpdates(notifyUserIfAvailable = true)
    }

    fun checkForUpdates() = updateManager.checkForUpdates(notifyUserIfAvailable = true)

    fun checkForUpdatesIfStale() = updateManager.checkForUpdatesIfStale()

    fun dismissUpdateBanner() = updateManager.dismissBanner()

    fun notifyPendingUpdateIfAllowed() = updateManager.postPendingNotificationIfAvailable()

    fun startAppUpdate(release: GitHubReleaseInfo) = updateManager.startUpdateDownload(release)

    fun installDownloadedUpdate(apkFile: File) = updateManager.installDownloadedApk(apkFile)

    private var openVideoJob: Job? = null
    private var reExtractJob: Job? = null

    private fun reExtractAndResume(videoId: String, resumePositionMs: Long) {
        reExtractJob?.cancel()
        reExtractJob = viewModelScope.launch {
            try {
                val refreshed = repository.resolveStreamFormats(videoId, forceRefresh = true)
                if (refreshed.bestPlaybackUrl != null && _activeVideo.value?.id == videoId) {
                    _watchFormatState.value = UiState.Success(refreshed)
                    _selectedQuality.value = "360p"
                    seamlessPlayerManager.playInstant360p(
                        formatInfo = refreshed,
                        speed = _playbackSpeed.value,
                        startPositionMs = resumePositionMs,
                        isReExtracted = true
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun selectCategory(category: FeedCategory) {
        _selectedCategory.value = category
        loadHomeFeed(category, forceRefresh = true)
    }

    fun refreshHomeFeed() {
        loadHomeFeed(category = _selectedCategory.value, forceRefresh = true, isPullRefresh = true)
    }

    fun loadHomeFeed(
        category: FeedCategory = _selectedCategory.value,
        forceRefresh: Boolean = false,
        isPullRefresh: Boolean = false
    ) {
        viewModelScope.launch {
            if (isPullRefresh && _homeFeedState.value is UiState.Success) {
                _isRefreshingHome.value = true
            } else if (forceRefresh || _homeFeedState.value !is UiState.Success) {
                _homeFeedState.value = UiState.Loading
            }
            homeBatchIndex = 0
            homeContinuationToken = null
            _isLoadingMoreHome.value = false
            try {
                val group = repository.getHomeFeedBatch(
                    category = category,
                    batchIndex = 0,
                    batchSize = 20,
                    continuationToken = null
                )
                homeContinuationToken = group.continuationToken
                if (group.items.isNotEmpty()) {
                    _homeFeedState.value = UiState.Success(group.items)
                } else if (!isPullRefresh || _homeFeedState.value !is UiState.Success) {
                    _homeFeedState.value = UiState.Error("No videos returned. Tap Retry to reload.")
                }
            } catch (e: Exception) {
                if (!isPullRefresh || _homeFeedState.value !is UiState.Success) {
                    _homeFeedState.value = UiState.Error(e.message ?: "Unable to connect to feed.")
                }
            } finally {
                _isRefreshingHome.value = false
            }
        }
    }

    /**
     * Automatically loads & pre-parses the next batch of 20 Home videos when user scrolls to ~15th item.
     */
    fun loadNextHomeBatch() {
        val currentList = (_homeFeedState.value as? UiState.Success)?.data ?: return
        if (_isLoadingMoreHome.value) return
        _isLoadingMoreHome.value = true
        val category = _selectedCategory.value
        viewModelScope.launch {
            try {
                val nextIndex = homeBatchIndex + 1
                val nextGroup = repository.getHomeFeedBatch(
                    category = category,
                    batchIndex = nextIndex,
                    batchSize = 20,
                    continuationToken = homeContinuationToken
                )
                if (_selectedCategory.value == category) {
                    homeBatchIndex = nextIndex
                    homeContinuationToken = nextGroup.continuationToken
                    val merged = (currentList + nextGroup.items).distinctBy { it.id }
                    _homeFeedState.value = UiState.Success(merged)
                }
            } catch (_: Exception) {
            } finally {
                _isLoadingMoreHome.value = false
            }
        }
    }

    private var hasLoadedShortsOnce = false

    fun loadInitialShortsBatch(forceRefresh: Boolean = false) {
        if (!forceRefresh && hasLoadedShortsOnce &&
            (_shortsState.value as? UiState.Success)?.data?.isNotEmpty() == true
        ) return
        viewModelScope.launch {
            _shortsState.value = UiState.Loading
            currentShortsBatchIndex = 0
            shortsContinuationToken = null
            try {
                var batch = repository.getShortsBatch(batchIndex = 0, batchSize = 20)
                if (batch.items.isEmpty()) {
                    // Empty-Tab Auto-Retry (#7): keep hasLoadedShortsOnce = false and run #shorts viral query
                    val fallbackBundle = repository.search("#shorts viral", SearchFilter.SHORTS)
                    if (fallbackBundle.shorts.isNotEmpty()) {
                        batch = batch.copy(items = fallbackBundle.shorts)
                    }
                }
                shortsContinuationToken = batch.continuationToken
                if (batch.items.isNotEmpty()) {
                    hasLoadedShortsOnce = true
                    _shortsState.value = UiState.Success(batch.items)
                    repository.prefetchShortsWindow(batch.items, 0)
                } else {
                    hasLoadedShortsOnce = false
                    _shortsState.value = UiState.Error("Could not load Shorts batch.")
                }
            } catch (e: Exception) {
                hasLoadedShortsOnce = false
                _shortsState.value = UiState.Error(e.message ?: "Network error loading Shorts.")
            }
        }
    }

    fun onShortPageChanged(currentIndex: Int) {
        val currentList = (_shortsState.value as? UiState.Success)?.data ?: return
        // Optimization #1: Background prefetch +1 and +2 Shorts with duplicate guard
        repository.prefetchShortsWindow(currentList, currentIndex)

        // Optimization #6: Pre-pagination trigger at videos.size - 5
        if (!isLoadingMoreShorts && currentList.isNotEmpty() && currentIndex >= (currentList.size - 5).coerceAtLeast(1)) {
            isLoadingMoreShorts = true
            viewModelScope.launch {
                try {
                    val nextBatchIndex = currentShortsBatchIndex + 1
                    val nextGroup = repository.getShortsBatch(
                        batchIndex = nextBatchIndex,
                        batchSize = 20,
                        continuationToken = shortsContinuationToken
                    )
                    shortsContinuationToken = nextGroup.continuationToken
                    currentShortsBatchIndex = nextBatchIndex
                    val merged = (currentList + nextGroup.items).distinctBy { it.id }
                    _shortsState.value = UiState.Success(merged)
                } catch (_: Exception) {
                } finally {
                    isLoadingMoreShorts = false
                }
            }
        }
    }

    fun focusShortInFeed(item: MediaItem) {
        val current = (_shortsState.value as? UiState.Success)?.data.orEmpty()
        val updated = (listOf(item.copy(isShort = true, badgeText = "SHORTS")) + current)
            .distinctBy { it.id }
        _shortsState.value = UiState.Success(updated)
    }

    fun markShortViewed(videoId: String) {
        repository.markShortSeen(videoId)
        val matched = (_shortsState.value as? UiState.Success)?.data?.firstOrNull { it.id == videoId }
        if (matched != null) {
            repository.recordShortInterest(matched, weight = 1)
        }
    }

    /**
     * Searches Shorts and immediately plays matching Shorts in the SAME Shorts VerticalPager container.
     */
    fun searchShortsInPlace(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _isSearchingShorts.value = true
            try {
                if (_shortsSearchQuery.value == null) {
                    mainShortsBackup = (_shortsState.value as? UiState.Success)?.data.orEmpty()
                }
                val bundle = repository.search(trimmed, SearchFilter.SHORTS)
                val items = bundle.shorts.map { it.copy(isShort = true, badgeText = "SHORTS") }
                if (items.isNotEmpty()) {
                    _shortsSearchQuery.value = trimmed
                    _shortsState.value = UiState.Success(items)
                } else {
                    _toastMessage.value = "No Shorts found for \"$trimmed\""
                }
            } catch (e: Exception) {
                _toastMessage.value = e.message ?: "Shorts search failed."
            } finally {
                _isSearchingShorts.value = false
            }
        }
    }

    fun clearShortsSearch() {
        _shortsSearchQuery.value = null
        if (mainShortsBackup.isNotEmpty()) {
            _shortsState.value = UiState.Success(mainShortsBackup)
            mainShortsBackup = emptyList()
        } else {
            loadInitialShortsBatch(forceRefresh = true)
        }
    }

    suspend fun resolveShortFormatInfo(videoId: String, forceRefresh: Boolean = false): MediaItemFormatInfo? {
        return try {
            val info = repository.resolveStreamFormats(videoId, forceRefresh = forceRefresh)
            if (info.bestPlaybackUrl != null) info else null
        } catch (_: Exception) {
            null
        }
    }

    fun preWarmUpcomingShort(videoId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = resolveShortFormatInfo(videoId, false) ?: return@launch
            playerEngine.preWarmStreamHead(info, isShort = true)
        }
    }

    fun loadFeaturedChannels() {
        viewModelScope.launch {
            _featuredChannelsState.value = UiState.Loading
            try {
                val list = repository.getFeaturedChannels()
                _featuredChannelsState.value = UiState.Success(list)
            } catch (e: Exception) {
                _featuredChannelsState.value = UiState.Error(e.message ?: "Unable to load channels.")
            }
        }
    }

    fun openChannel(channelId: String, fallbackName: String = "", fallbackAvatar: String = "") {
        viewModelScope.launch {
            _channelDetailState.value = UiState.Loading
            try {
                val info = repository.getChannelInfo(channelId, fallbackName, fallbackAvatar)
                _channelDetailState.value = UiState.Success(info)
            } catch (e: Exception) {
                _channelDetailState.value = UiState.Error(e.message ?: "Could not load channel.")
            }
        }
    }

    fun toggleSubscribe(channel: ChannelInfo) {
        viewModelScope.launch {
            val existing = subscriptions.value.any { it.channelId == channel.channelId }
            if (existing) {
                subscriptionDao.unsubscribe(channel.channelId)
                _toastMessage.value = "Unsubscribed from ${channel.name}"
            } else {
                subscriptionDao.subscribe(
                    SubscribedChannelEntity(
                        channelId = channel.channelId,
                        name = channel.name,
                        avatarUrl = channel.avatarUrl,
                        subscriberCountText = channel.subscriberCountText,
                        isVerified = channel.isVerified
                    )
                )
                _toastMessage.value = "Subscribed to ${channel.name}"
            }
        }
    }

    fun toggleLike(item: MediaItem) {
        if (item.isShort) repository.recordShortInterest(item, weight = 3)
        viewModelScope.launch {
            val isAlreadyLiked = likedVideos.value.any { it.videoId == item.id }
            if (isAlreadyLiked) {
                likedDao.unlikeVideo(item.id)
                _toastMessage.value = "Removed from Liked videos"
            } else {
                likedDao.likeVideo(
                    LikedVideoEntity(
                        videoId = item.id,
                        title = item.title,
                        channelId = item.channelId,
                        channelName = item.channelName,
                        channelAvatarUrl = item.channelAvatarUrl,
                        thumbnailUrl = item.thumbnailUrl,
                        viewCountText = item.viewCountText,
                        publishedDateText = item.displayDateTime,
                        durationText = item.durationText
                    )
                )
                _toastMessage.value = "Added to Liked videos"
            }
        }
    }

    fun toggleSave(item: MediaItem) {
        if (item.isShort) repository.recordShortInterest(item, weight = 3)
        viewModelScope.launch {
            val isAlreadySaved = savedVideos.value.any { it.videoId == item.id }
            if (isAlreadySaved) {
                savedDao.unsaveVideo(item.id)
                _toastMessage.value = "Removed from Saved videos"
            } else {
                savedDao.saveVideo(
                    SavedVideoEntity(
                        videoId = item.id,
                        title = item.title,
                        channelId = item.channelId,
                        channelName = item.channelName,
                        channelAvatarUrl = item.channelAvatarUrl,
                        thumbnailUrl = item.thumbnailUrl,
                        viewCountText = item.viewCountText,
                        publishedDateText = item.displayDateTime,
                        durationText = item.durationText
                    )
                )
                _toastMessage.value = "Saved video to Library"
            }
        }
    }

    fun unlikeVideo(videoId: String) {
        viewModelScope.launch {
            likedDao.unlikeVideo(videoId)
            _toastMessage.value = "Removed from Liked"
        }
    }

    fun unsaveVideo(videoId: String) {
        viewModelScope.launch {
            savedDao.unsaveVideo(videoId)
            _toastMessage.value = "Removed from Saved"
        }
    }

    fun onSearchQueryChanged(query: String) {
        suggestionJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchSuggestions.value = emptyList()
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(200L)
            val live = runCatching { repository.getSearchSuggestions(trimmed) }
                .getOrDefault(emptyList())
            _searchSuggestions.value = live
        }
    }

    fun removeRecentSearch(query: String) {
        val updated = _recentSearches.value.filterNot { it.equals(query, ignoreCase = true) }
        _recentSearches.value = updated
        saveRecentSearchesToPrefs(updated)
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        saveRecentSearchesToPrefs(emptyList())
    }

    private fun recordRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val updated = (listOf(trimmed) + _recentSearches.value.filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(20)
        _recentSearches.value = updated
        saveRecentSearchesToPrefs(updated)
    }

    private fun loadRecentSearchesFromPrefs(): List<String> {
        val raw = searchPrefs.getString(KEY_RECENT_SEARCHES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotBlank()) add(v)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecentSearchesToPrefs(items: List<String>) {
        val arr = JSONArray().apply { items.forEach { put(it) } }
        searchPrefs.edit().putString(KEY_RECENT_SEARCHES, arr.toString()).apply()
    }

    fun selectSearchFilter(filter: SearchFilter, currentQuery: String) {
        _searchFilter.value = filter
        if (currentQuery.isNotBlank()) {
            performSearch(currentQuery, filter)
        }
    }

    fun performSearch(query: String, filter: SearchFilter = _searchFilter.value) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        _searchFilter.value = filter
        recordRecentSearch(trimmed)
        viewModelScope.launch {
            _searchState.value = UiState.Loading
            try {
                val bundle = repository.search(trimmed, filter)
                _searchState.value = UiState.Success(bundle)
            } catch (e: Exception) {
                _searchState.value = UiState.Error(e.message ?: "Search failed.")
            }
        }
    }

    fun openVideo(item: MediaItem, offlineLocalPath: String? = null) {
        openVideoJob?.cancel()
        reExtractJob?.cancel()
        seamlessPlayerManager.stopImmediatelyForNewVideo()

        _activeVideo.value = item
        _activeOfflinePath.value = offlineLocalPath
        _isWatchExpanded.value = true
        _selectedQuality.value = "360p"

        viewModelScope.launch {
            historyDao.upsertHistory(
                WatchHistoryEntity(
                    videoId = item.id,
                    title = item.title,
                    channelId = item.channelId,
                    channelName = item.channelName,
                    channelAvatarUrl = item.channelAvatarUrl,
                    thumbnailUrl = item.thumbnailUrl,
                    viewCountText = item.viewCountText,
                    publishedDateText = item.displayDateTime,
                    durationText = item.durationText
                )
            )
        }

        if (!offlineLocalPath.isNullOrBlank()) {
            _watchFormatState.value = UiState.Success(
                MediaItemFormatInfo(
                    videoId = item.id,
                    title = item.title,
                    author = item.channelName,
                    channelId = item.channelId,
                    channelAvatarUrl = item.channelAvatarUrl,
                    viewCountText = item.viewCountText,
                    publishedDateText = item.publishedDateText,
                    publishedExactTimeText = item.publishedExactTimeText,
                    durationMs = item.durationMs
                )
            )
            seamlessPlayerManager.playOfflineFile(offlineLocalPath, _playbackSpeed.value)
            return
        }

        openVideoJob = viewModelScope.launch {
            _relatedVideos.value = emptyList()
            _isRelatedLoading.value = true
            val relatedStartMs = System.currentTimeMillis()
            val relatedJob = launch {
                val related = runCatching { repository.getRelatedVideos(item.id) }
                    .getOrDefault(emptyList())
                    .take(10)
                val elapsed = System.currentTimeMillis() - relatedStartMs
                val minDisplayDelay = (1200L - elapsed).coerceAtLeast(0L)
                if (minDisplayDelay > 0L) kotlinx.coroutines.delay(minDisplayDelay)
                if (_activeVideo.value?.id == item.id) {
                    _relatedVideos.value = related
                    _isRelatedLoading.value = false
                }
            }

            val completedDownload = downloadDao.getCompletedDownloadForVideo(item.id)
            if (completedDownload != null) {
                _activeOfflinePath.value = completedDownload.localFilePath
                seamlessPlayerManager.playOfflineFile(completedDownload.localFilePath, _playbackSpeed.value)
            }

            val preParsed = repository.getCachedStream(item.id)
            if (preParsed != null && completedDownload == null) {
                _watchFormatState.value = UiState.Success(preParsed)
                seamlessPlayerManager.playInstant360p(preParsed, _playbackSpeed.value)
            } else if (completedDownload == null) {
                _watchFormatState.value = UiState.Loading
            }

            val formatDeferred = async {
                preParsed ?: repository.resolveStreamFormats(item.id, forceRefresh = false)
            }

            try {
                val info = formatDeferred.await()
                if (info.bestPlaybackUrl != null) {
                    _watchFormatState.value = UiState.Success(info)
                    if (preParsed == null && completedDownload == null) {
                        seamlessPlayerManager.playInstant360p(info, _playbackSpeed.value)
                    }
                    _activeVideo.value = _activeVideo.value?.let { cur ->
                        cur.copy(
                            channelId = cur.channelId.ifBlank { info.channelId },
                            channelName = cur.channelName.ifBlank { info.author },
                            channelAvatarUrl = cur.channelAvatarUrl.ifBlank { info.channelAvatarUrl },
                            viewCountText = cur.viewCountText.ifBlank { info.viewCountText },
                            publishedDateText = info.publishedDateText.ifBlank { cur.publishedDateText },
                            publishedExactTimeText = info.publishedExactTimeText.ifBlank { cur.publishedExactTimeText },
                            description = info.description.ifBlank { cur.description }
                        )
                    }
                } else if (completedDownload == null) {
                    _watchFormatState.value = UiState.Error("Stream unavailable. Tap Retry.")
                }
            } catch (e: Exception) {
                if (completedDownload == null) {
                    _watchFormatState.value = UiState.Error(e.message ?: "Failed to load video stream.")
                }
            }

            relatedJob.join()
        }
    }

    fun minimizePlayer() {
        _isWatchExpanded.value = false
    }

    fun expandPlayer() {
        if (_activeVideo.value != null) {
            _isWatchExpanded.value = true
        }
    }

    fun closePlayer() {
        openVideoJob?.cancel()
        reExtractJob?.cancel()
        seamlessPlayerManager.stopImmediatelyForNewVideo()
        _activeVideo.value = null
        _activeOfflinePath.value = null
        _isWatchExpanded.value = false
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
    }

    fun switchQualitySeamlessly(quality: String) {
        val info = (_watchFormatState.value as? UiState.Success)?.data ?: return
        if (info.bestPlaybackUrl == null) return
        _selectedQuality.value = quality
        seamlessPlayerManager.switchQualitySeamlessly(
            formatInfo = info,
            targetQuality = quality,
            speed = _playbackSpeed.value
        )
    }

    fun openDownloadSheet(item: MediaItem, anchorOffset: Offset? = null) {
        if (anchorOffset != null) {
            pendingDownloadOrigin = anchorOffset
        }
        _rewardedLoadingFormatItag.value = null
        unityAdsManager.loadRewarded()
        _downloadSheetItem.value = item
        val preParsed = repository.getCachedStream(item.id)
        if (preParsed != null && preParsed.formats.isNotEmpty()) {
            _downloadSheetFormatState.value = UiState.Success(preParsed)
            return
        }
        viewModelScope.launch {
            _downloadSheetFormatState.value = UiState.Loading
            try {
                val info = repository.resolveStreamFormats(item.id)
                if (info.formats.isNotEmpty()) {
                    _downloadSheetFormatState.value = UiState.Success(info)
                } else {
                    _downloadSheetFormatState.value = UiState.Error("No downloadable formats found.")
                }
            } catch (e: Exception) {
                _downloadSheetFormatState.value = UiState.Error(e.message ?: "Failed to fetch formats.")
            }
        }
    }

    fun dismissDownloadSheet() {
        _rewardedLoadingFormatItag.value = null
        _downloadSheetItem.value = null
    }

    fun handleFormatDownloadClick(
        activity: Activity?,
        item: MediaItem,
        format: MediaFormatOption
    ) {
        if (!AdPlacementHelper.requiresRewardedAdForDownload(format.qualityLabel)) {
            startOneClickDownload(item, format)
            return
        }
        if (_rewardedLoadingFormatItag.value != null) return
        _rewardedLoadingFormatItag.value = format.itag
        unityAdsManager.showRewardedForDownload(
            activity = activity,
            qualityLabel = format.qualityLabel,
            onRewardEarned = {
                _rewardedLoadingFormatItag.value = null
                startOneClickDownload(item, format)
            },
            onBlocked = { reason ->
                _rewardedLoadingFormatItag.value = null
                _toastMessage.value = reason
            }
        )
    }

    fun startOneClickDownload(item: MediaItem, format: MediaFormatOption) {
        val ua = (_downloadSheetFormatState.value as? UiState.Success)?.data?.requestUserAgent
            ?: (_watchFormatState.value as? UiState.Success)?.data?.requestUserAgent
            ?: repository.getCachedStream(item.id)?.requestUserAgent
        downloadManager.startOneClickDownload(item, format, ua)
        _downloadSheetItem.value = null
        _flyingDownloadEvent.value = FlyingDownloadEvent(
            thumbnailUrl = item.thumbnailUrl,
            startOffset = pendingDownloadOrigin
        )
        _toastMessage.value = "Download started: ${format.qualityLabel}"
    }

    fun clearFlyingDownloadEvent() {
        _flyingDownloadEvent.value = null
    }

    fun retryDownload(entity: DownloadedVideoEntity) {
        downloadManager.retryDownload(entity)
        _toastMessage.value = "Retrying ${entity.qualityLabel}…"
    }

    fun deleteDownload(entity: DownloadedVideoEntity) {
        downloadManager.cancelOrDeleteDownload(entity)
        _toastMessage.value = "Removed ${entity.title}"
    }

    fun consumeToast() {
        _toastMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        seamlessPlayerManager.releaseAll()
    }

    companion object {
        private const val KEY_RECENT_SEARCHES = "recent_search_queries"
    }
}
