package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloaded_videos ORDER BY createdAtMs DESC")
    fun observeAllDownloads(): Flow<List<DownloadedVideoEntity>>

    @Query("SELECT * FROM downloaded_videos WHERE videoId = :videoId AND status = 'COMPLETED' LIMIT 1")
    suspend fun getCompletedDownloadForVideo(videoId: String): DownloadedVideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(entity: DownloadedVideoEntity)

    @Query("UPDATE downloaded_videos SET downloadedBytes = :downloaded, totalBytes = :total, status = :status WHERE downloadKey = :key")
    suspend fun updateProgress(key: String, downloaded: Long, total: Long, status: String)

    @Query("UPDATE downloaded_videos SET status = :status, localFilePath = :path, publicContentUri = :uri, downloadedBytes = :size, totalBytes = :size WHERE downloadKey = :key")
    suspend fun markCompleted(key: String, status: String, path: String, uri: String, size: Long)

    @Query("DELETE FROM downloaded_videos WHERE downloadKey = :key")
    suspend fun deleteDownload(key: String)
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscribed_channels ORDER BY subscribedAtMs DESC")
    fun observeSubscriptions(): Flow<List<SubscribedChannelEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :channelId)")
    fun observeIsSubscribed(channelId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun subscribe(channel: SubscribedChannelEntity)

    @Query("DELETE FROM subscribed_channels WHERE channelId = :channelId")
    suspend fun unsubscribe(channelId: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAtMs DESC LIMIT 30")
    fun observeRecentHistory(): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entry: WatchHistoryEntity)
}

@Dao
interface LikedVideoDao {
    @Query("SELECT * FROM liked_videos ORDER BY likedAtMs DESC")
    fun observeLikedVideos(): Flow<List<LikedVideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun likeVideo(entry: LikedVideoEntity)

    @Query("DELETE FROM liked_videos WHERE videoId = :videoId")
    suspend fun unlikeVideo(videoId: String)
}

@Dao
interface SavedVideoDao {
    @Query("SELECT * FROM saved_videos ORDER BY savedAtMs DESC")
    fun observeSavedVideos(): Flow<List<SavedVideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveVideo(entry: SavedVideoEntity)

    @Query("DELETE FROM saved_videos WHERE videoId = :videoId")
    suspend fun unsaveVideo(videoId: String)
}
