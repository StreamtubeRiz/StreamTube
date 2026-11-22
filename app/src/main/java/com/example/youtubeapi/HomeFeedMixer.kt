package com.example.youtubeapi

import com.example.mediaserviceinterfaces.MediaItem
import java.util.Locale

/**
 * Interleaves Homepage "All" buckets (Recommended, Trending, Music/Entertainment,
 * Tech/Sports/News, and capped Personalized Taste) while blocking duplicate topics,
 * repetitive search clusters, and channel domination.
 */
object HomeFeedMixer {

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "from", "that", "this", "what", "when", "where",
        "your", "about", "into", "over", "after", "before", "best", "latest", "newest",
        "official", "video", "videos", "full", "episode", "episodes", "part", "today",
        "trending", "viral", "live", "stream", "watch", "show", "highlights", "highlight",
        "match", "review", "unboxing", "song", "songs", "music", "news", "breaking",
        "india", "pakistan", "pakistani", "indian", "hindi", "urdu", "english",
        "2024", "2025", "2026", "hd", "4k", "1080p", "shorts", "short"
    )

    fun extractTopicTokens(title: String): Set<String> {
        if (title.isBlank()) return emptySet()
        return title.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .asSequence()
            .filter { it.length >= 4 && !STOP_WORDS.contains(it) }
            .take(8)
            .toSet()
    }

    private fun isTopicTooSimilar(
        tokens: Set<String>,
        selectedTokenSets: List<Set<String>>
    ): Boolean {
        if (tokens.isEmpty()) return false
        return selectedTokenSets.any { existing ->
            val overlap = tokens.count { existing.contains(it) }
            overlap >= 2 || (tokens.size == 1 && existing.size == 1 && overlap == 1)
        }
    }

    private fun channelKeyFor(item: MediaItem): String =
        item.channelId.ifBlank { item.channelName.trim().lowercase(Locale.US) }

    fun mixAndDiversify(
        buckets: List<List<MediaItem>>,
        targetSize: Int = 20,
        sessionShownIds: Set<String> = emptySet(),
        persistentShownIds: Set<String> = emptySet()
    ): List<MediaItem> {
        if (buckets.isEmpty() || targetSize <= 0) return emptyList()

        val result = ArrayList<MediaItem>(targetSize)
        val selectedIds = HashSet<String>(targetSize * 2)
        val channelCounts = HashMap<String, Int>()
        val selectedTokenSets = ArrayList<Set<String>>(targetSize)

        val cursors = IntArray(buckets.size)
        val maxRounds = buckets.maxOfOrNull { it.size } ?: 0

        fun tryAdd(
            item: MediaItem,
            maxPerChannel: Int,
            skipPersistentSeen: Boolean,
            enforceTopicDiversity: Boolean
        ): Boolean {
            if (result.size >= targetSize) return false
            if (selectedIds.contains(item.id) || sessionShownIds.contains(item.id)) return false
            if (skipPersistentSeen && persistentShownIds.contains(item.id)) return false

            val chKey = channelKeyFor(item)
            if (chKey.isNotBlank() && (channelCounts[chKey] ?: 0) >= maxPerChannel) {
                return false
            }

            val tokens = extractTopicTokens(item.title)
            if (enforceTopicDiversity && isTopicTooSimilar(tokens, selectedTokenSets)) {
                return false
            }

            selectedIds.add(item.id)
            if (chKey.isNotBlank()) {
                channelCounts[chKey] = (channelCounts[chKey] ?: 0) + 1
            }
            if (tokens.isNotEmpty()) {
                selectedTokenSets.add(tokens)
            }
            result.add(item)
            return true
        }

        // Pass 1: Strict round-robin across all buckets (1 per channel, unseen, non-similar topic)
        while (result.size < targetSize) {
            var addedInRound = false
            for (bIdx in buckets.indices) {
                val bucket = buckets[bIdx]
                while (cursors[bIdx] < bucket.size) {
                    val candidate = bucket[cursors[bIdx]++]
                    if (tryAdd(candidate, maxPerChannel = 1, skipPersistentSeen = true, enforceTopicDiversity = true)) {
                        addedInRound = true
                        break
                    }
                }
                if (result.size >= targetSize) break
            }
            if (!addedInRound || cursors.allIndexed { idx, c -> c >= buckets[idx].size }) break
        }

        if (result.size >= targetSize) return result

        // Pass 2: Allow up to 2 per channel & revisit persistent-seen if needed, keep anti-topic-clump
        val flatCandidates = buildList {
            for (round in 0 until maxRounds) {
                for (bucket in buckets) {
                    bucket.getOrNull(round)?.let(::add)
                }
            }
        }
        for (candidate in flatCandidates) {
            if (result.size >= targetSize) break
            tryAdd(candidate, maxPerChannel = 2, skipPersistentSeen = false, enforceTopicDiversity = true)
        }

        if (result.size >= targetSize) return result

        // Pass 3: Soft fallback fill ignoring topic/channel caps, still blocking session duplicates
        for (candidate in flatCandidates) {
            if (result.size >= targetSize) break
            tryAdd(candidate, maxPerChannel = Int.MAX_VALUE, skipPersistentSeen = false, enforceTopicDiversity = false)
        }

        return result
    }

    private inline fun IntArray.allIndexed(predicate: (Int, Int) -> Boolean): Boolean {
        for (i in indices) {
            if (!predicate(i, this[i])) return false
        }
        return true
    }
}
