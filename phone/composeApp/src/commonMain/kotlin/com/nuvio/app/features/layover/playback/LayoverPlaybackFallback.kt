package com.nuvio.app.features.layover.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.layover.LayoverSettingsRepository
import com.nuvio.app.features.layover.currentNetworkQuality
import com.nuvio.app.features.layover.fallbackPolicy
import com.nuvio.app.features.layover.log.AttemptLog
import com.nuvio.app.features.layover.log.layoverContentKey
import com.nuvio.app.features.layover.rank.TrackRecord
import com.nuvio.app.features.layover.streams.facts
import com.nuvio.app.features.layover.streams.isSample
import com.nuvio.app.features.player.PlaybackKey
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.PlayerStreamsRepository
import com.nuvio.app.features.player.activePlaybackKey
import com.nuvio.app.features.player.openSourcesPanel
import com.nuvio.app.features.player.switchToSource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.isIos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val TRYING_ANOTHER_SOURCE = "Trying another source"
internal const val NEXT_SOURCE_LABEL = "Next source"

private const val TICK_MS = 1_000L
private const val COMPLETED_FRACTION = 0.9

/**
 * Connects one player screen to the fallback engine. A "chain" starts whenever a playable http
 * stream starts (auto-picked, tapped on the streams screen, picked in the sources panel, or the
 * next episode) and walks the ranked candidates for that title. Every attempt runs a fresh
 * [AttemptMonitor]; Failed or Stalled switches to the next candidate with a "Trying another
 * source" toast, and running out opens the sources panel.
 *
 * Not part of the chain: local files (downloads), torrents, media-server items, iOS for now, and
 * anything while fallback is off in Layover settings (then the player behaves exactly like upstream).
 *
 * Same attempt, not a new one: the Auto engine swapping ExoPlayer for libmpv (it reports no error,
 * so the monitor just keeps running), upstream refreshing an expired link (the refreshed URL
 * continues the attempt with a restarted first-frame timeout), and a manual engine switch.
 *
 * Not judged: while the app is in the background, and while the device has no network with
 * internet capability (see [AttemptMonitor]); offline errors are left to upstream's error screen.
 * On a network that has internet capability but isn't validated (captive portal, in-flight Wi-Fi)
 * streams are judged and switched as usual, but no attempt records are written.
 */
internal class LayoverPlaybackFallback(private val runtime: PlayerScreenRuntime) {
    private val clock: () -> Long = { DownloadsClock.nowEpochMs() }
    /** Re-read on every tick, error and attempt start; false on a network that isn't validated. */
    private var recordAttempts = true

    private var controller: FallbackController? = null
    private var monitor: AttemptMonitor? = null
    private var chainContentKey: String? = null
    private var attemptUrl: String? = null
    private var attemptKey: PlaybackKey? = null
    private var attemptResumeMs = 0L
    private var attemptResumeFraction: Float? = null
    private var completedSent = false
    private var refreshClaimed = false
    private var appStarted = true

    private var latestSourceStreams: List<StreamItem> = emptyList()
    private var latestEpisodeStreams: List<StreamItem> = emptyList()

    /** True while a chain runs; the player shows "Next source" then. */
    var active by mutableStateOf(false)
        private set

    /** The player's active source changed (new key). Decide: same attempt, or a new chain. */
    fun onSourceChanged() {
        val url = runtime.activeSourceUrl
        val c = controller
        if (c != null && contentKey() == chainContentKey) {
            if (url == attemptUrl) {
                restartSameAttempt()
                return
            }
            if (refreshClaimed) {
                attemptUrl = url
                restartSameAttempt()
                return
            }
            // The user picked another source of the same title in the panel: judged like "Next source".
            networkJudges()
            c.userSwitched(logOutcome = monitor?.offlineOrSuspended != true)
        }
        endChain()
        startChain()
    }

