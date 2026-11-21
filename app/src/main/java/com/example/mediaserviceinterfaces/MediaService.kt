package com.example.mediaserviceinterfaces

/**
 * Core media service contract supporting batched Home/Shorts feeds,
 * filtered search (All, Videos, Shorts, Channels), channels, and multi-client stream extraction.
 */
interface MediaService {
    suspend fun getHomeFeed(
        category: FeedCategory = FeedCategory.HOME,
        batchIndex: Int = 0,
        batchSize: Int = 20,
        continuationToken: String? = null,
        historyInterestQuery: String? = null
    ): MediaGroup

    suspend fun getShortsBatch(
        batchIndex: Int = 0,
        batchSize: Int = 20,
        continuationToken: String? = null
    ): MediaGroup

    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.VIDEOS
    ): SearchResultBundle

    suspend fun getSearchSuggestions(query: String): List<String> = emptyList()

    fun markShortSeen(videoId: String) {}

    fun recordShortInterest(item: MediaItem, weight: Int = 1) {}

    suspend fun getChannelInfo(
        channelId: String,
        fallbackName: String = "",
        fallbackAvatar: String = ""
    ): ChannelInfo

    suspend fun getMediaItemFormatInfo(
        videoId: String,
        preferFallbackClient: Boolean = false
    ): MediaItemFormatInfo
    suspend fun getRelatedVideos(videoId: String): List<MediaItem>
    suspend fun getFeaturedChannels(): List<ChannelInfo>
}
