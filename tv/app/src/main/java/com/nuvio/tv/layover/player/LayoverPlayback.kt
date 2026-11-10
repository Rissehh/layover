package com.nuvio.tv.layover.player

import android.util.Log
import android.widget.Toast
import androidx.media3.common.Player
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.layover.LayoverNetwork
import com.nuvio.tv.layover.LayoverSettings
import com.nuvio.tv.layover.LayoverSettingsRepository
import com.nuvio.tv.layover.fallbackPolicy
import com.nuvio.tv.layover.log.AttemptLog
import com.nuvio.tv.layover.log.layoverContentKey
import com.nuvio.tv.layover.playback.AttemptMonitor
import com.nuvio.tv.layover.playback.FallbackController
import com.nuvio.tv.layover.playback.PlayerPlaybackSnapshot
import com.nuvio.tv.layover.playback.Verdict
import com.nuvio.tv.layover.streams.fallbackCandidates
import com.nuvio.tv.layover.rank.TrackRecord
import com.nuvio.tv.layover.streams.facts
import com.nuvio.tv.layover.streams.isPlaceholderClip
import com.nuvio.tv.layover.streams.isSample
import com.nuvio.tv.layover.streams.rankCandidates
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.buildSourceRequestKey
import com.nuvio.tv.ui.screens.player.currentPlaybackDurationMs
import com.nuvio.tv.ui.screens.player.currentPlaybackPositionMs
import com.nuvio.tv.ui.screens.player.isUsingMpvEngine
import com.nuvio.tv.ui.screens.player.loadSourceStreams
import com.nuvio.tv.ui.screens.player.showSourcesPanel
import com.nuvio.tv.ui.screens.player.switchToSourceStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_MS = 250L
private const val COMPLETED_FRACTION = 0.9

/**
 * How long an attempt may wait for its engine to exist (frame-rate matching, AFR display switch,
 * mpv surface) before the first-frame timeout starts anyway.
 */
private const val ENGINE_WAIT_MS = 30_000L

private const val TAG = "LayoverPlayback"

/**
 * Connects one NuvioTV player to Layover's fallback engine (the TV port of the phone's
 * `LayoverPlaybackFallback`). It reads the player every 250 ms instead of hooking each engine
 * callback, so ExoPlayer and libmpv are judged the same way:
 *
 * - first frame: `hasRenderedFirstFrame` (ExoPlayer onRenderedFirstFrame or the tunneled READY;
 *   libmpv's progress loop once the position moves),
 * - buffering: ExoPlayer STATE_BUFFERING, libmpv `paused-for-cache`,
 * - position and duration from the active engine, errors from the player's fatal `error`
 *   (set only after upstream's own codec fallbacks, engine switch and same-link retries gave up),
 * - background from `isInBackground`, connectivity from [LayoverNetwork],
 * - player commands (seek, track changes) counted from [onPlayerEvent].
 *
 * A chain starts whenever the player plays a plain http(s) link of a title (autoplay, a pick on
 * the streams screen, a reused last link, a source picked in the panel, the next episode) and
 * walks the ranked candidates for that title. Failed or Stalled switches to the next candidate
 * through upstream's own source switch with a "Trying another source" toast; running out opens
 * the Sources panel instead of the error screen. Torrents, media-server items, cloud-library
 * files and everything while fallback is off in Layover settings behave exactly like upstream.
 */
class LayoverPlayback(private val player: PlayerRuntimeController) {
    private val clock: () -> Long = { System.currentTimeMillis() }

    private var chain: FallbackController? = null
    private var monitor: AttemptMonitor? = null
    private var chainContentKey: String? = null
    private var attemptStream: Stream? = null
    private var attemptUrl: String? = null
    private var attemptStartedAt = 0L
    /** Where this attempt was told to start; null = upstream's saved progress. */
    private var attemptResumeMs: Long? = null
    private var completedSent = false
    private var errorSeen = false
    private var placeholderAttempt = false
    private var recordAttempts = true
    private var lastEngine: InternalPlayerEngine? = null
    private var lastFirstFrame = false
    private var lastBackground = false
    private var commandCount = 0
    private var lastBuffering = false
    private var healthyLogged = false
    private var poolSource: List<Stream>? = null
    private var pendingResume: PendingResume? = null

