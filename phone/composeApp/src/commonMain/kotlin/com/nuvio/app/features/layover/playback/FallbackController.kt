package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.log.AttemptKind
import com.nuvio.app.features.layover.log.AttemptOutcome
import com.nuvio.app.features.layover.log.AttemptRecord
import com.nuvio.app.features.layover.log.attemptRecord
import com.nuvio.app.features.layover.streams.isForeignOnly
import com.nuvio.app.features.layover.streams.isSample
import com.nuvio.app.features.streams.StreamItem

/** Switching away sooner than this after the first frame counts against the stream. */
const val EARLY_SWITCH_WINDOW_MS = 180_000L

/**
 * A stream the player can open as-is: a plain http(s) link. Excludes magnets and torrents (need a
 * local resolve), external-only and YouTube entries, media-server items (need preparation) and
 * local files.
 */
fun StreamItem.isFallbackPlayable(): Boolean {
    if (serverTarget != null) return false
    val url = playableDirectUrl ?: return false
    return url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
}

/**
 * The order the fallback walks: [started] first (it is what's playing), then every other playable
 * stream in [pool], English before foreign-only dubs ([isForeignOnly]), best track record first. The sort is stable, so equal scores keep upstream's
 * order (addon order, then the addon's own order). Duplicate links are tried once; samples never.
 */
fun fallbackCandidates(
    pool: List<StreamItem>,
    started: StreamItem,
    score: (StreamItem) -> Double,
): List<StreamItem> {
    val seen = mutableSetOf(started.playableDirectUrl)
    val rest = pool
        .filter { it.isFallbackPlayable() && !it.isSample() && seen.add(it.playableDirectUrl) }
        .sortedWith(compareBy<StreamItem> { it.isForeignOnly() }.thenByDescending(score))
    return listOf(started) + rest
}

/**
 * Walks [candidates] for one play. The first candidate is the stream already playing. Each attempt
 * leaves exactly one [AttemptRecord], or none when it ends before anything was learned (the player
 * was closed before the first frame):
 *
 * - Failed / Stalled verdicts log that outcome and move to the next candidate.
 * - Healthy logs Played, but only once the early-switch window after the first frame has passed,
 *   so a "Next source" tap inside the window can still record SwitchedEarly instead.
 * - Reaching 90% logs Completed, replacing the attempt's Played record if there is one. When that
 *   Played was never written (the glue drops records on an unvalidated network) but the network is
 *   validated by 90%, the replace finds nothing to swap and AttemptLog appends Completed instead.
 * - [close] (player left, other content started) logs Played if the stream showed a frame and
 *   nothing was logged yet.
 *
 * At most `settings.maxAttempts` streams are tried, counting the first; after that, or when the
 * list runs out, [exhausted] is true and every call returns null.
 */
class FallbackController(
    private val candidates: List<StreamItem>,
    private val settings: LayoverSettings,
    private val contentKey: String,
    private val log: (AttemptRecord) -> Unit,
    private val clock: () -> Long,
    private val replace: (old: AttemptRecord, new: AttemptRecord) -> Unit = { _, new -> log(new) },
    private val earlySwitchWindowMs: Long = EARLY_SWITCH_WINDOW_MS,
) {
    private var index = 0
    private var tried = if (candidates.isEmpty()) 0 else 1
    private var attemptStartedAt = clock()
    private var firstFrameAt: Long? = null
    private var healthy = false
    private var record: AttemptRecord? = null

    var exhausted: Boolean = candidates.isEmpty()
        private set

    val current: StreamItem?
        get() = candidates.getOrNull(index)

    /** Another candidate is left and the attempt budget allows trying it. */
    val hasNext: Boolean
        get() = !exhausted && index + 1 < candidates.size && tried < settings.maxAttempts

    fun onFirstFrame() {
        if (firstFrameAt == null) firstFrameAt = clock()
    }

    /** Returns the stream to switch to, or null to stay (or because nothing is left). */
    fun onVerdict(v: Verdict): StreamItem? {
        if (exhausted) return null
        return when (v) {
            Verdict.Pending -> null
            Verdict.Healthy -> {
                healthy = true
                logPlayedIfSettled()
                null
            }
            Verdict.Failed -> endAttempt(AttemptOutcome.Failed)
            Verdict.Stalled -> endAttempt(AttemptOutcome.Stalled)
        }
    }

    /**
     * "Next source" (or another source picked in the panel): the next candidate, or null when there
     * is none left. [logOutcome] false when the switch says nothing about the stream (offline).
     */
    fun userSwitched(logOutcome: Boolean = true): StreamItem? {
        if (exhausted) return null
        val frameAt = firstFrameAt
        val early = frameAt == null || clock() - frameAt < earlySwitchWindowMs
        return endAttempt(if (early) AttemptOutcome.SwitchedEarly else AttemptOutcome.Played, log = logOutcome)
    }

    /** Playback reached 90%. */
    fun onCompleted() {
        if (exhausted) return
        val existing = record
        when {
            existing == null -> logOnce(AttemptOutcome.Completed)
            existing.outcome == AttemptOutcome.Played -> {
                val completed = existing.copy(atEpochMs = clock(), outcome = AttemptOutcome.Completed)
                replace(existing, completed)
                record = completed
            }
        }
    }

    /** The attempt is over without a verdict (player closed, or something else started playing). */
    fun close() {
        if (exhausted) return
        if (firstFrameAt != null) logOnce(AttemptOutcome.Played)
    }

    private fun logPlayedIfSettled() {
        val frameAt = firstFrameAt ?: return
        if (healthy && clock() - frameAt >= earlySwitchWindowMs) logOnce(AttemptOutcome.Played)
    }

    private fun endAttempt(outcome: AttemptOutcome, log: Boolean = true): StreamItem? {
        if (log) logOnce(outcome)
        if (!hasNext) {
            exhausted = true
            return null
        }
        index++
        tried++
        attemptStartedAt = clock()
        firstFrameAt = null
        healthy = false
        record = null
        return candidates[index]
    }

    private fun logOnce(outcome: AttemptOutcome) {
        if (record != null) return
        val stream = current ?: return
        val now = clock()
        val ttff = firstFrameAt?.let { it - attemptStartedAt }
        val entry = attemptRecord(stream, AttemptKind.Play, contentKey, outcome, ttff, now)
        record = entry
        log(entry)
    }
}
