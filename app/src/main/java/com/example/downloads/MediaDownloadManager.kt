package com.example.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadedVideoEntity
import com.example.mediaserviceinterfaces.MediaFormatOption
import com.example.mediaserviceinterfaces.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Coordinates WorkManager yt-dlp + MediaMuxer downloads across all qualities
 * with reactive Queued/Downloading/Merging/Saved/Failed status & Retry support.
 */
class MediaDownloadManager(
    context: Context,
    private val downloadDao: DownloadDao
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val allDownloads: Flow<List<DownloadedVideoEntity>> = downloadDao.observeAllDownloads()

    fun startOneClickDownload(
        item: MediaItem,
        format: MediaFormatOption,
        requestUserAgent: String? = null
    ) {
        val downloadKey = "${item.id}_${format.itag}"

        scope.launch {
            val downloadsDir = File(appContext.filesDir, "offline_media").apply { mkdirs() }
            val safeTitle = item.title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
            val ext = when {
                format.isAudioOnly -> if (format.container.equals("M4A", ignoreCase = true)) "m4a" else "webm"
                format.container.equals("WEBM", ignoreCase = true) -> "webm"
                else -> "mp4"
            }
            val mime = when (ext) {
                "webm" -> if (format.isAudioOnly) "audio/webm" else "video/webm"
                "m4a" -> "audio/mp4"
                else -> "video/mp4"
            }
            val targetFile = File(downloadsDir, "${safeTitle}_${item.id}_${format.itag}.$ext")

            val initial = DownloadedVideoEntity(
                downloadKey = downloadKey,
                videoId = item.id,
                formatItag = format.itag,
                title = item.title,
                channelId = item.channelId,
                channelName = item.channelName,
                channelAvatarUrl = item.channelAvatarUrl,
                thumbnailUrl = item.thumbnailUrl,
                qualityLabel = format.qualityLabel,
                container = ext.uppercase(),
                localFilePath = targetFile.absolutePath,
                publicContentUri = "",
                totalBytes = format.contentLengthBytes,
                downloadedBytes = 0L,
                status = DownloadedVideoEntity.STATUS_QUEUED,
                viewCountText = item.viewCountText,
                publishedDateText = item.displayDateTime,
                durationText = item.durationText
            )
            downloadDao.upsertDownload(initial)

            enqueueDownloadWork(
                downloadKey = downloadKey,
                videoId = item.id,
                targetQuality = format.qualityLabel,
                targetItag = format.itag,
                videoUrl = format.url,
                audioUrl = format.audioPairUrl,
                targetPath = targetFile.absolutePath,
                displayName = "${safeTitle}_${format.qualityLabel}.$ext",
                mimeType = mime,
                expectedBytes = format.contentLengthBytes,
                userAgent = requestUserAgent.orEmpty(),
                isAudioOnly = format.isAudioOnly,
                targetContainer = ext.uppercase()
            )
        }
    }

    fun retryDownload(entity: DownloadedVideoEntity) {
        scope.launch {
            val safeTitle = entity.title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
            val ext = entity.container.lowercase().ifBlank { "mp4" }
            val isAudioOnly = ext == "m4a" || entity.qualityLabel.contains("Audio", ignoreCase = true)
            val mime = when (ext) {
                "webm" -> if (isAudioOnly) "audio/webm" else "video/webm"
                "m4a" -> "audio/mp4"
                else -> "video/mp4"
            }

            downloadDao.updateProgress(
                key = entity.downloadKey,
                downloaded = 0L,
                total = entity.totalBytes,
                status = DownloadedVideoEntity.STATUS_QUEUED
            )

            enqueueDownloadWork(
                downloadKey = entity.downloadKey,
                videoId = entity.videoId,
                targetQuality = entity.qualityLabel,
                targetItag = entity.formatItag,
                videoUrl = "",
                audioUrl = null,
                targetPath = entity.localFilePath,
                displayName = "${safeTitle}_${entity.qualityLabel}.$ext",
                mimeType = mime,
                expectedBytes = entity.totalBytes,
                userAgent = "",
                isAudioOnly = isAudioOnly,
                targetContainer = ext.uppercase()
            )
        }
    }

    private fun enqueueDownloadWork(
        downloadKey: String,
        videoId: String,
        targetQuality: String,
        targetItag: Int,
        videoUrl: String,
        audioUrl: String?,
        targetPath: String,
        displayName: String,
        mimeType: String,
        expectedBytes: Long,
        userAgent: String,
        isAudioOnly: Boolean,
        targetContainer: String
    ) {
        val input = Data.Builder()
            .putString(ParallelDownloadWorker.KEY_DOWNLOAD_KEY, downloadKey)
            .putString(ParallelDownloadWorker.KEY_VIDEO_ID, videoId)
            .putString(ParallelDownloadWorker.KEY_TARGET_QUALITY, targetQuality)
            .putInt(ParallelDownloadWorker.KEY_TARGET_ITAG, targetItag)
            .putString(ParallelDownloadWorker.KEY_VIDEO_URL, videoUrl)
            .putString(ParallelDownloadWorker.KEY_AUDIO_URL, audioUrl)
            .putString(ParallelDownloadWorker.KEY_TARGET_PATH, targetPath)
            .putString(ParallelDownloadWorker.KEY_DISPLAY_NAME, displayName)
            .putString(ParallelDownloadWorker.KEY_MIME_TYPE, mimeType)
            .putLong(ParallelDownloadWorker.KEY_EXPECTED_BYTES, expectedBytes)
            .putString(ParallelDownloadWorker.KEY_USER_AGENT, userAgent)
            .putBoolean(ParallelDownloadWorker.KEY_IS_AUDIO_ONLY, isAudioOnly)
            .putString(ParallelDownloadWorker.KEY_TARGET_CONTAINER, targetContainer)
            .build()

        val request = OneTimeWorkRequestBuilder<ParallelDownloadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            "download_$downloadKey",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelOrDeleteDownload(entity: DownloadedVideoEntity) {
        workManager.cancelUniqueWork("download_${entity.downloadKey}")
        scope.launch {
            runCatching { File(entity.localFilePath).delete() }
            downloadDao.deleteDownload(entity.downloadKey)
        }
    }
}
