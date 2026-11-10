package com.nuvio.tv.layover.rank

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.layover.log.AttemptLog
import com.nuvio.tv.layover.log.AttemptOutcome
import com.nuvio.tv.layover.log.AttemptRecord
import com.nuvio.tv.layover.streams.facts

/** Ported from Layover (phone) `rank/TrackRecord.kt`; addons are keyed by display name on the TV. */
private const val WINDOW_MS = 90L * 24 * 60 * 60 * 1000
private const val REFRESH_MS = 60L * 60 * 1000
private const val MIN_ATTEMPTS = 3
private const val GROUP_WEIGHT = 0.5

/** What counts as "worked" and "did not work". Other outcomes are ignored. */
internal val SUCCESS_OUTCOMES = setOf(AttemptOutcome.Played, AttemptOutcome.Completed, AttemptOutcome.DownloadCompleted)
internal val FAILURE_OUTCOMES = setOf(
    AttemptOutcome.Failed,
    AttemptOutcome.Stalled,
    AttemptOutcome.SwitchedEarly,
    AttemptOutcome.DownloadFailed,
)

internal fun heightBucket(height: Int?): String = when {
    height == null -> "Unknown"
    height >= 2160 -> "4K"
    height >= 1440 -> "1440p"
    height >= 1080 -> "1080p"
    height >= 720 -> "720p"
    else -> "SD"
}

private fun addonKey(addonId: String, height: Int?) = addonId + "|" + heightBucket(height)

private fun groupKey(releaseGroup: String) = releaseGroup.lowercase()

private class Tally {
    var successes = 0
    var attempts = 0

    fun add(won: Boolean) {
        attempts++
        if (won) successes++
    }

    /** Smoothed success rate minus 0.5, or 0 when there is too little history to trust. */
    fun centered(): Double =
        if (attempts < MIN_ATTEMPTS) 0.0 else (successes + 1.0) / (attempts + 2.0) - 0.5
}

/** Per-key success/attempt counts over the last 90 days, built in one pass over the log. */
internal class TrackRecordStats(records: List<AttemptRecord>, now: Long) {
    private val byAddon = HashMap<String, Tally>()
    private val byGroup = HashMap<String, Tally>()

    init {
        val cutoff = now - WINDOW_MS
        for (record in records) {
            if (record.atEpochMs < cutoff) continue
            val won = record.outcome in SUCCESS_OUTCOMES
            if (!won && record.outcome !in FAILURE_OUTCOMES) continue
            byAddon.getOrPut(addonKey(record.addonId, record.height)) { Tally() }.add(won)
            record.releaseGroup?.let { byGroup.getOrPut(groupKey(it)) { Tally() }.add(won) }
        }
    }

    fun score(addonId: String, height: Int?, releaseGroup: String?): Double {
        val addon = byAddon[addonKey(addonId, height)]?.centered() ?: 0.0
        val group = releaseGroup?.let { byGroup[groupKey(it)] }?.centered() ?: 0.0
        return addon + GROUP_WEIGHT * group
    }
}

/**
 * Pure scoring over a record list. Streams from an addon + quality bucket (and release group)
 * that worked before score above 0; ones that failed score below 0; thin history scores 0.
 */
internal fun scoreFrom(
    records: List<AttemptRecord>,
    addonId: String,
    height: Int?,
    releaseGroup: String?,
    now: Long,
): Double = TrackRecordStats(records, now).score(addonId, height, releaseGroup)

object TrackRecord {
    internal var recordsSource: () -> List<AttemptRecord> = { AttemptLog.records.value }
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private class Cache(val source: List<AttemptRecord>, val builtAt: Long, val stats: TrackRecordStats)

    @Volatile
    private var cache: Cache? = null

    /**
     * Cheap enough to call per stream in a sort: aggregates are rebuilt only when the log list
     * instance changes (or hourly, so the 90-day window keeps moving).
     */
    fun score(stream: Stream): Double {
        val facts = stream.facts()
        return stats().score(stream.addonName, facts.height, facts.releaseGroup)
    }

    private fun stats(): TrackRecordStats {
        val records = recordsSource()
        val now = clock()
        val cached = cache
        if (cached != null && cached.source === records && now - cached.builtAt in 0..REFRESH_MS) return cached.stats
        return TrackRecordStats(records, now).also { cache = Cache(records, now, it) }
    }

    internal fun resetForTests() {
        recordsSource = { AttemptLog.records.value }
        clock = { System.currentTimeMillis() }
        cache = null
    }
}