    private class PendingResume(val positionMs: Long?)

    private val _active = MutableStateFlow(false)

    /** True while a chain runs; the player shows "Next source" then. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _hidesErrorOverlay = MutableStateFlow(false)

    /** True while a fatal error will be answered by switching source (or the Sources panel). */
    val hidesErrorOverlay: StateFlow<Boolean> = _hidesErrorOverlay.asStateFlow()

    init {
        player.scope.launch {
            while (isActive) {
                delay(POLL_MS)
                runCatching { poll() }.onFailure { if (isActive) Log.w(TAG, "poll failed", it) }
            }
        }
    }

    /** Hook from `onEvent`: commands that can make the player buffer, so it isn't judged as a stall. */
    fun onPlayerEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.OnSeekBy,
            is PlayerEvent.OnSeekTo,
            PlayerEvent.OnCommitPreviewSeek,
            is PlayerEvent.OnSkipChapter,
            is PlayerEvent.OnSelectChapter,
            is PlayerEvent.OnSelectAudioTrack,
            is PlayerEvent.OnSetAudioDelayMs,
            is PlayerEvent.OnSelectSubtitleTrack,
            PlayerEvent.OnDisableSubtitles,
            is PlayerEvent.OnSelectAddonSubtitle,
            is PlayerEvent.OnAdjustSubtitleDelay,
            is PlayerEvent.OnResetSubtitleDelay,
            is PlayerEvent.OnApplySubtitleAutoSyncCue,
            is PlayerEvent.OnSetPlaybackSpeed,
            PlayerEvent.OnSkipIntro,
            PlayerEvent.OnRetry -> commandCount++
            else -> Unit
        }
    }

    /** "Next source" button. With nothing left it opens the Sources panel, logging nothing. */
    fun nextSource() {
        val c = chain
        if (c == null || !c.hasNext) {
            player.showSourcesPanel()
            return
        }
        val framed = monitor?.showedTitle() == true
        val next = c.userSwitched(logOutcome = !offline())
        monitor = null
        if (next == null) {
            player.showSourcesPanel()
            return
        }
        switchTo(next, automatic = false, framed = framed)
    }

    /**
     * Hook at the end of `switchToSourceStream`: a Layover switch sets its own resume point.
     * True = handled, so upstream must not load the saved progress.
     */
    fun applyPendingResume(): Boolean {
        val position = pendingResume?.positionMs ?: return false
        pendingResume = null
        player.pendingResumeProgress = if (position > 0L) resumeProgress(position) else null
        player._uiState.update { it.copy(pendingSeekPosition = null) }
        return true
    }

    /**
     * Hook in `saveWatchProgressInternal`: a "still downloading" clip or a sample must not
     * overwrite the title's progress (or mark it watched).
     */
    fun allowsProgressSave(): Boolean {
        if (chain == null) return true
        if (placeholderAttempt || monitor?.placeholder == true) return false
        val stream = attemptStream ?: return true
        return !isPlaceholderClip(player.currentPlaybackDurationMs(), claimedSize(stream), stream.isSample())
    }

    /** The player is going away. */
    fun close() {
        endChain()
    }

    private suspend fun poll() {
        val settings = LayoverSettingsRepository.state.value
        if (!settings.fallbackEnabled) {
            if (chain != null) endChain()
            return
        }
        if (player.isReleasingPlayer) return
        val url = player.currentStreamUrl
        val key = contentKey()
        val c = chain
        if (c != null) {
            if (key != chainContentKey) {
                endChain()
            } else if (url != attemptUrl) {
                // Another source of the same title was picked in the panel: judged like "Next source".
                c.userSwitched(logOutcome = !offline())
                endChain()
            }
        }
        if (chain == null && !startChain(url, key, settings)) return
        refreshPool()
        watchAttempt(settings)
        publishFlags()
    }

    private suspend fun startChain(url: String, key: String, settings: LayoverSettings): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        if (player.isTorrentStream || PlayerMediaSourceFactory.isLoopbackUrl(url)) return false
        if (player.contentId.isNullOrBlank() || player.contentType.equals("cloud", ignoreCase = true)) return false
        val pool = currentPool()
        val started = pool.firstOrNull { playbackUrlOf(it) == url } ?: currentStreamAsStream(url)
        if (started.serverTarget != null) return false
        chain = FallbackController(
            candidates = fallbackCandidates(pool, started, TrackRecord::score),
            settings = settings,
            contentKey = key,
            log = { if (recordAttempts) AttemptLog.add(it) },
            clock = clock,
            replace = { old, new -> if (recordAttempts) AttemptLog.replace(old, new) },
        )
        chainContentKey = key
        poolSource = null
        Log.i(TAG, "chain for $key: ${pool.size} in the pool, starting on ${started.addonName} / ${started.name}")
        beginAttempt(started, url, resumeMs = null)
        _active.value = true
        // The panel's list doubles as the candidate pool; it joins the streams screen's search when cached.
        fetchPoolIfNeeded()
        return true
    }

    private fun beginAttempt(stream: Stream, url: String, resumeMs: Long?) {
        attemptStream = stream
        attemptUrl = url
        attemptResumeMs = resumeMs
        attemptStartedAt = clock()
        monitor = null
        completedSent = false
        errorSeen = false
        placeholderAttempt = false
        commandCount = 0
        lastEngine = null
        lastFirstFrame = false
        lastBuffering = false
        healthyLogged = false
    }

    private fun watchAttempt(settings: LayoverSettings) {
        val c = chain ?: return
        if (c.exhausted) return
        val stream = attemptStream ?: return
        val now = clock()
        val policy = LayoverNetwork.quality().fallbackPolicy()
        recordAttempts = policy.record
        val hasError = player._uiState.value.error != null

        var m = monitor
        if (m == null) {
            // The first-frame timeout starts once the engine exists (after any display-mode switch).
            if (!engineReady() && !hasError && now - attemptStartedAt < ENGINE_WAIT_MS) return
            m = AttemptMonitor(
                settings.firstFrameTimeoutMs,
                startedAtMs = now,
                claimedSizeBytes = claimedSize(stream),
                isSample = stream.isSample(),
            )
            m.onConnectivity(policy.judge, now)
            lastBackground = player.isInBackground
            if (lastBackground) m.onStop(now)
            lastEngine = player.currentInternalPlayerEngine
            lastFirstFrame = player.hasRenderedFirstFrame
            monitor = m
            Log.i(TAG, "judging ${stream.addonName} / ${stream.name} (engine $lastEngine)")
        }

        val background = player.isInBackground
        if (background != lastBackground) {
            lastBackground = background
            if (act(if (background) m.onStop(now) else m.onStart(now))) return
        }
        if (act(m.onConnectivity(policy.judge, now))) return

        // Same attempt, new surface: the engine switch (ExoPlayer <-> libmpv) or a rebuild after an error.
        val engine = player.currentInternalPlayerEngine
        val frame = player.hasRenderedFirstFrame
        if (engine != lastEngine || (lastFirstFrame && !frame)) {
            Log.i(TAG, "surface restart (engine $lastEngine -> $engine, frame $lastFirstFrame -> $frame)")
            lastEngine = engine
            lastFirstFrame = frame
            commandCount = 0
            if (act(m.onSurfaceRestart(now))) return
        }
        lastFirstFrame = frame

        if (hasError && !errorSeen) {
            errorSeen = true
            if (act(m.onError(now))) return
        } else if (!hasError) {
            errorSeen = false
        }

        val snapshot = snapshot()
        val hadFrame = m.firstFrameSeen
        val verdict = m.onSnapshot(snapshot, now)
        if (!hadFrame && m.firstFrameSeen) {
            Log.i(TAG, "first frame after ${now - attemptStartedAt} ms")
            c.onFirstFrame()
        }
        if (snapshot.isBuffering != lastBuffering) {
            lastBuffering = snapshot.isBuffering
            Log.d(TAG, "buffering=${snapshot.isBuffering} at ${snapshot.positionMs} ms")
        }
        if (m.placeholder) placeholderAttempt = true
        if (act(verdict)) return
        if (act(m.onTick(now))) return

        val duration = snapshot.durationMs
        if (m.firstFrameSeen && !completedSent && duration > 0 && snapshot.positionMs >= duration * COMPLETED_FRACTION) {
            completedSent = true
            c.onCompleted()
        }
    }

    /** Acts on a verdict. True when the attempt ended (switched away, or out of candidates). */
    private fun act(verdict: Verdict): Boolean {
        val c = chain ?: return false
        return when (verdict) {
            Verdict.Pending -> false
            Verdict.Healthy -> {
                if (!healthyLogged) Log.i(TAG, "healthy")
                healthyLogged = true
                c.onVerdict(verdict)
                false
            }
            Verdict.Failed, Verdict.Stalled -> {
                Log.i(TAG, "verdict $verdict for ${attemptStream?.addonName} / ${attemptStream?.name}")
                val m = monitor
                val framed = m?.showedTitle() == true
                if (m?.placeholder == true) placeholderAttempt = true
                monitor = null
                val next = c.onVerdict(verdict)
                if (next != null) switchTo(next, automatic = true, framed = framed) else outOfSources()
                true
            }
        }
    }

    /** [framed]: the attempt being left showed the title (a first frame, and not a placeholder clip). */
    private fun switchTo(next: Stream, automatic: Boolean, framed: Boolean) {
        Log.i(TAG, "switching to ${next.addonName} / ${next.name} (automatic=$automatic, framed=$framed)")
        // Before the first frame (or in a placeholder clip) the position means nothing; keep the attempt's resume point.
        val resumeMs = if (framed) player.currentPlaybackPositionMs()?.coerceAtLeast(0L) else attemptResumeMs
        pendingResume = PendingResume(resumeMs)
        player.switchToSourceStream(next)
        pendingResume = null
        beginAttempt(next, player.currentStreamUrl, resumeMs)
        if (automatic) {
            Toast.makeText(player.context, R.string.layover_trying_another_source, Toast.LENGTH_SHORT).show()
        }
        fetchPoolIfNeeded()
        publishFlags()
    }

    /** Every candidate failed: the Sources panel instead of the error screen. */
    private fun outOfSources() {
        Log.i(TAG, "out of candidates, opening the sources panel")
        if (player._uiState.value.error != null) {
            player._uiState.update { it.copy(error = null, showSwitchToMpvErrorAction = false) }
        }
        publishFlags()
        player.showSourcesPanel()
    }

    private fun endChain() {
        chain?.let {
            recordAttempts = LayoverNetwork.quality().fallbackPolicy().record
            it.close()
        }
        chain = null
        monitor = null
        chainContentKey = null
        attemptStream = null
        attemptUrl = null
        poolSource = null
        placeholderAttempt = false
        publishFlags()
    }

    private fun publishFlags() {
        val c = chain
        _active.value = c != null
        _hidesErrorOverlay.value = c != null && !c.exhausted && monitor?.offlineOrSuspended != true &&
            LayoverNetwork.quality().fallbackPolicy().judge
    }

    /** New results arrived in the panel's list: re-rank the candidates not tried yet. */
    private fun refreshPool() {
        val c = chain ?: return
        if (c.exhausted) return
        if (player.sourceStreamsCacheRequestKey != sourceRequestKey()) return
        val pool = player._uiState.value.sourceAllStreams
        if (pool.isEmpty() || pool === poolSource) return
        poolSource = pool
        c.refresh(rankCandidates(pool, TrackRecord::score))
    }

    private fun fetchPoolIfNeeded() {
        val requestKey = sourceRequestKey() ?: return
        if (player.sourceStreamsCacheRequestKey == requestKey && player.sourceStreamsFetchCompleted) return
        player.loadSourceStreams(forceRefresh = false)
    }

    private suspend fun currentPool(): List<Stream> {
        val state = player._uiState.value
        if (player.sourceStreamsCacheRequestKey == sourceRequestKey() && state.sourceAllStreams.isNotEmpty()) {
            return state.sourceAllStreams
        }
        val request = sourceRequest() ?: return emptyList()
        return runCatching {
            player.streamRepository.getCachedStreamsFromAllAddons(
                type = request.type,
                videoId = request.videoId,
                season = request.season,
                episode = request.episode,
            )
        }.getOrNull()?.flatMap { it.streams }.orEmpty()
    }

    private class SourceRequest(val type: String, val videoId: String, val season: Int?, val episode: Int?)

    /** Same request the Sources panel makes (upstream's private `currentSourceStreamsRequest`). */
    private fun sourceRequest(): SourceRequest? {
        val type = player.contentType
        val season = player.currentSeason
        val episode = player.currentEpisode
        return if (type in listOf("series", "tv") && season != null && episode != null) {
            SourceRequest(type!!, player.currentVideoId ?: player.contentId ?: return null, season, episode)
        } else {
            SourceRequest(type ?: "movie", player.contentId ?: return null, null, null)
        }
    }

    private fun sourceRequestKey(): String? = sourceRequest()?.let {
        player.buildSourceRequestKey(type = it.type, videoId = it.videoId, season = it.season, episode = it.episode)
    }

    private fun contentKey(): String =
        layoverContentKey(player.contentId.orEmpty(), player.currentSeason, player.currentEpisode)

    private fun engineReady(): Boolean =
        player._exoPlayer != null || (player.isUsingMpvEngine() && player.mpvMediaLoadPrepared)

    private fun snapshot(): PlayerPlaybackSnapshot {
        val buffering = if (player.isUsingMpvEngine()) {
            runCatching { player.mpvView?.isPausedForCacheNow() }.getOrNull() == true
        } else {
            player._exoPlayer?.playbackState == Player.STATE_BUFFERING
        }
        return PlayerPlaybackSnapshot(
            positionMs = player.currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L,
            durationMs = player.currentPlaybackDurationMs().coerceAtLeast(0L),
            firstFrameRendered = player.hasRenderedFirstFrame,
            isBuffering = buffering,
            playerCommandCount = commandCount,
        )
    }

    private fun offline(): Boolean =
        monitor?.offlineOrSuspended == true || !LayoverNetwork.quality().fallbackPolicy().judge

    private fun claimedSize(stream: Stream): Long? = stream.facts().sizeBytes ?: player.currentVideoSize

    /** The stream the player opened, rebuilt from its own fields when the list doesn't have it (a reused link). */
    private fun currentStreamAsStream(url: String): Stream = Stream(
        name = player._uiState.value.currentStreamName,
        title = null,
        description = player.currentStreamDescription,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = player.currentStreamBingeGroup,
            countryWhitelist = null,
            proxyHeaders = null,
            videoHash = player.currentVideoHash,
            videoSize = player.currentVideoSize,
            filename = player.currentFilename,
        ),
        addonName = player.currentAddonName.orEmpty(),
        addonLogo = player.currentAddonLogo,
    )

    private fun resumeProgress(positionMs: Long): WatchProgress = WatchProgress(
        contentId = player.contentId.orEmpty(),
        contentType = player.contentType.orEmpty(),
        name = player.contentName ?: player.title,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = player.currentVideoId ?: player.contentId.orEmpty(),
        season = player.currentSeason,
        episode = player.currentEpisode,
        episodeTitle = player.currentEpisodeTitle,
        position = positionMs,
        duration = 0L,
        lastWatched = clock(),
    )
}

/** The link the player ends up opening for [stream] (upstream strips user-info into headers). */
internal fun playbackUrlOf(stream: Stream): String? {
    val url = stream.url ?: return null
    return runCatching { PlayerMediaSourceFactory.normalizePlaybackRequest(url, null).url }.getOrDefault(url)
}

/** The attempt played the title itself: a first frame that wasn't a placeholder clip. */
private fun AttemptMonitor.showedTitle(): Boolean = firstFrameSeen && !placeholder
