package com.example

import com.example.mediaserviceinterfaces.FeedCategory
import com.example.mediaserviceinterfaces.MediaFormatOption
import com.example.mediaserviceinterfaces.MediaItem
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import com.example.player.DashMpdGenerator
import com.example.update.AppUpdateManager
import com.example.youtubeapi.InnertubeClientProfiles
import com.example.youtubeapi.InnertubeParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InnertubeParserTest {

    @Test
    fun `parses video renderer with channel logo views and date`() {
        val json = JSONObject(
            """
            {
              "contents": {
                "videoRenderer": {
                  "videoId": "dQw4w9WgXcQ",
                  "title": { "runs": [{ "text": "4K Ultra HD Demo" }] },
                  "ownerText": {
                    "runs": [{
                      "text": "Stream Creator",
                      "navigationEndpoint": { "browseEndpoint": { "browseId": "UC123456" } }
                    }]
                  },
                  "channelThumbnailSupportedRenderers": {
                    "channelThumbnailWithLinkRenderer": {
                      "thumbnail": { "thumbnails": [{ "url": "//yt3.ggpht.com/avatar.jpg" }] }
                    }
                  },
                  "viewCountText": { "simpleText": "1.2M views" },
                  "publishedTimeText": { "simpleText": "2 days ago" },
                  "lengthText": { "simpleText": "10:42" }
                }
              }
            }
            """.trimIndent()
        )

        val videos = InnertubeParser.parseInnertubeVideos(json)
        assertEquals(1, videos.size)
        val item = videos.first()
        assertEquals("dQw4w9WgXcQ", item.id)
        assertEquals("4K Ultra HD Demo", item.title)
        assertEquals("UC123456", item.channelId)
        assertEquals("Stream Creator", item.channelName)
        assertEquals("https://yt3.ggpht.com/avatar.jpg", item.channelAvatarUrl)
        assertEquals("1.2M views", item.viewCountText)
        assertEquals("2 days ago", item.publishedDateText)
        assertEquals("10:42", item.durationText)
    }

    @Test
    fun `parses player formats and exact upload date time`() {
        val json = JSONObject(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "videoDetails": {
                "videoId": "abc123xyz00",
                "title": "Fast Stream Test",
                "author": "Tech Channel",
                "channelId": "UCtech01",
                "lengthSeconds": "125",
                "viewCount": "2450000"
              },
              "microformat": {
                "playerMicroformatRenderer": {
                  "publishDate": "2026-11-18T14:22:00Z"
                }
              },
              "streamingData": {
                "hlsManifestUrl": "https://example.com/master.m3u8",
                "formats": [
                  {
                    "itag": 18,
                    "url": "https://example.com/360p.mp4",
                    "mimeType": "video/mp4; codecs=\"avc1.42001E\"",
                    "qualityLabel": "360p",
                    "bitrate": 500000,
                    "contentLength": "10485760"
                  }
                ],
                "adaptiveFormats": [
                  {
                    "itag": 137,
                    "url": "https://example.com/1080p.mp4",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "qualityLabel": "1080p",
                    "bitrate": 4000000,
                    "fps": 60,
                    "contentLength": "52428800"
                  },
                  {
                    "itag": 140,
                    "url": "https://example.com/audio.m4a",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000,
                    "contentLength": "2097152"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val info = InnertubeParser.parsePlayerResponse(json, "abc123xyz00", "MediaServiceCore (IOS)")
        assertNotNull(info)
        assertEquals("2.5M views", info!!.viewCountText)
        assertTrue(info.publishedDateText.contains("Nov 18, 2026"))
        assertTrue(info.formats.any { it.qualityLabel == "360p" && it.isMuxed })
        assertTrue(info.formats.any { it.qualityLabel == "1080p" && it.audioPairUrl != null })
        assertTrue(info.formats.any { it.isAudioOnly && it.container == "M4A" })
    }

    @Test
    fun `enforces avc1 over vp9 over av01 priority and container-matched audio pairing`() {
        val json = JSONObject(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "videoDetails": { "videoId": "vidCodec01", "title": "Long Documentary", "lengthSeconds": "1800" },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 399,
                    "url": "https://example.com/1080_av1.mp4",
                    "mimeType": "video/mp4; codecs=\"av01.0.08M.08\"",
                    "qualityLabel": "1080p",
                    "bitrate": 3500000
                  },
                  {
                    "itag": 248,
                    "url": "https://example.com/1080_vp9.webm",
                    "mimeType": "video/webm; codecs=\"vp9\"",
                    "qualityLabel": "1080p",
                    "bitrate": 2800000
                  },
                  {
                    "itag": 137,
                    "url": "https://example.com/1080_h264.mp4",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "qualityLabel": "1080p",
                    "bitrate": 2400000
                  },
                  {
                    "itag": 140,
                    "url": "https://example.com/audio.m4a",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000
                  },
                  {
                    "itag": 251,
                    "url": "https://example.com/audio.webm",
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "bitrate": 160000
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val info = InnertubeParser.parsePlayerResponse(json, "vidCodec01", "custom-vr-ua")!!
        val videoFormats = info.formats.filter { it.hasVideo }
        assertEquals(listOf(137, 248, 399), videoFormats.map { it.itag })
        assertEquals("https://example.com/audio.m4a", videoFormats[0].audioPairUrl)
        assertEquals("https://example.com/audio.webm", videoFormats[1].audioPairUrl)
        assertEquals("custom-vr-ua", info.requestUserAgent)
    }

    @Test
    fun `detects newer github release versions accurately and ignores same or older versions`() {
        assertTrue(AppUpdateManager.isNewerVersion("1.1", "1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("2.0.0", "1.9.5"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.1", "1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0", "1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("0.9", "1.0"))
    }

    @Test
    fun `filters download formats strictly to available 240p 360p 720p 1080p and 2K`() {
        val raw = listOf(
            MediaFormatOption(160, "144p", "video/mp4", "MP4", "https://ex/144", audioPairUrl = "https://ex/a.m4a", codec = "avc1"),
            MediaFormatOption(133, "240p", "video/mp4", "MP4", "https://ex/240", audioPairUrl = "https://ex/a.m4a", codec = "avc1"),
            MediaFormatOption(18, "360p", "video/mp4", "MP4", "https://ex/360", codec = "avc1", hasAudio = true),
            MediaFormatOption(135, "480p", "video/mp4", "MP4", "https://ex/480", audioPairUrl = "https://ex/a.m4a", codec = "avc1"),
            MediaFormatOption(247, "720p", "video/webm", "WEBM", "https://ex/720_vp9", audioPairUrl = "https://ex/a.webm", codec = "vp9"),
            MediaFormatOption(136, "720p", "video/mp4", "MP4", "https://ex/720_h264", audioPairUrl = "https://ex/a.m4a", codec = "avc1"),
            MediaFormatOption(399, "1080p", "video/mp4", "MP4", "https://ex/1080_av1", audioPairUrl = "https://ex/a.m4a", codec = "av01"),
            MediaFormatOption(271, "1440p", "video/webm", "WEBM", "https://ex/1440", audioPairUrl = "https://ex/a.webm", codec = "vp9"),
            MediaFormatOption(313, "2160p", "video/webm", "WEBM", "https://ex/2160", audioPairUrl = "https://ex/a.webm", codec = "vp9"),
            MediaFormatOption(140, "Audio M4A", "audio/mp4", "M4A", "https://ex/a.m4a", hasVideo = false, hasAudio = true)
        )

        val filtered = InnertubeParser.filterAllowedDownloadFormats(raw)
        assertEquals(listOf("240p", "360p", "720p", "1080p", "2K (1440p)"), filtered.map { it.qualityLabel })
        assertEquals(136, filtered.first { it.qualityLabel == "720p" }.itag)
    }

    @Test
    fun `parses initRange indexRange width and height and builds static DASH MPD XML`() {
        val json = JSONObject(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "videoDetails": { "videoId": "dashVid01", "title": "DASH Adaptive Test", "lengthSeconds": "120" },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "itag": 134,
                    "url": "https://example.com/v360.mp4?a=1&b=2",
                    "mimeType": "video/mp4; codecs=\"avc1.4d401e\"",
                    "qualityLabel": "360p",
                    "width": 640,
                    "height": 360,
                    "bitrate": 650000,
                    "fps": 30,
                    "initRange": { "start": "0", "end": "740" },
                    "indexRange": { "start": "741", "end": "1020" }
                  },
                  {
                    "itag": 137,
                    "url": "https://example.com/v1080.mp4?a=1&b=2",
                    "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                    "qualityLabel": "1080p",
                    "width": 1920,
                    "height": 1080,
                    "bitrate": 3800000,
                    "fps": 60,
                    "initRange": { "start": "0", "end": "742" },
                    "indexRange": { "start": "743", "end": "1100" }
                  },
                  {
                    "itag": 140,
                    "url": "https://example.com/audio.m4a?a=1&b=2",
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "bitrate": 128000,
                    "initRange": { "start": "0", "end": "631" },
                    "indexRange": { "start": "632", "end": "899" }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val info = InnertubeParser.parsePlayerResponse(json, "dashVid01", "test-ua")!!
        val f1080 = info.formats.first { it.itag == 137 }
        assertTrue(f1080.hasSegmentRanges)
        assertEquals(0L, f1080.initRangeStart)
        assertEquals(742L, f1080.initRangeEnd)
        assertEquals(1920, f1080.width)
        assertEquals(1080, f1080.height)

        val mpd = DashMpdGenerator.buildStaticMpdXml(info)
        assertNotNull(mpd)
        assertTrue(mpd!!.contains("<MPD"))
        assertTrue(mpd.contains("contentType=\"video\""))
        assertTrue(mpd.contains("contentType=\"audio\""))
        assertTrue(mpd.contains("indexRange=\"743-1100\""))
        assertTrue(mpd.contains("range=\"0-742\""))
        assertTrue(mpd.contains("a=1&amp;b=2"))
    }

    @Test
    fun `isStrictShortStream rejects live streams and videos longer than 65 seconds`() {
        val validShort = MediaItem(
            id = "sh1",
            title = "Quick clip",
            durationMs = 45_000L,
            isShort = true
        )
        val tooLongShort = MediaItem(
            id = "sh2",
            title = "Long clip",
            durationMs = 95_000L,
            isShort = true
        )
        val liveShort = MediaItem(
            id = "sh3",
            title = "Live stream",
            durationMs = 20_000L,
            isShort = true,
            isLive = true
        )

        assertTrue(InnertubeParser.isStrictShortStream(validShort))
        assertFalse(InnertubeParser.isStrictShortStream(tooLongShort))
        assertFalse(InnertubeParser.isStrictShortStream(liveShort))
    }

    @Test
    fun `innertube client profiles mark only web clients for Origin headers`() {
        assertTrue(InnertubeClientProfiles.WEB.isWebClient)
        assertTrue(InnertubeClientProfiles.TV_EMBED.isWebClient)
        assertTrue(InnertubeClientProfiles.WEB_EMBEDDED_PLAYER.isWebClient)
        assertFalse(InnertubeClientProfiles.ANDROID_VR.isWebClient)
        assertFalse(InnertubeClientProfiles.IOS.isWebClient)
        assertFalse(InnertubeClientProfiles.ANDROID.isWebClient)
        assertFalse(InnertubeClientProfiles.ANDROID_TESTSUITE.isWebClient)
    }

    @Test
    fun `builds DASH MPD cleanly for ultrawide 21x9 video formats`() {
        val formats = listOf(
            MediaFormatOption(
                itag = 134,
                qualityLabel = "360p",
                mimeType = "video/mp4; codecs=\"avc1.4d401e\"",
                container = "MP4",
                url = "https://example.com/ultrawide_360.mp4",
                codec = "avc1.4d401e",
                bitrate = 650000,
                fps = 24,
                hasVideo = true,
                hasAudio = false,
                width = 640,
                height = 267,
                initRangeStart = 0L,
                initRangeEnd = 700L,
                indexRangeStart = 701L,
                indexRangeEnd = 980L
            ),
            MediaFormatOption(
                itag = 136,
                qualityLabel = "720p",
                mimeType = "video/mp4; codecs=\"avc1.4d401f\"",
                container = "MP4",
                url = "https://example.com/ultrawide_720.mp4",
                codec = "avc1.4d401f",
                bitrate = 1500000,
                fps = 24,
                hasVideo = true,
                hasAudio = false,
                width = 1280,
                height = 534,
                initRangeStart = 0L,
                initRangeEnd = 700L,
                indexRangeStart = 701L,
                indexRangeEnd = 1000L
            ),
            MediaFormatOption(
                itag = 140,
                qualityLabel = "Audio M4A",
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                container = "M4A",
                url = "https://example.com/audio.m4a",
                codec = "mp4a.40.2",
                bitrate = 128000,
                hasVideo = false,
                hasAudio = true,
                initRangeStart = 0L,
                initRangeEnd = 631L,
                indexRangeStart = 632L,
                indexRangeEnd = 899L
            )
        )
        val info = MediaItemFormatInfo(
            videoId = "ultra219",
            title = "Cinema Ultrawide Trailer",
            durationMs = 120_000L,
            formats = formats
        )

        val mpd = DashMpdGenerator.buildStaticMpdXml(info)
        assertNotNull(mpd)
        assertTrue(mpd!!.contains("width=\"1280\" height=\"534\""))
    }

    @Test
    fun `home category defaults to Recommended and innertube context applies regional gl`() {
        assertEquals("Recommended", FeedCategory.HOME.label)
        assertEquals("PK", InnertubeClientProfiles.WEB.toClientContextJson("PK").optString("gl"))
        assertEquals("IN", InnertubeClientProfiles.ANDROID_VR.toClientContextJson("IN").optString("gl"))
    }

    @Test
    fun `update banner auto-hides when up to date and reappears only for newer releases`() {
        assertEquals("2.2", AppUpdateManager.extractCleanVersion("StreamTube-v2.2"))
        assertEquals("2.3.1", AppUpdateManager.extractCleanVersion("v2.3.1"))

        // Installed v2.2 with remote v2.2 or older -> auto-hide
        val sameVersion = AppUpdateManager.evaluateReleaseForBanner("2.2", "2.2", null)
        assertFalse(sameVersion.isUpdateAvailable)
        assertFalse(sameVersion.shouldShowBanner)

        val olderRemote = AppUpdateManager.evaluateReleaseForBanner("2.0", "2.2", null)
        assertFalse(olderRemote.isUpdateAvailable)
        assertFalse(olderRemote.shouldShowBanner)

        // Installed v2.2 with new remote v2.3 -> show banner
        val newRelease = AppUpdateManager.evaluateReleaseForBanner("2.3", "2.2", null)
        assertTrue(newRelease.isUpdateAvailable)
        assertTrue(newRelease.shouldShowBanner)

        // Dismissed v2.3 -> stay hidden while remote is still v2.3
        val dismissedSame = AppUpdateManager.evaluateReleaseForBanner("2.3", "2.2", "2.3")
        assertTrue(dismissedSame.isUpdateAvailable)
        assertFalse(dismissedSame.shouldShowBanner)

        // Next release v2.4 arrives -> automatically show again
        val nextRelease = AppUpdateManager.evaluateReleaseForBanner("2.4", "2.2", "2.3")
        assertTrue(nextRelease.isUpdateAvailable)
        assertTrue(nextRelease.shouldShowBanner)
    }
}
