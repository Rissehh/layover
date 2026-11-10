package com.nuvio.tv.layover.playback

import com.nuvio.tv.layover.streams.isPlaceholderClip

/**
 * Watches one playback attempt: a [StallDetector] fed from player snapshots ([SnapshotSignals]),
 * plus the conditions under which the attempt must not be judged at all:
 *
 * - App in the background (stopped): ExoPlayer pauses without a surface, so a missing first frame
 *   says nothing about the stream. Ticks are ignored; coming back restarts the first-frame timeout.
 * - Offline (no network with internet capability): every stream would fail. Nothing is judged; an
 *   error while offline suspends the attempt (upstream's error screen stays up) until a player
 *   command (a seek, a track change) or a new surface. Coming back online restarts the timeout.
 * - Surface restart (refreshed link, engine switch): the open spell is closed and the new
 *   surface's own load isn't a rebuffer.
 * - Placeholder: a duration under 5 minutes on a stream claiming a real file's size (or named as
 *   a sample) is the addon's "still downloading" clip or the release's sample; the attempt fails
 *   as soon as the duration is known.
 *
 * A buffering spell crossing such a pause is closed when judging stops and reopened when it
 * resumes if the player is still buffering. The reopened spell gets the same 4 s grace as a
 * command-caused one ([SETTLE_GRACE_MS], the player refilling after the pause) and is then
 * reported from the grace end: as the same spell if it was being counted (or was itself an
 * uncounted, command-caused spell), as a new rebuffer if it began while nothing was judged.
 *
 * Every call returns the verdict to act on: [Verdict.Pending] whenever the attempt isn't being
 * judged. A verdict reached while not judged (a stall that ended as the app left) is returned once
 * judging resumes.
 */
class AttemptMonitor(
    private val firstFrameTimeoutMs: Long,
    startedAtMs: Long,
    /** The size the stream claims, for spotting a placeholder clip ([isPlaceholderClip]). */
    private val claimedSizeBytes: Long? = null,
    /** The stream is named as a sample: any short playback is that sample, whatever size it claims. */
    private val isSample: Boolean = false,
) {
    private val detector = StallDetector(firstFrameTimeoutMs)
    private val signals = SnapshotSignals()
    private var verdict = detector.on(PlaybackEvent.Started(startedAtMs))
    private var stopped = false
    private var online = true
    private var suspended = false
    private var waitingForRestartFrame = false
    /** When a surface restart after the first frame must have shown a frame again; null when not waiting. */
    private var restartDeadlineMs: Long? = null
    private var lastCommandCount = 0
    private var pausedSpell = false

    var firstFrameSeen = false
        private set

    /** The source turned out to be a "still downloading" clip: failed, and its position means nothing. */
    var placeholder = false
        private set

    /** Offline, or stuck on an error that happened offline: user actions here say nothing about the stream. */
    val offlineOrSuspended: Boolean
        get() = !online || suspended

    private val judging: Boolean
        get() = !stopped && online && !suspended

    fun onSnapshot(snapshot: PlayerPlaybackSnapshot, nowMs: Long): Verdict {
        if (snapshot.playerCommandCount != lastCommandCount) {
            lastCommandCount = snapshot.playerCommandCount
            if (suspended && online) {
                suspended = false
                restartTimeout(nowMs)
            }
        }
        signals.map(snapshot, nowMs).forEach(::route)
        if (judging && !placeholder && isPlaceholderClip(snapshot.durationMs, claimedSizeBytes, isSample)) {
            placeholder = true
            verdict = detector.on(PlaybackEvent.Error(nowMs))
        }
        return current()
    }

    fun onTick(nowMs: Long): Verdict {
        signals.tick(nowMs).forEach(::route)
        if (judging) verdict = detector.on(PlaybackEvent.Tick(nowMs))
        // A rebuilt surface (engine switch, rebuild after an error) that never shows a frame again would
        // otherwise wait forever: the detector's first-frame timeout ended with the first frame.
        val deadline = restartDeadlineMs
        if (judging && waitingForRestartFrame && deadline != null && nowMs >= deadline && verdict == Verdict.Pending) {
            verdict = Verdict.Stalled
        }
        return current()
    }

    fun onError(nowMs: Long): Verdict {
        if (!online) {
            suspended = true
            return Verdict.Pending
        }
        verdict = detector.on(PlaybackEvent.Error(nowMs))
        return current()
    }

    fun onStop(nowMs: Long): Verdict {
        if (judging) pauseSpell(nowMs)
        stopped = true
        return current()
    }

    fun onStart(nowMs: Long): Verdict {
        if (!stopped) return current()
        stopped = false
        if (judging) resume(nowMs)
        return current()
    }

    fun onConnectivity(online: Boolean, nowMs: Long): Verdict {
        if (online == this.online) return current()
        if (!online && judging) pauseSpell(nowMs)
        this.online = online
        if (judging) resume(nowMs)
        return current()
    }

    fun onSurfaceRestart(nowMs: Long): Verdict {
        if (signals.dropOpenSpell()) verdict = detector.on(PlaybackEvent.BufferingEnd(nowMs))
        signals.reset()
        pausedSpell = false
        lastCommandCount = 0
        waitingForRestartFrame = firstFrameSeen
        restartDeadlineMs = if (firstFrameSeen) nowMs + firstFrameTimeoutMs + RESTART_EXTRA_MS else null
        suspended = false
        restartTimeout(nowMs)
        return current()
    }

    private fun route(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.FirstFrame -> {
                firstFrameSeen = true
                waitingForRestartFrame = false
                restartDeadlineMs = null
                verdict = detector.on(event)
            }
            is PlaybackEvent.BufferingStart, is PlaybackEvent.BufferingEnd -> when {
                waitingForRestartFrame -> Unit
                judging -> verdict = detector.on(event)
                event is PlaybackEvent.BufferingStart -> {
                    // Reported while nothing is judged: forget it now, reopen it on resume if it's still
                    // going, as new unless it was an uncounted (command-caused) spell.
                    signals.dropOpenSpell()
                    pausedSpell = !event.countsAsRebuffer
                }
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun current(): Verdict = if (judging) verdict else Verdict.Pending

    /** Fresh first-frame timeout; the detector ignores this once a frame was shown. */
    private fun restartTimeout(nowMs: Long) {
        verdict = detector.on(PlaybackEvent.Started(nowMs))
    }

    /** Judging stops: close a counted spell in the detector, remembering it may continue. */
    private fun pauseSpell(nowMs: Long) {
        val uncountedGrace = signals.inUncountedGrace
        if (signals.dropOpenSpell()) {
            verdict = detector.on(PlaybackEvent.BufferingEnd(nowMs))
            pausedSpell = true
        } else {
            pausedSpell = uncountedGrace
        }
    }

    /**
     * Judging resumes: fresh timeout before a first frame, and a spell that is still going gets a
     * grace, then is reported (from [onTick] or the next snapshot) as continuing or as new.
     */
    private fun resume(nowMs: Long) {
        restartTimeout(nowMs)
        if (waitingForRestartFrame) restartDeadlineMs = nowMs + firstFrameTimeoutMs + RESTART_EXTRA_MS
        signals.reopenSpell(nowMs, countsAsRebuffer = !pausedSpell)
        pausedSpell = false
    }
}

/**
 * A surface rebuilt after the first frame gets the first-frame timeout plus this much to show a frame
 * again (reopening the link, seeking back to the position) before the attempt counts as stalled.
 */
internal const val RESTART_EXTRA_MS = 10_000L
