package com.nuvio.app.features.layover.stats

import com.nuvio.app.features.layover.log.AttemptOutcome
import com.nuvio.app.features.layover.log.AttemptRecord
import com.nuvio.app.features.layover.rank.FAILURE_OUTCOMES
import com.nuvio.app.features.layover.rank.SUCCESS_OUTCOMES
import com.nuvio.app.features.layover.rank.heightBucket
import kotlin.math.roundToInt

data class StatRow(
    val label: String,
    val attempts: Int,
    val successRate: Double,
    val avgTimeToFirstFrameMs: Long?,
    val earlySwitchRate: Double,
)

/** Outcomes listed under "Recent failures". SwitchedEarly is left out: the stream did play. */
private val RECENT_FAILURE_OUTCOMES = setOf(AttemptOutcome.Failed, AttemptOutcome.Stalled, AttemptOutcome.DownloadFailed)

/** One row per addon (grouped by id, labelled by name), most attempts first. */
fun statsByAddon(records: List<AttemptRecord>): List<StatRow> =
    statRows(records, key = { it.addonId }, label = { it.addonName.ifBlank { it.addonId } })

/** One row per quality bucket ("4K", "1440p", "1080p", "720p", "SD", "Unknown"), most attempts first. */
fun statsByQuality(records: List<AttemptRecord>): List<StatRow> =
    statRows(records, key = { heightBucket(it.height) }, label = { heightBucket(it.height) })

/** Failed, Stalled and DownloadFailed records, newest first, at most [limit]. */
fun recentFailures(records: List<AttemptRecord>, limit: Int = 20): List<AttemptRecord> =
    records
        .filter { it.outcome in RECENT_FAILURE_OUTCOMES }
        .sortedByDescending { it.atEpochMs }
        .take(limit)

private fun statRows(
    records: List<AttemptRecord>,
    key: (AttemptRecord) -> String,
    label: (AttemptRecord) -> String,
): List<StatRow> =
    records
        .filter { it.outcome in SUCCESS_OUTCOMES || it.outcome in FAILURE_OUTCOMES }
        .groupBy(key)
        .values
        .map { group ->
            val attempts = group.size
            val ttffs = group.mapNotNull { it.timeToFirstFrameMs }
            StatRow(
                label = label(group.last()),
                attempts = attempts,
                successRate = group.count { it.outcome in SUCCESS_OUTCOMES }.toDouble() / attempts,
                avgTimeToFirstFrameMs = if (ttffs.isEmpty()) null else ttffs.sum() / ttffs.size,
                earlySwitchRate = group.count { it.outcome == AttemptOutcome.SwitchedEarly }.toDouble() / attempts,
            )
        }
        .sortedByDescending { it.attempts }

/** "2.4 s", or "-" when there is no start time. */
fun formatStartTime(ms: Long?): String {
    if (ms == null) return "-"
    val tenths = (ms + 50) / 100
    return "${tenths / 10}.${tenths % 10} s"
}

fun formatPercent(rate: Double): String = "${(rate * 100).roundToInt()}%"

/** "just now", "5 min ago", "3 h ago", "2 d ago". */
fun formatRelativeTime(atEpochMs: Long, now: Long): String {
    val minutes = (now - atEpochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

fun failureLabel(outcome: AttemptOutcome): String = when (outcome) {
    AttemptOutcome.Failed -> "Failed to start"
    AttemptOutcome.Stalled -> "Stalled"
    AttemptOutcome.DownloadFailed -> "Download failed"
    AttemptOutcome.SwitchedEarly -> "Switched early"
    AttemptOutcome.Played -> "Played"
    AttemptOutcome.Completed -> "Completed"
    AttemptOutcome.DownloadCompleted -> "Downloaded"
}

/** "S1E3" for an episode content key ("<metaId>|<season>|<episode>"), null otherwise. */
fun episodeCode(contentKey: String): String? {
    val parts = contentKey.split('|')
    if (parts.size != 3) return null
    val season = parts[1].toIntOrNull() ?: return null
    val episode = parts[2].toIntOrNull() ?: return null
    return "S${season}E$episode"
}

/** Title for a content key with no matching download: "tt123 S1E3" or "Movie tt123". */
fun fallbackTitle(contentKey: String): String {
    val metaId = contentKey.substringBefore('|')
    episodeCode(contentKey)?.let { return "$metaId $it" }
    return if (contentKey.endsWith("|movie") && metaId.isNotBlank()) "Movie $metaId" else contentKey
}
