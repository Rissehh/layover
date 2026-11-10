package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.sanitizeRequestHeaders
import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.LayoverSettingsRepository
import com.nuvio.app.features.layover.log.AttemptLog
import com.nuvio.app.features.layover.log.AttemptOutcome
import com.nuvio.app.features.layover.log.AttemptRecord
import com.nuvio.app.features.layover.log.attemptRecordForDownload
import com.nuvio.app.features.layover.rank.TrackRecord
import com.nuvio.app.features.layover.streams.LayoverStreamSource
import com.nuvio.app.features.layover.streams.StreamFetchResult
import com.nuvio.app.features.layover.streams.layoverAddonsSettled
import com.nuvio.app.features.layover.streams.isDownloadable
import com.nuvio.app.features.layover.streams.pickDownloadCandidates
import com.nuvio.app.features.layover.streams.refind
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val SOURCE_GONE_MESSAGE = "Source gone. Tap retry to pick another."
internal const val FINDING_SOURCE_MESSAGE = "Download interrupted, finding the file again"
const val ADDONS_UNREACHABLE_MESSAGE = "Couldn't reach your addons. Tap retry."

/** Automatic recoveries per download, shared with the Ready check's one clean retry. */
internal const val MAX_RECOVERY_ATTEMPTS = 2

private const val ADDONS_READY_TIMEOUT_MS = 30_000L

sealed interface RecoveryPlan {
    /** The same file is still offered: swap in its fresh link and resume from the bytes on disk. */
    data class SameFile(val newUrl: String, val headers: Map<String, String>) : RecoveryPlan

    /** The file is gone: download this stream instead (starts from zero). */
    data class OtherFile(val stream: StreamItem) : RecoveryPlan

    data object GiveUp : RecoveryPlan
}

/**
 * What to do after a download failed for good. Pure given [streams] (fresh from the addons) and [score].
 * The same file wins, even when the addon hands back the same resolve url (it mints a new debrid link
 * per request). Otherwise the picker's best stream that isn't the failed file.
 */
