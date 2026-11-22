package com.example

import com.example.ads.AdPlacementHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPlacementHelperTest {

    @Test
    fun `720p 1080p and 2K downloads require rewarded ad while 240p 360p and audio are free`() {
        assertTrue(AdPlacementHelper.requiresRewardedAdForDownload("720p"))
        assertTrue(AdPlacementHelper.requiresRewardedAdForDownload("1080p"))
        assertTrue(AdPlacementHelper.requiresRewardedAdForDownload("2K (1440p)"))
        assertTrue(AdPlacementHelper.requiresRewardedAdForDownload("1440p"))

        assertFalse(AdPlacementHelper.requiresRewardedAdForDownload("240p"))
        assertFalse(AdPlacementHelper.requiresRewardedAdForDownload("360p"))
        assertFalse(AdPlacementHelper.requiresRewardedAdForDownload("480p"))
        assertFalse(AdPlacementHelper.requiresRewardedAdForDownload("Audio M4A"))
        assertFalse(AdPlacementHelper.requiresRewardedAdForDownload(""))
    }

    @Test
    fun `shorts interstitial triggers after every 10th distinct short viewed`() {
        val triggered = mutableSetOf<Int>()

        for (viewed in 1..9) {
            assertNull(AdPlacementHelper.milestoneForShortsInterstitial(viewed, triggered))
        }

        // Right after 10th Short viewed -> milestone 10
        assertEquals(10, AdPlacementHelper.milestoneForShortsInterstitial(10, triggered))
        triggered.add(10)

        // Same milestone does not repeat until 20
        assertNull(AdPlacementHelper.milestoneForShortsInterstitial(10, triggered))
        for (viewed in 11..19) {
            assertNull(AdPlacementHelper.milestoneForShortsInterstitial(viewed, triggered))
        }

        // After 20th Short viewed -> milestone 20
        assertEquals(20, AdPlacementHelper.milestoneForShortsInterstitial(20, triggered))
        triggered.add(20)

        // After 30th Short viewed -> milestone 30
        assertEquals(30, AdPlacementHelper.milestoneForShortsInterstitial(30, triggered))
    }
}
