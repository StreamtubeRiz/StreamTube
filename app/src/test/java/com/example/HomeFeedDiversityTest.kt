package com.example

import com.example.mediaserviceinterfaces.FeedCategory
import com.example.mediaserviceinterfaces.MediaItem
import com.example.youtubeapi.HomeFeedMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedDiversityTest {

    private fun item(id: String, title: String, channelId: String) = MediaItem(
        id = id,
        title = title,
        channelId = channelId,
        channelName = "Channel $channelId"
    )

    @Test
    fun `homepage default category is All`() {
        assertEquals(FeedCategory.HOME, FeedCategory.entries.first())
        assertEquals("All", FeedCategory.HOME.label)
    }

    @Test
    fun `mixAndDiversify interleaves buckets and prevents repetitive search or channel spam`() {
        val recommended = listOf(
            item("r1", "Smartphone Camera Comparison Test", "ch_rec_1"),
            item("r2", "Space Station Orbital Tour", "ch_rec_2")
        )
        val trending = listOf(
            item("t1", "Champions League Final Drama Highlights", "ch_tr_1"),
            item("t2", "Mountain Drone Adventure Cinema", "ch_tr_2")
        )
        // Simulate a single repetitive search returning multiple clones of the exact same topic/channel
        val repetitiveTopicBucket = listOf(
            item("s1", "Samsung Galaxy Ultra Camera Battle", "ch_spam"),
            item("s2", "Samsung Galaxy Ultra Camera Test", "ch_spam"),
            item("s3", "Samsung Galaxy Ultra Unboxing Comparison", "ch_spam"),
            item("s4", "Street Food Karachi Biryani", "ch_food")
        )

        val mixed = HomeFeedMixer.mixAndDiversify(
            buckets = listOf(recommended, trending, repetitiveTopicBucket),
            targetSize = 6,
            sessionShownIds = setOf("already_seen"),
            persistentShownIds = emptySet()
        )

        // First 3 slots come round-robin across buckets: Recommended, Trending, Diverse Bucket
        assertEquals("r1", mixed[0].id)
        assertEquals("t1", mixed[1].id)
        assertEquals("s1", mixed[2].id)

        // Cloned Samsung Galaxy / ch_spam items s2 & s3 must NOT appear before distinct topics s4 / r2 / t2
        val ids = mixed.map { it.id }
        assertTrue(ids.contains("r2"))
        assertTrue(ids.contains("t2"))
        assertTrue(ids.contains("s4"))
        assertFalse(ids.contains("s2"))
        assertFalse(ids.contains("s3"))
    }

    @Test
    fun `mixAndDiversify never returns sessionShownIds`() {
        val bucket = listOf(
            item("v1", "Cooking Pasta Guide", "ch1"),
            item("v2", "Cricket World Cup Finale", "ch2")
        )
        val mixed = HomeFeedMixer.mixAndDiversify(
            buckets = listOf(bucket),
            targetSize = 5,
            sessionShownIds = setOf("v1"),
            persistentShownIds = setOf("v2")
        )
        assertEquals(listOf("v2"), mixed.map { it.id })
    }
}
