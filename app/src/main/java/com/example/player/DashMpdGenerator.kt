package com.example.player

import android.content.Context
import android.net.Uri
import com.example.mediaserviceinterfaces.MediaFormatOption
import com.example.mediaserviceinterfaces.MediaItemFormatInfo
import java.io.File

/**
 * Generates a static MPEG-DASH MPD manifest XML locally from adaptive H.264/MP4 + M4A streams
 * so ExoPlayer DashMediaSource + DefaultTrackSelector can switch resolutions seamlessly on a single player.
 */
object DashMpdGenerator {

    fun buildMpdUri(context: Context, info: MediaItemFormatInfo): Uri? {
        val xml = buildStaticMpdXml(info) ?: return null
        return runCatching {
            val dir = File(context.cacheDir, "streamtube_mpd").apply { mkdirs() }
            val file = File(dir, "mpd_${info.videoId}.mpd")
            file.writeText(xml, Charsets.UTF_8)
            Uri.fromFile(file)
        }.getOrNull()
    }

    fun buildStaticMpdXml(info: MediaItemFormatInfo): String? {
        if (info.isLive || info.durationMs <= 0L) return null

        val h264Adaptive = info.formats
            .filter {
                !it.isMuxed && !it.isHls && it.hasVideo && !it.hasAudio &&
                    it.isH264Compatible && it.hasSegmentRanges && it.url.isNotBlank()
            }
            .distinctBy { it.height.takeIf { h -> h > 0 } ?: it.itag }
            .sortedBy { it.height.takeIf { h -> h > 0 } ?: it.bitrate }

        val m4aAudio = info.formats
            .filter {
                it.isAudioOnly &&
                    (it.container.equals("M4A", ignoreCase = true) || it.mimeType.contains("mp4", ignoreCase = true)) &&
                    it.hasSegmentRanges && it.url.isNotBlank()
            }
            .maxByOrNull { it.bitrate }

        if (h264Adaptive.size < 2 || m4aAudio == null) return null

        val durationSec = String.format(java.util.Locale.US, "%.3f", info.durationMs / 1000.0)

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append('\n')
            append(
                """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="PT${durationSec}S" minBufferTime="PT1.5S">"""
            )
            append('\n')
            append("""  <Period duration="PT${durationSec}S">""")
            append('\n')
            append("""    <AdaptationSet id="0" contentType="video" mimeType="video/mp4" subsegmentAlignment="true">""")
            append('\n')
            h264Adaptive.forEach { v ->
                appendRepresentation(v, isVideo = true)
            }
            append("    </AdaptationSet>\n")
            append("""    <AdaptationSet id="1" contentType="audio" mimeType="audio/mp4" subsegmentAlignment="true">""")
            append('\n')
            appendRepresentation(m4aAudio, isVideo = false)
            append("    </AdaptationSet>\n")
            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun StringBuilder.appendRepresentation(opt: MediaFormatOption, isVideo: Boolean) {
        val bw = opt.bitrate.coerceAtLeast(128_000)
        val codecs = opt.codec.ifBlank { if (isVideo) "avc1.4d401f" else "mp4a.40.2" }
        val escapedUrl = escapeXml(opt.url)
        val initRange = "${opt.initRangeStart}-${opt.initRangeEnd}"
        val indexRange = "${opt.indexRangeStart}-${opt.indexRangeEnd}"

        append("      <Representation id=\"${opt.itag}\" bandwidth=\"$bw\" codecs=\"${escapeXml(codecs)}\"")
        if (isVideo) {
            val w = opt.width.takeIf { it > 0 } ?: 1280
            val h = opt.height.takeIf { it > 0 } ?: 720
            val fps = opt.fps.coerceAtLeast(24)
            append(" width=\"$w\" height=\"$h\" frameRate=\"$fps\"")
        } else {
            append(" audioSamplingRate=\"44100\"")
        }
        append(">\n")
        append("        <BaseURL>$escapedUrl</BaseURL>\n")
        append("        <SegmentBase indexRange=\"$indexRange\">\n")
        append("          <Initialization range=\"$initRange\"/>\n")
        append("        </SegmentBase>\n")
        append("      </Representation>\n")
    }

    private fun escapeXml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
