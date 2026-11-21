package com.example.youtubeapi

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException

/**
 * OkHttp-backed Downloader for NewPipeExtractor (v0.26.5) JS signature/HTML parsing fallback.
 */
class NewPipeDownloaderImpl(
    private val okHttpClient: OkHttpClient
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val dataToSend = request.dataToSend()

        val requestBody = when {
            method.equals("POST", ignoreCase = true) ||
                method.equals("PUT", ignoreCase = true) ||
                method.equals("PATCH", ignoreCase = true) -> {
                (dataToSend ?: ByteArray(0)).toRequestBody(null)
            }
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(method, requestBody)

        var hasUserAgent = false
        for ((headerName, headerValues) in request.headers()) {
            if (headerName.equals("User-Agent", ignoreCase = true)) {
                hasUserAgent = true
            }
            for (value in headerValues) {
                builder.addHeader(headerName, value)
            }
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val bodyString = resp.body?.string() ?: ""
            val latestUrl = resp.request.url.toString()
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                bodyString,
                latestUrl
            )
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        @Volatile
        private var initialized = false

        fun ensureInitialized(okHttpClient: OkHttpClient) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    NewPipe.init(
                        NewPipeDownloaderImpl(okHttpClient),
                        Localization.DEFAULT,
                        ContentCountry.DEFAULT
                    )
                    initialized = true
                }
            }
        }
    }
}
