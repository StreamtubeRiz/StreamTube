package com.example.youtubeapi

import org.json.JSONObject

/**
 * Client profiles rotated to obtain unencrypted direct MP4 & adaptive stream URLs.
 */
data class InnertubeClientProfile(
    val id: String,
    val clientName: String,
    val clientVersion: String,
    val clientNameHeader: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    val isWebClient: Boolean = false
) {
    fun toClientContextJson(glCountry: String = "US"): JSONObject = JSONObject().apply {
        put("clientName", clientName)
        put("clientVersion", clientVersion)
        put("hl", "en")
        put("gl", glCountry.ifBlank { "US" })
        osName?.let { put("osName", it) }
        osVersion?.let { put("osVersion", it) }
        deviceMake?.let { put("deviceMake", it) }
        deviceModel?.let { put("deviceModel", it) }
        androidSdkVersion?.let { put("androidSdkVersion", it) }
    }
}

object InnertubeClientProfiles {
    val WEB = InnertubeClientProfile(
        id = "WEB",
        clientName = "WEB",
        clientVersion = "2.20250312.01.00",
        clientNameHeader = "1",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        isWebClient = true
    )

    val ANDROID_VR = InnertubeClientProfile(
        id = "ANDROID_VR",
        clientName = "ANDROID_VR",
        clientVersion = "1.62.27",
        clientNameHeader = "28",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        osName = "Android",
        osVersion = "12L",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        androidSdkVersion = 32
    )

    val TV_EMBED = InnertubeClientProfile(
        id = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        clientNameHeader = "85",
        userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/5.0 Chrome/94.0.4606.31 TV Safari/537.36",
        isWebClient = true
    )

    val IOS = InnertubeClientProfile(
        id = "IOS",
        clientName = "IOS",
        clientVersion = "20.10.4",
        clientNameHeader = "5",
        userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        osName = "iPhone",
        osVersion = "18.3.2.22D82",
        deviceMake = "Apple",
        deviceModel = "iPhone16,2"
    )

    val ANDROID_TESTSUITE = InnertubeClientProfile(
        id = "ANDROID_TESTSUITE",
        clientName = "ANDROID_TESTSUITE",
        clientVersion = "1.9",
        clientNameHeader = "30",
        userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip",
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34
    )

    val WEB_EMBEDDED_PLAYER = InnertubeClientProfile(
        id = "WEB_EMBEDDED_PLAYER",
        clientName = "WEB_EMBEDDED_PLAYER",
        clientVersion = "1.20250312.01.00",
        clientNameHeader = "56",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        isWebClient = true
    )

    val ANDROID = InnertubeClientProfile(
        id = "ANDROID",
        clientName = "ANDROID",
        clientVersion = "20.10.38",
        clientNameHeader = "3",
        userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
        osName = "Android",
        osVersion = "14",
        androidSdkVersion = 34
    )

    val PLAYER_FALLBACK_ORDER = listOf(ANDROID_VR, TV_EMBED, WEB_EMBEDDED_PLAYER, IOS, ANDROID, ANDROID_TESTSUITE)
    val PLAYER_RETRY_ORDER = listOf(TV_EMBED, WEB_EMBEDDED_PLAYER, ANDROID_VR, IOS, ANDROID, ANDROID_TESTSUITE)
}
