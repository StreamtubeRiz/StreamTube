package com.example.mediaserviceinterfaces

/**
 * Core media contracts for feeds, channels, search, playback formats, and downloads.
 */
data class MediaItem(
    val id: String,
    val title: String,
    val description: String = "",
    val videoUrl: String = "https://www.youtube.com/watch?v=$id",
    val thumbnailUrl: String = "https://i.ytimg.com/vi/$id/hq720.jpg",
    val fallbackThumbnailUrl: String = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
    val channelId: String = "",
    val channelName: String = "",
    val channelAvatarUrl: String = "",
    val viewCountText: String = "",
    val viewCount: Long = 0L,
    val publishedDateText: String = "",
    val publishedExactTimeText: String = "",
    val durationText: String = "",
    val durationMs: Long = 0L,
    val isShort: Boolean = false,
    val isLive: Boolean = false,
    val badgeText: String? = null
) {
    val displayDateTime: String
        get() = when {
            publishedDateText.isNotBlank() && publishedExactTimeText.isNotBlank() ->
                "$publishedDateText • $publishedExactTimeText"
            publishedDateText.isNotBlank() -> publishedDateText
            publishedExactTimeText.isNotBlank() -> publishedExactTimeText
            else -> "Recent"
        }
}

enum class FeedCategory(val label: String, val queryOrBrowseId: String) {
    HOME("All", "FEwhat_to_watch"),
    TRENDING("Trending", "FEtrending"),
    MUSIC("Music", "music"),
    GAMING("Gaming", "gaming"),
    TECH("Tech", "tech"),
    SPORTS("Sports", "sports"),
    NEWS("News", "news"),
    LIVE("Live", "live")
}

enum class SearchFilter(val label: String, val innertubeParams: String?) {
    VIDEOS("Videos", "EgIQAQ%3D%3D"),
    ALL("All", null),
    SHORTS("Shorts", "EgIQAQ%3D%3D"),
    CHANNELS("Channels", "EgIQAg%3D%3D")
}

data class MediaGroup(
    val id: String,
    val title: String,
    val category: FeedCategory = FeedCategory.HOME,
    val items: List<MediaItem> = emptyList(),
    val continuationToken: String? = null
)

data class MediaFormatOption(
    val itag: Int,
    val qualityLabel: String,
    val mimeType: String,
    val container: String,
    val url: String,
    val audioPairUrl: String? = null,
    val codec: String = "",
    val bitrate: Int = 0,
    val fps: Int = 30,
    val contentLengthBytes: Long = 0L,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    val isHls: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val initRangeStart: Long = -1L,
    val initRangeEnd: Long = -1L,
    val indexRangeStart: Long = -1L,
    val indexRangeEnd: Long = -1L
) {
    val hasSegmentRanges: Boolean
        get() = initRangeStart >= 0L && initRangeEnd > initRangeStart &&
            indexRangeStart >= 0L && indexRangeEnd > indexRangeStart

    val isAudioOnly: Boolean get() = !hasVideo && hasAudio
    val isMuxed: Boolean get() = hasVideo && hasAudio
    val isAv1: Boolean
        get() = codec.contains("av01", ignoreCase = true) || codec.contains("av1", ignoreCase = true)
    val isVp9: Boolean
        get() = codec.contains("vp9", ignoreCase = true) || codec.contains("vp09", ignoreCase = true)
    val isH264Compatible: Boolean
        get() = container.equals("MP4", ignoreCase = true) &&
            (codec.contains("avc1", ignoreCase = true) || codec.contains("h264", ignoreCase = true) ||
                (!isAv1 && !isVp9))

    /**
     * Hardware-friendly codec sort rank: avc1 (3) > vp9 (2) > unknown non-AV1 (1) > av01 (0).
     */
    val codecPriority: Int
        get() = when {
            isH264Compatible -> 3
            isVp9 -> 2
            isAv1 -> 0
            else -> 1
        }

    val formattedSize: String
        get() = when {
            contentLengthBytes <= 0L -> if (bitrate > 0) "${bitrate / 1000} kbps" else "Stream"
            contentLengthBytes >= 1_073_741_824L -> String.format("%.2f GB", contentLengthBytes / 1_073_741_824.0)
            contentLengthBytes >= 1_048_576L -> String.format("%.1f MB", contentLengthBytes / 1_048_576.0)
            else -> String.format("%d KB", contentLengthBytes / 1024L)
        }
}

data class MediaItemFormatInfo(
    val videoId: String,
    val title: String,
    val description: String = "",
    val author: String = "",
    val channelId: String = "",
    val channelAvatarUrl: String = "",
    val viewCountText: String = "",
    val publishedDateText: String = "",
    val publishedExactTimeText: String = "",
    val durationMs: Long = 0L,
    val hlsManifestUrl: String? = null,
    val dashManifestUrl: String? = null,
    val formats: List<MediaFormatOption> = emptyList(),
    val isLive: Boolean = false,
    val requestUserAgent: String? = null
) {
    val bestPlaybackUrl: String?
        get() = formats.firstOrNull { it.isMuxed && !it.isHls && it.url.isNotBlank() }?.url
            ?: formats.firstOrNull { it.hasVideo && !it.isHls && it.url.isNotBlank() }?.url
            ?: hlsManifestUrl?.takeIf { it.isNotBlank() }

    val bestAudioPairUrl: String?
        get() = formats.firstOrNull { it.isAudioOnly && it.url.isNotBlank() }?.url
}

data class ChannelInfo(
    val channelId: String,
    val name: String,
    val handle: String = "",
    val avatarUrl: String = "",
    val bannerUrl: String = "",
    val subscriberCountText: String = "",
    val videoCountText: String = "",
    val description: String = "",
    val isVerified: Boolean = false,
    val videos: List<MediaItem> = emptyList(),
    val shorts: List<MediaItem> = emptyList()
)

data class SearchResultBundle(
    val query: String,
    val filter: SearchFilter = SearchFilter.ALL,
    val channels: List<ChannelInfo> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    val shorts: List<MediaItem> = emptyList()
)
