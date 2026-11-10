package com.nuvio.app.features.layover.playback

/** What the player did, with wall-clock timestamps. */
sealed interface PlaybackEvent {
    val atMs: Long

    /** An attempt began (or a credential refresh restarted it); starts the first-frame timeout. */
    data class Started(override val atMs: Long) : PlaybackEvent
    data class FirstFrame(override val atMs: Long) : PlaybackEvent
    /**
     * [countsAsRebuffer] false: the spell's time counts toward the buffering budget but it is not a
     * new rebuffer, either because it is the same spell resuming after it was paused (app away,
     * offline) or because it is the part of an app-caused spell (seek, track change) that outlasted
     * its settle grace.
     */
    data class BufferingStart(override val atMs: Long, val countsAsRebuffer: Boolean = true) : PlaybackEvent
    data class BufferingEnd(override val atMs: Long) : PlaybackEvent
    data class Error(override val atMs: Long) : PlaybackEvent
    data class Tick(override val atMs: Long) : PlaybackEvent
}

enum class Verdict { Pending, Healthy, Failed, Stalled }

/**
 * Judges one playback attempt.
 *
 * - Failed: an error, or no first frame within [firstFrameTimeoutMs] of Started.
 * - Stalled: in the [windowMs] after the first frame, [maxRebuffers] or more rebuffers, or more than
 *   [maxRebufferMs] spent rebuffering. Buffering before the first frame is startup, not a rebuffer.
 * - Healthy: the window passed after the first frame without a stall.
 *
 * Failed and Stalled are final. Healthy is final too, except that a later error still fails the
 * attempt (the stream died mid-play and needs replacing).
 */
class StallDetector(
    private val firstFrameTimeoutMs: Long,
    private val maxRebuffers: Int = 2,
    private val maxRebufferMs: Long = 10_000,
    private val windowMs: Long = 120_000,
) {
    private var verdict = Verdict.Pending
    private var startedAt: Long? = null
    private var firstFrameAt: Long? = null
    private var bufferingSince: Long? = null
    private var rebuffers = 0
    private var rebufferMs = 0L

    fun on(event: PlaybackEvent): Verdict {
        if (event is PlaybackEvent.Error && verdict != Verdict.Stalled) verdict = Verdict.Failed
        if (verdict != Verdict.Pending) return verdict

        val at = event.atMs
        val windowEnd = firstFrameAt?.let { it + windowMs }
        when (event) {
            is PlaybackEvent.Started -> if (firstFrameAt == null) startedAt = at
            is PlaybackEvent.FirstFrame -> if (firstFrameAt == null) {
                firstFrameAt = at
                bufferingSince = null
            }
            is PlaybackEvent.BufferingStart -> if (windowEnd != null && at < windowEnd && bufferingSince == null) {
                if (event.countsAsRebuffer) rebuffers++
                bufferingSince = at
            }
            is PlaybackEvent.BufferingEnd -> bufferingSince?.let { since ->
                rebufferMs += minOf(at, windowEnd ?: at) - since
                bufferingSince = null
            }
            is PlaybackEvent.Error, is PlaybackEvent.Tick -> Unit
        }
        verdict = evaluate(at)
        return verdict
    }

    private fun evaluate(at: Long): Verdict {
        val frameAt = firstFrameAt
        if (frameAt == null) {
            val started = startedAt ?: return Verdict.Pending
            return if (at - started >= firstFrameTimeoutMs) Verdict.Failed else Verdict.Pending
        }
        val windowEnd = frameAt + windowMs
        val ongoing = bufferingSince?.let { minOf(at, windowEnd) - it } ?: 0L
        return when {
            rebuffers >= maxRebuffers -> Verdict.Stalled
            rebufferMs + ongoing > maxRebufferMs -> Verdict.Stalled
            at >= windowEnd -> Verdict.Healthy
            else -> Verdict.Pending
        }
    }
}
