package com.nuvio.tv.layover.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

/**
 * The link the player opens for a stream Layover may play by itself: a plain http(s) `url`.
 * Null for magnets and torrents (need a local resolve), external-only links (WatchHub,
 * "Subscription" entries), YouTube trailers, media-server items and local files.
 */
fun Stream.fallbackUrl(): String? {
    if (serverTarget != null || isExternal()) return null
    val link = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scheme = link.substringBefore(':', "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (link.substringBefore('?').lowercase().endsWith(".torrent")) return null
    return link
}

fun Stream.isFallbackPlayable(): Boolean = fallbackUrl() != null

enum class CacheState { Cached, Unknown, Uncached }

/**
 * How debrid addons label a result that is (or isn't) already on the debrid service. Torrentio:
 * "[RD+]" / "[RD download]"; Comet: "[RD⚡]" / "[RD⬇️]"; MediaFusion and AIOStreams:
 * "⚡" / "⏳". Data, not code: add a marker here when an addon labels it differently.
 */
private const val DEBRID_SERVICES = "RD|AD|PM|TB|DL|ED|OC|PKP|EN|ST"
internal val CACHED_MARKERS: List<Regex> = listOf(
    Regex("(?<![A-Za-z0-9])(?:$DEBRID_SERVICES)\\+(?![A-Za-z0-9])"),
    Regex("⚡"),
    token("cached|instant"),
)
internal val UNCACHED_MARKERS: List<Regex> = listOf(
    Regex("(?<![A-Za-z0-9])(?:$DEBRID_SERVICES)[ _]download", RegexOption.IGNORE_CASE),
    Regex("⬇"),
    Regex("⏳"),
    token("uncached|not cached|download to debrid"),
)

fun Stream.cacheState(): CacheState {
    when (debridCacheStatus?.state) {
        StreamDebridCacheState.CACHED -> return CacheState.Cached
        StreamDebridCacheState.NOT_CACHED -> return CacheState.Uncached
        else -> Unit
    }
    val texts = listOfNotNull(name, title, description).filter { it.isNotBlank() }
    return when {
        texts.any { text -> UNCACHED_MARKERS.any { it.containsMatchIn(text) } } -> CacheState.Uncached
        texts.any { text -> CACHED_MARKERS.any { it.containsMatchIn(text) } } -> CacheState.Cached
        else -> CacheState.Unknown
    }
}

/**
 * Tiers for a 4K HDR TV whose chip decodes HEVC 10-bit and VP9 but not AV1: no size cap and no
 * height preference (the addon's own order already sorts quality the way the user set it up).
 * Debrid-cached first, unlabelled next, not-yet-cached after (they usually answer with a
 * "still downloading" clip), AV1 last whatever its cache state.
 */
internal fun rankTier(stream: Stream): Int {
    if (stream.facts().codec == "av1") return 3
    return when (stream.cacheState()) {
        CacheState.Cached -> 0
        CacheState.Unknown -> 1
        CacheState.Uncached -> 2
    }
}

private class Ranked(val stream: Stream, val tier: Int, val score: Double)

/**
 * Every stream Layover may play by itself, best first: by [rankTier], then by track record
 * [score] (higher first), then in upstream's order (the sort is stable). Samples never, and a
 * link listed twice only once.
 */
fun rankCandidates(pool: List<Stream>, score: (Stream) -> Double): List<Stream> {
    val seen = mutableSetOf<String>()
    return pool
        .filter { stream ->
            val url = stream.fallbackUrl() ?: return@filter false
            !stream.isSample() && seen.add(url)
        }
        .map { Ranked(it, rankTier(it), score(it)) }
        .sortedWith(compareBy<Ranked> { it.tier }.thenByDescending { it.score })
        .map { it.stream }
}

/**
 * The order the fallback walks: [started] first (it is what's playing, whatever it is), then
 * [rankCandidates] of the rest of [pool].
 */
fun fallbackCandidates(pool: List<Stream>, started: Stream, score: (Stream) -> Double): List<Stream> {
    val startedUrl = started.fallbackUrl() ?: started.url
    return listOf(started) + rankCandidates(pool.filter { it.fallbackUrl() != startedUrl }, score)
}
