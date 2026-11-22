package com.example.data

import android.util.Log
import com.example.data.local.LikedVideoDao
import com.example.data.local.SavedVideoDao
import com.example.data.local.WatchHistoryDao
import com.example.mediaserviceinterfaces.ChannelInfo
import com.example.mediaserviceinterfaces.FeedCategory
import com.example.mediaserviceinterfaces.MediaGroup
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import com.example.mediaserviceinterfaces.MediaService
import com.example.mediaserviceinterfaces.SearchFilter
import com.example.mediaserviceinterfaces.SearchResultBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository managing 20-item Home & Shorts batch loading, filtered Search, Channel fetching,
 * and background coroutine pre-parsing so video playback starts instantly.
 */
class StreamRepository(
    private val primaryService: MediaService,
    private val watchHistoryDao: WatchHistoryDao,
    private val likedVideoDao: LikedVideoDao? = null,
    private val savedVideoDao: SavedVideoDao? = null,
    private val onPreWarmHead: ((MediaItemFormatInfo, Boolean) -> Unit)? = null
) {
    private data class CachedStreamEntry(
        val formatInfo: MediaItemFormatInfo,
        val cachedAtMs: Long = System.currentTimeMillis()
    )

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchSemaphore = Semaphore(2)
    private val warmSemaphore = Semaphore(1)

    private val streamCache = ConcurrentHashMap<String, CachedStreamEntry>()
    private val inFlightExtractions = ConcurrentHashMap<String, Deferred<MediaItemFormatInfo>>()
    private val inFlightPrefetches = ConcurrentHashMap.newKeySet<String>()

    fun getCachedStream(videoId: String): MediaItemFormatInfo? {
        val entry = streamCache[videoId] ?: return null
        val isFresh = System.currentTimeMillis() - entry.cachedAtMs < CACHE_TTL_MS
        return if (isFresh && entry.formatInfo.bestPlaybackUrl != null) entry.formatInfo else null
    }

    suspend fun getHomeFeedBatch(
        category: FeedCategory,
        batchIndex: Int = 0,
        batchSize: Int = 20,
        continuationToken: String? = null
    ): MediaGroup {
        val interestQuery = if (category == FeedCategory.HOME && batchIndex == 0) {
            buildPersonalizedInterestQuery()
        } else null

        val group = primaryService.getHomeFeed(
            category = category,
            batchIndex = batchIndex,
            batchSize = batchSize,
            continuationToken = continuationToken,
            historyInterestQuery = interestQuery
        )
        if (group.items.isNotEmpty()) {
            // Pre-parse top Home items cleanly without saturating slow mobile links
            prefetchStreamsInBackground(group.items.take(8), warmHeadCount = 3, isShort = false)
        }
        return group
    }

    suspend fun getShortsBatch(
        batchIndex: Int,
        batchSize: Int = 20,
        continuationToken: String? = null
    ): MediaGroup {
        val group = primaryService.getShortsBatch(
            batchIndex = batchIndex,
            batchSize = batchSize,
            continuationToken = continuationToken
        )
        if (group.items.isNotEmpty()) {
            // Pre-parse all 20 Shorts and pre-warm first 6 for zero-buffer vertical swipes
            prefetchStreamsInBackground(group.items, warmHeadCount = 6, isShort = true)
        }
        return group
    }

    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.VIDEOS
    ): SearchResultBundle {
        val bundle = primaryService.search(query, filter)
        prefetchStreamsInBackground(bundle.videos, warmHeadCount = 5, isShort = false)
        prefetchStreamsInBackground(bundle.shorts, warmHeadCount = 5, isShort = true)
        return bundle
    }

    suspend fun getSearchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return primaryService.getSearchSuggestions(query)
    }

    fun markShortSeen(videoId: String) {
        primaryService.markShortSeen(videoId)
    }

    fun recordShortInterest(item: MediaItem, weight: Int = 1) {
        primaryService.recordShortInterest(item, weight)
    }

    suspend fun getChannelInfo(
        channelId: String,
        fallbackName: String = "",
        fallbackAvatar: String = ""
    ): ChannelInfo {
        val info = primaryService.getChannelInfo(channelId, fallbackName, fallbackAvatar)
        prefetchStreamsInBackground(info.videos, warmHeadCount = 4, isShort = false)
        prefetchStreamsInBackground(info.shorts, warmHeadCount = 3, isShort = true)
        return info
    }

    suspend fun getRelatedVideos(videoId: String): List<MediaItem> {
        val related = primaryService.getRelatedVideos(videoId).take(10)
        prefetchStreamsInBackground(related, warmHeadCount = 4, isShort = false)
        return related
    }

    suspend fun getFeaturedChannels(): List<ChannelInfo> = primaryService.getFeaturedChannels()

    suspend fun resolveStreamFormats(
        videoId: String,
        forceRefresh: Boolean = false
    ): MediaItemFormatInfo {
        if (!forceRefresh) {
            getCachedStream(videoId)?.let { return it }
        } else {
            streamCache.remove(videoId)
        }

        val existingJob = inFlightExtractions[videoId]
        if (existingJob != null) {
            return existingJob.await()
        }

        val deferred = repoScope.async {
            try {
                val resolved = try {
                    primaryService.getMediaItemFormatInfo(
                        videoId = videoId,
                        preferFallbackClient = forceRefresh
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Stream resolution failed for $videoId: ${e.message}")
                    MediaItemFormatInfo(videoId = videoId, title = "Stream Unavailable")
                }

                if (resolved.bestPlaybackUrl != null) {
                    streamCache[videoId] = CachedStreamEntry(resolved)
                }
                resolved
            } finally {
                inFlightExtractions.remove(videoId)
            }
        }

        inFlightExtractions[videoId] = deferred
        return deferred.await()
    }

    fun prefetchShortsWindow(items: List<MediaItem>, currentIndex: Int) {
        (1..2).forEach { offset ->
            val upcoming = items.getOrNull(currentIndex + offset) ?: return@forEach
            if (getCachedStream(upcoming.id) != null) {
                repoScope.launch {
                    getCachedStream(upcoming.id)?.let { cached ->
                        warmSemaphore.withPermit {
                            onPreWarmHead?.invoke(cached, true)
                        }
                    }
                }
                return@forEach
            }
            if (!inFlightPrefetches.add(upcoming.id)) return@forEach
            repoScope.launch {
                try {
                    val info = prefetchSemaphore.withPermit {
                        runCatching { resolveStreamFormats(upcoming.id) }.getOrNull()
                    }
                    if (info != null && info.bestPlaybackUrl != null) {
                        warmSemaphore.withPermit {
                            onPreWarmHead?.invoke(info, true)
                        }
                    }
                } finally {
                    inFlightPrefetches.remove(upcoming.id)
                }
            }
        }
    }

    fun prefetchStreamsInBackground(
        items: List<MediaItem>,
        warmHeadCount: Int = 4,
        isShort: Boolean = false
    ) {
        if (items.isEmpty()) return
        items.forEachIndexed { index, item ->
            val shouldWarm = index < warmHeadCount
            if (getCachedStream(item.id) != null && !shouldWarm) return@forEachIndexed
            if (!inFlightPrefetches.add(item.id)) return@forEachIndexed
            repoScope.launch {
                try {
                    val info = prefetchSemaphore.withPermit {
                        getCachedStream(item.id)
                            ?: runCatching { resolveStreamFormats(item.id) }.getOrNull()
                    }
                    if (shouldWarm && info != null && info.bestPlaybackUrl != null) {
                        warmSemaphore.withPermit {
                            onPreWarmHead?.invoke(info, isShort || item.isShort)
                        }
                    }
                } finally {
                    inFlightPrefetches.remove(item.id)
                }
            }
        }
    }

    private var interestRotationCounter = 0

    private suspend fun buildPersonalizedInterestQuery(): String? {
        return try {
            val liked = likedVideoDao?.observeLikedVideos()?.firstOrNull().orEmpty().map { it.title }
            val saved = savedVideoDao?.observeSavedVideos()?.firstOrNull().orEmpty().map { it.title }
            val recent = watchHistoryDao.observeRecentHistory().firstOrNull().orEmpty().map { it.title }
            val fillerWords = setOf(
                "official", "video", "videos", "full", "episode", "latest", "trending",
                "viral", "shorts", "short", "live", "today", "watch", "review", "new"
            )
            val candidateKeywords = (recent.take(6) + liked.take(4) + saved.take(4))
                .mapNotNull { title ->
                    title.split(Regex("[\\s|\\-:/()\\[\\]]+"))
                        .firstOrNull { token ->
                            token.length >= 4 &&
                                token.all { ch -> ch.isLetter() } &&
                                !fillerWords.contains(token.lowercase())
                        }
                }
                .distinctBy { it.lowercase() }
            if (candidateKeywords.isEmpty()) return null
            val picked = candidateKeywords[interestRotationCounter % candidateKeywords.size]
            interestRotationCounter++
            picked
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "StreamRepository"
        private const val CACHE_TTL_MS = 25 * 60 * 1000L
    }
}
