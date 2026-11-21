package com.example.youtubeapi

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.example.mediaserviceinterfaces.ChannelInfo
import com.example.mediaserviceinterfaces.FeedCategory
import com.example.mediaserviceinterfaces.MediaGroup
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import com.example.mediaserviceinterfaces.MediaService
import com.example.mediaserviceinterfaces.SearchFilter
import com.example.mediaserviceinterfaces.SearchResultBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import java.util.TimeZone

/**
 * YouTube InnerTube client with auto-location PK/IN trending, fresh non-repeating Recommended feed,
 * interest-learning Shorts batching, full Channel resolution, and Piped/Invidious fallback.
 */
class YouTubeMediaService(
    context: Context,
    private val networkStack: NetworkStackProvider
) : MediaService {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("innertube_session", Context.MODE_PRIVATE)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val sessionRandomOffset = kotlin.random.Random.nextInt(0, 100)

    private val preferredRegion: String by lazy { detectPreferredRegion() }

    @Volatile
    private var visitorDataToken: String = prefs.getString(KEY_VISITOR_DATA, "").orEmpty()

    private val streamExtractor by lazy {
        YouTubeStreamExtractor(networkStack) { profile, videoId ->
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", profile.toClientContextJson().apply {
                        if (visitorDataToken.isNotBlank()) put("visitorData", visitorDataToken)
                    })
                    if (profile.id == "TVHTML5_SIMPLY_EMBEDDED_PLAYER" || profile.id == "WEB_EMBEDDED_PLAYER") {
                        put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com/"))
                    }
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            postInnertube("player", profile, payload)
        }
    }

    private val seenShortIds: LinkedHashSet<String> by lazy {
        val raw = prefs.getString(KEY_SEEN_SHORTS, "").orEmpty()
        LinkedHashSet(raw.split(",").filter { it.isNotBlank() })
    }

    private val sessionDeliveredShortIds = java.util.Collections.synchronizedSet(HashSet<String>())

    @Synchronized
    override fun markShortSeen(videoId: String) {
        if (videoId.isBlank()) return
        sessionDeliveredShortIds.add(videoId)
        seenShortIds.remove(videoId)
        seenShortIds.add(videoId)
        while (seenShortIds.size > MAX_SEEN_SHORTS) {
            val oldest = seenShortIds.firstOrNull() ?: break
            seenShortIds.remove(oldest)
        }
        prefs.edit().putString(KEY_SEEN_SHORTS, seenShortIds.joinToString(",")).apply()
    }

    private val seenHomeIds: LinkedHashSet<String> by lazy {
        val raw = prefs.getString(KEY_SEEN_HOME, "").orEmpty()
        LinkedHashSet(raw.split(",").filter { it.isNotBlank() })
    }

    private val shortsAffinityScores: MutableMap<String, Int> by lazy {
        val raw = prefs.getString(KEY_SHORTS_AFFINITY, "").orEmpty()
        val map = linkedMapOf<String, Int>()
        runCatching {
            if (raw.isNotBlank()) {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.optInt(k, 0)
                }
            }
        }
        map
    }

    private fun detectPreferredRegion(): String {
        val tm = runCatching { appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager }.getOrNull()
        val netCountry = tm?.networkCountryIso?.uppercase(Locale.US)?.takeIf { it.length == 2 }
        if (netCountry == "PK" || netCountry == "IN") return netCountry
        val simCountry = tm?.simCountryIso?.uppercase(Locale.US)?.takeIf { it.length == 2 }
        if (simCountry == "PK" || simCountry == "IN") return simCountry

        val tz = TimeZone.getDefault().id
        if (tz.contains("Karachi", ignoreCase = true)) return "PK"
        if (tz.contains("Kolkata", ignoreCase = true) || tz.contains("Calcutta", ignoreCase = true)) return "IN"

        val localeCountry = Locale.getDefault().country.uppercase(Locale.US).takeIf { it.length == 2 }
        if (localeCountry == "PK" || localeCountry == "IN") return localeCountry
        if (netCountry != null && netCountry != "US") return netCountry
        if (simCountry != null && simCountry != "US") return simCountry
        // Default to PK/IN rotation when emulator/device reports generic US locale
        return if ((sessionRandomOffset % 2) == 0) "PK" else "IN"
    }

    @Synchronized
    private fun getSeenHomeSnapshot(): Set<String> = seenHomeIds.toHashSet()

    @Synchronized
    private fun markHomeDelivered(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.forEach { id ->
            seenHomeIds.remove(id)
            seenHomeIds.add(id)
        }
        while (seenHomeIds.size > MAX_SEEN_HOME) {
            val oldest = seenHomeIds.firstOrNull() ?: break
            seenHomeIds.remove(oldest)
        }
        prefs.edit().putString(KEY_SEEN_HOME, seenHomeIds.joinToString(",")).apply()
    }

    @Synchronized
    private fun nextHomeSeedCursor(modulo: Int): Int {
        val current = prefs.getInt(KEY_HOME_CURSOR, 0)
        val next = (current + 1) % modulo.coerceAtLeast(1)
        prefs.edit().putInt(KEY_HOME_CURSOR, next).apply()
        return current
    }

    @Synchronized
    override fun recordShortInterest(item: MediaItem, weight: Int) {
        val lower = item.title.lowercase(Locale.US)
        val matchedTags = buildList {
            if (lower.contains("funny") || lower.contains("comedy") || lower.contains("meme") ||
                lower.contains("prank") || lower.contains("haso") || lower.contains("mazak")
            ) {
                add(if (lower.contains("pak") || lower.contains("ind") || lower.contains("desi") || lower.contains("hindi") || lower.contains("urdu")) "funny_desi" else "funny")
            }
            if (lower.contains("cricket") || lower.contains("football") || lower.contains("sport") ||
                lower.contains("match") || lower.contains("goal") || lower.contains("kohli") || lower.contains("babar")
            ) add("sports")
            if (lower.contains("news") || lower.contains("breaking") || lower.contains("live") || lower.contains("update")) {
                add("news")
            }
            if (lower.contains("diy") || lower.contains("craft") || lower.contains("origami") ||
                lower.contains("art") || lower.contains("hack") || lower.contains("wood")
            ) add("diy_craft")
            if (lower.contains("drama") || lower.contains("movie") || lower.contains("song") ||
                lower.contains("music") || lower.contains("bollywood") || lower.contains("dance") || lower.contains("entertain")
            ) add("entertainment")
        }
        if (matchedTags.isEmpty()) return
        matchedTags.forEach { tag ->
            val current = shortsAffinityScores[tag] ?: 0
            shortsAffinityScores[tag] = (current + weight.coerceAtLeast(1)).coerceAtMost(200)
        }
        val json = JSONObject().apply {
            shortsAffinityScores.forEach { (k, v) -> put(k, v) }
        }
        prefs.edit().putString(KEY_SHORTS_AFFINITY, json.toString()).apply()
    }

    @Synchronized
    private fun getTopInterestCategories(): List<String> {
        return shortsAffinityScores.entries
            .sortedByDescending { it.value }
            .filter { it.value > 0 }
            .map { it.key }
    }

    @Synchronized
    private fun getSeenShortsSnapshot(): Set<String> = (seenShortIds + sessionDeliveredShortIds).toHashSet()

    @Synchronized
    private fun trimSeenShortsHalf() {
        val keep = seenShortIds.drop(seenShortIds.size / 2)
        seenShortIds.clear()
        seenShortIds.addAll(keep)
        prefs.edit().putString(KEY_SEEN_SHORTS, seenShortIds.joinToString(",")).apply()
    }

    @Synchronized
    private fun nextShortsSeedCursor(modulo: Int): Int {
        val current = prefs.getInt(KEY_SHORTS_CURSOR, 0)
        val next = (current + 1) % modulo.coerceAtLeast(1)
        prefs.edit().putInt(KEY_SHORTS_CURSOR, next).apply()
        return current
    }

    private fun isLongFormCandidate(item: MediaItem): Boolean {
        if (item.isShort) return false
        if (item.videoUrl.contains("/shorts/", ignoreCase = true)) return false
        if (item.title.contains("#short", ignoreCase = true)) return false
        if (!item.isLive && item.durationMs in 1L..62_000L) return false
        return true
    }

    override suspend fun getHomeFeed(
        category: FeedCategory,
        batchIndex: Int,
        batchSize: Int,
        continuationToken: String?,
        historyInterestQuery: String?
    ): MediaGroup = withContext(Dispatchers.IO) {
        val freshPool = mutableListOf<MediaItem>()
        val fallbackPool = mutableListOf<MediaItem>()
        val seenInBatch = hashSetOf<String>()
        val previouslyShownHome = if (category == FeedCategory.HOME) getSeenHomeSnapshot() else emptySet()
        var nextToken: String? = null

        fun addCandidate(item: MediaItem) {
            if (!seenInBatch.add(item.id)) return
            if (category == FeedCategory.HOME && previouslyShownHome.contains(item.id)) {
                fallbackPool.add(item)
            } else {
                freshPool.add(item)
            }
        }

        val regionalPrimaryName = if (preferredRegion == "IN") "India" else "Pakistan"
        val regionalSecondaryName = if (preferredRegion == "IN") "Pakistan" else "India"
        val categorySeeds = when (category) {
            FeedCategory.HOME -> listOf(
                "$regionalPrimaryName trending videos today",
                "$regionalSecondaryName viral trending video today",
                "Pakistani drama comedy entertainment 2026",
                "Bollywood latest trending songs official",
                "India Pakistan cricket highlights match",
                "latest tech smartphone review unboxing",
                "desi comedy standup funny show",
                "breaking news $regionalPrimaryName today",
                "4k travel documentary nature",
                "street food India Pakistan vlog"
            )
            FeedCategory.TRENDING -> listOf(
                "$regionalPrimaryName trending videos today",
                "$regionalSecondaryName viral trending today",
                "top trending music hits 2026"
            )
            FeedCategory.LIVE -> listOf("live news $regionalPrimaryName 24/7", "live lofi radio stream", "live cricket sports stream")
            else -> listOf(
                "${category.label} $regionalPrimaryName trending",
                "${category.label} trending videos today",
                "${category.label} best 2026"
            )
        }

        val homeCursor = if (category == FeedCategory.HOME) nextHomeSeedCursor(categorySeeds.size) else batchIndex
        val cursor = (homeCursor + batchIndex * 2 + (sessionRandomOffset % categorySeeds.size)) % categorySeeds.size

        if (category == FeedCategory.HOME) {
            // Fast Recommended Home: parallel Innertube calls only (~400-800ms), fallback mirrors only if sparse
            coroutineScope {
                val homeBrowseDeferred = async {
                    val root = if (batchIndex > 0 && !continuationToken.isNullOrBlank()) {
                        postInnertube(
                            endpoint = "browse",
                            profile = InnertubeClientProfiles.WEB,
                            payload = JSONObject().apply {
                                put("context", buildClientContext(InnertubeClientProfiles.WEB))
                                put("continuation", continuationToken)
                            }
                        )
                    } else if (batchIndex == 0) {
                        postInnertube(
                            endpoint = "browse",
                            profile = InnertubeClientProfiles.WEB,
                            payload = JSONObject().apply {
                                put("context", buildClientContext(InnertubeClientProfiles.WEB))
                                put("browseId", "FEwhat_to_watch")
                            }
                        )
                    } else null
                    val token = root?.let { InnertubeParser.extractContinuationToken(it) }
                    val items = root?.let {
                        InnertubeParser.parseInnertubeVideos(it, forceShort = false)
                            .filter { item -> isLongFormCandidate(item) }
                    }.orEmpty()
                    token to items
                }
                val primaryRegionalDeferred = async {
                    if (batchIndex == 0) {
                        fetchInnertubeSearch("$regionalPrimaryName trending videos today")
                            .filter { isLongFormCandidate(it) }
                            .shuffled()
                    } else emptyList()
                }
                val rotatedTopicDeferred = async {
                    val seedQuery = categorySeeds[cursor]
                    fetchInnertubeSearch(seedQuery)
                        .filter { isLongFormCandidate(it) }
                        .shuffled()
                }
                val recommendedDeferred = async {
                    if (batchIndex == 0 && !historyInterestQuery.isNullOrBlank()) {
                        fetchInnertubeSearch(historyInterestQuery)
                            .filter { isLongFormCandidate(it) }
                            .shuffled()
                            .take(12)
                    } else emptyList()
                }

                val (homeToken, homeBrowseItems) = homeBrowseDeferred.await()
                nextToken = homeToken
                val recommendedItems = recommendedDeferred.await()
                val trendingPrimary = primaryRegionalDeferred.await()
                val rotatedItems = rotatedTopicDeferred.await()

                val maxLen = maxOf(
                    recommendedItems.size,
                    trendingPrimary.size,
                    rotatedItems.size,
                    homeBrowseItems.size
                )
                for (i in 0 until maxLen) {
                    recommendedItems.getOrNull(i)?.let(::addCandidate)
                    trendingPrimary.getOrNull(i)?.let(::addCandidate)
                    rotatedItems.getOrNull(i)?.let(::addCandidate)
                    homeBrowseItems.getOrNull(i)?.let(::addCandidate)
                }
            }

            // Only hit slower Kiosk/Piped/Invidious fallback if parallel Innertube returned < 10 items
            if (freshPool.size < 10) {
                fetchRealTrendingVideos(preferredRegion).forEach(::addCandidate)
            }
        } else if (category == FeedCategory.TRENDING) {
            if (batchIndex == 0) {
                (fetchInnertubeSearch("$regionalPrimaryName trending videos today") +
                    fetchInnertubeSearch("$regionalSecondaryName viral trending today"))
                    .filter { isLongFormCandidate(it) }
                    .ifEmpty { fetchRealTrendingVideos(preferredRegion) }
                    .forEach(::addCandidate)
            } else if (!continuationToken.isNullOrBlank()) {
                val root = postInnertube(
                    endpoint = "browse",
                    profile = InnertubeClientProfiles.WEB,
                    payload = JSONObject().apply {
                        put("context", buildClientContext(InnertubeClientProfiles.WEB))
                        put("continuation", continuationToken)
                    }
                )
                if (root != null) {
                    nextToken = InnertubeParser.extractContinuationToken(root)
                    InnertubeParser.parseInnertubeVideos(root, forceShort = false)
                        .filter { isLongFormCandidate(it) }
                        .forEach(::addCandidate)
                }
            }
        }

        var attempts = 0
        val startOffset = if (category == FeedCategory.HOME) 1 else 0
        while (freshPool.size < batchSize && attempts < 2) {
            val seedQuery = categorySeeds[(cursor + startOffset + attempts * 3) % categorySeeds.size]
            val fetched = fetchInnertubeSearch(seedQuery)
                .let { list ->
                    if (list.isNotEmpty() || freshPool.size >= 8) list
                    else fetchPipedTrending(preferredRegion).ifEmpty { fetchInvidiousTrending() }
                }
                .filter { isLongFormCandidate(it) }
                .shuffled()
            fetched.forEach { item ->
                if (freshPool.size < batchSize) addCandidate(item)
            }
            attempts++
        }

        val combined = if (freshPool.size >= batchSize) {
            freshPool.take(batchSize)
        } else {
            (freshPool + fallbackPool.shuffled()).take(batchSize)
        }

        if (category == FeedCategory.HOME && combined.isNotEmpty()) {
            markHomeDelivered(combined.map { it.id })
        }

        MediaGroup(
            id = "home_${category.name.lowercase()}_batch_${batchIndex}_${System.currentTimeMillis()}",
            title = category.label,
            category = category,
            items = combined,
            continuationToken = nextToken
        )
    }

    private fun fetchRegionalTrendingSearch(regionCode: String): List<MediaItem> {
        val q = if (regionCode == "PK") "Pakistan viral trending today" else "India viral trending today"
        return fetchInnertubeSearch(q)
            .ifEmpty { fetchPipedTrending(regionCode) }
            .filter { isLongFormCandidate(it) }
    }

    private fun fetchRealTrendingVideos(regionCode: String = preferredRegion): List<MediaItem> {
        val trendingOut = mutableListOf<MediaItem>()
        val seen = hashSetOf<String>()

        // 1) Innertube FEtrending with regional client context
        val root = postInnertube(
            endpoint = "browse",
            profile = InnertubeClientProfiles.WEB,
            payload = JSONObject().apply {
                put("context", buildClientContext(InnertubeClientProfiles.WEB, regionCode))
                put("browseId", "FEtrending")
            }
        )
        if (root != null) {
            InnertubeParser.parseInnertubeVideos(root, forceShort = false)
                .filter { isLongFormCandidate(it) }
                .forEach { if (seen.add(it.id)) trendingOut.add(it) }
        }

        // 2) NewPipeExtractor YouTube Trending Kiosk if FEtrending was sparse
        if (trendingOut.size < 10) {
            runCatching {
                NewPipeDownloaderImpl.ensureInitialized(networkStack.okHttpClient)
                val kiosk = ServiceList.YouTube.kioskList.defaultKioskExtractor
                kiosk.forceLocalization(org.schabi.newpipe.extractor.localization.Localization("en", regionCode))
                kiosk.forceContentCountry(org.schabi.newpipe.extractor.localization.ContentCountry(regionCode))
                kiosk.fetchPage()
                kiosk.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { mapNewPipeStreamItem(it) }
                    .filter { isLongFormCandidate(it) }
                    .forEach { if (seen.add(it.id)) trendingOut.add(it) }
            }
        }

        // 3) Piped / Invidious Regional Trending fallback
        if (trendingOut.size < 10) {
            (fetchPipedTrending(regionCode) + fetchInvidiousTrending())
                .filter { isLongFormCandidate(it) }
                .forEach { if (seen.add(it.id)) trendingOut.add(it) }
        }

        return trendingOut
    }

    private fun mapNewPipeStreamItem(item: StreamInfoItem): MediaItem? {
        val url = item.url.orEmpty()
        val videoId = url.substringAfter("v=").substringAfter("/shorts/").substringBefore("&")
        if (videoId.length < 5) return null
        val durationSec = item.duration.coerceAtLeast(0L)
        val isShort = item.isShortFormContent || url.contains("/shorts/") || (durationSec in 1L..62L)
        val thumb = item.thumbnails.lastOrNull()?.url?.let { InnertubeParser.normalizeUrl(it) }
            ?: "https://i.ytimg.com/vi/$videoId/hq720.jpg"
        val avatar = item.uploaderAvatars.lastOrNull()?.url?.let { InnertubeParser.normalizeUrl(it) }.orEmpty()
        val uploaderId = item.uploaderUrl.orEmpty().substringAfterLast("/")
        return MediaItem(
            id = videoId,
            title = item.name.orEmpty().ifBlank { return null },
            videoUrl = url.ifBlank { "https://www.youtube.com/watch?v=$videoId" },
            thumbnailUrl = thumb,
            fallbackThumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            channelId = uploaderId,
            channelName = item.uploaderName.orEmpty().ifBlank { "Creator" },
            channelAvatarUrl = avatar,
            viewCountText = InnertubeParser.formatViewCount(item.viewCount.coerceAtLeast(0L)),
            publishedDateText = item.textualUploadDate.orEmpty(),
            durationText = InnertubeParser.formatSeconds(durationSec),
            durationMs = durationSec * 1000L,
            isShort = isShort,
            badgeText = if (isShort) "SHORTS" else null
        )
    }

    override suspend fun getShortsBatch(
        batchIndex: Int,
        batchSize: Int,
        continuationToken: String?
    ): MediaGroup = withContext(Dispatchers.IO) {
        val collected = mutableListOf<MediaItem>()
        val seenInBatch = hashSetOf<String>()
        val previouslySeen = getSeenShortsSnapshot()
        val fallbackPool = mutableListOf<MediaItem>()

        val browseRoot = if (!continuationToken.isNullOrBlank()) {
            postInnertube(
                endpoint = "browse",
                profile = InnertubeClientProfiles.WEB,
                payload = JSONObject().apply {
                    put("context", buildClientContext(InnertubeClientProfiles.WEB))
                    put("continuation", continuationToken)
                }
            )
        } else if (batchIndex == 0 && kotlin.random.Random.nextInt(3) == 0) {
            postInnertube(
                endpoint = "browse",
                profile = InnertubeClientProfiles.WEB,
                payload = JSONObject().apply {
                    put("context", buildClientContext(InnertubeClientProfiles.WEB))
                    put("browseId", "FEshorts")
                }
            )
        } else null

        var nextToken: String? = null
        if (browseRoot != null) {
            nextToken = InnertubeParser.extractContinuationToken(browseRoot)
            InnertubeParser.parseInnertubeVideos(browseRoot, forceShort = true)
                .shuffled()
                .forEach { item ->
                    if (!previouslySeen.contains(item.id)) {
                        if (seenInBatch.add(item.id)) collected.add(item)
                    } else {
                        fallbackPool.add(item)
                    }
                }
        }

        val categorizedSeeds = mapOf(
            "funny_desi" to listOf(
                "#shorts Pakistan funny comedy",
                "#shorts India funny comedy skits",
                "#shorts desi funny memes",
                "#shorts Urdu Hindi comedy clip",
                "#shorts funny reaction desi"
            ),
            "funny" to listOf(
                "#shorts comedy standup clip",
                "#shorts funny cat dog pets",
                "#shorts funny prank try not to laugh",
                "#shorts relatable comedy skits"
            ),
            "entertainment" to listOf(
                "#shorts Pakistani drama best scene",
                "#shorts Bollywood entertainment viral",
                "#shorts trending music remix phonk",
                "#shorts movie scenes viral"
            ),
            "news" to listOf(
                "#shorts breaking news Pakistan India",
                "#shorts world trending news today",
                "#shorts tech ai news update"
            ),
            "sports" to listOf(
                "#shorts cricket sixes Pakistan India",
                "#shorts cricket bowling yorkers",
                "#shorts football goals skills",
                "#shorts boxing ufc knockout"
            ),
            "diy_craft" to listOf(
                "#shorts DIY craft creative ideas",
                "#shorts origami paper craft",
                "#shorts woodworking craft satisfying",
                "#shorts painting drawing art hack",
                "#shorts miniature cooking craft"
            )
        )

        val allCategories = categorizedSeeds.keys.toList()
        val topUserCategories = getTopInterestCategories().filter { categorizedSeeds.containsKey(it) }

        // Build ~60% weighted to user's favorite categories + ~40% diverse regional discovery
        val weightedPool = mutableListOf<String>()
        if (topUserCategories.isNotEmpty()) {
            topUserCategories.take(2).forEach { favCat ->
                categorizedSeeds[favCat]?.let { seeds ->
                    weightedPool.addAll(seeds.shuffled().take(3))
                }
            }
        }
        allCategories.shuffled().forEach { cat ->
            categorizedSeeds[cat]?.let { seeds ->
                weightedPool.add(seeds.random())
            }
        }
        val topicSeeds = weightedPool.distinct().ifEmpty { categorizedSeeds.values.flatten().shuffled() }

        // Rotate search sort parameters so even overlapping topics yield fresh uploads & results
        val sortParams = listOf<String?>(
            null,
            "EgQQARgB",      // Short duration (<4 min)
            "CAISAhAB",      // Sort by upload date (fresh newest clips)
            "EgIQAQ%3D%3D"   // Videos filter
        )

        val baseCursor = nextShortsSeedCursor(topicSeeds.size)
        val freshSeedOffset = (baseCursor + sessionRandomOffset + batchIndex * 3 +
            kotlin.random.Random.nextInt(topicSeeds.size)) % topicSeeds.size

        var attempts = 0
        while (collected.size < batchSize && attempts < 7) {
            val query = topicSeeds[(freshSeedOffset + attempts * 2) % topicSeeds.size]
            val sp = sortParams[(baseCursor + attempts) % sortParams.size]
            val fetched = fetchInnertubeSearch(query, forceShort = true, params = sp)
                .ifEmpty { fetchPipedSearch(query, forceShort = true) }
                .filter { InnertubeParser.isStrictShortStream(it) }
                .shuffled()

            fetched.forEach { item ->
                if (!previouslySeen.contains(item.id)) {
                    if (collected.size < batchSize && seenInBatch.add(item.id)) {
                        collected.add(item)
                    }
                } else {
                    fallbackPool.add(item)
                }
            }

            // Chain related Shorts from a fresh seed item so infinite scroll keeps discovering new Shorts
            if (collected.size < batchSize && fetched.isNotEmpty()) {
                val seedItem = fetched.random()
                val relatedShorts = runCatching { getRelatedVideos(seedItem.id) }
                    .getOrDefault(emptyList())
                    .filter { InnertubeParser.isStrictShortStream(it) }
                    .shuffled()
                relatedShorts.forEach { rel ->
                    if (!previouslySeen.contains(rel.id) && collected.size < batchSize && seenInBatch.add(rel.id)) {
                        collected.add(rel)
                    }
                }
            }
            attempts++
        }

        // Empty-Tab Auto-Retry (#7): if still fewer than 10 pure Shorts, run #shorts viral query
        if (collected.size < 10) {
            val viral = (fetchInnertubeSearch("#shorts viral trending", forceShort = true) +
                fetchInnertubeSearch("#shorts", forceShort = true))
                .filter { InnertubeParser.isStrictShortStream(it) }
                .shuffled()
            viral.forEach { item ->
                if (collected.size < batchSize && seenInBatch.add(item.id)) {
                    collected.add(item)
                }
            }
        }

        // Only reuse fallback pool if completely out of unseen Shorts
        if (collected.size < batchSize && fallbackPool.isNotEmpty()) {
            trimSeenShortsHalf()
            fallbackPool
                .filter { InnertubeParser.isStrictShortStream(it) }
                .filterNot { sessionDeliveredShortIds.contains(it.id) }
                .shuffled()
                .forEach { item ->
                    if (collected.size < batchSize && seenInBatch.add(item.id)) {
                        collected.add(item)
                    }
                }
        }

        val normalized = collected
            .filter { InnertubeParser.isStrictShortStream(it) }
            .take(batchSize)
            .shuffled()
            .map { it.copy(isShort = true, badgeText = "SHORTS") }

        normalized.forEach { sessionDeliveredShortIds.add(it.id) }

        MediaGroup(
            id = "shorts_batch_${batchIndex}_${System.currentTimeMillis()}",
            title = "Shorts Batch $batchIndex",
            items = normalized,
            continuationToken = nextToken
        )
    }

    override suspend fun search(query: String, filter: SearchFilter): SearchResultBundle = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext SearchResultBundle(query = trimmed, filter = filter)

        coroutineScope {
            val effectiveQuery = if (filter == SearchFilter.SHORTS && !trimmed.contains("short", true)) {
                "$trimmed #shorts"
            } else trimmed

            val mainDeferred = async {
                postInnertube(
                    endpoint = "search",
                    profile = InnertubeClientProfiles.WEB,
                    payload = JSONObject().apply {
                        put("context", buildClientContext(InnertubeClientProfiles.WEB))
                        put("query", effectiveQuery)
                        filter.innertubeParams?.let { put("params", it) }
                    }
                )
            }

            // When searching ALL, also query channels explicitly so creators always show up
            val channelsDeferred = if (filter == SearchFilter.ALL) {
                async {
                    postInnertube(
                        endpoint = "search",
                        profile = InnertubeClientProfiles.WEB,
                        payload = JSONObject().apply {
                            put("context", buildClientContext(InnertubeClientProfiles.WEB))
                            put("query", trimmed)
                            put("params", SearchFilter.CHANNELS.innertubeParams)
                        }
                    )
                }
            } else null

            val shortsDeferred = if (filter == SearchFilter.ALL) {
                async {
                    fetchInnertubeSearch("$trimmed #shorts", forceShort = true)
                }
            } else null

            val root = mainDeferred.await()
            val extraChannelsRoot = channelsDeferred?.await()
            val extraShorts = shortsDeferred?.await().orEmpty()

            if (root != null) {
                val parsedItems = InnertubeParser.parseInnertubeVideos(
                    root,
                    forceShort = filter == SearchFilter.SHORTS
                )
                val parsedChannels = (InnertubeParser.parseInnertubeChannels(root) +
                    (extraChannelsRoot?.let { InnertubeParser.parseInnertubeChannels(it) }.orEmpty()))
                    .distinctBy { it.channelId }

                val videos = if (filter == SearchFilter.SHORTS || filter == SearchFilter.CHANNELS) {
                    emptyList()
                } else {
                    parsedItems.filter { isLongFormCandidate(it) }
                }

                val shorts = when (filter) {
                    SearchFilter.SHORTS -> parsedItems.map { it.copy(isShort = true, badgeText = "SHORTS") }
                    SearchFilter.ALL -> (parsedItems.filter { it.isShort } + extraShorts)
                        .distinctBy { it.id }
                        .take(12)
                    else -> emptyList()
                }

                val channels = when (filter) {
                    SearchFilter.VIDEOS, SearchFilter.SHORTS -> emptyList()
                    else -> parsedChannels
                }

                if (videos.isNotEmpty() || shorts.isNotEmpty() || channels.isNotEmpty()) {
                    return@coroutineScope SearchResultBundle(
                        query = trimmed,
                        filter = filter,
                        channels = channels,
                        videos = videos,
                        shorts = shorts
                    )
                }
            }

            val pipedVideos = fetchPipedSearch(effectiveQuery, forceShort = filter == SearchFilter.SHORTS)
            SearchResultBundle(
                query = trimmed,
                filter = filter,
                videos = if (filter == SearchFilter.SHORTS || filter == SearchFilter.CHANNELS) emptyList()
                else pipedVideos.filter { isLongFormCandidate(it) },
                shorts = if (filter == SearchFilter.VIDEOS || filter == SearchFilter.CHANNELS) emptyList()
                else pipedVideos.filter { it.isShort || filter == SearchFilter.SHORTS }
            )
        }
    }

    override suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        // 1) Official YouTube/Google Suggest endpoint (ultra-fast ~80ms)
        val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=en&gl=$preferredRegion&q=$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", InnertubeClientProfiles.WEB.userAgent)
            .build()

        val fromSuggestApi = runCatching {
            networkStack.okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    parseSearchSuggestJson(resp.body?.string().orEmpty())
                } else emptyList()
            }
        }.getOrDefault(emptyList())

        if (fromSuggestApi.isNotEmpty()) {
            return@withContext fromSuggestApi
        }

        // 2) NewPipeExtractor SuggestionExtractor fallback
        return@withContext runCatching {
            NewPipeDownloaderImpl.ensureInitialized(networkStack.okHttpClient)
            ServiceList.YouTube.suggestionExtractor.suggestionList(trimmed)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(10)
        }.getOrDefault(emptyList())
    }

    override suspend fun getChannelInfo(
        channelId: String,
        fallbackName: String,
        fallbackAvatar: String
    ): ChannelInfo = withContext(Dispatchers.IO) {
        // Resolve real UC... channel ID when clicked item lacked UC id or only provided handle/name
        val resolvedId = resolveRealChannelId(channelId, fallbackName)

        if (resolvedId.startsWith("UC")) {
            val channelFromBrowse = coroutineScope {
                val videosDeferred = async {
                    postInnertube(
                        endpoint = "browse",
                        profile = InnertubeClientProfiles.WEB,
                        payload = JSONObject().apply {
                            put("context", buildClientContext(InnertubeClientProfiles.WEB))
                            put("browseId", resolvedId)
                            put("params", "EgZ2aWRlb3PyBgQKAjoA")
                        }
                    )
                }
                val homeTabDeferred = async {
                    postInnertube(
                        endpoint = "browse",
                        profile = InnertubeClientProfiles.WEB,
                        payload = JSONObject().apply {
                            put("context", buildClientContext(InnertubeClientProfiles.WEB))
                            put("browseId", resolvedId)
                        }
                    )
                }
                val shortsDeferred = async {
                    postInnertube(
                        endpoint = "browse",
                        profile = InnertubeClientProfiles.WEB,
                        payload = JSONObject().apply {
                            put("context", buildClientContext(InnertubeClientProfiles.WEB))
                            put("browseId", resolvedId)
                            put("params", "EgZzaG9ydHPyBgUKA5oBAA%3D%3D")
                        }
                    )
                }

                val videosJson = videosDeferred.await()
                val homeJson = homeTabDeferred.await()
                val shortsJson = shortsDeferred.await()

                val parsedVideosTab = videosJson?.let {
                    InnertubeParser.parseChannelBrowse(it, resolvedId, fallbackName, fallbackAvatar)
                }
                val parsedHomeTab = homeJson?.let {
                    InnertubeParser.parseChannelBrowse(it, resolvedId, fallbackName, fallbackAvatar)
                }

                val bestMeta = when {
                    parsedHomeTab != null && parsedHomeTab.name.isNotBlank() && parsedHomeTab.name != "Channel" -> parsedHomeTab
                    parsedVideosTab != null -> parsedVideosTab
                    else -> null
                }

                val mergedVideos = ((parsedVideosTab?.videos.orEmpty()) + (parsedHomeTab?.videos.orEmpty()))
                    .distinctBy { it.id }

                val parsedShorts = (shortsJson?.let {
                    InnertubeParser.parseInnertubeVideos(it, forceShort = true).map { item ->
                        item.copy(
                            isShort = true,
                            channelId = resolvedId,
                            channelName = bestMeta?.name ?: fallbackName,
                            channelAvatarUrl = bestMeta?.avatarUrl?.ifBlank { fallbackAvatar } ?: fallbackAvatar,
                            badgeText = "SHORTS"
                        )
                    }
                }.orEmpty() + (bestMeta?.shorts.orEmpty())).distinctBy { it.id }

                if (bestMeta != null && (mergedVideos.isNotEmpty() || parsedShorts.isNotEmpty())) {
                    bestMeta.copy(
                        videos = mergedVideos,
                        shorts = parsedShorts
                    )
                } else null
            }

            if (channelFromBrowse != null) {
                return@withContext channelFromBrowse
            }

            fetchPipedChannel(resolvedId, fallbackName, fallbackAvatar)?.let {
                if (it.videos.isNotEmpty() || it.shorts.isNotEmpty()) return@withContext it
            }
            fetchInvidiousChannel(resolvedId, fallbackName, fallbackAvatar)?.let {
                if (it.videos.isNotEmpty() || it.shorts.isNotEmpty()) return@withContext it
            }
        }

        // Final guaranteed fallback: search creator uploads so opened channel always displays videos
        val searchName = fallbackName.ifBlank { channelId }.ifBlank { "Popular Creator" }
        val creatorVideos = fetchInnertubeSearch(searchName)
            .filterNot { it.isShort }
            .map {
                it.copy(
                    channelId = resolvedId.ifBlank { it.channelId },
                    channelName = it.channelName.ifBlank { searchName },
                    channelAvatarUrl = it.channelAvatarUrl.ifBlank { fallbackAvatar }
                )
            }
        val creatorShorts = fetchInnertubeSearch("$searchName #shorts", forceShort = true)
            .take(10)

        ChannelInfo(
            channelId = resolvedId.ifBlank { searchName },
            name = searchName,
            avatarUrl = fallbackAvatar,
            videos = creatorVideos,
            shorts = creatorShorts
        )
    }

    private fun resolveRealChannelId(rawId: String, fallbackName: String): String {
        if (rawId.startsWith("UC") && rawId.length >= 20) return rawId

        if (rawId.startsWith("@")) {
            val resolved = postInnertube(
                endpoint = "navigation/resolve_url",
                profile = InnertubeClientProfiles.WEB,
                payload = JSONObject().apply {
                    put("context", buildClientContext(InnertubeClientProfiles.WEB))
                    put("url", "https://www.youtube.com/$rawId")
                }
            )
            val ucId = resolved?.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            if (!ucId.isNullOrBlank() && ucId.startsWith("UC")) return ucId
        }

        val query = fallbackName.ifBlank { rawId }.trim()
        if (query.isNotBlank()) {
            val searchRoot = postInnertube(
                endpoint = "search",
                profile = InnertubeClientProfiles.WEB,
                payload = JSONObject().apply {
                    put("context", buildClientContext(InnertubeClientProfiles.WEB))
                    put("query", query)
                    put("params", SearchFilter.CHANNELS.innertubeParams)
                }
            )
            val matchedChannel = searchRoot?.let { InnertubeParser.parseInnertubeChannels(it).firstOrNull() }
            if (matchedChannel != null && matchedChannel.channelId.startsWith("UC")) {
                return matchedChannel.channelId
            }
        }
        return rawId
    }

    override suspend fun getMediaItemFormatInfo(
        videoId: String,
        preferFallbackClient: Boolean
    ): MediaItemFormatInfo = withContext(Dispatchers.IO) {
        streamExtractor.extract(
            videoId = videoId,
            preferFallbackClient = preferFallbackClient
        )
    }

    override suspend fun getRelatedVideos(videoId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val nextJson = postInnertube(
            endpoint = "next",
            profile = InnertubeClientProfiles.WEB,
            payload = JSONObject().apply {
                put("context", buildClientContext(InnertubeClientProfiles.WEB))
                put("videoId", videoId)
            }
        )
        if (nextJson != null) {
            val related = InnertubeParser.parseInnertubeVideos(nextJson)
                .filter { it.id != videoId }
            if (related.isNotEmpty()) return@withContext related
        }

        for (baseUrl in PIPED_INSTANCES) {
            try {
                val req = Request.Builder().url("$baseUrl/streams/$videoId").build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string().orEmpty())
                        val relatedArr = root.optJSONArray("relatedStreams")
                        if (relatedArr != null) {
                            return@withContext InnertubeParser.parsePipedVideos(relatedArr)
                                .filter { it.id != videoId }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    override suspend fun getFeaturedChannels(): List<ChannelInfo> = withContext(Dispatchers.IO) {
        val seedIds = listOf(
            "UCXuqSBlHAE6Xw-yeJA0Tunw",
            "UCBJycsmduvYEL83R_U4JriQ",
            "UCsooa4yRKGN_zEE8iknghZA",
            "UC_x5XG1OV2P6uZZ5FSM9Ttw",
            "UC4a-Gbdw7vOaccHmFo40b9g"
        )
        coroutineScope {
            seedIds.map { id ->
                async {
                    runCatching { getChannelInfo(id) }.getOrNull()
                }
            }.mapNotNull { it.await() }.filter { it.name.isNotBlank() }
        }
    }

    private fun buildClientContext(
        profile: InnertubeClientProfile,
        regionCode: String = preferredRegion
    ): JSONObject {
        val client = profile.toClientContextJson(regionCode).apply {
            if (visitorDataToken.isNotBlank()) {
                put("visitorData", visitorDataToken)
            }
        }
        return JSONObject().put("client", client)
    }

    private fun captureVisitorData(root: JSONObject) {
        val token = root.optJSONObject("responseContext")?.optString("visitorData")
        if (!token.isNullOrBlank() && token != visitorDataToken) {
            visitorDataToken = token
            prefs.edit().putString(KEY_VISITOR_DATA, token).apply()
        }
    }

    private fun fetchInnertubeSearch(
        query: String,
        forceShort: Boolean = false,
        params: String? = null
    ): List<MediaItem> {
        val root = postInnertube(
            endpoint = "search",
            profile = InnertubeClientProfiles.WEB,
            payload = JSONObject().apply {
                put("context", buildClientContext(InnertubeClientProfiles.WEB))
                put("query", query)
                if (!params.isNullOrBlank()) {
                    put("params", params)
                }
            }
        ) ?: return emptyList()
        return InnertubeParser.parseInnertubeVideos(root, forceShort = forceShort)
    }

    private fun fetchPipedTrending(region: String): List<MediaItem> {
        for (baseUrl in PIPED_INSTANCES) {
            try {
                val req = Request.Builder().url("$baseUrl/trending?region=$region").build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("[")) {
                            val items = InnertubeParser.parsePipedVideos(JSONArray(body))
                            if (items.isNotEmpty()) return items
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun fetchInvidiousTrending(): List<MediaItem> {
        for (baseUrl in INVIDIOUS_INSTANCES) {
            try {
                val req = Request.Builder().url("$baseUrl/api/v1/trending").build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("[")) {
                            val items = InnertubeParser.parseInvidiousVideos(JSONArray(body))
                            if (items.isNotEmpty()) return items
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun fetchPipedSearch(query: String, forceShort: Boolean = false): List<MediaItem> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        for (baseUrl in PIPED_INSTANCES) {
            try {
                val req = Request.Builder().url("$baseUrl/search?q=$encoded&filter=videos").build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string().orEmpty())
                        val arr = root.optJSONArray("items") ?: continue
                        val parsed = InnertubeParser.parsePipedVideos(arr, forceShort)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun fetchPipedChannel(
        channelId: String,
        fallbackName: String,
        fallbackAvatar: String
    ): ChannelInfo? {
        for (baseUrl in PIPED_INSTANCES) {
            try {
                val req = Request.Builder().url("$baseUrl/channel/$channelId").build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string().orEmpty())
                        val related = root.optJSONArray("relatedStreams")
                        val videos = if (related != null) {
                            InnertubeParser.parsePipedVideos(related).map {
                                it.copy(
                                    channelId = channelId,
                                    channelName = root.optString("name", fallbackName),
                                    channelAvatarUrl = InnertubeParser.normalizeUrl(root.optString("avatarUrl"))
                                        .ifBlank { fallbackAvatar }
                                )
                            }
                        } else emptyList()
                        val subs = root.optLong("subscriberCount", -1L)
                        return ChannelInfo(
                            channelId = channelId,
                            name = root.optString("name", fallbackName.ifBlank { "Channel" }),
                            avatarUrl = InnertubeParser.normalizeUrl(root.optString("avatarUrl"))
                                .ifBlank { fallbackAvatar },
                            bannerUrl = InnertubeParser.normalizeUrl(root.optString("bannerUrl")),
                            subscriberCountText = if (subs >= 0) "${subs / 1000}K subscribers" else "",
                            description = root.optString("description"),
                            isVerified = root.optBoolean("verified", false),
                            videos = videos.filterNot { it.isShort },
                            shorts = videos.filter { it.isShort }
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun fetchInvidiousChannel(
        channelId: String,
        fallbackName: String,
        fallbackAvatar: String
    ): ChannelInfo? {
        for (baseUrl in INVIDIOUS_INSTANCES) {
            try {
                val req = Request.Builder().url("$baseUrl/api/v1/channels/$channelId").build()
                networkStack.okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string().orEmpty())
                        val latest = root.optJSONArray("latestVideos")
                        val videos = if (latest != null) {
                            InnertubeParser.parseInvidiousVideos(latest).map {
                                it.copy(
                                    channelId = channelId,
                                    channelName = root.optString("author", fallbackName),
                                    channelAvatarUrl = fallbackAvatar
                                )
                            }
                        } else emptyList()
                        if (videos.isNotEmpty()) {
                            return ChannelInfo(
                                channelId = channelId,
                                name = root.optString("author", fallbackName.ifBlank { "Channel" }),
                                avatarUrl = fallbackAvatar,
                                description = root.optString("description"),
                                videos = videos.filterNot { it.isShort },
                                shorts = videos.filter { it.isShort }
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun postInnertube(
        endpoint: String,
        profile: InnertubeClientProfile,
        payload: JSONObject
    ): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("Content-Type", "application/json")
                .header("User-Agent", profile.userAgent)
                .header("X-YouTube-Client-Name", profile.clientNameHeader)
                .header("X-YouTube-Client-Version", profile.clientVersion)
                .apply {
                    if (profile.isWebClient) {
                        header("Origin", "https://www.youtube.com")
                        header("Referer", "https://www.youtube.com/")
                    }
                    if (visitorDataToken.isNotBlank()) {
                        header("X-Goog-Visitor-Id", visitorDataToken)
                    }
                }
                .build()

            networkStack.okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Innertube $endpoint [${profile.id}] HTTP ${resp.code}")
                    return null
                }
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) return null
                JSONObject(text).also { captureVisitorData(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Innertube $endpoint [${profile.id}] error: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "YouTubeMediaService"
        private const val KEY_VISITOR_DATA = "visitor_data_token"
        private const val KEY_SEEN_SHORTS = "seen_short_ids"
        private const val KEY_SEEN_HOME = "seen_home_ids"
        private const val KEY_HOME_CURSOR = "home_seed_cursor"
        private const val KEY_SHORTS_CURSOR = "shorts_seed_cursor"
        private const val KEY_SHORTS_AFFINITY = "shorts_affinity_scores"
        private const val MAX_SEEN_SHORTS = 600
        private const val MAX_SEEN_HOME = 160
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

        fun parseSearchSuggestJson(rawBody: String): List<String> {
            if (rawBody.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(rawBody)
                val suggestions = arr.optJSONArray(1) ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until suggestions.length()) {
                        val value = suggestions.optString(i).trim()
                        if (value.isNotBlank()) add(value)
                    }
                }.distinct().take(10)
            }.getOrDefault(emptyList())
        }
    }
}
