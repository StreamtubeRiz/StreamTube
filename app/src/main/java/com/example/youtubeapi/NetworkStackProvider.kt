package com.example.youtubeapi

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import com.google.android.gms.net.CronetProviderInstaller
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP/2 + HTTP/3 QUIC network stack for fast API fetching, streaming, and downloads.
 */
@OptIn(UnstableApi::class)
class NetworkStackProvider(context: Context) {

    private val appContext = context.applicationContext
    private val networkExecutor = Executors.newCachedThreadPool()

    @Volatile
    var isCronetActive: Boolean = false
        private set

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val cronetEngine: CronetEngine? by lazy {
        try {
            CronetProviderInstaller.installProvider(appContext)
            val engine = CronetUtil.buildCronetEngine(
                appContext,
                USER_AGENT_MOBILE,
                /* preferGooglePlayServices= */ true
            )
            isCronetActive = engine != null
            engine
        } catch (t: Throwable) {
            Log.w(TAG, "Cronet unavailable on device, using DefaultHttpDataSource", t)
            isCronetActive = false
            null
        }
    }

    val defaultHttpDataSourceFactory: DefaultHttpDataSource.Factory by lazy {
        createDefaultHttpFactory(USER_AGENT_MOBILE)
    }

    val mediaDataSourceFactory: DataSource.Factory by lazy {
        createMediaDataSourceFactory(USER_AGENT_MOBILE)
    }

    fun createMediaDataSourceFactory(customUserAgent: String?): DataSource.Factory {
        val ua = customUserAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT_MOBILE
        val httpFallback = createDefaultHttpFactory(ua)
        val engine = cronetEngine
        return if (engine != null) {
            CronetDataSource.Factory(engine, networkExecutor)
                .setConnectionTimeoutMs(8_000)
                .setReadTimeoutMs(12_000)
                .setUserAgent(ua)
                .setDefaultRequestProperties(mapOf("User-Agent" to ua))
                .setHandleSetCookieRequests(true)
                .setFallbackFactory(httpFallback)
        } else {
            httpFallback
        }
    }

    private fun createDefaultHttpFactory(ua: String): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(mapOf("User-Agent" to ua))
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)
    }

    companion object {
        private const val TAG = "NetworkStackProvider"
        const val USER_AGENT_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
    }
}
