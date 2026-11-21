package com.example.downloads

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.StreamTubeApp
import com.example.data.local.DownloadedVideoEntity
import com.example.youtubeapi.InnertubeParser
import com.example.youtubeapi.NetworkStackProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * WorkManager CoroutineWorker powered by yt-dlp (without FFmpeg) + built-in Android MediaMuxer,
 * with finite 1MB byte-range OkHttp chunked downloader + Tier 1/Tier 2 re-extraction fallback
 * and strict container matching (MP4+M4A -> .mp4, WebM+Opus -> .webm).
 */
@OptIn(UnstableApi::class)
class ParallelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private class ExpiredStreamUrlException(val code: Int) : IllegalStateException("HTTP $code")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as StreamTubeApp
        val downloadDao = app.database.downloadDao()
        val downloadOkHttp = app.networkStack.okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val downloadKey = inputData.getString(KEY_DOWNLOAD_KEY) ?: return@withContext Result.failure()
        val videoId = inputData.getString(KEY_VIDEO_ID).orEmpty()
        val targetQuality = inputData.getString(KEY_TARGET_QUALITY).orEmpty()
        val targetItag = inputData.getInt(KEY_TARGET_ITAG, 0)
        var videoUrl = inputData.getString(KEY_VIDEO_URL).orEmpty()
        var audioUrl = inputData.getString(KEY_AUDIO_URL)?.takeIf { it.isNotBlank() }
        val targetPath = inputData.getString(KEY_TARGET_PATH) ?: return@withContext Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "video.mp4"
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "video/mp4"
        var expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, 0L)
        var userAgent = inputData.getString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() }
            ?: NetworkStackProvider.USER_AGENT_MOBILE
        val isAudioOnly = inputData.getBoolean(KEY_IS_AUDIO_ONLY, false)
        val targetContainer = inputData.getString(KEY_TARGET_CONTAINER)?.uppercase().orEmpty()

        val targetFile = File(targetPath)
        targetFile.parentFile?.mkdirs()

        val tempVideoFile = File(targetFile.parentFile, "${targetFile.name}.vpart")
        val tempAudioFile = File(targetFile.parentFile, "${targetFile.name}.apart")

        suspend fun emitProgress(downloaded: Long, total: Long, status: String = DownloadedVideoEntity.STATUS_DOWNLOADING) {
            downloadDao.updateProgress(
                key = downloadKey,
                downloaded = downloaded.coerceAtLeast(0L),
                total = total.coerceAtLeast(downloaded).coerceAtLeast(0L),
                status = status
            )
        }

        suspend fun refreshUrlsFromExtractor(forceRefresh: Boolean = true): Boolean {
            if (videoId.isBlank()) return false
            return try {
                val refreshed = app.streamRepository.resolveStreamFormats(videoId, forceRefresh = forceRefresh)
                val targetHeight = InnertubeParser.extractResolutionHeight(targetQuality)
                val matched = if (isAudioOnly) {
                    refreshed.formats.firstOrNull { it.isAudioOnly && it.url.isNotBlank() }
                } else {
                    val candidates = refreshed.formats.filter {
                        !it.isHls && it.hasVideo && it.url.isNotBlank() &&
                            (it.isMuxed || !it.audioPairUrl.isNullOrBlank()) &&
                            ((targetHeight > 0 && InnertubeParser.extractResolutionHeight(it.qualityLabel) == targetHeight) ||
                                (targetItag > 0 && it.itag == targetItag))
                    }
                    candidates
                        .sortedWith(
                            compareByDescending<com.example.mediaserviceinterfaces.MediaFormatOption> { it.isMuxed }
                                .thenByDescending { it.isH264Compatible }
                                .thenByDescending { !it.isAv1 }
                                .thenByDescending { it.codecPriority }
                        )
                        .firstOrNull()
                        ?: InnertubeParser.filterAllowedDownloadFormats(refreshed.formats)
                            .firstOrNull {
                                (targetHeight > 0 && InnertubeParser.extractResolutionHeight(it.qualityLabel) == targetHeight) ||
                                    (targetItag > 0 && it.itag == targetItag)
                            }
                }
                if (matched != null && matched.url.isNotBlank()) {
                    videoUrl = matched.url
                    audioUrl = matched.audioPairUrl?.takeIf { it.isNotBlank() }
                    userAgent = refreshed.requestUserAgent?.takeIf { it.isNotBlank() } ?: userAgent
                    if (matched.contentLengthBytes > 0L) expectedBytes = matched.contentLengthBytes
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stream re-extraction failed for $videoId: ${e.message}")
                false
            }
        }

        return@withContext try {
            emitProgress(0L, expectedBytes, DownloadedVideoEntity.STATUS_DOWNLOADING)

            // Stage 1: Non-blocking yt-dlp (acquire mutex via tryLock so concurrent jobs don't collide or wait)
            var ytDlpSucceeded = false
            if (videoId.isNotBlank() && app.youtubeDlMutex.tryLock()) {
                try {
                    if (app.ensureYoutubeDlInitialized()) {
                        ytDlpSucceeded = tryYtDlpDownloadAndMux(
                            downloadKey = downloadKey,
                            videoId = videoId,
                            targetQuality = targetQuality,
                            targetItag = targetItag,
                            isAudioOnly = isAudioOnly,
                            targetContainer = targetContainer,
                            expectedBytes = expectedBytes,
                            tempVideoFile = tempVideoFile,
                            tempAudioFile = tempAudioFile,
                            targetFile = targetFile,
                            onProgress = { dl, tot, status -> emitProgress(dl, tot, status) }
                        )
                    }
                } finally {
                    app.youtubeDlMutex.unlock()
                }
            }

            // Stage 2: Chunked 1MB finite byte-range OkHttp downloader with automatic URL refresh on 403/410
            if (!ytDlpSucceeded) {
                tempVideoFile.delete()
                tempAudioFile.delete()

                if (videoUrl.isBlank()) {
                    refreshUrlsFromExtractor(forceRefresh = true)
                }
                if (videoUrl.isBlank()) {
                    throw IllegalStateException("No downloadable URL resolved for $videoId")
                }

                val totalVideoBytes = try {
                    downloadChunkedWithOkHttp(
                        okHttp = downloadOkHttp,
                        url = videoUrl,
                        userAgent = userAgent,
                        outFile = tempVideoFile,
                        expectedLength = expectedBytes
                    ) { downloaded, total ->
                        val adjustedTotal = if (audioUrl != null) {
                            (total.coerceAtLeast(downloaded) * 1.12).toLong()
                        } else {
                            total.coerceAtLeast(downloaded)
                        }
                        emitProgress(downloaded, adjustedTotal)
                    }
                } catch (firstErr: Throwable) {
                    Log.w(TAG, "Stage 2 stream download error ($targetQuality): ${firstErr.message}, refreshing URLs")
                    if (!refreshUrlsFromExtractor(forceRefresh = true)) throw firstErr
                    tempVideoFile.delete()
                    downloadChunkedWithOkHttp(
                        okHttp = downloadOkHttp,
                        url = videoUrl,
                        userAgent = userAgent,
                        outFile = tempVideoFile,
                        expectedLength = expectedBytes
                    ) { downloaded, total ->
                        val adjustedTotal = if (audioUrl != null) {
                            (total.coerceAtLeast(downloaded) * 1.12).toLong()
                        } else {
                            total.coerceAtLeast(downloaded)
                        }
                        emitProgress(downloaded, adjustedTotal)
                    }
                }

                val activeAudioUrl = audioUrl
                if (activeAudioUrl != null && !isAudioOnly && !fileHasAudioTrack(tempVideoFile)) {
                    val audioBytes = try {
                        downloadChunkedWithOkHttp(
                            okHttp = downloadOkHttp,
                            url = activeAudioUrl,
                            userAgent = userAgent,
                            outFile = tempAudioFile,
                            expectedLength = 0L
                        ) { audioDownloaded, audioTotal ->
                            val combinedRead = totalVideoBytes + audioDownloaded
                            val combinedTotal = totalVideoBytes + audioTotal.coerceAtLeast(audioDownloaded)
                            emitProgress(combinedRead, combinedTotal.coerceAtLeast(combinedRead + 1024L))
                        }
                    } catch (audioErr: Throwable) {
                        Log.w(TAG, "Audio download error ($targetQuality): ${audioErr.message}")
                        if (refreshUrlsFromExtractor(forceRefresh = true) && audioUrl != null) {
                            tempAudioFile.delete()
                            downloadChunkedWithOkHttp(
                                okHttp = downloadOkHttp,
                                url = audioUrl!!,
                                userAgent = userAgent,
                                outFile = tempAudioFile,
                                expectedLength = 0L
                            ) { audioDownloaded, audioTotal ->
                                val combinedRead = totalVideoBytes + audioDownloaded
                                val combinedTotal = totalVideoBytes + audioTotal.coerceAtLeast(audioDownloaded)
                                emitProgress(combinedRead, combinedTotal.coerceAtLeast(combinedRead + 1024L))
                            }
                        } else {
                            0L
                        }
                    }

                    val isWebm = targetFile.extension.equals("webm", ignoreCase = true)
                    if (audioBytes > 0L) {
                        emitProgress(
                            totalVideoBytes + audioBytes,
                            totalVideoBytes + audioBytes,
                            DownloadedVideoEntity.STATUS_MERGING
                        )
                    }
                    val merged = if (audioBytes > 0L) {
                        muxWithMediaMuxer(
                            videoFile = tempVideoFile,
                            audioFile = tempAudioFile,
                            outputFile = targetFile,
                            isWebm = isWebm
                        )
                    } else {
                        false
                    }

                    if (!merged) {
                        Log.w(TAG, "Muxer fallback for $downloadKey: preserving video stream")
                        tempVideoFile.copyTo(targetFile, overwrite = true)
                    }
                    tempVideoFile.delete()
                    tempAudioFile.delete()
                } else {
                    if (tempVideoFile.exists()) {
                        tempVideoFile.copyTo(targetFile, overwrite = true)
                        tempVideoFile.delete()
                    }
                }
            }

            if (!targetFile.exists() || targetFile.length() <= 0L) {
                throw IllegalStateException("Downloaded file is empty")
            }

            val finalSize = targetFile.length()
            val publicUri = exportToMediaStoreIfSupported(
                file = targetFile,
                displayName = displayName,
                mimeType = mimeType
            )

            downloadDao.markCompleted(
                key = downloadKey,
                status = DownloadedVideoEntity.STATUS_COMPLETED,
                path = targetFile.absolutePath,
                uri = publicUri,
                size = finalSize
            )
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed for $downloadKey ($targetQuality): ${t.message}", t)
            tempVideoFile.delete()
            tempAudioFile.delete()
            downloadDao.updateProgress(
                key = downloadKey,
                downloaded = 0L,
                total = expectedBytes,
                status = DownloadedVideoEntity.STATUS_FAILED
            )
            Result.failure()
        }
    }

    /**
     * Executes yt-dlp single-track downloads paired with strict container-matched Android MediaMuxer
     * (MP4+M4A -> .mp4; WebM+Opus -> .webm) with stall timeout protection and live file growth polling.
     */
    private suspend fun tryYtDlpDownloadAndMux(
        downloadKey: String,
        videoId: String,
        targetQuality: String,
        targetItag: Int,
        isAudioOnly: Boolean,
        targetContainer: String,
        expectedBytes: Long,
        tempVideoFile: File,
        tempAudioFile: File,
        targetFile: File,
        onProgress: suspend (Long, Long, String) -> Unit
    ): Boolean {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val targetHeight = InnertubeParser.extractResolutionHeight(targetQuality)
        val useWebm = targetContainer == "WEBM" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        return try {
            if (isAudioOnly) {
                val audioSelector = if (targetContainer == "M4A") {
                    "ba[ext=m4a]/ba[acodec^=mp4a]/ba"
                } else {
                    "ba[ext=webm]/ba[acodec=opus]/ba"
                }
                executeYtDlpSingleTrack(downloadKey, watchUrl, audioSelector, targetFile) { pct, fileBytes ->
                    val estTotal = expectedBytes.coerceAtLeast(fileBytes).takeIf { it > 0L } ?: 4_000_000L
                    val estDone = fileBytes.takeIf { it > 0L }
                        ?: ((pct.coerceIn(0f, 100f) / 100f) * estTotal).toLong()
                    onProgress(estDone, estTotal, DownloadedVideoEntity.STATUS_DOWNLOADING)
                }
                return targetFile.exists() && targetFile.length() > 0L
            }

            if (targetItag == 18 || targetItag == 22) {
                val muxedSelector = "$targetItag/b[ext=mp4]/b"
                executeYtDlpSingleTrack(downloadKey, watchUrl, muxedSelector, targetFile) { pct, fileBytes ->
                    val estTotal = expectedBytes.coerceAtLeast(fileBytes).takeIf { it > 0L } ?: 12_000_000L
                    val estDone = fileBytes.takeIf { it > 0L }
                        ?: ((pct.coerceIn(0f, 100f) / 100f) * estTotal).toLong()
                    onProgress(estDone, estTotal, DownloadedVideoEntity.STATUS_DOWNLOADING)
                }
                return targetFile.exists() && targetFile.length() > 0L
            }

            // Adaptive HD / 2K: strictly prefer H.264 (avc1) for clean Android MediaMuxer compatibility
            val videoSelector = if (useWebm) {
                if (targetHeight > 0) {
                    "bv*[height=$targetHeight][ext=webm]/bv*[height<=$targetHeight][ext=webm]/bv*[height<=$targetHeight]"
                } else {
                    "bv*[ext=webm]/bv*"
                }
            } else {
                if (targetHeight > 0) {
                    "bv*[height=$targetHeight][vcodec^=avc1][ext=mp4]/bv*[height=$targetHeight][ext=mp4]/bv*[height<=$targetHeight][vcodec^=avc1][ext=mp4]/b[ext=mp4]"
                } else {
                    "bv*[vcodec^=avc1][ext=mp4]/bv*[ext=mp4]/b[ext=mp4]"
                }
            }

            executeYtDlpSingleTrack(downloadKey, watchUrl, videoSelector, tempVideoFile) { pct, fileBytes ->
                val estTotal = expectedBytes.coerceAtLeast(fileBytes).takeIf { it > 0L } ?: 25_000_000L
                val estDone = fileBytes.takeIf { it > 0L }
                    ?: ((pct.coerceIn(0f, 100f) / 100f) * (estTotal * 0.85)).toLong()
                onProgress(estDone, estTotal, DownloadedVideoEntity.STATUS_DOWNLOADING)
            }

            if (!tempVideoFile.exists() || tempVideoFile.length() <= 0L) {
                return false
            }

            if (fileHasAudioTrack(tempVideoFile)) {
                tempVideoFile.copyTo(targetFile, overwrite = true)
                tempVideoFile.delete()
                return targetFile.exists() && targetFile.length() > 0L
            }

            val videoIsWebm = isFileWebm(tempVideoFile)
            val audioSelector = if (videoIsWebm) {
                "ba[ext=webm]/ba[acodec=opus]"
            } else {
                "ba[ext=m4a]/ba[acodec^=mp4a]"
            }

            executeYtDlpSingleTrack(downloadKey, watchUrl, audioSelector, tempAudioFile) { pct, audioBytes ->
                val vSize = tempVideoFile.length()
                val estAudio = audioBytes.coerceAtLeast((vSize * 0.14).toLong().coerceAtLeast(512_000L))
                val estTotal = vSize + estAudio
                val estDone = vSize + (audioBytes.takeIf { it > 0L }
                    ?: ((pct.coerceIn(0f, 100f) / 100f) * estAudio).toLong())
                onProgress(estDone, estTotal, DownloadedVideoEntity.STATUS_DOWNLOADING)
            }

            val totalSoFar = tempVideoFile.length() + tempAudioFile.length()
            onProgress(totalSoFar, totalSoFar, DownloadedVideoEntity.STATUS_MERGING)

            val muxed = if (tempAudioFile.exists() && tempAudioFile.length() > 0L) {
                val outIsWebm = videoIsWebm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                val muxTarget = if (outIsWebm && !targetFile.name.endsWith(".webm", true)) {
                    File(targetFile.parentFile, targetFile.nameWithoutExtension + ".webm")
                } else {
                    targetFile
                }
                val ok = muxWithMediaMuxer(
                    videoFile = tempVideoFile,
                    audioFile = tempAudioFile,
                    outputFile = muxTarget,
                    isWebm = outIsWebm
                )
                if (ok && muxTarget != targetFile) {
                    muxTarget.copyTo(targetFile, overwrite = true)
                    muxTarget.delete()
                }
                ok
            } else {
                false
            }

            if (!muxed) {
                return false
            }

            tempVideoFile.delete()
            tempAudioFile.delete()
            targetFile.exists() && targetFile.length() > 0L
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "yt-dlp Stage 1 fallback to Stage 2 for $videoId ($targetQuality): ${t.message}")
            false
        }
    }

    private suspend fun executeYtDlpSingleTrack(
        downloadKey: String,
        watchUrl: String,
        formatSelector: String,
        outFile: File,
        onProgress: suspend (Float, Long) -> Unit
    ) = coroutineScope {
        outFile.delete()
        val processId = "dl_${downloadKey}_${System.currentTimeMillis()}"
        val request = YoutubeDLRequest(watchUrl).apply {
            addOption("--extractor-args", "youtube:player_client=android_vr,android,ios")
            addOption("--force-ipv4")
            addOption("--no-part")
            addOption("--no-playlist")
            addOption("--socket-timeout", "12")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--http-chunk-size", "1M")
            addOption("-f", formatSelector)
            addOption("-o", outFile.absolutePath)
        }

        val startTime = System.currentTimeMillis()
        val lastActivityMs = AtomicLong(startTime)
        val lastKnownSize = AtomicLong(0L)
        val lastKnownPct = AtomicReference(0f)

        val monitorJob = launch(Dispatchers.IO) {
            while (isActive) {
                delay(350L)
                val currentSize = outFile.length()
                val now = System.currentTimeMillis()
                if (currentSize > lastKnownSize.get()) {
                    lastKnownSize.set(currentSize)
                    lastActivityMs.set(now)
                    onProgress(lastKnownPct.get(), currentSize)
                }
                val recordedSize = lastKnownSize.get()
                val initialStall = recordedSize == 0L && (now - startTime) > 14_000L
                val midStall = recordedSize > 0L && (now - lastActivityMs.get()) > 22_000L
                if (initialStall || midStall) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                    break
                }
            }
        }

        try {
            var lastEmit = 0L
            YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                val now = System.currentTimeMillis()
                if (progress > lastKnownPct.get()) {
                    lastKnownPct.set(progress)
                    lastActivityMs.set(now)
                }
                val fileSize = outFile.length()
                if (fileSize > lastKnownSize.get()) {
                    lastKnownSize.set(fileSize)
                    lastActivityMs.set(now)
                }
                if (progress >= 0f && (now - lastEmit >= 250L || progress >= 99f)) {
                    lastEmit = now
                    runBlocking {
                        onProgress(progress, lastKnownSize.get())
                    }
                }
            }
        } finally {
            monitorJob.cancel()
        }
    }

    private fun fileHasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun isFileWebm(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any {
                val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.contains("vp9", true) || mime.contains("vp8", true) || mime.contains("opus", true) || mime.contains("vorbis", true)
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Finite 1MB byte-range OkHttp chunked downloader to bypass YouTube CDN open-range throttling
     * and recover cleanly on slow/unstable links with 3 retries per chunk + 403/410 re-extraction.
     */
    private suspend fun downloadChunkedWithOkHttp(
        okHttp: OkHttpClient,
        url: String,
        userAgent: String,
        outFile: File,
        expectedLength: Long,
        onProgress: suspend (Long, Long) -> Unit
    ): Long {
        val chunkSize = 1024L * 1024L // 1 MB finite range prevents googlevideo CDN throttling
        val isMozillaWebUa = userAgent.contains("Mozilla", ignoreCase = true)

        fun buildRequest(rangeHeader: String?): Request {
            return Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .apply {
                    if (isMozillaWebUa) {
                        header("Origin", "https://www.youtube.com")
                        header("Referer", "https://www.youtube.com/")
                    }
                    if (!rangeHeader.isNullOrBlank()) {
                        header("Range", rangeHeader)
                    }
                }
                .build()
        }

        fun parseTotalFromContentRange(contentRange: String?): Long {
            val totalStr = contentRange?.substringAfter('/')?.trim() ?: return 0L
            return totalStr.toLongOrNull() ?: 0L
        }

        outFile.delete()
        val firstEnd = if (expectedLength in 1L..chunkSize) expectedLength - 1L else chunkSize - 1L
        var totalDownloaded = 0L
        var totalSize = expectedLength.coerceAtLeast(0L)
        var lastEmitTime = 0L
        val buf = ByteArray(64 * 1024)

        // Probe and write Chunk 0 using finite byte range
        val firstResp = okHttp.newCall(buildRequest("bytes=0-$firstEnd")).execute()
        val needsPlainGet = firstResp.code == 416
        val respToUse = if (needsPlainGet) {
            firstResp.close()
            okHttp.newCall(buildRequest(null)).execute()
        } else {
            firstResp
        }

        var supportsRanges = false
        respToUse.use { resp ->
            if (resp.code == 403 || resp.code == 410) {
                throw ExpiredStreamUrlException(resp.code)
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty stream body")
            if (resp.code == 206) {
                supportsRanges = true
                val rangeTotal = parseTotalFromContentRange(resp.header("Content-Range"))
                if (rangeTotal > 0L) totalSize = rangeTotal
            } else {
                val contentLen = body.contentLength()
                if (contentLen > 0L) totalSize = contentLen
            }

            body.byteStream().use { input ->
                FileOutputStream(outFile, false).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        totalDownloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmitTime >= 150L) {
                            lastEmitTime = now
                            onProgress(totalDownloaded, totalSize.coerceAtLeast(totalDownloaded))
                        }
                    }
                    output.flush()
                }
            }
        }

        onProgress(totalDownloaded, totalSize.coerceAtLeast(totalDownloaded))

        if (!supportsRanges || totalSize <= 0L || totalDownloaded >= totalSize) {
            if (totalDownloaded <= 0L) throw IllegalStateException("Zero bytes read from OkHttp")
            return totalDownloaded
        }

        // Fetch remaining 1MB finite chunks sequentially with per-chunk retry
        while (totalDownloaded < totalSize) {
            val start = totalDownloaded
            val end = (start + chunkSize - 1L).coerceAtMost(totalSize - 1L)
            var chunkAttempt = 0
            var chunkSuccess = false
            var lastChunkErr: Throwable? = null

            while (chunkAttempt < 3 && !chunkSuccess) {
                chunkAttempt++
                try {
                    okHttp.newCall(buildRequest("bytes=$start-$end")).execute().use { chunkResp ->
                        if (chunkResp.code == 403 || chunkResp.code == 410) {
                            throw ExpiredStreamUrlException(chunkResp.code)
                        }
                        if (!chunkResp.isSuccessful) {
                            throw IllegalStateException("HTTP ${chunkResp.code} on chunk $start-$end")
                        }
                        val chunkBody = chunkResp.body ?: throw IllegalStateException("Empty chunk body")
                        var writtenInChunk = 0L
                        val expectedChunk = (end - start) + 1L
                        chunkBody.byteStream().use { input ->
                            FileOutputStream(outFile, true).use { output ->
                                while (true) {
                                    val n = input.read(buf)
                                    if (n == -1) break
                                    output.write(buf, 0, n)
                                    writtenInChunk += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitTime >= 150L) {
                                        lastEmitTime = now
                                        onProgress(
                                            (totalDownloaded + writtenInChunk).coerceAtMost(totalSize),
                                            totalSize
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }
                        if (writtenInChunk <= 0L) {
                            throw IllegalStateException("Empty chunk $start-$end")
                        }
                        totalDownloaded += writtenInChunk
                        if (writtenInChunk < expectedChunk && totalDownloaded < totalSize) {
                            Log.w(TAG, "Short chunk $start-$end ($writtenInChunk/$expectedChunk), continuing")
                        }
                        chunkSuccess = true
                    }
                } catch (e: ExpiredStreamUrlException) {
                    throw e
                } catch (t: Throwable) {
                    lastChunkErr = t
                    if (chunkAttempt < 3) delay(450L * chunkAttempt)
                }
            }

            if (!chunkSuccess) {
                throw lastChunkErr ?: IllegalStateException("Failed chunk $start-$end")
            }
            onProgress(totalDownloaded, totalSize.coerceAtLeast(totalDownloaded))
        }

        if (totalDownloaded <= 0L) throw IllegalStateException("Zero bytes read from OkHttp")
        return totalDownloaded
    }

    /**
     * Merges container-matched video & audio tracks using Android MediaMuxer
     * (MP4+M4A -> MUXER_OUTPUT_MPEG_4; WebM+Opus -> MUXER_OUTPUT_WEBM)
     * with normalized timestamps and dynamic ByteBuffer resizing for high-res keyframes.
     */
    @SuppressLint("WrongConstant")
    private fun muxWithMediaMuxer(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        isWebm: Boolean
    ): Boolean {
        if (!videoFile.exists() || videoFile.length() <= 0L) return false
        if (!audioFile.exists() || audioFile.length() <= 0L) return false

        var muxer: MediaMuxer? = null
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrackIdx = (0 until videoExtractor.trackCount).firstOrNull {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false

            val audioTrackIdx = (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false

            val vMime = videoExtractor.getTrackFormat(videoTrackIdx).getString(MediaFormat.KEY_MIME).orEmpty()
            val aMime = audioExtractor.getTrackFormat(audioTrackIdx).getString(MediaFormat.KEY_MIME).orEmpty()

            // Enforce strict container parity: never mix MP4 video with WebM/Opus audio
            val videoIsWebm = vMime.contains("vp8", true) || vMime.contains("vp9", true)
            val audioIsWebm = aMime.contains("opus", true) || aMime.contains("vorbis", true)
            if (videoIsWebm != audioIsWebm) {
                Log.w(TAG, "Refusing mismatched containers in MediaMuxer: video=$vMime audio=$aMime")
                return false
            }

            videoExtractor.selectTrack(videoTrackIdx)
            audioExtractor.selectTrack(audioTrackIdx)

            val useWebmMuxer = (isWebm || videoIsWebm) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val muxerFormat = if (useWebmMuxer) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
            val outVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIdx))
            val outAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIdx))
            muxer.start()

            var buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            val allowedFlags = MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME

            fun readSampleResilient(extractor: MediaExtractor): Int {
                while (true) {
                    try {
                        return extractor.readSampleData(buffer, 0)
                    } catch (e: IllegalArgumentException) {
                        val nextCap = buffer.capacity() * 2
                        if (nextCap > 16 * 1024 * 1024) throw e
                        buffer = ByteBuffer.allocate(nextCap)
                    }
                }
            }

            var firstVideoPts = -1L
            var lastVideoPts = -1L
            while (true) {
                val sampleSize = readSampleResilient(videoExtractor)
                if (sampleSize < 0) break
                val rawTime = videoExtractor.sampleTime
                if (firstVideoPts < 0L) firstVideoPts = rawTime
                val normalized = (rawTime - firstVideoPts).coerceAtLeast(0L)
                val safePts = if (normalized <= lastVideoPts) lastVideoPts + 1L else normalized
                lastVideoPts = safePts

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = safePts
                bufferInfo.flags = videoExtractor.sampleFlags and allowedFlags
                muxer.writeSampleData(outVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            var firstAudioPts = -1L
            var lastAudioPts = -1L
            while (true) {
                val sampleSize = readSampleResilient(audioExtractor)
                if (sampleSize < 0) break
                val rawTime = audioExtractor.sampleTime
                if (firstAudioPts < 0L) firstAudioPts = rawTime
                val normalized = (rawTime - firstAudioPts).coerceAtLeast(0L)
                val safePts = if (normalized <= lastAudioPts) lastAudioPts + 1L else normalized
                lastAudioPts = safePts

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = safePts
                bufferInfo.flags = audioExtractor.sampleFlags and allowedFlags
                muxer.writeSampleData(outAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            true
        } catch (t: Throwable) {
            Log.w(TAG, "MediaMuxer error: ${t.message}")
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun exportToMediaStoreIfSupported(
        file: File,
        displayName: String,
        mimeType: String
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/StreamTubeRiz")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return ""
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore export skipped: ${e.message}")
            ""
        }
    }

    companion object {
        private const val TAG = "ParallelDownloadWorker"

        const val KEY_DOWNLOAD_KEY = "download_key"
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_TARGET_QUALITY = "target_quality"
        const val KEY_TARGET_ITAG = "target_itag"
        const val KEY_VIDEO_URL = "video_url"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_TARGET_PATH = "target_path"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_EXPECTED_BYTES = "expected_bytes"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_IS_AUDIO_ONLY = "is_audio_only"
        const val KEY_TARGET_CONTAINER = "target_container"
    }
}
