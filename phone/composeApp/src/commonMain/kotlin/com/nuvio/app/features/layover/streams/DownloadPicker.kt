package com.nuvio.app.features.layover.streams

import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.streams.StreamItem

private const val FALLBACK_HEIGHT = 720

/** How far over the size cap a pick may go when nothing at or under it exists. */
internal const val OVER_CAP_FACTOR = 1.5

/**
 * Orders downloadable streams best-first for a phone. Within the size cap: preferred height with a
 * known size, preferred height with unknown size, 720p known, 720p unknown, any other height up to
 * the preferred one. Then, so a title whose results are all 4K or all a little big still downloads:
 * up to [OVER_CAP_FACTOR] times the cap at or under the preferred height, smallest first; then above
 * the preferred height within the cap (not AV1, which many phones can't decode at 4K).
 * Remuxes and samples are never picked. Foreign-only dubs ([isForeignOnly]) come after every English
 * pick, whatever its tier. [score] only reorders streams inside one tier; addon order breaks remaining ties.
 */
fun pickDownloadCandidates(
    streams: List<StreamItem>,
    isEpisode: Boolean,
    settings: LayoverSettings,
    score: (StreamItem) -> Double = { 0.0 },
): List<StreamItem> {
    val cap = if (isEpisode) settings.episodeCapBytes else settings.movieCapBytes
    val overCap = (cap * OVER_CAP_FACTOR).toLong()
    return streams
        .filter { it.isDownloadable() && !it.isSample() }
        .map { it to it.facts() }
        .filter { (_, f) -> !f.isRemux }
        .mapNotNull { (stream, f) ->
            tier(f, settings.preferredMaxHeight, cap, overCap)?.let {
                Pick(stream, it, score(stream), f.sizeBytes, stream.isForeignOnly())
            }
        }
        .sortedWith(
            compareBy<Pick> { it.foreignOnly }
                .thenBy { it.tier }
                .thenBy { if (it.tier == OVER_CAP_TIER) it.sizeBytes ?: Long.MAX_VALUE else 0L }
                .thenByDescending { it.score },
        )
        .map { it.stream }
}

private data class Pick(
    val stream: StreamItem,
    val tier: Int,
    val score: Double,
    val sizeBytes: Long?,
    val foreignOnly: Boolean,
)

private const val OVER_CAP_TIER = 5

/** Null: not a phone download at all. */
private fun tier(facts: StreamFacts, preferredMaxHeight: Int, cap: Long, overCap: Long): Int? {
    val height = facts.height ?: 0
    val size = facts.sizeBytes
    val withinCap = size == null || size <= cap
    return when {
        height <= preferredMaxHeight && withinCap -> when {
            facts.height == preferredMaxHeight && size != null -> 0
            facts.height == preferredMaxHeight -> 1
            facts.height == FALLBACK_HEIGHT && size != null -> 2
            facts.height == FALLBACK_HEIGHT -> 3
            else -> 4
        }
        height <= preferredMaxHeight && size != null && size <= overCap -> OVER_CAP_TIER
        height > preferredMaxHeight && withinCap && facts.codec != "av1" -> 6
        else -> null
    }
}
