package com.example.youtubeapi

import com.example.mediaserviceinterfaces.ChannelInfo
import com.example.mediaserviceinterfaces.MediaFormatOption
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Recursive & resilient JSON parser for YouTube Innertube, Piped, and Invidious responses.
 */
object InnertubeParser {

    fun parseInnertubeVideos(root: JSONObject, forceShort: Boolean = false): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val seenIds = hashSetOf<String>()
        walkJsonForVideos(root, items, seenIds, forceShort)
        return items
    }

    private fun walkJsonForVideos(
        node: Any?,
        out: MutableList<MediaItem>,
        seenIds: MutableSet<String>,
        forceShort: Boolean
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = node.opt(key)
                    when (key) {
                        "videoRenderer",
                        "gridVideoRenderer",
                        "compactVideoRenderer",
                        "playlistVideoRenderer",
                        "videoWithContextRenderer" -> {
                            (child as? JSONObject)?.let { renderer ->
                                parseStandardVideoRenderer(renderer, forceShort)?.let { item ->
                                    if (seenIds.add(item.id)) out.add(item)
                                }
                            }
                        }
                        "reelItemRenderer" -> {
                            (child as? JSONObject)?.let { renderer ->
                                parseReelRenderer(renderer)?.let { item ->
                                    if (seenIds.add(item.id)) out.add(item)
                                }
                            }
                        }
                        "shortsLockupViewModel" -> {
                            (child as? JSONObject)?.let { vm ->
                                parseShortsLockupViewModel(vm)?.let { item ->
                                    if (seenIds.add(item.id)) out.add(item)
                                }
                            }
                        }
                        "lockupViewModel" -> {
                            (child as? JSONObject)?.let { vm ->
                                parseLockupViewModel(vm, forceShort)?.let { item ->
                                    if (seenIds.add(item.id)) out.add(item)
                                }
                            }
                        }
                        else -> walkJsonForVideos(child, out, seenIds, forceShort)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkJsonForVideos(node.opt(i), out, seenIds, forceShort)
                }
            }
        }
    }

    private fun parseStandardVideoRenderer(obj: JSONObject, forceShort: Boolean): MediaItem? {
        val videoId = obj.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = readText(obj.optJSONObject("title"))
            ?: readText(obj.optJSONObject("headline"))
            ?: return null

        val ownerObj = obj.optJSONObject("ownerText")
            ?: obj.optJSONObject("longBylineText")
            ?: obj.optJSONObject("shortBylineText")

        val channelName = readText(ownerObj) ?: "Creator"
        val channelId = extractBrowseId(ownerObj)
            .ifBlank { extractUcBrowseIdDeep(obj) }
        val avatarUrl = extractAvatarUrl(obj)
        val viewCountText = readText(obj.optJSONObject("viewCountText"))
            ?: readText(obj.optJSONObject("shortViewCountText"))
            ?: ""
        val publishedText = readText(obj.optJSONObject("publishedTimeText")) ?: ""
        val durationText = readText(obj.optJSONObject("lengthText")) ?: ""
        val isLive = durationText.isBlank() &&
            (viewCountText.contains("watching", ignoreCase = true) ||
                obj.toString().contains("\"LIVE\""))

        val thumbUrl = extractBestThumbnail(obj.optJSONObject("thumbnail"))
            ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"

        val durationMs = parseDurationToMs(durationText)
        val isShort = forceShort ||
            obj.optJSONObject("navigationEndpoint")?.optJSONObject("reelWatchEndpoint") != null ||
            obj.optString("navigationEndpoint").contains("/shorts/") ||
            (durationMs in 1L..62_000L && title.contains("#short", ignoreCase = true))
        val badge = when {
            isLive -> "LIVE"
            isShort -> "SHORTS"
            else -> null
        }

        return MediaItem(
            id = videoId,
            title = title,
            thumbnailUrl = thumbUrl,
            fallbackThumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            channelId = channelId,
            channelName = channelName,
            channelAvatarUrl = avatarUrl,
            viewCountText = viewCountText.ifBlank { if (isLive) "Live now" else "Popular" },
            publishedDateText = publishedText.ifBlank { if (isLive) "Streaming now" else "" },
            durationText = if (isLive) "LIVE" else durationText,
            durationMs = durationMs,
            isShort = isShort,
            isLive = isLive,
            badgeText = badge
        )
    }

    private fun parseReelRenderer(obj: JSONObject): MediaItem? {
        val videoId = obj.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = readText(obj.optJSONObject("headline")) ?: "Short Video"
        val viewText = readText(obj.optJSONObject("viewCountText")) ?: "Trending Short"
        val channelId = extractUcBrowseIdDeep(obj)
        val thumbUrl = extractBestThumbnail(obj.optJSONObject("thumbnail"))
            ?: "https://i.ytimg.com/vi/$videoId/oar2.jpg"
        return MediaItem(
            id = videoId,
            title = title,
            thumbnailUrl = thumbUrl,
            fallbackThumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            channelId = channelId,
            channelName = "Shorts Creator",
            viewCountText = viewText,
            publishedDateText = "Short",
            durationText = "0:59",
            durationMs = 59_000L,
            isShort = true,
            badgeText = "SHORTS"
        )
    }

    private fun parseShortsLockupViewModel(obj: JSONObject): MediaItem? {
        val endpoint = obj.optJSONObject("onTap")?.optJSONObject("innertubeCommand")
        val reelEndpoint = endpoint?.optJSONObject("reelWatchEndpoint")
        val videoId = reelEndpoint?.optString("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val overlay = obj.optJSONObject("overlayMetadata")
        val title = overlay?.optJSONObject("primaryText")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: obj.optString("accessibilityText").substringBefore(",").ifBlank { "Short" }
        val views = overlay?.optJSONObject("secondaryText")?.optString("content") ?: "Shorts"
        val channelId = extractUcBrowseIdDeep(obj)
        val thumbUrl = obj.optJSONObject("thumbnail")?.optJSONArray("sources")
            ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }
            ?: "https://i.ytimg.com/vi/$videoId/oar2.jpg"

        return MediaItem(
            id = videoId,
            title = title,
            thumbnailUrl = normalizeUrl(thumbUrl),
            fallbackThumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            channelId = channelId,
            channelName = "Shorts",
            viewCountText = views,
            publishedDateText = "Short",
            durationText = "0:59",
            durationMs = 59_000L,
            isShort = true,
            badgeText = "SHORTS"
        )
    }

    private fun parseLockupViewModel(obj: JSONObject, forceShort: Boolean): MediaItem? {
        val contentId = obj.optString("contentId").takeIf { it.length == 11 } ?: return null
        val metadata = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = metadata.optJSONObject("title")?.optString("content")?.takeIf { it.isNotBlank() } ?: return null
        val metaRows = metadata.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")

        var channelName = "Creator"
        var viewCountText = ""
        var publishedText = ""
        if (metaRows != null) {
            if (metaRows.length() > 0) {
                channelName = metaRows.optJSONObject(0)
                    ?.optJSONArray("metadataParts")
                    ?.optJSONObject(0)
                    ?.optJSONObject("text")
                    ?.optString("content") ?: channelName
            }
            if (metaRows.length() > 1) {
                val parts = metaRows.optJSONObject(1)?.optJSONArray("metadataParts")
                if (parts != null && parts.length() > 0) {
                    viewCountText = parts.optJSONObject(0)?.optJSONObject("text")?.optString("content") ?: ""
                }
                if (parts != null && parts.length() > 1) {
                    publishedText = parts.optJSONObject(1)?.optJSONObject("text")?.optString("content") ?: ""
                }
            }
        }

        val avatarUrl = metadata.optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
            ?.optJSONObject("avatar")
            ?.optJSONObject("avatarViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.optString("url")
            .orEmpty()

        val channelId = metadata.optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
            ?.optJSONObject("rendererContext")
            ?.optJSONObject("commandContext")
            ?.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotBlank() }
            ?: extractUcBrowseIdDeep(obj)

        val thumbVm = obj.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
        val thumbUrl = thumbVm?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null }
            ?: "https://i.ytimg.com/vi/$contentId/hq720.jpg"

        val durationText = extractOverlayDurationText(thumbVm)
        val durationMs = parseDurationToMs(durationText)
        val isShort = forceShort ||
            obj.optString("rendererContext").contains("reelWatchEndpoint") ||
            obj.optString("contentType").contains("SHORT", ignoreCase = true)

        return MediaItem(
            id = contentId,
            title = title,
            thumbnailUrl = normalizeUrl(thumbUrl),
            fallbackThumbnailUrl = "https://i.ytimg.com/vi/$contentId/hqdefault.jpg",
            channelId = channelId,
            channelName = channelName,
            channelAvatarUrl = normalizeUrl(avatarUrl),
            viewCountText = viewCountText,
            publishedDateText = publishedText,
            durationText = durationText,
            durationMs = durationMs,
            isShort = isShort,
            badgeText = if (isShort) "SHORTS" else null
        )
    }

    private fun extractOverlayDurationText(thumbVm: JSONObject?): String {
        val overlays = thumbVm?.optJSONArray("overlays") ?: return ""
        for (i in 0 until overlays.length()) {
            val badges = overlays.optJSONObject(i)
                ?.optJSONObject("thumbnailOverlayBadgeViewModel")
                ?.optJSONArray("thumbnailBadges") ?: continue
            for (j in 0 until badges.length()) {
                val text = badges.optJSONObject(j)
                    ?.optJSONObject("thumbnailBadgeViewModel")
                    ?.optString("text")
                if (!text.isNullOrBlank() && (text.contains(":") || text.equals("LIVE", ignoreCase = true))) {
                    return text
                }
            }
        }
        return ""
    }

    private fun extractUcBrowseIdDeep(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val direct = node.optJSONObject("browseEndpoint")?.optString("browseId")
                if (!direct.isNullOrBlank() && direct.startsWith("UC")) return direct
                val browseId = node.optString("browseId")
                if (browseId.startsWith("UC")) return browseId
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = extractUcBrowseIdDeep(node.opt(keys.next()))
                    if (found.isNotBlank()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = extractUcBrowseIdDeep(node.opt(i))
                    if (found.isNotBlank()) return found
                }
            }
        }
        return ""
    }

    fun parseInnertubeChannels(root: JSONObject): List<ChannelInfo> {
        val channels = mutableListOf<ChannelInfo>()
        val seen = hashSetOf<String>()
        walkJsonForChannels(root, channels, seen)
        return channels
    }

    private fun walkJsonForChannels(
        node: Any?,
        out: MutableList<ChannelInfo>,
        seen: MutableSet<String>
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = node.opt(key)
                    if (key == "channelRenderer" && child is JSONObject) {
                        val id = child.optString("channelId")
                        val name = readText(child.optJSONObject("title")) ?: ""
                        if (id.isNotBlank() && name.isNotBlank() && seen.add(id)) {
                            val avatar = extractBestThumbnail(child.optJSONObject("thumbnail")) ?: ""
                            val subs = readText(child.optJSONObject("subscriberCountText")) ?: ""
                            val vids = readText(child.optJSONObject("videoCountText")) ?: ""
                            val desc = readText(child.optJSONObject("descriptionSnippet")) ?: ""
                            out.add(
                                ChannelInfo(
                                    channelId = id,
                                    name = name,
                                    avatarUrl = normalizeUrl(avatar),
                                    subscriberCountText = subs,
                                    videoCountText = vids,
                                    description = desc,
                                    isVerified = child.toString().contains("BADGE_STYLE_TYPE_VERIFIED")
                                )
                            )
                        }
                    } else {
                        walkJsonForChannels(child, out, seen)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walkJsonForChannels(node.opt(i), out, seen)
                }
            }
        }
    }

    fun parseChannelBrowse(
        root: JSONObject,
        channelId: String,
        fallbackName: String,
        fallbackAvatar: String
    ): ChannelInfo {
        val allParsed = parseInnertubeVideos(root, forceShort = false)
            .map { if (it.channelId.isBlank()) it.copy(channelId = channelId) else it }
        val videos = allParsed.filterNot { it.isShort }
        val shorts = allParsed.filter { it.isShort }

        val header = root.optJSONObject("header")
        val c4 = header?.optJSONObject("c4TabbedHeaderRenderer")
        val pageHeader = header?.optJSONObject("pageHeaderRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("pageHeaderViewModel")

        val metadata = root.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")

        val name = c4?.optString("title")?.takeIf { it.isNotBlank() }
            ?: pageHeader?.optJSONObject("title")?.optJSONObject("dynamicTextViewModel")
                ?.optJSONObject("text")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: metadata?.optString("title")?.takeIf { it.isNotBlank() }
            ?: videos.firstOrNull()?.channelName?.takeIf { it.isNotBlank() }
            ?: fallbackName.ifBlank { "Channel" }

        val avatar = extractBestThumbnail(c4?.optJSONObject("avatar"))
            ?: pageHeader?.optJSONObject("image")?.optJSONObject("decoratedAvatarViewModel")
                ?.optJSONObject("avatar")?.optJSONObject("avatarViewModel")
                ?.optJSONObject("image")?.optJSONArray("sources")?.let { arr ->
                    if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null
                }
            ?: extractBestThumbnail(metadata?.optJSONObject("avatar"))
            ?: fallbackAvatar

        val banner = extractBestThumbnail(c4?.optJSONObject("banner"))
            ?: pageHeader?.optJSONObject("banner")?.optJSONObject("imageBannerViewModel")
                ?.optJSONObject("image")?.optJSONArray("sources")?.let { arr ->
                    if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url") else null
                }
            ?: ""

        val subsText = readText(c4?.optJSONObject("subscriberCountText"))
            ?: extractMetadataRowText(pageHeader, "subscriber")
            ?: ""

        val vidsText = readText(c4?.optJSONObject("videosCountText"))
            ?: extractMetadataRowText(pageHeader, "video")
            ?: if (videos.isNotEmpty()) "${videos.size} videos" else ""

        val handle = readText(c4?.optJSONObject("channelHandleText"))
            ?: extractMetadataRowText(pageHeader, "@")
            ?: ""

        val description = metadata?.optString("description")?.takeIf { it.isNotBlank() } ?: ""

        val enrichedVideos = videos.map {
            it.copy(
                channelId = channelId,
                channelName = name,
                channelAvatarUrl = normalizeUrl(avatar).ifBlank { it.channelAvatarUrl }
            )
        }
        val enrichedShorts = shorts.map {
            it.copy(
                isShort = true,
                channelId = channelId,
                channelName = name,
                channelAvatarUrl = normalizeUrl(avatar).ifBlank { it.channelAvatarUrl },
                badgeText = "SHORTS"
            )
        }

        return ChannelInfo(
            channelId = channelId,
            name = name,
            handle = handle,
            avatarUrl = normalizeUrl(avatar),
            bannerUrl = normalizeUrl(banner),
            subscriberCountText = subsText,
            videoCountText = vidsText,
            description = description,
            isVerified = header?.toString()?.contains("VERIFIED") == true,
            videos = enrichedVideos,
            shorts = enrichedShorts
        )
    }

    private fun extractMetadataRowText(pageHeader: JSONObject?, keyword: String): String? {
        val rows = pageHeader?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows") ?: return null
        for (i in 0 until rows.length()) {
            val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            for (j in 0 until parts.length()) {
                val text = parts.optJSONObject(j)?.optJSONObject("text")?.optString("content") ?: continue
                if (text.contains(keyword, ignoreCase = true)) return text
            }
        }
        return null
    }

    fun parsePlayerResponse(
        root: JSONObject,
        videoId: String,
        requestUserAgent: String? = null
    ): MediaItemFormatInfo? {
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status") ?: ""
        if (status != "OK" && root.optJSONObject("streamingData") == null) {
            return null
        }

        val streamingData = root.optJSONObject("streamingData") ?: return null
        val hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
        val dashUrl = streamingData.optString("dashManifestUrl").takeIf { it.isNotBlank() }

        val videoDetails = root.optJSONObject("videoDetails")
        val title = videoDetails?.optString("title").orEmpty().ifBlank { "Video" }
        val author = videoDetails?.optString("author").orEmpty().ifBlank { "Creator" }
        val channelId = videoDetails?.optString("channelId").orEmpty()
        val description = videoDetails?.optString("shortDescription").orEmpty()
        val lengthSeconds = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
        val rawViewCount = videoDetails?.optString("viewCount")?.toLongOrNull() ?: 0L
        val isLive = videoDetails?.optBoolean("isLiveContent", false) == true && lengthSeconds == 0L

        val microformat = root.optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")
        val rawPublishDate = microformat?.optString("publishDate").orEmpty()
            .ifBlank { microformat?.optString("uploadDate").orEmpty() }
        val formattedDateTime = formatIsoDateTime(rawPublishDate)

        val parsedFormats = mutableListOf<MediaFormatOption>()
        val muxedArray = streamingData.optJSONArray("formats")
        if (muxedArray != null) {
            for (i in 0 until muxedArray.length()) {
                parseFormatObject(muxedArray.optJSONObject(i), isMuxedDefault = true)?.let {
                    parsedFormats.add(it)
                }
            }
        }

        val adaptiveArray = streamingData.optJSONArray("adaptiveFormats")
        val adaptiveAudioUrls = mutableListOf<MediaFormatOption>()
        val adaptiveVideoList = mutableListOf<MediaFormatOption>()
        if (adaptiveArray != null) {
            for (i in 0 until adaptiveArray.length()) {
                val opt = parseFormatObject(adaptiveArray.optJSONObject(i), isMuxedDefault = false) ?: continue
                if (opt.isAudioOnly) {
                    adaptiveAudioUrls.add(opt)
                } else {
                    adaptiveVideoList.add(opt)
                }
            }
        }

        val bestM4aAudio = adaptiveAudioUrls
            .filter { it.container == "M4A" || it.mimeType.contains("mp4", ignoreCase = true) }
            .maxByOrNull { it.bitrate }?.url
        val bestWebmAudio = adaptiveAudioUrls
            .filter { it.container == "WEBM" || it.container == "OPUS" || it.mimeType.contains("webm", ignoreCase = true) }
            .maxByOrNull { it.bitrate }?.url

        // Strict container matching: MP4 (avc1/h264) + M4A (mp4a), WEBM (vp9) + WEBM (opus)
        val pairedAdaptiveVideos = adaptiveVideoList.map { vid ->
            if (vid.audioPairUrl != null) return@map vid
            val matchedAudio = if (vid.container.equals("WEBM", ignoreCase = true)) {
                bestWebmAudio
            } else {
                bestM4aAudio
            }
            vid.copy(audioPairUrl = matchedAudio)
        }

        val combined = (parsedFormats + pairedAdaptiveVideos + adaptiveAudioUrls)
            .distinctBy { "${it.itag}_${it.qualityLabel}_${it.container}_${it.codec}" }
            .sortedWith(
                compareByDescending<MediaFormatOption> { it.isMuxed }
                    .thenByDescending { it.hasVideo }
                    .thenByDescending { it.hasAudio || !it.audioPairUrl.isNullOrBlank() }
                    .thenByDescending { it.codecPriority }
                    .thenByDescending { extractResolutionHeight(it.qualityLabel) }
                    .thenByDescending { it.bitrate }
            )

        if (combined.isEmpty() && hlsUrl == null) return null

        val allFormats = if (hlsUrl != null && combined.none { it.isHls }) {
            combined + MediaFormatOption(
                itag = 999,
                qualityLabel = "Auto Adaptive (HLS)",
                mimeType = "application/x-mpegURL",
                container = "HLS",
                url = hlsUrl,
                hasVideo = true,
                hasAudio = true,
                isHls = true
            )
        } else {
            combined
        }

        return MediaItemFormatInfo(
            videoId = videoId,
            title = title,
            description = description,
            author = author,
            channelId = channelId,
            viewCountText = formatViewCount(rawViewCount),
            publishedDateText = formattedDateTime.first,
            publishedExactTimeText = formattedDateTime.second,
            durationMs = lengthSeconds * 1000L,
            hlsManifestUrl = hlsUrl,
            dashManifestUrl = dashUrl,
            formats = allFormats,
            isLive = isLive,
            requestUserAgent = requestUserAgent
        )
    }

    private fun parseFormatObject(obj: JSONObject?, isMuxedDefault: Boolean): MediaFormatOption? {
        if (obj == null) return null
        val directUrl = obj.optString("url").takeIf { it.isNotBlank() } ?: return null
        val itag = obj.optInt("itag", 0)
        val mimeType = obj.optString("mimeType", "video/mp4")
        val codec = Regex("codecs=\"([^\"]+)\"").find(mimeType)?.groupValues?.getOrNull(1).orEmpty()
        val isAudio = mimeType.startsWith("audio/")
        val isVideo = mimeType.startsWith("video/")
        val bitrate = obj.optInt("bitrate", 0)
        val fps = obj.optInt("fps", 30)
        val contentLength = obj.optString("contentLength").toLongOrNull() ?: 0L
        val width = obj.optInt("width", 0)
        val height = obj.optInt("height", 0)
        val shortEdge = if (width > 0 && height > 0) minOf(width, height) else height

        val qualityLabel = when {
            isAudio -> {
                val kbps = if (bitrate > 0) "${bitrate / 1000}kbps" else "HQ"
                if (mimeType.contains("mp4a")) "Audio M4A ($kbps)" else "Audio Opus ($kbps)"
            }
            obj.optString("qualityLabel").isNotBlank() -> obj.optString("qualityLabel")
            shortEdge > 0 -> "${shortEdge}p"
            else -> obj.optString("quality", "360p")
        }

        val container = when {
            mimeType.contains("mp4a") -> "M4A"
            mimeType.contains("webm") -> "WEBM"
            mimeType.contains("mp4") -> "MP4"
            else -> "MP4"
        }

        val initRangeObj = obj.optJSONObject("initRange")
        val indexRangeObj = obj.optJSONObject("indexRange")
        val initStart = initRangeObj?.optString("start")?.toLongOrNull() ?: -1L
        val initEnd = initRangeObj?.optString("end")?.toLongOrNull() ?: -1L
        val indexStart = indexRangeObj?.optString("start")?.toLongOrNull() ?: -1L
        val indexEnd = indexRangeObj?.optString("end")?.toLongOrNull() ?: -1L

        val hasAudio = isMuxedDefault || isAudio || obj.has("audioQuality")
        return MediaFormatOption(
            itag = itag,
            qualityLabel = qualityLabel,
            mimeType = mimeType.substringBefore(";"),
            container = container,
            url = directUrl,
            codec = codec,
            bitrate = bitrate,
            fps = fps,
            contentLengthBytes = contentLength,
            hasVideo = isVideo,
            hasAudio = hasAudio,
            width = width,
            height = shortEdge,
            initRangeStart = initStart,
            initRangeEnd = initEnd,
            indexRangeStart = indexStart,
            indexRangeEnd = indexEnd
        )
    }

    fun parsePipedVideos(arr: JSONArray, forceShort: Boolean = false): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            val videoId = url.substringAfter("v=").substringAfter("/shorts/").substringBefore("&")
            if (videoId.length < 5) continue
            val isShort = forceShort || obj.optBoolean("isShort", false) || url.contains("/shorts/")
            val durationSec = obj.optLong("duration", 0L)
            val views = obj.optLong("views", -1L)
            val uploaded = obj.optLong("uploaded", 0L)
            val uploadedStr = obj.optString("uploadedDate")
            val exactTime = if (uploaded > 0) formatEpochDateTime(uploaded) else ""
            val uploaderUrl = obj.optString("uploaderUrl")
            val channelId = uploaderUrl.substringAfterLast("/").takeIf { it.startsWith("UC") } ?: ""

            list.add(
                MediaItem(
                    id = videoId,
                    title = obj.optString("title", "Video"),
                    thumbnailUrl = normalizeUrl(obj.optString("thumbnail"))
                        .ifBlank { "https://i.ytimg.com/vi/$videoId/hq720.jpg" },
                    channelId = channelId,
                    channelName = obj.optString("uploaderName", "Creator"),
                    channelAvatarUrl = normalizeUrl(obj.optString("uploaderAvatar")),
                    viewCountText = if (views >= 0) formatViewCount(views) else "",
                    viewCount = views.coerceAtLeast(0L),
                    publishedDateText = uploadedStr,
                    publishedExactTimeText = exactTime,
                    durationText = formatSeconds(durationSec),
                    durationMs = durationSec * 1000L,
                    isShort = isShort,
                    badgeText = if (isShort) "SHORTS" else if (durationSec <= 0) "LIVE" else null
                )
            )
        }
        return list
    }

    fun parsePipedStreams(obj: JSONObject, videoId: String): MediaItemFormatInfo? {
        val hls = obj.optString("hls").takeIf { it.isNotBlank() }
        val title = obj.optString("title", "Video")
        val uploader = obj.optString("uploader", "Creator")
        val uploaderUrl = obj.optString("uploaderUrl")
        val channelId = uploaderUrl.substringAfterLast("/")
        val avatar = normalizeUrl(obj.optString("uploaderAvatar"))
        val views = obj.optLong("views", 0L)
        val uploadDate = obj.optString("uploadDate")
        val durationSec = obj.optLong("duration", 0L)
        val formattedDate = formatIsoDateTime(uploadDate)

        val formats = mutableListOf<MediaFormatOption>()
        val audioStreams = obj.optJSONArray("audioStreams")
        val audioOptions = mutableListOf<MediaFormatOption>()
        if (audioStreams != null) {
            for (i in 0 until audioStreams.length()) {
                val a = audioStreams.optJSONObject(i) ?: continue
                val url = a.optString("url").takeIf { it.isNotBlank() } ?: continue
                val bitrate = a.optInt("bitrate", 128000)
                val mime = a.optString("mimeType", "audio/mp4")
                val container = if (mime.contains("webm") || mime.contains("opus")) "OPUS" else "M4A"
                audioOptions.add(
                    MediaFormatOption(
                        itag = a.optInt("itag", 140 + i),
                        qualityLabel = "Audio $container (${bitrate / 1000}kbps)",
                        mimeType = mime,
                        container = container,
                        url = url,
                        bitrate = bitrate,
                        contentLengthBytes = a.optLong("contentLength", 0L),
                        hasVideo = false,
                        hasAudio = true
                    )
                )
            }
        }
        val bestM4aAudio = audioOptions
            .filter { it.container == "M4A" || it.mimeType.contains("mp4", ignoreCase = true) }
            .maxByOrNull { it.bitrate }?.url
        val bestWebmAudio = audioOptions
            .filter { it.container == "OPUS" || it.container == "WEBM" || it.mimeType.contains("webm", ignoreCase = true) }
            .maxByOrNull { it.bitrate }?.url

        val videoStreams = obj.optJSONArray("videoStreams")
        if (videoStreams != null) {
            for (i in 0 until videoStreams.length()) {
                val v = videoStreams.optJSONObject(i) ?: continue
                val url = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                val videoOnly = v.optBoolean("videoOnly", true)
                val quality = v.optString("quality", "360p")
                val fps = v.optInt("fps", 30)
                val mime = v.optString("mimeType", "video/mp4")
                val codec = v.optString("codec")
                val container = if (mime.contains("webm", ignoreCase = true)) "WEBM" else "MP4"
                val matchedAudio = if (videoOnly) {
                    if (container == "WEBM") bestWebmAudio else bestM4aAudio
                } else null
                formats.add(
                    MediaFormatOption(
                        itag = v.optInt("itag", 18 + i),
                        qualityLabel = quality,
                        mimeType = mime,
                        container = container,
                        url = url,
                        audioPairUrl = matchedAudio,
                        codec = codec,
                        bitrate = v.optInt("bitrate", 0),
                        fps = fps,
                        contentLengthBytes = v.optLong("contentLength", 0L),
                        hasVideo = true,
                        hasAudio = !videoOnly
                    )
                )
            }
        }

        val combined = (formats + audioOptions).sortedWith(
            compareByDescending<MediaFormatOption> { it.isMuxed }
                .thenByDescending { it.hasVideo }
                .thenByDescending { it.hasAudio || !it.audioPairUrl.isNullOrBlank() }
                .thenByDescending { it.codecPriority }
                .thenByDescending { extractResolutionHeight(it.qualityLabel) }
        )

        if (combined.isEmpty() && hls == null) return null

        return MediaItemFormatInfo(
            videoId = videoId,
            title = title,
            description = obj.optString("description"),
            author = uploader,
            channelId = channelId,
            channelAvatarUrl = avatar,
            viewCountText = formatViewCount(views),
            publishedDateText = formattedDate.first.ifBlank { uploadDate },
            publishedExactTimeText = formattedDate.second,
            durationMs = durationSec * 1000L,
            hlsManifestUrl = hls,
            dashManifestUrl = obj.optString("dash").takeIf { it.isNotBlank() },
            formats = combined,
            isLive = obj.optBoolean("livestream", false),
            requestUserAgent = NetworkStackProvider.USER_AGENT_MOBILE
        )
    }

    fun parseInvidiousVideos(arr: JSONArray, forceShort: Boolean = false): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val videoId = obj.optString("videoId").takeIf { it.isNotBlank() } ?: continue
            val durationSec = obj.optLong("lengthSeconds", 0L)
            val views = obj.optLong("viewCount", 0L)
            val published = obj.optLong("published", 0L)
            val publishedText = obj.optString("publishedText")
            val thumbs = obj.optJSONArray("videoThumbnails")
            val thumbUrl = thumbs?.optJSONObject(0)?.optString("url")
                ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
            val isShort = forceShort || (durationSec in 1..60 && obj.optString("title").contains("#short", true))

            list.add(
                MediaItem(
                    id = videoId,
                    title = obj.optString("title", "Video"),
                    thumbnailUrl = normalizeUrl(thumbUrl),
                    channelId = obj.optString("authorId"),
                    channelName = obj.optString("author", "Creator"),
                    viewCountText = formatViewCount(views),
                    viewCount = views,
                    publishedDateText = publishedText,
                    publishedExactTimeText = if (published > 0) formatEpochDateTime(published * 1000L) else "",
                    durationText = formatSeconds(durationSec),
                    durationMs = durationSec * 1000L,
                    isShort = isShort,
                    badgeText = if (isShort) "SHORTS" else null
                )
            )
        }
        return list
    }

    fun parseInvidiousStreams(obj: JSONObject, videoId: String): MediaItemFormatInfo? {
        val hls = obj.optString("hlsUrl").takeIf { it.isNotBlank() }
        val title = obj.optString("title", "Video")
        val author = obj.optString("author", "Creator")
        val channelId = obj.optString("authorId")
        val views = obj.optLong("viewCount", 0L)
        val durationSec = obj.optLong("lengthSeconds", 0L)
        val published = obj.optLong("published", 0L)

        val formats = mutableListOf<MediaFormatOption>()
        val formatStreams = obj.optJSONArray("formatStreams")
        if (formatStreams != null) {
            for (i in 0 until formatStreams.length()) {
                val f = formatStreams.optJSONObject(i) ?: continue
                val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                val container = f.optString("container", "mp4").uppercase()
                formats.add(
                    MediaFormatOption(
                        itag = f.optString("itag").toIntOrNull() ?: (18 + i),
                        qualityLabel = f.optString("qualityLabel", "360p"),
                        mimeType = f.optString("type", "video/mp4").substringBefore(";"),
                        container = container,
                        url = url,
                        bitrate = f.optString("bitrate").toIntOrNull() ?: 0,
                        hasVideo = true,
                        hasAudio = true
                    )
                )
            }
        }

        val adaptive = obj.optJSONArray("adaptiveFormats")
        val audios = mutableListOf<MediaFormatOption>()
        val videos = mutableListOf<MediaFormatOption>()
        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val a = adaptive.optJSONObject(i) ?: continue
                val url = a.optString("url").takeIf { it.isNotBlank() } ?: continue
                val type = a.optString("type", "video/mp4")
                val isAudio = type.startsWith("audio/")
                val container = if (type.contains("webm")) "WEBM" else if (isAudio) "M4A" else "MP4"
                val bitrate = a.optString("bitrate").toIntOrNull() ?: 0
                val opt = MediaFormatOption(
                    itag = a.optString("itag").toIntOrNull() ?: (134 + i),
                    qualityLabel = if (isAudio) "Audio $container (${bitrate / 1000}kbps)"
                    else a.optString("qualityLabel", "720p"),
                    mimeType = type.substringBefore(";"),
                    container = container,
                    url = url,
                    bitrate = bitrate,
                    contentLengthBytes = a.optString("clen").toLongOrNull() ?: 0L,
                    hasVideo = !isAudio,
                    hasAudio = isAudio
                )
                if (isAudio) audios.add(opt) else videos.add(opt)
            }
        }

        val bestM4aAudio = audios
            .filter { it.container == "M4A" || it.mimeType.contains("mp4", ignoreCase = true) }
            .maxByOrNull { it.bitrate }?.url
        val bestWebmAudio = audios
            .filter { it.container == "WEBM" || it.container == "OPUS" || it.mimeType.contains("webm", ignoreCase = true) }
            .maxByOrNull { it.bitrate }?.url

        val pairedVideos = videos.map { vid ->
            val matchedAudio = if (vid.container.equals("WEBM", ignoreCase = true)) {
                bestWebmAudio
            } else {
                bestM4aAudio
            }
            vid.copy(audioPairUrl = matchedAudio)
        }

        val combined = (formats + pairedVideos + audios)
            .sortedWith(
                compareByDescending<MediaFormatOption> { it.isMuxed }
                    .thenByDescending { it.hasVideo }
                    .thenByDescending { it.hasAudio || !it.audioPairUrl.isNullOrBlank() }
                    .thenByDescending { it.codecPriority }
                    .thenByDescending { extractResolutionHeight(it.qualityLabel) }
            )

        if (combined.isEmpty() && hls == null) return null

        return MediaItemFormatInfo(
            videoId = videoId,
            title = title,
            description = obj.optString("description"),
            author = author,
            channelId = channelId,
            viewCountText = formatViewCount(views),
            publishedDateText = if (published > 0) formatEpochDateTime(published * 1000L) else "",
            durationMs = durationSec * 1000L,
            hlsManifestUrl = hls,
            dashManifestUrl = obj.optString("dashUrl").takeIf { it.isNotBlank() },
            formats = combined,
            requestUserAgent = NetworkStackProvider.USER_AGENT_MOBILE
        )
    }

    fun extractContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val token = node.optJSONObject("continuationCommand")?.optString("token")
                if (!token.isNullOrBlank()) return token
                val keys = node.keys()
                while (keys.hasNext()) {
                    extractContinuationToken(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractContinuationToken(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun readText(obj: JSONObject?): String? {
        if (obj == null) return null
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val content = obj.optString("content")
        if (content.isNotBlank()) return content
        val runs = obj.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun extractBrowseId(obj: JSONObject?): String {
        val runs = obj?.optJSONArray("runs") ?: return ""
        for (i in 0 until runs.length()) {
            val browseId = runs.optJSONObject(i)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
            if (!browseId.isNullOrBlank()) return browseId
        }
        return ""
    }

    private fun extractAvatarUrl(obj: JSONObject): String {
        val legacy = obj.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")
        extractBestThumbnail(legacy)?.let { return normalizeUrl(it) }

        val thumb = obj.optJSONObject("channelThumbnail")
        extractBestThumbnail(thumb)?.let { return normalizeUrl(it) }

        return ""
    }

    private fun extractBestThumbnail(obj: JSONObject?): String? {
        val arr = obj?.optJSONArray("thumbnails") ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(arr.length() - 1)?.optString("url")?.let { normalizeUrl(it) }
    }

    fun normalizeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return when {
            raw.startsWith("//") -> "https:$raw"
            else -> raw
        }
    }

    fun extractResolutionHeight(label: String): Int {
        val digits = Regex("(\\d{3,4})p").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (digits != null) return digits
        if (label.contains("2k", ignoreCase = true)) return 1440
        if (label.contains("4k", ignoreCase = true)) return 2160
        return 0
    }

    /**
     * Strict Shorts Filter (#6): duration <= 65s and non-live.
     */
    fun isStrictShortStream(item: MediaItem): Boolean {
        if (item.isLive) return false
        if (item.durationMs > 65_000L) return false
        return item.isShort ||
            item.videoUrl.contains("/shorts/", ignoreCase = true) ||
            item.durationMs in 1L..65_000L
    }

    /**
     * Filters formats strictly to the 5 requested downloadable video resolutions
     * (240p, 360p, 720p, 1080p, 2K/1440p), keeping only playable streams (muxed or paired with audio)
     * and hiding any resolution that is unavailable for this video.
     */
    fun filterAllowedDownloadFormats(formats: List<MediaFormatOption>): List<MediaFormatOption> {
        val allowedHeights = listOf(240, 360, 720, 1080, 1440)
        val playable = formats.filter {
            !it.isHls && it.hasVideo && it.url.isNotBlank() &&
                (it.isMuxed || !it.audioPairUrl.isNullOrBlank())
        }

        val result = mutableListOf<MediaFormatOption>()
        for (targetH in allowedHeights) {
            val bestForHeight = playable
                .filter { extractResolutionHeight(it.qualityLabel) == targetH }
                .sortedWith(
                    compareByDescending<MediaFormatOption> { it.isMuxed }
                        .thenByDescending { it.codecPriority }
                        .thenByDescending { it.fps }
                        .thenByDescending { it.bitrate }
                )
                .firstOrNull()

            if (bestForHeight != null) {
                val cleanLabel = if (targetH == 1440) "2K (1440p)" else "${targetH}p"
                result.add(bestForHeight.copy(qualityLabel = cleanLabel))
            }
        }
        return result
    }

    /**
     * Returns dynamic quality selector labels available for the currently playing video.
     */
    fun extractAvailablePlayerQualities(formats: List<MediaFormatOption>): List<String> {
        val standardHeights = listOf(240, 360, 480, 720, 1080, 1440)
        val available = formats
            .filter { !it.isHls && it.hasVideo && it.url.isNotBlank() && (it.isMuxed || !it.audioPairUrl.isNullOrBlank()) }
            .map { extractResolutionHeight(it.qualityLabel) }
            .filter { it in standardHeights }
            .distinct()
            .sorted()
            .map { h -> if (h == 1440) "2K" else "${h}p" }

        val merged = (available + "Auto").distinct()
        return if (merged.size > 1) merged else listOf("360p", "480p", "720p", "1080p", "Auto")
    }

    fun parseDurationToMs(text: String): Long {
        if (text.isBlank()) return 0L
        val parts = text.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> ((parts[0] * 3600) + (parts[1] * 60) + parts[2]) * 1000L
            2 -> ((parts[0] * 60) + parts[1]) * 1000L
            1 -> parts[0] * 1000L
            else -> 0L
        }
    }

    fun formatSeconds(seconds: Long): String {
        if (seconds <= 0L) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun formatViewCount(views: Long): String {
        return when {
            views <= 0L -> ""
            views >= 1_000_000_000L -> String.format(Locale.US, "%.1fB views", views / 1_000_000_000.0)
            views >= 1_000_000L -> String.format(Locale.US, "%.1fM views", views / 1_000_000.0)
            views >= 1_000L -> String.format(Locale.US, "%.1fK views", views / 1_000.0)
            else -> "$views views"
        }
    }

    fun formatIsoDateTime(iso: String): Pair<String, String> {
        if (iso.isBlank()) return "" to ""
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = parser.parse(iso) ?: continue
                val dateOut = SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
                val timeOut = if (pattern.contains("HH:mm")) {
                    SimpleDateFormat("h:mm a", Locale.US).format(date)
                } else {
                    ""
                }
                return dateOut to timeOut
            } catch (_: Exception) {
            }
        }
        return iso to ""
    }

    private fun formatEpochDateTime(epochMs: Long): String {
        return try {
            val d = Date(epochMs)
            SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(d)
        } catch (_: Exception) {
            ""
        }
    }
}
