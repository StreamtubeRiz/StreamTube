package com.example

import android.app.Application
import android.util.Log
import com.example.data.StreamRepository
import com.example.data.local.AppDatabase
import com.example.downloads.MediaDownloadManager
import com.example.mediaserviceinterfaces.MediaService
import com.example.player.StreamPlayerEngine
import com.example.update.AppUpdateManager
import com.example.youtubeapi.NetworkStackProvider
import com.example.youtubeapi.NewPipeDownloaderImpl
import com.example.youtubeapi.YouTubeMediaService
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StreamTubeApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isYoutubeDlReady: Boolean = false
        private set

    val youtubeDlMutex = Mutex()

    val networkStack: NetworkStackProvider by lazy { NetworkStackProvider(this) }
    val mediaService: MediaService by lazy { YouTubeMediaService(this, networkStack) }
    val playerEngine: StreamPlayerEngine by lazy { StreamPlayerEngine(this, networkStack) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val streamRepository: StreamRepository by lazy {
        StreamRepository(
            primaryService = mediaService,
            watchHistoryDao = database.watchHistoryDao(),
            likedVideoDao = database.likedVideoDao(),
            savedVideoDao = database.savedVideoDao(),
            onPreWarmHead = { formatInfo, isShort ->
                playerEngine.preWarmStreamHead(formatInfo, isShort)
            }
        )
    }

    val downloadManager: MediaDownloadManager by lazy {
        MediaDownloadManager(this, database.downloadDao())
    }

    val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManager(this, networkStack.okHttpClient)
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching {
                NewPipeDownloaderImpl.ensureInitialized(networkStack.okHttpClient)
            }
            // Delay yt-dlp self-update slightly so immediate playback/downloads are not contended
            delay(5_000L)
            if (ensureYoutubeDlInitialized()) {
                runCatching {
                    youtubeDlMutex.withLock {
                        YoutubeDL.getInstance().updateYoutubeDL(
                            applicationContext,
                            YoutubeDL.UpdateChannel.STABLE
                        )
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Silent yt-dlp update skipped: ${err.message}")
                }
            }
        }
    }

    @Synchronized
    fun ensureYoutubeDlInitialized(): Boolean {
        if (isYoutubeDlReady) return true
        return try {
            YoutubeDL.getInstance().init(applicationContext)
            isYoutubeDlReady = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "YoutubeDL init unavailable: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "StreamTubeApp"
    }
}
