package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlin.math.abs

/** A position jump bigger than this (beyond normal playback drift) is a seek, not playback. */
private const val SEEK_JUMP_MS = 3_000L

/** After a player command, this much uninterrupted playback means the player has settled again. */
private const val SETTLE_PLAYBACK_MS = 10_000L

/**
 * How much of a spell that starts while settling is forgiven. ExoPlayer re-opening the source for
 * a subtitle (new HTTP request, range seek, demuxer restart) and libmpv refilling its cache after
 * a seek take one to three seconds on a working debrid link; 4 s covers that with a little margin.
 * Anything longer is the stream struggling, so the spell's time counts from the end of the grace
 * (toward the buffering budget only, not as a rebuffer: two slow seeks are not a stall), and a
 * hang that never ends still reaches the 10 s stall limit 14 s after it began. A spell still going
 * when judging resumes (back from the background or offline) gets the same grace.
 */
internal const val SETTLE_GRACE_MS = 4_000L

/**
 * Turns the player's snapshots (both engines poll every 250 ms) into [PlaybackEvent]s: buffering
 * start/end edges, then the first frame once. Buffering edges come first, so buffering that rises
 * in the first-frame snapshot is still startup, not a rebuffer.
 *
 * Buffering the app caused is forgiven, within limits: after a player command (seek, subtitle or
 * track change; the engines count them in [PlayerPlaybackSnapshot.playerCommandCount]) or a
 * position jump (a seek that bypassed the controller), issued after the first frame, the player is
 * "settling" until it has played [SETTLE_PLAYBACK_MS] without buffering. A spell that starts while
 * settling is only reported if it outlasts [SETTLE_GRACE_MS], and then as starting at the grace
 * end, as buffering time but not as a new rebuffer. Commands before the first frame (the
 * preferred-audio pick every source gets) don't start settling: startup buffering never counts
 * anyway. libmpv applies the resume seek after its first frame, so that one does start settling and
 * only the 4 s grace covers the refill it causes.
 */
class SnapshotSignals {
    private var firstFrame = false
    private var buffering = false
    private var reportedBuffering = false
    private var graceUntilMs: Long? = null
    /** Whether the spell in its grace becomes a new rebuffer when the grace runs out. */
    private var graceCountsAsRebuffer = false
    private var lastPositionMs: Long? = null
    private var lastAtMs = 0L
    private var lastCommandCount = 0
    private var settling = false
    private var settleBaseMs: Long? = null

    /** Call when the player surface is rebuilt (new source or engine); its snapshots start over. */
    fun reset() {
        firstFrame = false
        buffering = false
        reportedBuffering = false
        graceUntilMs = null
        lastPositionMs = null
        lastCommandCount = 0
        settling = false
        settleBaseMs = null
    }

    /**
     * Forgets an open spell (reported, or still in its grace) so its end is not reported. True if a
     * reported spell was open; the caller closes it in the detector itself.
     */
    fun dropOpenSpell(): Boolean {
        val open = buffering && reportedBuffering
        reportedBuffering = false
        graceUntilMs = null
        return open
    }

    /** True while an open spell sits in a grace that would not make it a new rebuffer. */
    val inUncountedGrace: Boolean
        get() = buffering && graceUntilMs != null && !graceCountsAsRebuffer

    /**
     * For a dropped spell the player is still in: gives it a fresh [SETTLE_GRACE_MS] grace from
     * [atMs] (the player refilling after the pause), reported at the grace end with
     * [countsAsRebuffer] unless it ends first. False when there is nothing to reopen (not
     * buffering, already reported, or still inside its grace).
     */
    fun reopenSpell(atMs: Long, countsAsRebuffer: Boolean): Boolean {
        if (!buffering || reportedBuffering || graceUntilMs != null) return false
        graceUntilMs = atMs + SETTLE_GRACE_MS
        graceCountsAsRebuffer = countsAsRebuffer
        return true
    }

    /** A spell in its grace becomes a rebuffer once the grace is over, snapshot or not. */
    fun tick(atMs: Long): List<PlaybackEvent> = listOfNotNull(graceExpired(atMs))

    fun map(snapshot: PlayerPlaybackSnapshot, atMs: Long): List<PlaybackEvent> {
        val events = mutableListOf<PlaybackEvent>()
        val position = snapshot.positionMs

        if (snapshot.playerCommandCount != lastCommandCount && firstFrame) startSettling()
        lastCommandCount = snapshot.playerCommandCount
        val previous = lastPositionMs
        if (previous != null && firstFrame) {
            val drift = (atMs - lastAtMs).coerceAtLeast(0L) * 2
            if (abs(position - previous) > SEEK_JUMP_MS + drift) startSettling()
        }
        lastPositionMs = position
        lastAtMs = atMs

        if (snapshot.isBuffering && !buffering) {
            buffering = true
            if (settling) {
                reportedBuffering = false
                graceUntilMs = atMs + SETTLE_GRACE_MS
                graceCountsAsRebuffer = false
            } else {
                reportedBuffering = true
                events += PlaybackEvent.BufferingStart(atMs)
            }
        } else if (snapshot.isBuffering) {
            graceExpired(atMs)?.let(events::add)
        } else if (buffering) {
            buffering = false
            if (reportedBuffering) events += PlaybackEvent.BufferingEnd(atMs)
            reportedBuffering = false
            graceUntilMs = null
        }

        if (settling && !snapshot.isBuffering) {
            val base = settleBaseMs
            when {
                base == null || position < base -> settleBaseMs = position
                position - base >= SETTLE_PLAYBACK_MS -> {
                    settling = false
                    settleBaseMs = null
                }
            }
        }

        if (snapshot.firstFrameRendered && !firstFrame) {
            firstFrame = true
            events += PlaybackEvent.FirstFrame(atMs)
        }
        return events
    }

    private fun graceExpired(atMs: Long): PlaybackEvent? {
        val graceEnd = graceUntilMs ?: return null
        if (!buffering || atMs < graceEnd) return null
        graceUntilMs = null
        reportedBuffering = true
        return PlaybackEvent.BufferingStart(graceEnd, countsAsRebuffer = graceCountsAsRebuffer)
    }

    private fun startSettling() {
        settling = true
        settleBaseMs = null
    }
}