    fun onSnapshot(snapshot: PlayerPlaybackSnapshot, key: PlaybackKey) {
        val m = monitor ?: return
        if (key != attemptKey) return
        val now = clock()
        val hadFrame = m.firstFrameSeen
        val verdict = m.onSnapshot(snapshot, now)
        if (!hadFrame && m.firstFrameSeen) controller?.onFirstFrame()
        if (act(verdict)) return
        val duration = snapshot.durationMs
        if (m.firstFrameSeen && !completedSent && duration > 0 && snapshot.positionMs >= duration * COMPLETED_FRACTION) {
            completedSent = true
            controller?.onCompleted()
        }
    }

    /** From the player's onError, after upstream's credential refresh declined it. True = handled. */
    fun takeOverError(key: PlaybackKey): Boolean {
        val m = monitor ?: return false
        if (key != attemptKey) return false
        val now = clock()
        m.onConnectivity(networkJudges(), now)
        return act(m.onError(now))
    }

    /** Upstream is refreshing the link of the current attempt; its new URL continues the attempt. */
    fun onCredentialRefreshClaimed(key: PlaybackKey) {
        if (key == attemptKey) refreshClaimed = true
    }

    /** "Next source" button. With nothing left it just opens the sources panel, logging nothing. */
    fun nextSource() {
        val c = controller ?: return
        if (!c.hasNext) {
            runtime.openSourcesPanel()
            return
        }
        val framed = monitor?.showedTitle() == true
        c.userSwitched(logOutcome = monitor?.offlineOrSuspended != true)?.let { switchTo(it, automatic = false, framed = framed) }
    }

    fun endChain() {
        if (controller != null) networkJudges() // refresh recordAttempts for the closing record
        controller?.close()
        controller = null
        monitor = null
        chainContentKey = null
        attemptUrl = null
        attemptKey = null
        refreshClaimed = false
        active = false
    }

    internal fun tick() {
        val m = monitor ?: return
        if (runtime.credentialRefreshJob?.isActive == true) return
        if (runtime.activePlaybackKey != attemptKey) return
        val now = clock()
        if (act(m.onConnectivity(networkJudges(), now))) return
        act(m.onTick(now))
    }

    /** Errors that reach the screen another way (a failed credential refresh). */
    internal fun onErrorShown() {
        val m = monitor ?: return
        if (runtime.activePlaybackKey != attemptKey) return
        val now = clock()
        m.onConnectivity(networkJudges(), now)
        act(m.onError(now))
    }

    /** App moved to or from the background (lifecycle STARTED). */
    internal fun onAppStartedChanged(started: Boolean) {
        appStarted = started
        val m = monitor ?: return
        val now = clock()
        act(if (started) m.onStart(now) else m.onStop(now))
    }

    internal fun rememberSourceStreams(streams: List<StreamItem>) {
        if (streams.isNotEmpty()) latestSourceStreams = streams
    }

    internal fun rememberEpisodeStreams(streams: List<StreamItem>) {
        if (streams.isNotEmpty()) latestEpisodeStreams = streams
    }

    /** Reads the network once: returns whether to judge, and updates whether records are written. */
    private fun networkJudges(): Boolean {
        val policy = currentNetworkQuality().fallbackPolicy()
        recordAttempts = policy.record
        return policy.judge
    }

    private fun contentKey(): String =
        layoverContentKey(runtime.parentMetaId, runtime.activeSeasonNumber, runtime.activeEpisodeNumber)

    private fun startChain() {
        // The iOS player doesn't report first frame / buffering yet; every attempt would time out there.
        if (isIos) return
        val settings = LayoverSettingsRepository.state.value
        if (!settings.fallbackEnabled) return
        val stream = runtime.activeStream ?: return
        val url = runtime.activeSourceUrl
        if (runtime.activeTorrentInfoHash != null) return
        if (!stream.isFallbackPlayable() || stream.playableDirectUrl != url) return

        val key = contentKey()
        val pool = LayoverPlaybackCandidates.take(key)
            ?: listOf(latestSourceStreams, latestEpisodeStreams).firstOrNull { streams ->
                streams.any { it.playableDirectUrl == url }
            }
            ?: emptyList()
        controller = FallbackController(
            candidates = fallbackCandidates(pool, started = stream, score = TrackRecord::score),
            settings = settings,
            contentKey = key,
            log = { if (recordAttempts) AttemptLog.add(it) },
            clock = clock,
            replace = { old, new -> if (recordAttempts) AttemptLog.replace(old, new) },
        )
        chainContentKey = key
        beginAttempt(url, stream, runtime.activeInitialPositionMs, runtime.activeInitialProgressFraction)
        active = true
    }

