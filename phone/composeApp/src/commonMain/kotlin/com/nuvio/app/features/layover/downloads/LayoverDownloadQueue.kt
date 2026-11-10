package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.layover.streams.layoverAddonsSettled
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadOrigin
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.LayoverSettingsRepository
import com.nuvio.app.features.layover.LayoverStorage
import com.nuvio.app.features.layover.rank.TrackRecord
import com.nuvio.app.features.layover.streams.facts
import com.nuvio.app.features.layover.streams.pickDownloadCandidates
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.concurrent.Volatile

const val NO_SOURCE_FAILURE = "No phone-sized source found"
internal const val START_FAILED_FAILURE = "Couldn't start this download"

/** A download the user asked for that hasn't started yet. Layover picks the stream when its turn comes. */
@Serializable
data class QueuedDownload(
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val origin: DownloadOrigin,
    val allowMetered: Boolean,
    val queuedAtEpochMs: Long,
    /** Set when no stream could be picked; the entry stays (skipped by the pump) until Retry. */
    val failure: String? = null,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null
}

/** Queue entries smart downloads (removed 2026-11-10) added on their own. The user never asked for these. */
internal fun isAutoQueued(item: QueuedDownload): Boolean = item.origin == DownloadOrigin.Smart

/**
 * Downloads smart downloads started that never finished (waiting, downloading, failed). They go; a finished
 * one stays until it's watched, like any other download.
 */
internal fun autoDownloadLeftovers(downloads: List<DownloadItem>): List<DownloadItem> =
    downloads.filter { it.origin == DownloadOrigin.Smart && it.status != DownloadStatus.Completed }

/**
 * Which queued downloads to start now: up to `MAX_ACTIVE - activeCount`, in queue order, skipping
 * ones already downloaded or downloading ([alreadyHave]) and ones that failed to find a source.
 */
internal fun nextToStart(
    pending: List<QueuedDownload>,
    activeCount: Int,
    alreadyHave: Set<String>,
): List<QueuedDownload> {
    val room = LayoverDownloadQueue.MAX_ACTIVE - activeCount
    if (room <= 0) return emptyList()
    return pending
        .asSequence()
        .filter { it.failure == null && it.videoId !in alreadyHave }
        .distinctBy { it.videoId }
        .take(room)
        .toList()
}

/**
 * True while a download holds a slot: transferring, or Failed but due to transfer again (link recovery
 * finding the file, waiting to resume once the addons are reachable again, or the Ready check's one clean
 * retry). [recovering] is DownloadRecovery's in-flight set.
 */
internal fun DownloadItem.holdsSlot(recovering: Set<String>): Boolean =
    status == DownloadStatus.Downloading ||
        (status == DownloadStatus.Failed && (
            id in recovering ||
                errorMessage == FINDING_SOURCE_MESSAGE ||
                // Recovered automatically on the next load (onRestored), with no cap check of its own.
                errorMessage == ADDONS_UNREACHABLE_MESSAGE ||
                (errorMessage == FILE_CHECK_RETRYING_MESSAGE && recoveryAttempts == 0)
            ))

/** Downloads counted against [LayoverDownloadQueue.MAX_ACTIVE]. */
internal fun activeDownloadCount(downloads: List<DownloadItem>, recovering: Set<String>): Int =
    downloads.count { it.holdsSlot(recovering) }

/**
 * Video ids that need no queueing: finished, or holding a slot right now. Paused and failed ones (recovery
 * done) can be re-queued.
 */
internal fun alreadyHave(downloads: List<DownloadItem>, recovering: Set<String> = emptySet()): Set<String> =
    downloads
        .filter { it.status == DownloadStatus.Completed || it.holdsSlot(recovering) }
        .mapTo(mutableSetOf()) { it.videoId }

/** Largest file the picker may choose for [item]: what a pick could need on disk before its size is known. */
internal fun capBytes(item: QueuedDownload, settings: LayoverSettings): Long =
    if (item.isEpisode) settings.episodeCapBytes else settings.movieCapBytes

/** Bytes [stream] will take: the addon's stated size, or [capBytes] when it gives none. */
internal fun neededBytes(stream: StreamItem, cap: Long): Long = stream.facts().sizeBytes ?: cap

/**
 * One-tap and season downloads. At most [MAX_ACTIVE] downloads transfer at once (RD safety); the rest wait
 * here, persisted per profile, so a season survives the app being killed. The pump runs on queue changes,
 * whenever a download's status changes, when addons finish loading, on app start, and on [LayoverWakeUp].
 *
 * Free space is checked twice: before asking the addons (room for a file at the size cap, so a full phone
 * never sends a single stream request) and before enqueueing (room for the picked file). Not enough room
 * marks the entry [NO_SPACE_MESSAGE]: it holds no slot, offers Retry, and is no verdict on any stream.
 */
