package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_videos")
data class DownloadedVideoEntity(
    @PrimaryKey val downloadKey: String, // "${videoId}_${itag}"
    val videoId: String,
    val formatItag: Int,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String,
    val thumbnailUrl: String,
    val qualityLabel: String,
    val container: String,
    val localFilePath: String,
    val publicContentUri: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String, // QUEUED, DOWNLOADING, MERGING, COMPLETED, FAILED
    val viewCountText: String,
    val publishedDateText: String,
    val durationText: String,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        else if (status == STATUS_COMPLETED) 1f else 0f

    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_MERGING = "MERGING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
    }
}

@Entity(tableName = "subscribed_channels")
data class SubscribedChannelEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val avatarUrl: String,
    val subscriberCountText: String,
    val isVerified: Boolean,
    val subscribedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String,
    val thumbnailUrl: String,
    val viewCountText: String,
    val publishedDateText: String,
    val durationText: String,
    val lastPositionMs: Long = 0L,
    val watchedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "liked_videos")
data class LikedVideoEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String,
    val thumbnailUrl: String,
    val viewCountText: String,
    val publishedDateText: String,
    val durationText: String,
    val likedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_videos")
data class SavedVideoEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String,
    val thumbnailUrl: String,
    val viewCountText: String,
    val publishedDateText: String,
    val durationText: String,
    val savedAtMs: Long = System.currentTimeMillis()
)