    private fun beginAttempt(url: String, stream: StreamItem, resumeMs: Long, resumeFraction: Float?) {
        val now = clock()
        val m = AttemptMonitor(
            LayoverSettingsRepository.state.value.firstFrameTimeoutMs,
            startedAtMs = now,
            claimedSizeBytes = stream.facts().sizeBytes,
            isSample = stream.isSample(),
        )
        m.onConnectivity(networkJudges(), now)
        if (!appStarted) m.onStop(now)
        monitor = m
        attemptUrl = url
        attemptKey = runtime.activePlaybackKey
        attemptResumeMs = resumeMs
        attemptResumeFraction = resumeFraction
        completedSent = false
        refreshClaimed = false
    }

    private fun restartSameAttempt() {
        refreshClaimed = false
        attemptKey = runtime.activePlaybackKey
        monitor?.let { act(it.onSurfaceRestart(clock())) }
    }

    /** Acts on a verdict. True when the player was switched away. */
    private fun act(verdict: Verdict): Boolean {
        val c = controller ?: return false
        return when (verdict) {
            Verdict.Pending -> false
            Verdict.Healthy -> {
                c.onVerdict(verdict)
                false
            }
            Verdict.Failed, Verdict.Stalled -> {
                val framed = monitor?.showedTitle() == true
                monitor = null
                val next = c.onVerdict(verdict)
                if (next != null) {
                    switchTo(next, automatic = true, framed = framed)
                    true
                } else {
                    runtime.openSourcesPanel()
                    false
                }
            }
        }
    }

    /** [framed]: the attempt being left showed the title (a first frame, and not a placeholder clip). */
    private fun switchTo(next: StreamItem, automatic: Boolean, framed: Boolean) {
        val url = next.playableDirectUrl ?: return
        // Before the first frame (or in a placeholder clip) the position is meaningless; keep the attempt's resume point.
        val resumeMs = if (framed) runtime.playbackSnapshot.positionMs.coerceAtLeast(0L) else attemptResumeMs
        val resumeFraction = if (framed) null else attemptResumeFraction
        runtime.switchToSource(next)
        runtime.activeInitialPositionMs = resumeMs
        runtime.activeInitialProgressFraction = resumeFraction
        beginAttempt(url, next, resumeMs, resumeFraction)
        if (automatic) NuvioToastController.show(TRYING_ANOTHER_SOURCE)
    }
}

@Composable
internal fun LayoverPlaybackFallback.BindEffects(runtime: PlayerScreenRuntime) {
    LaunchedEffect(runtime.activePlaybackKey) { onSourceChanged() }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val appStarted = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(appStarted) { onAppStartedChanged(appStarted) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TICK_MS)
            tick()
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { runtime.errorMessage }.collect { if (it != null) onErrorShown() }
    }
    LaunchedEffect(Unit) {
        PlayerStreamsRepository.sourceState.collect { state -> rememberSourceStreams(state.groups.flatMap { it.streams }) }
    }
    LaunchedEffect(Unit) {
        PlayerStreamsRepository.episodeStreamsState.collect { state -> rememberEpisodeStreams(state.groups.flatMap { it.streams }) }
    }
    DisposableEffect(Unit) {
        onDispose { endChain() }
    }
}

/** The attempt played the title itself: a first frame that wasn't a placeholder clip. */
private fun AttemptMonitor.showedTitle(): Boolean = firstFrameSeen && !placeholder