object LayoverDownloadQueue {
    internal const val MAX_ACTIVE = 2
    private const val storageKey = "download_queue"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Seams for tests.
    internal var loader: () -> String? = { LayoverStorage.load(storageKey) }
    internal var saver: (String) -> Unit = { LayoverStorage.save(storageKey, it) }
    internal var downloads: () -> List<DownloadItem> = {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState.value.items
    }
    internal var recovering: () -> Set<String> = { DownloadRecovery.inFlightIds() }
    internal var fetchStreams: suspend (type: String, videoId: String) -> List<StreamItem>? = ::fetchFreshStreams
    internal var settings: () -> LayoverSettings = { LayoverSettingsRepository.state.value }
    internal var freeBytes: () -> Long? = { runCatching { downloadsFreeBytes() }.getOrNull() }

    /** Ranks streams inside one picker tier by their addon/quality/release-group track record. */
    internal var score: (StreamItem) -> Double = TrackRecord::score
    internal var enqueue: (QueuedDownload, StreamItem) -> DownloadEnqueueResult = ::enqueueInRepository
    internal var cancelDownload: (String) -> Unit = DownloadsRepository::cancelDownload
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    private val job = SupervisorJob()

    /** A queue bug must never crash the app. */
    private val swallowErrors = CoroutineExceptionHandler { _, _ -> }

    private val _pending: MutableStateFlow<List<QueuedDownload>> by lazy { MutableStateFlow(decode(loader())) }

    /** Waiting downloads in queue order, including ones that failed to find a source. */
    val pending: StateFlow<List<QueuedDownload>>
        get() = _pending.asStateFlow()

    /** Video ids picked by a pump whose stream is still being found. Counted as active so the cap holds. */
    private val starting = MutableStateFlow(emptySet<String>())

    /** Bumped on profile change so a start from the old profile never lands in the new one. */
    private val generation = MutableStateFlow(0)

    @Volatile
    private var watching = false

    /** True once the app started the queue ([start]); before that the profile and downloads aren't loaded. */
    internal val started: Boolean
        get() = watching

    private fun onMain(block: () -> Unit) {
        CoroutineScope(job + mainDispatcher + swallowErrors).launch { block() }
    }

    /**
     * App start: pump now, then whenever a download's status (or recovery message) changes and when the addons
     * have all settled. Every queue mutation and persist runs on the main dispatcher.
     */
    fun start() {
        if (watching) return
        watching = true
        CoroutineScope(job + mainDispatcher + swallowErrors).launch {
            dropAutoDownloadsNow()
            merge(
                DownloadsRepository.uiState
                    .map { state -> state.items.map { Triple(it.videoId, it.status, it.errorMessage) } }
                    .distinctUntilChanged(),
                AddonRepository.uiState
                    .map { state -> state.addons.layoverAddonsSettled() }
                    .distinctUntilChanged(),
            ).collect { pumpNow() }
        }
    }

    /**
     * Reload the (profile-scoped) queue after the active profile changes. The generation bump and reload are
     * synchronous: a start already posted to Main from the old profile then sees the new generation and drops
     * out, instead of enqueueing into the new profile or persisting the old queue under its key.
     */
    fun onProfileChanged() {
        generation.update { it + 1 }
        _pending.value = decode(loader())
        onMain {
            dropAutoDownloadsNow()
            pumpNow()
        }
    }

    /** On Main. Clears what smart downloads left behind in this profile (see [isAutoQueued]). Idempotent. */
    internal fun dropAutoDownloadsNow() {
        if (_pending.value.any(::isAutoQueued)) {
            _pending.update { current -> current.filterNot(::isAutoQueued) }
            persist()
        }
        autoDownloadLeftovers(downloads()).forEach { cancelDownload(it.id) }
    }

    /**
     * Queues [items] in order and pumps. Skips ones already queued and ones already downloaded or downloading.
     * Returns how many were newly queued.
     */
    fun add(items: List<QueuedDownload>): Int {
        val have = alreadyHave(downloads(), recovering())
        var added = 0
        _pending.update { current ->
            val known = current.mapTo(mutableSetOf()) { it.videoId }
            val fresh = items.filter { it.videoId !in have && known.add(it.videoId) }
            added = fresh.size
            current + fresh
        }
        persist()
        pump()
        return added
    }

    fun remove(videoId: String) {
        _pending.update { current -> current.filterNot { it.videoId == videoId } }
        persist()
    }

    /** Retry on an entry that found no source: clear the failure and try again. */
    fun retry(videoId: String) = retry(listOf(videoId))

    /** Retry several (a season's worth) at once; returns how many had a failure to clear. */
    fun retry(videoIds: Collection<String>): Int {
        val ids = videoIds.toSet()
        val count = _pending.value.count { it.videoId in ids && it.failure != null }
        _pending.update { current ->
            current.map { if (it.videoId in ids) it.copy(failure = null) else it }
        }
        persist()
        pump()
        return count
    }

    /** Starts what fits under the cap, on the main dispatcher. Idempotent. */
    fun pump() = onMain { pumpNow() }

