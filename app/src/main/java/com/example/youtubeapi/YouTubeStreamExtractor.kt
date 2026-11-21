package com.example.youtubeapi

import android.util.Log
import com.example.mediaserviceinterfaces.MediaFormatOption
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Two-Tier Stream Extractor:
 * - Tier 1 (Primary): Direct InnerTube `/youtubei/v1/player` rotating
 *   ANDROID_VR -> ANDROID -> IOS -> TVHTML5_SIMPLY_EMBEDDED_PLAYER -> WEB_EMBEDDED_PLAYER
 * - Tier 2 (Built-in JS/HTML Fallback): NewPipeExtractor (v0.26.5) with Rhino JS signature decryption
 * - Tier 3 (Safety Mirror Fallback): Piped / Invidious public instances
 */
class YouTubeStreamExtractor(
    private val networkStack: NetworkStackProvider,
    private val postPlayerEndpoint: (InnertubeClientProfile, String) -> JSONObject?
) {

    fun extract(
        videoId: String,
        preferFallbackClient: Boolean = false
    ): MediaItemFormatInfo {
        var bestPartial: MediaItemFormatInfo? = null

        fun isPlayableDirect(info: MediaItemFormatInfo?): Boolean {
            if (info == null || info.bestPlaybackUrl == null) return false
            return info.formats.any {
                !it.isHls && it.hasVideo && (it.isMuxed || !it.audioPairUrl.isNullOrBlank()) &&
                    (it.isH264Compatible || it.isVp9) && it.url.isNotBlank()
            }
        }

        // On retry/re-extract (e.g. after 403 or stall), run NewPipeExtractor v0.26.5 JS deciphering FIRST
        if (preferFallbackClient) {
            val newPipeFirst = extractViaNewPipe(videoId)
            if (isPlayableDirect(newPipeFirst)) {
                return newPipeFirst!!
            }
            if (newPipeFirst != null && newPipeFirst.bestPlaybackUrl != null) {
                bestPartial = newPipeFirst
            }
        }

        // Tier 1A: Fast ANDROID_VR client (~200ms, no PoToken/JS needed)
        if (!preferFallbackClient) {
            val vrResponse = postPlayerEndpoint(InnertubeClientProfiles.ANDROID_VR, videoId)
            val vrParsed = vrResponse?.let {
                InnertubeParser.parsePlayerResponse(
                    root = it,
                    videoId = videoId,
                    requestUserAgent = InnertubeClientProfiles.ANDROID_VR.userAgent
                )
            }
            if (isPlayableDirect(vrParsed)) {
                return vrParsed!!
            }
            if (vrParsed != null && vrParsed.bestPlaybackUrl != null && bestPartial == null) {
                bestPartial = vrParsed
            }

            // Tier 2: NewPipeExtractor (v0.26.5) JS Signature & HTML Deciphering before slow/restricted clients
            val newPipeExtracted = extractViaNewPipe(videoId)
            if (isPlayableDirect(newPipeExtracted)) {
                return newPipeExtracted!!
            }
            if (newPipeExtracted != null && newPipeExtracted.bestPlaybackUrl != null && bestPartial == null) {
                bestPartial = newPipeExtracted
            }
        }

        // Tier 1B: Remaining InnerTube clients (TV_EMBED, WEB_EMBEDDED_PLAYER, IOS, ANDROID)
        val clientOrder = if (preferFallbackClient) {
            InnertubeClientProfiles.PLAYER_RETRY_ORDER
        } else {
            InnertubeClientProfiles.PLAYER_FALLBACK_ORDER.drop(1)
        }
        for (profile in clientOrder) {
            val response = postPlayerEndpoint(profile, videoId) ?: continue
            val parsed = InnertubeParser.parsePlayerResponse(
                root = response,
                videoId = videoId,
                requestUserAgent = profile.userAgent
            )
            if (isPlayableDirect(parsed)) {
                return parsed!!
            }
            if (parsed != null && parsed.bestPlaybackUrl != null && bestPartial == null) {
                bestPartial = parsed
            }
        }

        if (bestPartial != null) return bestPartial

        // Tier 3: Piped / Invidious public fallback mirrors
        fetchPipedFallback(videoId)?.let { return it }
        fetchInvidiousFallback(videoId)?.let { return it }

        return MediaItemFormatInfo(
            videoId = videoId,
            title = "Stream Unavailable"
        )
    }

    private fun extractViaNewPipe(videoId: String): MediaItemFormatInfo? {
        return try {
            NewPipeDownloaderImpl.ensureInitialized(networkStack.okHttpClient)
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)

            val audioOptions = streamInfo.audioStreams
                .orEmpty()
                .mapNotNull { mapAudioStream(it) }

            val bestM4aAudio = audioOptions
                .filter { it.container == "M4A" || it.mimeType.contains("mp4", ignoreCase = true) }
                .maxByOrNull { it.bitrate }?.url
            val bestWebmAudio = audioOptions
                .filter { it.container == "WEBM" || it.container == "OPUS" || it.mimeType.contains("webm", ignoreCase = true) }
                .maxByOrNull { it.bitrate }?.url

            val muxedVideos = streamInfo.videoStreams
                .orEmpty()
                .mapNotNull { mapVideoStream(it, isVideoOnly = false, bestM4a = bestM4aAudio, bestWebm = bestWebmAudio) }

            val adaptiveVideos = streamInfo.videoOnlyStreams
                .orEmpty()
                .mapNotNull { mapVideoStream(it, isVideoOnly = true, bestM4a = bestM4aAudio, bestWebm = bestWebmAudio) }

            val combined = (muxedVideos + adaptiveVideos + audioOptions)
                .distinctBy { "${it.itag}_${it.qualityLabel}_${it.container}_${it.codec}" }
                .sortedWith(
                    compareByDescending<MediaFormatOption> { it.isMuxed }
                        .thenByDescending { it.hasVideo }
                        .thenByDescending { it.hasAudio || !it.audioPairUrl.isNullOrBlank() }
                        .thenByDescending { it.codecPriority }
                        .thenByDescending { InnertubeParser.extractResolutionHeight(it.qualityLabel) }
                        .thenByDescending { it.bitrate }
                )

            val hls = streamInfo.hlsUrl?.takeIf { it.isNotBlank() }
            val dash = streamInfo.dashMpdUrl?.takeIf { it.isNotBlank() }
            if (combined.isEmpty() && hls == null && dash == null) return null

            val uploaderAvatar = streamInfo.uploaderAvatars.lastOrNull()?.url.orEmpty()
            val formattedUpload = streamInfo.uploadDate?.offsetDateTime()?.toString()
                ?.let { InnertubeParser.formatIsoDateTime(it) }
                ?: ("" to "")

            MediaItemFormatInfo(
                videoId = videoId,
                title = streamInfo.name.orEmpty().ifBlank { "Video" },
                description = streamInfo.description?.content.orEmpty(),
                author = streamInfo.uploaderName.orEmpty().ifBlank { "Creator" },
                channelId = streamInfo.uploaderUrl.orEmpty().substringAfterLast("/"),
                channelAvatarUrl = InnertubeParser.normalizeUrl(uploaderAvatar),
                viewCountText = InnertubeParser.formatViewCount(streamInfo.viewCount.coerceAtLeast(0L)),
                publishedDateText = formattedUpload.first.ifBlank { streamInfo.textualUploadDate.orEmpty() },
                publishedExactTimeText = formattedUpload.second,
                durationMs = (streamInfo.duration.coerceAtLeast(0L)) * 1000L,
                hlsManifestUrl = hls,
                dashManifestUrl = dash,
                formats = combined,
                isLive = streamInfo.duration <= 0L,
                requestUserAgent = NEWPIPE_WEB_UA
            )
        } catch (t: Throwable) {
            Log.w(TAG, "NewPipeExtractor fallback failed for $videoId: ${t.message}")
            null
        }
    }

    private fun mapVideoStream(
        stream: VideoStream,
        isVideoOnly: Boolean,
        bestM4a: String?,
        bestWebm: String?
    ): MediaFormatOption? {
        val url = stream.content?.takeIf { it.isNotBlank() } ?: return null
        val format = stream.format
        val container = when (format) {
            MediaFormat.WEBM -> "WEBM"
            MediaFormat.MPEG_4 -> "MP4"
            MediaFormat.v3GPP -> "3GP"
            else -> if (stream.codec.orEmpty().contains("vp9", true)) "WEBM" else "MP4"
        }
        val mime = format?.mimeType ?: if (container == "WEBM") "video/webm" else "video/mp4"
        val res = stream.getResolution().orEmpty().ifBlank { "360p" }
        val height = InnertubeParser.extractResolutionHeight(res)
        val pairedAudio = if (isVideoOnly) {
            if (container == "WEBM") bestWebm else bestM4a
        } else null

        val itagItem = stream.itagItem
        val initStart = itagItem?.initStart?.toLong() ?: -1L
        val initEnd = itagItem?.initEnd?.toLong() ?: -1L
        val indexStart = itagItem?.indexStart?.toLong() ?: -1L
        val indexEnd = itagItem?.indexEnd?.toLong() ?: -1L

        return MediaFormatOption(
            itag = stream.itag,
            qualityLabel = res,
            mimeType = mime,
            container = container,
            url = url,
            audioPairUrl = pairedAudio,
            codec = stream.codec.orEmpty(),
            bitrate = stream.bitrate.coerceAtLeast(0),
            fps = stream.fps.coerceAtLeast(24),
            contentLengthBytes = itagItem?.contentLength ?: 0L,
            hasVideo = true,
            hasAudio = !isVideoOnly,
            width = stream.width.coerceAtLeast(0),
            height = if (stream.height > 0) stream.height else height,
            initRangeStart = initStart,
            initRangeEnd = initEnd,
            indexRangeStart = indexStart,
            indexRangeEnd = indexEnd
        )
    }

    private fun mapAudioStream(stream: AudioStream): MediaFormatOption? {
        val url = stream.content?.takeIf { it.isNotBlank() } ?: return null
        val format = stream.format
        val container = when (format) {
            MediaFormat.M4A -> "M4A"
            MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS -> "OPUS"
            else -> if (stream.codec.orEmpty().contains("mp4a", true)) "M4A" else "OPUS"
        }
        val mime = format?.mimeType ?: if (container == "M4A") "audio/mp4" else "audio/webm"
        val kbps = stream.averageBitrate.coerceAtLeast(64)
        val itagItem = stream.itagItem
        return MediaFormatOption(
            itag = stream.itag,
            qualityLabel = "Audio $container (${kbps}kbps)",
            mimeType = mime,
            container = container,
            url = url,
            codec = stream.codec.orEmpty(),
            bitrate = kbps * 1000,
            contentLengthBytes = itagItem?.contentLength ?: 0L,
            hasVideo = false,
            hasAudio = true,
            initRangeStart = itagItem?.initStart?.toLong() ?: -1L,
            initRangeEnd = itagItem?.initEnd?.toLong() ?: -1L,
            indexRangeStart = itagItem?.indexStart?.toLong() ?: -1L,
            indexRangeEnd = itagItem?.indexEnd?.toLong() ?: -1L
        )
    }

    private fun fetchPipedFallback(videoId: String): MediaItemFormatInfo? {
        for (baseUrl in PIPED_INSTANCES) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/streams/$videoId")
                    .header("User-Agent", NetworkStackProvider.USER_AGENT_MOBILE)
                    .build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val parsed = InnertubeParser.parsePipedStreams(JSONObject(body), videoId)
                            if (parsed != null && parsed.bestPlaybackUrl != null) return parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piped stream fallback failed for $baseUrl: ${e.message}")
            }
        }
        return null
    }

    private fun fetchInvidiousFallback(videoId: String): MediaItemFormatInfo? {
        for (baseUrl in INVIDIOUS_INSTANCES) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/videos/$videoId")
                    .header("User-Agent", NetworkStackProvider.USER_AGENT_MOBILE)
                    .build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val parsed = InnertubeParser.parseInvidiousStreams(JSONObject(body), videoId)
                            if (parsed != null && parsed.bestPlaybackUrl != null) return parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invidious stream fallback failed for $baseUrl: ${e.message}")
            }
        }
        return null
    }

    companion object {
        private const val TAG = "YouTubeStreamExtractor"
        private const val NEWPIPE_WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee"
        )
        private val INVIDIOUS_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be"
        )
    }
}
