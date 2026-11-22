package com.example.ads

import java.util.Locale

/**
 * Pure Kotlin helper rules for StreamTube's Unity Ads placements:
 * 1. App Launch Interstitial (shown once per cold launch)
 * 2. Shorts Interstitial after every 10th Short viewed (10, 20, 30, ...)
 * 3. Rewarded Ad gate for HD downloads (720p, 1080p, 2K / 1440p)
 * Main video player remains 100% ad-free.
 */
object AdPlacementHelper {

    const val SHORTS_INTERSTITIAL_INTERVAL = 10

    /**
     * Returns true when downloading [qualityLabel] requires watching a Rewarded Ad first.
     * - 720p, 1080p, 1440p / 2K -> true (Watch Ad to Download)
     * - 240p, 360p, 480p, Audio M4A -> false (Free 1-Click instant download)
     */
    fun requiresRewardedAdForDownload(qualityLabel: String): Boolean {
        val normalized = qualityLabel.trim().uppercase(Locale.US)
        if (normalized.isEmpty()) return false
        if (normalized.contains("AUDIO") || normalized.contains("M4A")) return false
        return normalized.contains("720") ||
            normalized.contains("1080") ||
            normalized.contains("1440") ||
            normalized.contains("2K")
    }

    /**
     * Evaluates whether switching from the current viewed count should trigger the
     * every-10th-Short interstitial ad.
     *
     * Trigger happens right after the user finishes/moves past the 10th, 20th, 30th... Short
     * (i.e. on the transition after reaching `milestone` distinct viewed Shorts).
     *
     * @param completedShortsCount Total distinct Shorts viewed before moving to the next Short.
     * @param alreadyTriggeredMilestones Milestones (10, 20, 30...) already triggered this session.
     * @return Milestone number (10, 20, 30, ...) to trigger now, or null if no ad yet.
     */
    fun milestoneForShortsInterstitial(
        completedShortsCount: Int,
        alreadyTriggeredMilestones: Set<Int>
    ): Int? {
        if (completedShortsCount < SHORTS_INTERSTITIAL_INTERVAL) return null
        val latestMilestone =
            (completedShortsCount / SHORTS_INTERSTITIAL_INTERVAL) * SHORTS_INTERSTITIAL_INTERVAL
        return if (latestMilestone >= SHORTS_INTERSTITIAL_INTERVAL &&
            !alreadyTriggeredMilestones.contains(latestMilestone)
        ) {
            latestMilestone
        } else {
            null
        }
    }
}
