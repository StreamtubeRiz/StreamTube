package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Centralized Unity Ads SDK manager (Game ID: 6230045).
 *
 * Placements:
 * - Launch Interstitial: [PLACEMENT_INTERSTITIAL] once per cold app start
 * - Shorts Interstitial: [PLACEMENT_INTERSTITIAL] after every 10th Short
 * - HD Download Gate: [PLACEMENT_REWARDED] for 720p, 1080p, and 2K downloads
 * - Banner constant retained per dashboard config: [PLACEMENT_BANNER]
 */
class UnityAdsManager(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val initStarted = AtomicBoolean(false)
    private val launchAdConsumed = AtomicBoolean(false)

    @Volatile
    private var isInterstitialReady = false

    @Volatile
    private var isInterstitialLoading = false

    @Volatile
    private var isRewardedReady = false

    @Volatile
    private var isRewardedLoading = false

    @Volatile
    private var isAdShowing = false

    private var interstitialRetryAttempt = 0
    private var rewardedRetryAttempt = 0

    @Volatile
    private var interstitialWaiter: CompletableDeferred<Boolean>? = null

    @Volatile
    private var rewardedWaiter: CompletableDeferred<Boolean>? = null

    fun initialize() {
        if (UnityAds.isInitialized) {
            preloadAllPlacements()
            return
        }
        if (!initStarted.compareAndSet(false, true)) return

        mainHandler.post {
            try {
                UnityAds.initialize(
                    appContext.applicationContext,
                    UNITY_GAME_ID,
                    TEST_MODE,
                    object : IUnityAdsInitializationListener {
                        override fun onInitializationComplete() {
                            Log.d(TAG, "Unity Ads initialized (gameId=$UNITY_GAME_ID)")
                            preloadAllPlacements()
                        }

                        override fun onInitializationFailed(
                            error: UnityAds.UnityAdsInitializationError?,
                            message: String?
                        ) {
                            Log.w(TAG, "Unity Ads init failed ($error): $message")
                            initStarted.set(false)
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Unity Ads initialize error: ${t.message}")
                initStarted.set(false)
            }
        }
    }

    private fun preloadAllPlacements() {
        loadInterstitial()
        loadRewarded()
    }

    fun loadInterstitial() {
        if (!UnityAds.isInitialized) {
            initialize()
            return
        }
        if (isInterstitialReady || isInterstitialLoading) return
        isInterstitialLoading = true

        mainHandler.post {
            try {
                UnityAds.load(
                    PLACEMENT_INTERSTITIAL,
                    object : IUnityAdsLoadListener {
                        override fun onUnityAdsAdLoaded(placementId: String?) {
                            isInterstitialLoading = false
                            isInterstitialReady = true
                            interstitialRetryAttempt = 0
                            interstitialWaiter?.complete(true)
                            interstitialWaiter = null
                            Log.d(TAG, "Loaded placement: $placementId")
                        }

                        override fun onUnityAdsFailedToLoad(
                            placementId: String?,
                            error: UnityAds.UnityAdsLoadError?,
                            message: String?
                        ) {
                            isInterstitialLoading = false
                            isInterstitialReady = false
                            interstitialWaiter?.complete(false)
                            interstitialWaiter = null
                            Log.w(TAG, "Interstitial failed to load ($error): $message")
                            scheduleInterstitialRetry()
                        }
                    }
                )
            } catch (t: Throwable) {
                isInterstitialLoading = false
                interstitialWaiter?.complete(false)
                interstitialWaiter = null
                Log.w(TAG, "Interstitial load exception: ${t.message}")
            }
        }
    }

    fun loadRewarded() {
        if (!UnityAds.isInitialized) {
            initialize()
            return
        }
        if (isRewardedReady || isRewardedLoading) return
        isRewardedLoading = true

        mainHandler.post {
            try {
                UnityAds.load(
                    PLACEMENT_REWARDED,
                    object : IUnityAdsLoadListener {
                        override fun onUnityAdsAdLoaded(placementId: String?) {
                            isRewardedLoading = false
                            isRewardedReady = true
                            rewardedRetryAttempt = 0
                            rewardedWaiter?.complete(true)
                            rewardedWaiter = null
                            Log.d(TAG, "Loaded placement: $placementId")
                        }

                        override fun onUnityAdsFailedToLoad(
                            placementId: String?,
                            error: UnityAds.UnityAdsLoadError?,
                            message: String?
                        ) {
                            isRewardedLoading = false
                            isRewardedReady = false
                            rewardedWaiter?.complete(false)
                            rewardedWaiter = null
                            Log.w(TAG, "Rewarded failed to load ($error): $message")
                            scheduleRewardedRetry()
                        }
                    }
                )
            } catch (t: Throwable) {
                isRewardedLoading = false
                rewardedWaiter?.complete(false)
                rewardedWaiter = null
                Log.w(TAG, "Rewarded load exception: ${t.message}")
            }
        }
    }

    private fun scheduleInterstitialRetry() {
        if (interstitialRetryAttempt >= MAX_LOAD_RETRIES) return
        val delayMs = 4_000L * (1 shl interstitialRetryAttempt)
        interstitialRetryAttempt++
        scope.launch {
            delay(delayMs)
            if (!isInterstitialReady && !isInterstitialLoading) {
                loadInterstitial()
            }
        }
    }

    private fun scheduleRewardedRetry() {
        if (rewardedRetryAttempt >= MAX_LOAD_RETRIES) return
        val delayMs = 4_000L * (1 shl rewardedRetryAttempt)
        rewardedRetryAttempt++
        scope.launch {
            delay(delayMs)
            if (!isRewardedReady && !isRewardedLoading) {
                loadRewarded()
            }
        }
    }

    /**
     * Waits briefly for [PLACEMENT_INTERSTITIAL] to finish preloading right after splash,
     * then shows it at most once per cold app launch if [canStillShow] returns true.
     */
    suspend fun awaitAndShowLaunchInterstitial(
        activity: Activity?,
        maxWaitMs: Long = 5_500L,
        canStillShow: () -> Boolean = { true }
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        if (launchAdConsumed.get()) return

        if (!isInterstitialReady) {
            val waiter = CompletableDeferred<Boolean>().also { interstitialWaiter = it }
            loadInterstitial()
            withTimeoutOrNull(maxWaitMs) { waiter.await() }
        }

        if (isInterstitialReady &&
            canStillShow() &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            launchAdConsumed.compareAndSet(false, true)
        ) {
            showInterstitialInternal(activity) { }
        } else {
            // Mark consumed if launch window passed cleanly so it doesn't ambush playback later
            launchAdConsumed.set(true)
        }
    }

    /**
     * Shows [PLACEMENT_INTERSTITIAL] during Shorts browsing (e.g. after every 10th Short).
     * Always invokes [onFinished] on Main thread so playback resumes cleanly even if ad fails.
     */
    fun showShortsInterstitial(
        activity: Activity?,
        onFinished: () -> Unit
    ) {
        val finishOnce = singleCallback(onFinished)
        if (activity == null || activity.isFinishing || activity.isDestroyed || isAdShowing) {
            finishOnce()
            return
        }
        if (!isInterstitialReady) {
            loadInterstitial()
            finishOnce()
            return
        }
        showInterstitialInternal(activity, finishOnce)
    }

    private fun showInterstitialInternal(
        activity: Activity,
        onFinished: () -> Unit
    ) {
        val finishOnce = singleCallback(onFinished)
        isInterstitialReady = false
        isAdShowing = true
        mainHandler.post {
            try {
                UnityAds.show(
                    activity,
                    PLACEMENT_INTERSTITIAL,
                    UnityAdsShowOptions(),
                    object : IUnityAdsShowListener {
                        override fun onUnityAdsShowFailure(
                            placementId: String?,
                            error: UnityAds.UnityAdsShowError?,
                            message: String?
                        ) {
                            Log.w(TAG, "Interstitial show failed ($error): $message")
                            isAdShowing = false
                            loadInterstitial()
                            finishOnce()
                        }

                        override fun onUnityAdsShowStart(placementId: String?) = Unit

                        override fun onUnityAdsShowClick(placementId: String?) = Unit

                        override fun onUnityAdsShowComplete(
                            placementId: String?,
                            state: UnityAds.UnityAdsShowCompletionState?
                        ) {
                            isAdShowing = false
                            loadInterstitial()
                            finishOnce()
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Interstitial show exception: ${t.message}")
                isAdShowing = false
                loadInterstitial()
                finishOnce()
            }
        }
    }

    /**
     * Gates HD downloads (720p, 1080p, 2K) behind [PLACEMENT_REWARDED].
     * Grants [onRewardEarned] ONLY when [UnityAds.UnityAdsShowCompletionState.COMPLETED].
     */
    fun showRewardedForDownload(
        activity: Activity?,
        qualityLabel: String,
        onRewardEarned: () -> Unit,
        onBlocked: (String) -> Unit
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            mainHandler.post { onBlocked("Unable to open ad right now. Try again.") }
            return
        }
        if (isAdShowing) {
            mainHandler.post { onBlocked("An ad is already open.") }
            return
        }

        scope.launch {
            if (!isRewardedReady) {
                val waiter = CompletableDeferred<Boolean>().also { rewardedWaiter = it }
                rewardedRetryAttempt = 0
                loadRewarded()
                withTimeoutOrNull(5_000L) { waiter.await() }
            }

            if (!isRewardedReady || activity.isFinishing || activity.isDestroyed) {
                onBlocked("Rewarded ad is loading. Please try $qualityLabel again in a moment.")
                return@launch
            }

            val settled = AtomicBoolean(false)
            isRewardedReady = false
            isAdShowing = true

            try {
                UnityAds.show(
                    activity,
                    PLACEMENT_REWARDED,
                    UnityAdsShowOptions(),
                    object : IUnityAdsShowListener {
                        override fun onUnityAdsShowFailure(
                            placementId: String?,
                            error: UnityAds.UnityAdsShowError?,
                            message: String?
                        ) {
                            Log.w(TAG, "Rewarded show failed ($error): $message")
                            isAdShowing = false
                            loadRewarded()
                            if (settled.compareAndSet(false, true)) {
                                mainHandler.post {
                                    onBlocked("Ad couldn't load ($qualityLabel). Please try again.")
                                }
                            }
                        }

                        override fun onUnityAdsShowStart(placementId: String?) = Unit

                        override fun onUnityAdsShowClick(placementId: String?) = Unit

                        override fun onUnityAdsShowComplete(
                            placementId: String?,
                            state: UnityAds.UnityAdsShowCompletionState?
                        ) {
                            isAdShowing = false
                            loadRewarded()
                            if (settled.compareAndSet(false, true)) {
                                mainHandler.post {
                                    if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                                        onRewardEarned()
                                    } else {
                                        onBlocked("Watch the full ad to unlock $qualityLabel download.")
                                    }
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Rewarded show exception: ${t.message}")
                isAdShowing = false
                loadRewarded()
                if (settled.compareAndSet(false, true)) {
                    onBlocked("Ad error. Please try again.")
                }
            }
        }
    }

    private fun singleCallback(block: () -> Unit): () -> Unit {
        val called = AtomicBoolean(false)
        return {
            if (called.compareAndSet(false, true)) {
                mainHandler.post(block)
            }
        }
    }

    companion object {
        private const val TAG = "UnityAdsManager"
        const val UNITY_GAME_ID = "6230045"
        const val PLACEMENT_BANNER = "Banner_Android"
        const val PLACEMENT_INTERSTITIAL = "Interstitial_Android"
        const val PLACEMENT_REWARDED = "Rewarded_Android"
        const val TEST_MODE = false
        private const val MAX_LOAD_RETRIES = 3
    }
}
