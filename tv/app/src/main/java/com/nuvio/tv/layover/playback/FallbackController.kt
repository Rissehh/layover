package com.nuvio.tv.layover.playback

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.layover.LayoverSettings
import com.nuvio.tv.layover.log.AttemptKind
import com.nuvio.tv.layover.log.AttemptOutcome
import com.nuvio.tv.layover.log.AttemptRecord
import com.nuvio.tv.layover.log.attemptRecord
import com.nuvio.tv.layover.streams.fallbackUrl

/** Switching away sooner than this after the first frame counts against the stream. */
const val EARLY_SWITCH_WINDOW_MS = 180_000L

/**
 * Ported from Layover (phone) `playback/FallbackController.kt`. Walks [candidates] for one play.
 * The first candidate is the stream already playing. Each attempt leaves exactly one
 * [AttemptRecord], or none when it ends before anything was learned (the player was closed
 * before the first frame):
 *
 * - Failed / Stalled verdicts log that outcome and move to the next candidate.
 * - Healthy logs Played, but only once the early-switch window after the first frame has passed,
 *   so a "Next source" press inside the window can still record SwitchedEarly instead.
 * - Reaching 90% logs Completed, replacing the attempt's Played record if there is one.
 * - [close] (player left, other content started) logs Played if the stream showed a frame and
 *   nothing was logged yet.
 *
 * TV addition: the stream list keeps arriving after playback started (addons answer at their own
 * pace), so [refresh] swaps in a re-ranked tail of not-yet-tried candidates.
 *
 * At most `settings.maxAttempts` streams are tried, counting the first; after that, or when the
 * list runs out, [exhausted] is true and every call returns null.
 */
class FallbackController(
    candidates: List<Stream>,
    private val settings: LayoverSettings,
    private val contentKey: String,
    private val log: (AttemptRecord) -> Unit,
    private val clock: () -> Long,
    private val replace: (old: AttemptRecord, new: AttemptRecord) -> Unit = { _, new -> log(new) },
    private val earlySwitchWindowMs: Long = EARLY_SWITCH_WINDOW_MS,
) {
    private val candidates: MutableList<Stream> = candidates.toMutableList()
    private var index = 0
    private var tried = if (candidates.isEmpty()) 0 else 1
    private var attemptStartedAt = clock()
    private var firstFrameAt: Long? = null
    private var healthy = false
    private var record: AttemptRecord? = null

    var exhausted: Boolean = candidates.isEmpty()
        private set

    val current: Stream?
        get() = candidates.getOrNull(index)

    /** Another candidate is left and the attempt budget allows trying it. */
    val hasNext: Boolean
        get() = !exhausted && index + 1 < candidates.size && tried < settings.maxAttempts

    /** The candidates not tried yet, in order. */
    val remaining: List<Stream>
        get() = candidates.drop(index + 1)

    /**
     * Replaces the untried tail with [ranked] (a full fallback order, current stream first or not),
     * minus every link already tried. Tried candidates and the attempt in progress are untouched.
     */
    fun refresh(ranked: List<Stream>) {
        if (exhausted) return
        val triedUrls = candidates.take(index + 1).map { it.fallbackUrl() ?: it.url }.toSet()
        val tail = ranked.filter { (it.fallbackUrl() ?: it.url) !in triedUrls }
        while (candidates.size > index + 1) candidates.removeAt(candidates.size - 1)
        candidates += tail
    }

    fun onFirstFrame() {
        if (firstFrameAt == null) firstFrameAt = clock()
    }

    /** Returns the stream to switch to, or null to stay (or because nothing is left). */
    fun onVerdict(v: Verdict): Stream? {
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
    fun userSwitched(logOutcome: Boolean = true): Stream? {
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

    private fun endAttempt(outcome: AttemptOutcome, log: Boolean = true): Stream? {
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
