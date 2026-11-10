package com.nuvio.app.features.layover.streams

import com.nuvio.app.features.streams.StreamItem
import kotlin.math.roundToLong

data class StreamFacts(
    val height: Int?,
    val sizeBytes: Long?,
    val isRemux: Boolean,
    val codec: String?,
    val releaseGroup: String?,
)

// Release names use `.`, `-` and `_` as separators; `\b` treats `_` as a word char, so bound on letters/digits instead.
private fun token(body: String) =
    Regex("(?<![A-Za-z0-9])(?:$body)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

// An explicit resolution beats a "4K"/"UHD" label: "1080p.UHD.BluRay" is a 1080p encode of a UHD source.
private val heightRules: List<Pair<Regex, Int>> = listOf(
    token("2160p") to 2160,
    token("1440p") to 1440,
    token("1080p") to 1080,
    token("720p") to 720,
    token("480p") to 480,
    token("4k|uhd") to 2160,
)

private val codecRules: List<Pair<Regex, String>> = listOf(
    token("x265|h\\.?265|hevc") to "hevc",
    token("x264|h\\.?264|avc") to "avc",
    token("av1") to "av1",
)

private val sizeRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|GiB|MB|MiB)""", RegexOption.IGNORE_CASE)
private val remuxRegex = token("remux")
private val sampleRegex = token("sample")
private val releaseGroupRegex = Regex("""[A-Za-z0-9]{2,20}""")
private val videoExtensions = setOf("mkv", "mp4", "avi", "m4v", "ts", "mov", "webm", "wmv")

private const val BYTES_PER_MIB = 1024.0 * 1024.0
private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0

fun StreamItem.facts(): StreamFacts {
    val filename = behaviorHints.filename?.takeIf { it.isNotBlank() }
    // Precedence for quality text: filename, title, description, name.
    val texts = listOfNotNull(filename, title, description, name).filter { it.isNotBlank() }
    return StreamFacts(
        height = texts.firstNotNullOfOrNull { text ->
            heightRules.firstOrNull { it.first.containsMatchIn(text) }?.second
        },
        sizeBytes = behaviorHints.videoSize?.takeIf { it > 0 }
            ?: listOfNotNull(title, description, name).firstNotNullOfOrNull { parseSizeBytes(it) },
        isRemux = texts.any { remuxRegex.containsMatchIn(it) },
        codec = texts.firstNotNullOfOrNull { text ->
            codecRules.firstOrNull { it.first.containsMatchIn(text) }?.second
        },
        releaseGroup = filename?.let { parseReleaseGroup(it) },
    )
}

// GB/GiB/MB/MiB are all treated as binary (1024-based); addons mix them freely.
private fun parseSizeBytes(text: String): Long? {
    val match = sizeRegex.find(text) ?: return null
    val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val unit = match.groupValues[2].uppercase()
    val bytes = if (unit == "GB" || unit == "GIB") amount * BYTES_PER_GIB else amount * BYTES_PER_MIB
    return bytes.roundToLong().takeIf { it > 0 }
}

private fun parseReleaseGroup(filename: String): String? {
    val ext = filename.substringAfterLast('.', "").lowercase()
    val stem = if (ext in videoExtensions) filename.dropLast(ext.length + 1) else filename
    if ('-' !in stem) return null
    return stem.substringAfterLast('-').takeIf { releaseGroupRegex.matches(it) }
}

/** A release's sample clip (about a minute), listed by some addons next to the real file. */
fun StreamItem.isSample(): Boolean =
    listOfNotNull(behaviorHints.filename, title, description, name).any { sampleRegex.containsMatchIn(it) }

fun StreamItem.isDownloadable(): Boolean {
    // Not gated on isTorrentStream: debrid addons send an infoHash alongside a direct https url.
    // Magnets and torrent:// are already excluded by playableDirectUrl.
    val url = playableDirectUrl ?: return false
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val path = url.substringBefore('#').substringBefore('?').lowercase()
    return !path.contains(".m3u8") && !path.endsWith(".mpd") && !path.endsWith(".torrent")
}