suspend fun planRecovery(
    item: DownloadItem,
    streams: List<StreamItem>,
    settings: LayoverSettings,
    score: (StreamItem) -> Double = TrackRecord::score,
): RecoveryPlan {
    if (item.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return RecoveryPlan.GiveUp
    val identity = item.streamIdentity ?: return RecoveryPlan.GiveUp
    refind(identity, streams)?.sameFilePlan()?.let { return it }
    return planOtherFile(item, streams, settings, score)
}

/**
 * The picker's best stream that isn't [item]'s file, with no attempt cap. For Retry on a download whose
 * file failed the Ready check twice: the same file would fail again.
 */
internal fun planOtherFile(
    item: DownloadItem,
    streams: List<StreamItem>,
    settings: LayoverSettings,
    score: (StreamItem) -> Double = TrackRecord::score,
): RecoveryPlan =
    pickDownloadCandidates(streams, item.isEpisode, settings, score)
        .firstOrNull { !it.isSameFileAs(item) }
        ?.let { RecoveryPlan.OtherFile(it) }
        ?: RecoveryPlan.GiveUp

/** Retry tapped on a download recovery gave up on: the picker's best stream, the old file allowed. */
internal fun planManualRetry(
    item: DownloadItem,
    streams: List<StreamItem>,
    settings: LayoverSettings,
    score: (StreamItem) -> Double = TrackRecord::score,
): RecoveryPlan {
    val best = pickDownloadCandidates(streams, item.isEpisode, settings, score).firstOrNull() ?: return RecoveryPlan.GiveUp
    val identity = item.streamIdentity
    if (identity != null && refind(identity, listOf(best)) != null) best.sameFilePlan()?.let { return it }
    return RecoveryPlan.OtherFile(best)
}

private fun StreamItem.sameFilePlan(): RecoveryPlan.SameFile? {
    if (!isDownloadable()) return null
    val url = playableDirectUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return RecoveryPlan.SameFile(url, behaviorHints.proxyHeaders?.request.orEmpty())
}

/** The failed file under any name it could come back as: same identity, same filename (any addon), same url. */
private fun StreamItem.isSameFileAs(item: DownloadItem): Boolean {
    val identity = item.streamIdentity
    if (identity != null && refind(identity, listOf(this)) != null) return true
    if (identity?.filename != null && behaviorHints.filename == identity.filename) return true
    return playableDirectUrl?.trim() == item.sourceUrl.trim()
}

/** Which downloads a reconnect should recover again: waiting on unreachable addons, and not already recovering. */
internal fun unreachableToRetry(items: List<DownloadItem>, inFlight: Set<String>): List<DownloadItem> =
    items.filter {
        it.status == DownloadStatus.Failed &&
            it.errorMessage == ADDONS_UNREACHABLE_MESSAGE &&
            it.streamIdentity != null &&
            it.id !in inFlight
    }

/**
 * Re-resolves expired links. When a transfer fails for good (debrid link expired overnight), asks the addons
 * again: same file -> resume from the .part bytes with the fresh link; file gone -> next best stream;
 * nothing -> "Source gone". Network work runs off the main thread; every repository change runs on Main.
 *
 * A full phone is not a dead link: a "No space" / ENOSPC failure starts no recovery and logs nothing. It
 * shows [NO_SPACE_MESSAGE], frees its slot, and Retry resumes it from the bytes on disk.
 */
object DownloadRecovery {
    // Seams for tests.
    internal var fetchStreams: suspend (type: String, videoId: String) -> List<StreamItem>? = ::fetchFreshStreams
    internal var settings: () -> LayoverSettings = { LayoverSettingsRepository.state.value }
    /** Ranks streams inside one picker tier by their track record, like the download queue. */
    internal var score: (StreamItem) -> Double = TrackRecord::score
    internal var downloads: () -> List<DownloadItem> = { DownloadsRepository.uiState.value.items }
    internal var logAttempt: (AttemptRecord) -> Unit = { record -> runCatching { AttemptLog.add(record) } }
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    private val job = SupervisorJob()

    /** Last line of defence: a recovery bug must never crash the app. */
    private val swallowErrors = CoroutineExceptionHandler { _, _ -> }

    /** Download ids with a recovery in flight. A StateFlow so claim and release are atomic from any thread. */
    private val inFlight = MutableStateFlow(emptySet<String>())

    /**
     * Called after a transfer error left [item] Failed. Not for user pause or cancel (those never fail),
     * nor for Ready check failures (that check runs its own clean retry).
     */
    fun onDownloadFailed(item: DownloadItem) {
        if (item.status != DownloadStatus.Failed) return
        // The phone is full: the link is fine, a new one would fail the same way. No recovery, no log.
        if (isNoSpaceError(item.errorMessage)) {
            DownloadsRepository.layoverUpdateItem(item.id) {
                if (it.status == DownloadStatus.Failed && isNoSpaceError(it.errorMessage)) it.copy(errorMessage = NO_SPACE_MESSAGE) else it
            }
            return
        }
        if (item.streamIdentity == null) return
        if (item.errorMessage == FILE_CHECK_RETRYING_MESSAGE || item.errorMessage == FILE_CHECK_FAILED_MESSAGE) return
        if (item.errorMessage == SOURCE_GONE_MESSAGE || item.errorMessage == NO_SPACE_MESSAGE) return
        start(item, Mode.Auto)
    }

    /**
     * The network came back (or the app returned to the foreground): recover again the downloads that were
     * waiting on unreachable addons. They already hold their slots, so this never adds a transfer beyond the
     * cap; the attempt cap still applies (an unreachable fetch never spent one). Posts to Main; any thread.
     */
    fun retryUnreachable() {
        CoroutineScope(job + mainDispatcher + swallowErrors).launch {
            unreachableToRetry(downloads(), inFlight.value).forEach(::onDownloadFailed)
        }
    }

    /** The Ready check's clean retry failed too ("File check failed"): one DownloadFailed record for that file. */
    internal fun onReadyCheckGaveUp(item: DownloadItem) {
        runCatching { logFailed(item) }
    }

    /**
     * After the downloads list is loaded or re-attached. Recovers a download that failed while nothing was
     * watching, and one the app was killed in the middle of recovering (stored with the "finding" message,
     * which would otherwise stay on screen forever).
     */
    fun onRestored(before: DownloadItem, current: DownloadItem) {
        if (before.status != DownloadStatus.Failed ||
            before.errorMessage == FINDING_SOURCE_MESSAGE ||
            current.errorMessage == FINDING_SOURCE_MESSAGE ||
            current.errorMessage == ADDONS_UNREACHABLE_MESSAGE ||
            // The transfer store keeps the raw "ENOSPC" text; show the friendly one again (no recovery).
            isNoSpaceError(current.errorMessage)
        ) {
            onDownloadFailed(current)
        }
    }

    /**
     * Retry on a download that recovery gave up on runs the picker again on fresh streams (instead of
     * retrying the dead link), with the recovery count back at 0. Returns true when handled here.
     */
    fun onManualRetry(item: DownloadItem): Boolean {
        if (item.status != DownloadStatus.Failed || item.streamIdentity == null) return false
        if (item.id in inFlight.value) return true
        when (item.errorMessage) {
            // Addons were unreachable last time: an ordinary recovery (same file first), the count was never spent.
            ADDONS_UNREACHABLE_MESSAGE -> start(item, Mode.Auto)
            SOURCE_GONE_MESSAGE, FINDING_SOURCE_MESSAGE -> start(item, Mode.ManualPick)
            // The file failed the Ready check twice: replaying its url would fetch the same bad bytes.
            FILE_CHECK_FAILED_MESSAGE -> start(item, Mode.ManualOtherFile)
            else -> return false
        }
        return true
    }

    private enum class Mode(val manual: Boolean) {
        /** Automatic recovery: same file first, counts against [MAX_RECOVERY_ATTEMPTS]. */
        Auto(manual = false),

        /** Retry after "Source gone": the picker's best, the old file allowed, count back at 0. */
        ManualPick(manual = true),

        /** Retry after a failed Ready check: the picker's best other file, count back at 0. */
        ManualOtherFile(manual = true),
    }

    private fun start(item: DownloadItem, mode: Mode) {
        val manual = mode.manual
        if (!claim(item.id)) return
        // At the cap there is nothing to fetch: give up straight away so Retry offers a fresh pick.
        val skipFetch = !manual && item.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS
        if (!skipFetch) DownloadsRepository.layoverUpdateItem(item.id) { it.copy(errorMessage = FINDING_SOURCE_MESSAGE) }
        CoroutineScope(job + workDispatcher + swallowErrors).launch {
            try {
                // A failure while planning (a bug, a bad addon payload) ends in "Source gone", never a stuck row.
                val plan = try {
                    if (skipFetch) RecoveryPlan.GiveUp else plan(item, mode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    RecoveryPlan.GiveUp
                }
                withContext(mainDispatcher) {
                    release(item.id)
                    try {
                        apply(item, plan, mode)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        markSourceGone(item.id)
                    }
                }
            } finally {
                release(item.id)
            }
        }
    }

    /** Fresh streams, then the plan; null when the addons couldn't be asked. */
    private suspend fun plan(item: DownloadItem, mode: Mode): RecoveryPlan? {
        val identity = checkNotNull(item.streamIdentity)
        val streams = try {
            fetchStreams(identity.type, identity.videoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return null
        return when (mode) {
            Mode.Auto -> planRecovery(item, streams, settings(), score)
            Mode.ManualPick -> planManualRetry(item, streams, settings(), score)
            Mode.ManualOtherFile -> planOtherFile(item, streams, settings(), score)
        }
    }

    private fun claim(id: String): Boolean {
        while (true) {
            val current = inFlight.value
            if (id in current) return false
            if (inFlight.compareAndSet(current, current + id)) return true
        }
    }

    private fun release(id: String) = inFlight.update { it - id }

    /** Download ids being recovered right now. They still hold a download slot (the queue counts them). */
    internal fun inFlightIds(): Set<String> = inFlight.value

    /** [inFlightIds] as a flow, so the Downloads tab badges follow recovery as it starts and ends. */
    internal val inFlightIdsFlow: StateFlow<Set<String>>
        get() = inFlight.asStateFlow()

    /** Stops every running recovery, the way a process kill would. For tests. */
    internal fun cancelAllForTests() = job.cancelChildren()

    /** [failed] is the item as it was when recovery started; [plan] null means the addons couldn't be asked. */
    private fun apply(failed: DownloadItem, plan: RecoveryPlan?, mode: Mode) {
        val manual = mode.manual
        val current = DownloadsRepository.uiState.value.items.firstOrNull { it.id == failed.id } ?: return
        // The user retried, resumed or replaced it meanwhile: their action wins.
        if (current.status != DownloadStatus.Failed || current.sourceUrl != failed.sourceUrl) return
        val attempts = if (manual) 0 else failed.recoveryAttempts + 1
        when (plan) {
            // Couldn't ask the addons (still loading, offline, all down): not a verdict on the file. No attempt spent;
            // Retry, the next app start or the next reload recovers it then.
            // After a failed Ready check the message stays, so the next Retry still skips the bad file.
            null -> DownloadsRepository.layoverUpdateItem(failed.id) {
                it.copy(errorMessage = if (mode == Mode.ManualOtherFile) FILE_CHECK_FAILED_MESSAGE else ADDONS_UNREACHABLE_MESSAGE)
            }
            is RecoveryPlan.SameFile -> {
                // fileName is unchanged, so the transfer appends to the existing .part file.
                DownloadsRepository.layoverUpdateItem(failed.id) {
                    it.copy(sourceUrl = plan.newUrl, sourceHeaders = sanitizeRequestHeaders(plan.headers), recoveryAttempts = attempts, errorMessage = null)
                }
                DownloadsRepository.resumeDownload(failed.id)
            }
            is RecoveryPlan.OtherFile -> {
                if (!manual) logFailed(current)
                val result = DownloadsRepository.enqueueFromStream(
                    contentType = current.contentType,
                    videoId = current.videoId,
                    parentMetaId = current.parentMetaId,
                    parentMetaType = current.parentMetaType,
                    title = current.title,
                    logo = current.logo,
                    poster = current.poster,
                    background = current.background,
                    seasonNumber = current.seasonNumber,
                    episodeNumber = current.episodeNumber,
                    episodeTitle = current.episodeTitle,
                    episodeThumbnail = current.episodeThumbnail,
                    stream = plan.stream,
                    allowMetered = current.allowMetered,
                    origin = current.origin,
                    recoveryAttempts = attempts,
                )
                if (result == DownloadEnqueueResult.MissingUrl || result == DownloadEnqueueResult.UnsupportedFormat) {
                    giveUp(current, log = false)
                }
            }
            RecoveryPlan.GiveUp -> giveUp(current, log = !manual)
        }
    }

    private fun giveUp(item: DownloadItem, log: Boolean) {
        DownloadsRepository.layoverUpdateItem(item.id) {
            it.copy(errorMessage = SOURCE_GONE_MESSAGE, updatedAtEpochMs = DownloadsClock.nowEpochMs())
        }
        if (log) logFailed(item)
    }

    /** Fallback when applying a plan threw: if it is still Failed, the row must offer a fresh pick. */
    private fun markSourceGone(downloadId: String) {
        runCatching {
            DownloadsRepository.layoverUpdateItem(downloadId) {
                if (it.status == DownloadStatus.Failed) it.copy(errorMessage = SOURCE_GONE_MESSAGE) else it
            }
        }
    }

    private fun logFailed(item: DownloadItem) {
        logAttempt(attemptRecordForDownload(item, AttemptOutcome.DownloadFailed, DownloadsClock.nowEpochMs()))
    }
}

/**
 * Fresh links from every enabled addon, or null when the answer says nothing about the title: addons still
 * loading, or every addon request failed (offline, addons down). An empty list means the addons answered and
 * offered nothing. Shared by link recovery and the download queue.
 */
internal suspend fun fetchFreshStreams(type: String, videoId: String): List<StreamItem>? =
    freshStreamsOrNull(
        addonsReady = { awaitAddonsSettled(AddonRepository.uiState.map { it.addons }) },
        fetch = { LayoverStreamSource.fetchResult(type, videoId, forceRefresh = true) },
    )

internal suspend fun freshStreamsOrNull(
    addonsReady: suspend () -> Boolean,
    fetch: suspend () -> StreamFetchResult,
): List<StreamItem>? {
    if (!addonsReady()) return null
    val result = fetch()
    return result.streams.takeIf { result.anyAddonAnswered }
}

/** Waits (up to [timeoutMs]) until every enabled addon has settled; false on timeout. */
internal suspend fun awaitAddonsSettled(
    addons: Flow<List<ManagedAddon>>,
    timeoutMs: Long = ADDONS_READY_TIMEOUT_MS,
): Boolean = withTimeoutOrNull(timeoutMs) { addons.first { it.layoverAddonsSettled() } } != null