    /** On Main. An entry already being started is never started twice. */
    private fun pumpNow() {
        val items = downloads()
        val recoveringIds = recovering()
        val have = alreadyHave(items, recoveringIds)
        val active = activeDownloadCount(items, recoveringIds)
        dropDone(have)

        val free = freeBytes()
        val reserved = reservedBytes(items, recoveringIds)
        val settingsNow = settings()

        var selection: List<QueuedDownload>
        while (true) {
            val inFlight = starting.value
            selection = nextToStart(_pending.value, active + inFlight.size, have + inFlight)
            if (selection.isEmpty()) return
            // Short on space even for a capped pick: fail them here, before any stream request.
            val short = selection.filterNot { hasRoomFor(capBytes(it, settingsNow), free, reserved) }
            if (short.isNotEmpty()) {
                short.forEach { markFailed(it.videoId, NO_SPACE_MESSAGE) }
                continue
            }
            if (starting.compareAndSet(inFlight, inFlight + selection.map { it.videoId })) break
        }
        val gen = generation.value
        selection.forEach { launchStart(it, gen) }
    }

    /** Entries downloaded or downloading by other means (a manual stream pick) have nothing left to wait for. */
    private fun dropDone(have: Set<String>) {
        if (_pending.value.none { it.videoId in have && it.videoId !in starting.value }) return
        _pending.update { current -> current.filterNot { it.videoId in have && it.videoId !in starting.value } }
        persist()
    }

    private fun launchStart(item: QueuedDownload, gen: Int) {
        CoroutineScope(job + workDispatcher + swallowErrors).launch {
            try {
                // Network and picking off the main thread...
                val streams = try {
                    fetchStreams(item.contentType, item.videoId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                // Addons still loading, or none answered (offline): not a verdict on the title. Stays queued
                // with no failure; the next trigger (status change, addons settling, app start) tries again.
                if (streams == null) return@launch
                val candidates = try {
                    pickDownloadCandidates(streams, item.isEpisode, settings(), score)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                // ...every queue change, persist and re-pump on Main, so no write races another.
                withContext(mainDispatcher) {
                    val pumpAgain = finishStart(item, candidates, gen)
                    release(item.videoId)
                    if (pumpAgain) pumpNow()
                }
            } finally {
                release(item.videoId)
            }
        }
    }

    /** On Main. Returns true when a slot or the queue changed, so the pump should run again. */
    private fun finishStart(item: QueuedDownload, candidates: List<StreamItem>?, gen: Int): Boolean {
        if (gen != generation.value) return false
        // Removed while its stream was being found: the user's action wins.
        if (_pending.value.none { it.videoId == item.videoId && it.failure == null }) return true
        if (candidates == null) {
            markFailed(item.videoId, START_FAILED_FAILURE)
            return true
        }
        val free = freeBytes()
        val reserved = reservedBytes(downloads(), recovering())
        val cap = capBytes(item, settings())
        var shortOfSpace = false
        for (stream in candidates) {
            if (!hasRoomFor(neededBytes(stream, cap), free, reserved)) {
                shortOfSpace = true
                continue
            }
            val result = try {
                enqueue(item, stream)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                continue
            }
            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                remove(item.videoId)
                return true
            }
        }
        markFailed(item.videoId, if (shortOfSpace) NO_SPACE_MESSAGE else NO_SOURCE_FAILURE)
        return true
    }

    private fun markFailed(videoId: String, failure: String) {
        _pending.update { current ->
            current.map { if (it.videoId == videoId) it.copy(failure = failure) else it }
        }
        persist()
    }

    private fun release(videoId: String) = starting.update { it - videoId }

    private fun persist() {
        runCatching { saver(encode(_pending.value)) }
    }

    /** Forgets in-memory state and reloads from storage, the way a process restart would. For tests. */
    internal fun resetForTests() {
        job.cancelChildren()
        starting.value = emptySet()
        generation.update { it + 1 }
        _pending.value = decode(loader())
    }

    /** Entries are decoded one by one so a single bad one is skipped, never the whole queue. */
    internal fun decode(raw: String?): List<QueuedDownload> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(QueuedDownload.serializer(), element) }.getOrNull()
        }
    }

    internal fun encode(items: List<QueuedDownload>): String =
        json.encodeToString(ListSerializer(QueuedDownload.serializer()), items)

    private fun enqueueInRepository(item: QueuedDownload, stream: StreamItem): DownloadEnqueueResult =
        DownloadsRepository.enqueueFromStream(
            contentType = item.contentType,
            videoId = item.videoId,
            parentMetaId = item.parentMetaId,
            parentMetaType = item.parentMetaType,
            title = item.title,
            logo = item.logo,
            poster = item.poster,
            background = item.background,
            seasonNumber = item.seasonNumber,
            episodeNumber = item.episodeNumber,
            episodeTitle = item.episodeTitle,
            episodeThumbnail = item.episodeThumbnail,
            stream = stream,
            allowMetered = item.allowMetered,
            origin = item.origin,
        )
}
