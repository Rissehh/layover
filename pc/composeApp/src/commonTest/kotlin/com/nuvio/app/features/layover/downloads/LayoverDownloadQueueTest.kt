package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadOrigin
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.layover.streams.StreamFetchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoverDownloadQueueTest {
    private fun queued(n: Int, failure: String? = null) = QueuedDownload(
        contentType = "series",
        videoId = "tt9:1:$n",
        parentMetaId = "tt9",
        parentMetaType = "series",
        title = "Show",
        logo = null,
        poster = "https://img/poster.jpg",
        background = null,
        seasonNumber = 1,
        episodeNumber = n,
        episodeTitle = "Episode $n",
        episodeThumbnail = null,
        origin = DownloadOrigin.Season,
        allowMetered = false,
        queuedAtEpochMs = 1_000L + n,
        failure = failure,
    )

    private val five = (1..5).map { queued(it) }

    // nextToStart

    @Test
    fun `five pending and nothing active starts the first two`() {
        assertEquals(five.take(2), nextToStart(five, activeCount = 0, alreadyHave = emptySet()))
    }

    @Test
    fun `five pending and two active starts nothing`() {
        assertEquals(emptyList(), nextToStart(five, activeCount = 2, alreadyHave = emptySet()))
    }

    @Test
    fun `one active leaves room for one`() {
        assertEquals(five.take(1), nextToStart(five, activeCount = 1, alreadyHave = emptySet()))
    }

    @Test
    fun `an item already downloaded is skipped and the next one taken`() {
        val next = nextToStart(five, activeCount = 0, alreadyHave = setOf("tt9:1:1"))
        assertEquals(listOf(five[1], five[2]), next)
    }

    @Test
    fun `queue order is preserved, not sorted by episode or time`() {
        val shuffled = listOf(five[3], five[0], five[4], five[1])
        assertEquals(listOf(five[3], five[0]), nextToStart(shuffled, activeCount = 0, alreadyHave = emptySet()))
    }

    @Test
    fun `items with a failure are skipped`() {
        val pending = listOf(queued(1, failure = NO_SOURCE_FAILURE), queued(2), queued(3))
        assertEquals(listOf(pending[1], pending[2]), nextToStart(pending, activeCount = 0, alreadyHave = emptySet()))
    }

    // Persistence

    @Test
    fun `queued download survives an encode and decode round trip`() {
        val items = listOf(queued(1), queued(2, failure = NO_SOURCE_FAILURE))
        assertEquals(items, LayoverDownloadQueue.decode(LayoverDownloadQueue.encode(items)))
    }

    @Test
    fun `garbage or a single bad entry never loses the rest of the queue`() {
        assertEquals(emptyList(), LayoverDownloadQueue.decode("not json"))
        assertEquals(emptyList(), LayoverDownloadQueue.decode(null))
        val good = LayoverDownloadQueue.encode(listOf(queued(1))).removePrefix("[").removeSuffix("]")
        val raw = "[{\"videoId\":\"broken\",\"origin\":\"Nope\"},$good]"
        assertEquals(listOf(queued(1)), LayoverDownloadQueue.decode(raw))
    }
}

/** Marks when a block runs on it, so a test can tell "on main" from "on the work dispatcher". */
private class MarkingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var running = false
        private set

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        delegate.dispatch(context, Runnable {
            running = true
            try {
                block.run()
            } finally {
                running = false
            }
        })
    }
}

/** Holds dispatched blocks until the test runs them, in order: lets a test interleave events on "Main". */
private class ManualDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

/** The pump with fakes for the downloads list, the addons and the repository. */
class LayoverDownloadQueuePumpTest {
    private var stored: String? = null
    private val downloads = mutableListOf<DownloadItem>()
    private val started = mutableListOf<Pair<String, StreamItem>>()
    private val fetched = mutableListOf<String>()
    private var offer: suspend (videoId: String) -> List<StreamItem>? = { listOf(good(it)) }
    private var recovering: Set<String> = emptySet()
    private val cancelled = mutableListOf<String>()

    private fun good(videoId: String) = StreamItem(
        name = "Comet 1080p",
        description = "Show.S01.1080p.WEB-DL.x264-GRP\n💾 900 MB",
        url = "https://comet.example/resolve/$videoId",
        addonName = "Comet",
        addonId = "addon:comet",
        behaviorHints = StreamBehaviorHints(filename = "Show.$videoId.1080p.WEB-DL.x264-GRP.mkv"),
    )

    private val remux = StreamItem(
        name = "Comet 2160p",
        description = "Show.S01.2160p.REMUX\n💾 40 GB",
        url = "https://comet.example/resolve/remux",
        addonName = "Comet",
        addonId = "addon:comet",
    )

    private fun queued(n: Int) = QueuedDownload(
        contentType = "series",
        videoId = "tt9:1:$n",
        parentMetaId = "tt9",
        parentMetaType = "series",
        title = "Show",
        logo = null,
        poster = null,
        background = null,
        seasonNumber = 1,
        episodeNumber = n,
        episodeTitle = "Episode $n",
        episodeThumbnail = null,
        origin = DownloadOrigin.Season,
        allowMetered = false,
        queuedAtEpochMs = n.toLong(),
    )

    private fun download(videoId: String, status: DownloadStatus) = DownloadItem(
        id = "d-$videoId",
        contentType = "series",
        parentMetaId = "tt9",
        parentMetaType = "series",
        videoId = videoId,
        title = "Show",
        streamTitle = "Comet",
        providerName = "Comet",
        sourceUrl = "https://comet.example/$videoId",
        fileName = "$videoId.mkv",
        status = status,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    @BeforeTest
    fun setUp() {
        LayoverDownloadQueue.loader = { stored }
        LayoverDownloadQueue.saver = { stored = it }
        LayoverDownloadQueue.downloads = { downloads.toList() }
        LayoverDownloadQueue.recovering = { recovering }
        LayoverDownloadQueue.fetchStreams = { _, videoId -> fetched += videoId; offer(videoId) }
        LayoverDownloadQueue.settings = { LayoverSettings() }
        LayoverDownloadQueue.enqueue = { item, stream ->
            started += item.videoId to stream
            downloads += download(item.videoId, DownloadStatus.Downloading)
            DownloadEnqueueResult.Started
        }
        LayoverDownloadQueue.workDispatcher = Dispatchers.Unconfined
        LayoverDownloadQueue.mainDispatcher = Dispatchers.Unconfined
        LayoverDownloadQueue.freeBytes = { null }
        LayoverDownloadQueue.cancelDownload = { id -> cancelled += id; downloads.removeAll { it.id == id } }
        LayoverDownloadQueue.resetForTests()
        LayoverWakeUp.ready = { true }
        LayoverWakeUp.retryUnreachable = {}
        LayoverWakeUp.retryAddons = {}
        LayoverWakeUp.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        LayoverDownloadQueue.freeBytes = { null }
        LayoverDownloadQueue.cancelDownload = DownloadsRepository::cancelDownload
        LayoverDownloadQueue.resetForTests()
        LayoverWakeUp.ready = { LayoverDownloadQueue.started }
        LayoverWakeUp.retryUnreachable = { DownloadRecovery.retryUnreachable() }
        LayoverWakeUp.retryAddons = { AddonRepository.retryUnloaded() }
        LayoverWakeUp.resetForTests()
    }

    // Final review fix 4a: free space.

    private val mb = 1024L * 1024

    @Test
    fun `a full phone fails every waiting entry before any stream request`() {
        LayoverDownloadQueue.freeBytes = { 500 * mb }

        LayoverDownloadQueue.add((1..4).map(::queued))

        assertEquals(emptyList(), fetched, "no addon (and so no RD) request while space is short")
        assertEquals(emptyList(), startedIds())
        assertEquals(4, LayoverDownloadQueue.pending.value.size)
        assertTrue(LayoverDownloadQueue.pending.value.all { it.failure == NO_SPACE_MESSAGE })
        assertEquals(0, activeDownloadCount(downloads, recovering), "an out-of-space entry holds no slot")
    }

    @Test
    fun `out of space entries start again on Retry once there is room`() {
        LayoverDownloadQueue.freeBytes = { 500 * mb }
        LayoverDownloadQueue.add((1..3).map(::queued))

        LayoverDownloadQueue.freeBytes = { 50_000 * mb }
        LayoverDownloadQueue.retry((1..3).map { "tt9:1:$it" })

        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
        assertEquals(listOf("tt9:1:3"), LayoverDownloadQueue.pending.value.map { it.videoId })
        assertNull(LayoverDownloadQueue.pending.value.single().failure)
    }

    @Test
    fun `space is checked again against the picked file before enqueueing`() {
        // Room for a capped pick when the pump runs, then the phone fills up while the stream is found.
        val readings = ArrayDeque(listOf(50_000 * mb, 300 * mb))
        LayoverDownloadQueue.freeBytes = { readings.removeFirstOrNull() ?: (300 * mb) }

        LayoverDownloadQueue.add(listOf(queued(1)))

        assertEquals(listOf("tt9:1:1"), fetched)
        assertEquals(emptyList(), startedIds())
        assertEquals(NO_SPACE_MESSAGE, LayoverDownloadQueue.pending.value.single().failure)
    }

    @Test
    fun `room for the cap is room enough, and a stream with no size needs the cap`() {
        val cap = LayoverSettings().episodeCapBytes
        LayoverDownloadQueue.freeBytes = { cap + SPACE_MARGIN_BYTES }

        LayoverDownloadQueue.add(listOf(queued(1)))

        assertEquals(listOf("tt9:1:1"), startedIds())
        val noSize = good("x").copy(description = "Show.S01.1080p.WEB-DL")
        assertEquals(cap, neededBytes(noSize, cap))
        assertEquals(900 * mb, neededBytes(good("x"), cap))
    }

    @Test
    fun `space still to be written by running downloads counts against the room`() {
        val cap = LayoverSettings().episodeCapBytes
        downloads += download("tt9:1:8", DownloadStatus.Downloading).copy(downloadedBytes = 0L, totalBytes = 1_000 * mb)
        LayoverDownloadQueue.freeBytes = { cap + SPACE_MARGIN_BYTES + 999 * mb }

        LayoverDownloadQueue.add(listOf(queued(1)))

        assertEquals(emptyList(), fetched)
        assertEquals(NO_SPACE_MESSAGE, LayoverDownloadQueue.pending.value.single().failure)
    }

    // Final review fix 3: reconnect wake-up.

    @Test
    fun `a reconnect wake-up starts entries whose fetch found the phone offline`() {
        offer = { null }
        LayoverDownloadQueue.add((1..3).map(::queued))
        assertEquals(emptyList(), startedIds())
        assertTrue(LayoverDownloadQueue.pending.value.all { it.failure == null })

        offer = { listOf(good(it)) }
        LayoverWakeUp.onNetworkValidated()

        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
        assertEquals(listOf("tt9:1:3"), LayoverDownloadQueue.pending.value.map { it.videoId })
    }

    @Test
    fun `a reconnect wake-up never goes past two transfers`() {
        downloads += download("tt9:1:1", DownloadStatus.Failed).copy(errorMessage = ADDONS_UNREACHABLE_MESSAGE)
        downloads += download("tt9:1:2", DownloadStatus.Failed).copy(errorMessage = ADDONS_UNREACHABLE_MESSAGE)
        LayoverDownloadQueue.add((3..5).map(::queued))
        assertEquals(emptyList(), startedIds())
        // Recovery resumes both (same file) on the wake-up.
        LayoverWakeUp.retryUnreachable = {
            downloads.replaceAll { it.copy(status = DownloadStatus.Downloading, errorMessage = null) }
        }

        LayoverWakeUp.onForeground()

        assertEquals(emptyList(), startedIds())
        assertEquals(LayoverDownloadQueue.MAX_ACTIVE, activeDownloadCount(downloads, recovering))
    }

    private fun startedIds() = started.map { it.first }

    @Test
    fun `adding a season starts two and keeps the rest queued in order`() {
        LayoverDownloadQueue.add((1..5).map(::queued))

        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
        assertEquals(listOf("tt9:1:3", "tt9:1:4", "tt9:1:5"), LayoverDownloadQueue.pending.value.map { it.videoId })
    }

    @Test
    fun `a finished download lets the next one start`() {
        LayoverDownloadQueue.add((1..5).map(::queued))
        downloads.replaceAll { if (it.videoId == "tt9:1:1") it.copy(status = DownloadStatus.Completed) else it }

        LayoverDownloadQueue.pump()

        assertEquals(listOf("tt9:1:1", "tt9:1:2", "tt9:1:3"), startedIds())
        assertEquals(listOf("tt9:1:4", "tt9:1:5"), LayoverDownloadQueue.pending.value.map { it.videoId })
    }

    @Test
    fun `pumping again while nothing changed starts nothing twice`() {
        LayoverDownloadQueue.add((1..5).map(::queued))
        repeat(3) { LayoverDownloadQueue.pump() }

        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `a start still in flight is never started again by a second pump`() = runTest {
        val gate = CompletableDeferred<Unit>()
        offer = { gate.await(); listOf(good(it)) }
        LayoverDownloadQueue.workDispatcher = StandardTestDispatcher(testScheduler)

        LayoverDownloadQueue.add((1..5).map(::queued))
        LayoverDownloadQueue.pump()
        LayoverDownloadQueue.pump()
        testScheduler.advanceUntilIdle()
        // Two fetches in flight, the third waits: the cap counts starts that are still finding a stream.
        assertEquals(listOf("tt9:1:1", "tt9:1:2"), fetched)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
        assertEquals(listOf("tt9:1:3", "tt9:1:4", "tt9:1:5"), LayoverDownloadQueue.pending.value.map { it.videoId })
    }

    @Test
    fun `no phone-sized source keeps the entry with a failure and moves on to the next`() {
        offer = { videoId -> if (videoId == "tt9:1:1") listOf(remux) else listOf(good(videoId)) }

        LayoverDownloadQueue.add((1..3).map(::queued))

        assertEquals(setOf("tt9:1:2", "tt9:1:3"), startedIds().toSet())
        val failed = LayoverDownloadQueue.pending.value.single()
        assertEquals("tt9:1:1", failed.videoId)
        assertEquals(NO_SOURCE_FAILURE, failed.failure)
    }

    @Test
    fun `retry clears the failure and tries again`() {
        offer = { listOf(remux) }
        LayoverDownloadQueue.add(listOf(queued(1)))
        assertEquals(NO_SOURCE_FAILURE, LayoverDownloadQueue.pending.value.single().failure)

        offer = { listOf(good(it)) }
        LayoverDownloadQueue.retry("tt9:1:1")

        assertEquals(listOf("tt9:1:1"), startedIds())
        assertTrue(LayoverDownloadQueue.pending.value.isEmpty())
    }

    @Test
    fun `addons not loaded yet leaves the entry queued without a failure`() {
        offer = { null }

        LayoverDownloadQueue.add(listOf(queued(1)))

        assertEquals(emptyList(), startedIds())
        assertNull(LayoverDownloadQueue.pending.value.single().failure)
    }

    @Test
    fun `a stream the repository refuses falls through to the next candidate`() {
        val refused = good("tt9:1:1").copy(url = "https://comet.example/refused")
        offer = { listOf(refused, good(it)) }
        LayoverDownloadQueue.enqueue = { item, stream ->
            if (stream.url == refused.url) {
                DownloadEnqueueResult.UnsupportedFormat
            } else {
                started += item.videoId to stream
                downloads += download(item.videoId, DownloadStatus.Downloading)
                DownloadEnqueueResult.Started
            }
        }

        LayoverDownloadQueue.add(listOf(queued(1)))

        assertEquals("https://comet.example/resolve/tt9:1:1", started.single().second.url)
    }

    @Test
    fun `adding the same episode twice or one already downloading queues it once`() {
        downloads += download("tt9:1:1", DownloadStatus.Downloading)
        downloads += download("tt9:1:9", DownloadStatus.Downloading)

        LayoverDownloadQueue.add(listOf(queued(1), queued(2), queued(2)))
        LayoverDownloadQueue.add(listOf(queued(2)))

        assertEquals(listOf("tt9:1:2"), LayoverDownloadQueue.pending.value.map { it.videoId })
        assertEquals(emptyList(), startedIds())
    }

    @Test
    fun `paused and failed downloads do not block a re-queue`() {
        downloads += download("tt9:1:1", DownloadStatus.Paused)
        downloads += download("tt9:1:2", DownloadStatus.Failed)

        LayoverDownloadQueue.add(listOf(queued(1), queued(2)))

        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
    }

    @Test
    fun `remove takes an entry out of the queue`() {
        offer = { null }
        LayoverDownloadQueue.add((1..3).map(::queued))

        LayoverDownloadQueue.remove("tt9:1:2")

        assertEquals(listOf("tt9:1:1", "tt9:1:3"), LayoverDownloadQueue.pending.value.map { it.videoId })
    }

    /** Review focus 3: the app is killed during a season download. */
    @Test
    fun `queued episodes survive an app restart and keep pumping`() {
        LayoverDownloadQueue.add((1..5).map(::queued))
        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
        val survivingStorage = stored

        // Process killed: in-memory queue and in-flight state are gone, storage and the downloads list remain.
        LayoverDownloadQueue.resetForTests()
        stored = survivingStorage
        started.clear()
        assertEquals(listOf("tt9:1:3", "tt9:1:4", "tt9:1:5"), LayoverDownloadQueue.pending.value.map { it.videoId })

        // App start: the two downloads that were running still are, so nothing new starts yet.
        LayoverDownloadQueue.pump()
        assertEquals(emptyList(), startedIds())

        // One finishes after the restart: the next queued episode starts, in order.
        downloads.replaceAll { if (it.videoId == "tt9:1:1") it.copy(status = DownloadStatus.Completed) else it }
        LayoverDownloadQueue.pump()
        assertEquals(listOf("tt9:1:3"), startedIds())
        assertEquals(listOf("tt9:1:4", "tt9:1:5"), LayoverDownloadQueue.pending.value.map { it.videoId })
    }

    // Review fix 1: "couldn't ask the addons" is not "no phone-sized source".

    @Test
    fun `every addon request failing leaves the whole season queued, no failure, no cascade`() {
        offer = { freshStreamsOrNull({ true }, { StreamFetchResult(emptyList(), anyAddonAnswered = false) }) }

        LayoverDownloadQueue.add((1..4).map(::queued))

        assertEquals(emptyList(), startedIds())
        assertEquals(listOf("tt9:1:1", "tt9:1:2"), fetched, "only the two picked are asked; no re-pump cascade")
        assertTrue(LayoverDownloadQueue.pending.value.all { it.failure == null })
        assertEquals(4, LayoverDownloadQueue.pending.value.size)

        // Back online: the next trigger starts them.
        offer = { listOf(good(it)) }
        LayoverDownloadQueue.pump()
        assertEquals(listOf("tt9:1:1", "tt9:1:2"), startedIds())
    }

    @Test
    fun `addons still loading leaves the entry queued without a failure`() {
        offer = { freshStreamsOrNull(addonsReady = { false }, fetch = { error("must not fetch") }) }

        LayoverDownloadQueue.add(listOf(queued(1)))

        assertNull(LayoverDownloadQueue.pending.value.single().failure)
    }

    @Test
    fun `addons answered but offered nothing marks no phone-sized source`() {
        offer = { freshStreamsOrNull({ true }, { StreamFetchResult(emptyList(), anyAddonAnswered = true) }) }

        LayoverDownloadQueue.add(listOf(queued(1)))

        assertEquals(NO_SOURCE_FAILURE, LayoverDownloadQueue.pending.value.single().failure)
    }

    // Review fix 2: a download being recovered still holds its slot.

    @Test
    fun `a download re-finding its expired link holds a slot, so no third download starts`() {
        downloads += download("tt9:1:8", DownloadStatus.Downloading)
        downloads += download("tt9:1:9", DownloadStatus.Failed).copy(errorMessage = FINDING_SOURCE_MESSAGE)

        LayoverDownloadQueue.add((1..3).map(::queued))
        assertEquals(emptyList(), startedIds())

        // Recovery gave up: the slot frees and the queue moves on.
        downloads.replaceAll { if (it.videoId == "tt9:1:9") it.copy(errorMessage = SOURCE_GONE_MESSAGE) else it }
        LayoverDownloadQueue.pump()
        assertEquals(listOf("tt9:1:1"), startedIds())
    }

    @Test
    fun `recovery in flight and the Ready check retry also hold slots`() {
        downloads += download("tt9:1:8", DownloadStatus.Failed)
        recovering = setOf("d-tt9:1:8")
        downloads += download("tt9:1:9", DownloadStatus.Failed).copy(errorMessage = FILE_CHECK_RETRYING_MESSAGE)

        LayoverDownloadQueue.add(listOf(queued(1)))
        assertEquals(emptyList(), startedIds())

        recovering = emptySet()
        LayoverDownloadQueue.pump()
        assertEquals(listOf("tt9:1:1"), startedIds())
    }

    @Test
    fun `slot counting`() {
        val active = listOf(
            download("a", DownloadStatus.Downloading),
            download("b", DownloadStatus.Failed).copy(errorMessage = FINDING_SOURCE_MESSAGE),
            download("c", DownloadStatus.Failed).copy(errorMessage = FILE_CHECK_RETRYING_MESSAGE),
            download("d", DownloadStatus.Failed),
        )
        val idle = listOf(
            download("e", DownloadStatus.Failed).copy(errorMessage = SOURCE_GONE_MESSAGE),
            download("f", DownloadStatus.Failed).copy(errorMessage = FILE_CHECK_RETRYING_MESSAGE, recoveryAttempts = 1),
            download("g", DownloadStatus.Paused),
            download("h", DownloadStatus.Completed),
        )
        assertEquals(4, activeDownloadCount(active + idle, recovering = setOf("d-d")))
        assertEquals(setOf("a", "b", "c", "d", "h"), alreadyHave(active + idle, recovering = setOf("d-d")))
    }

    @Test
    fun `downloads waiting for the addons to come back keep their slots, so resuming them never makes four`() {
        // E1 and E2 were transferring, the phone went offline, recovery couldn't reach the addons.
        downloads += download("tt9:1:1", DownloadStatus.Failed).copy(errorMessage = ADDONS_UNREACHABLE_MESSAGE)
        downloads += download("tt9:1:2", DownloadStatus.Failed).copy(errorMessage = ADDONS_UNREACHABLE_MESSAGE)

        // Back online, the user taps more episodes: nothing extra starts.
        LayoverDownloadQueue.add((3..5).map(::queued))
        assertEquals(emptyList(), startedIds())
        assertEquals(2, activeDownloadCount(downloads, recovering))

        // The next load recovers E1 and E2 (same file, resume): still two at once, the queue still waits.
        downloads.replaceAll { it.copy(status = DownloadStatus.Downloading, errorMessage = null) }
        LayoverDownloadQueue.pump()
        assertEquals(emptyList(), startedIds())
        assertTrue(activeDownloadCount(downloads, recovering) <= LayoverDownloadQueue.MAX_ACTIVE)

        // One finishes: exactly one queued episode takes its place.
        downloads.replaceAll { if (it.videoId == "tt9:1:1") it.copy(status = DownloadStatus.Completed) else it }
        LayoverDownloadQueue.pump()
        assertEquals(listOf("tt9:1:3"), startedIds())
    }

    // Review fix round 2: a profile switch never lets an old-profile start land in the new profile.

    @Test
    fun `a start from the old profile already posted to Main is dropped after a profile switch`() {
        val main = ManualDispatcher()
        LayoverDownloadQueue.mainDispatcher = main
        LayoverDownloadQueue.workDispatcher = Dispatchers.Unconfined
        var profile = "A"
        val storage = mutableMapOf<String, String?>()
        LayoverDownloadQueue.loader = { storage[profile] }
        LayoverDownloadQueue.saver = { storage[profile] = it }
        LayoverDownloadQueue.resetForTests()
        val gate = CompletableDeferred<Unit>()
        offer = { videoId -> if (videoId == "tt9:1:1") gate.await(); listOf(good(videoId)) }

        LayoverDownloadQueue.add(listOf(queued(1)))
        main.runAll() // pump claims E1, its fetch waits on the gate
        assertEquals(listOf("tt9:1:1"), fetched)

        // The fetch finishes and posts its Main block; then the profile switches before Main runs it.
        gate.complete(Unit)
        profile = "B"
        storage["B"] = LayoverDownloadQueue.encode(listOf(queued(7)))
        LayoverDownloadQueue.onProfileChanged()
        main.runAll()

        assertTrue("tt9:1:1" !in startedIds(), "old-profile item never enqueued into the new profile")
        assertTrue(LayoverDownloadQueue.decode(storage["B"]).none { it.videoId == "tt9:1:1" })
        assertEquals(listOf("tt9:1:1"), LayoverDownloadQueue.decode(storage["A"]).map { it.videoId }, "A's queue untouched")
        assertEquals(listOf("tt9:1:7"), startedIds(), "B's own queue pumps")
    }

    // Review fix 3: every queue write happens on the main dispatcher.

    @Test
    fun `queue writes, enqueues and re-pumps all run on the main dispatcher`() = runTest {
        val main = MarkingDispatcher(StandardTestDispatcher(testScheduler))
        LayoverDownloadQueue.mainDispatcher = main
        LayoverDownloadQueue.workDispatcher = StandardTestDispatcher(testScheduler)
        val offMain = mutableListOf<String>()
        LayoverDownloadQueue.saver = { if (!main.running) offMain += "save"; stored = it }
        val enqueueFake = LayoverDownloadQueue.enqueue
        LayoverDownloadQueue.enqueue = { item, stream -> if (!main.running) offMain += "enqueue"; enqueueFake(item, stream) }
        offer = { videoId -> if (videoId == "tt9:1:1") listOf(remux) else listOf(good(videoId)) }

        withContext(main) { LayoverDownloadQueue.add((1..4).map(::queued)) }
        // Episode 4 gets downloaded another way while 1 and 2 are being found: the re-pump drops it (a write).
        downloads += download("tt9:1:4", DownloadStatus.Completed)
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), offMain)
        assertEquals(setOf("tt9:1:2", "tt9:1:3"), startedIds().toSet())
        assertEquals(listOf("tt9:1:1"), LayoverDownloadQueue.pending.value.map { it.videoId })
        assertEquals(LayoverDownloadQueue.pending.value, LayoverDownloadQueue.decode(stored), "storage matches memory")
    }

    @Test
    fun `entries already downloaded are dropped from the queue on pump`() {
        offer = { null }
        LayoverDownloadQueue.add((1..2).map(::queued))
        downloads += download("tt9:1:1", DownloadStatus.Completed)

        LayoverDownloadQueue.pump()

        assertEquals(listOf("tt9:1:2"), LayoverDownloadQueue.pending.value.map { it.videoId })
    }

    // Smart downloads removed (2026-11-10): their leftovers go, what the user tapped stays.

    @Test
    fun `entries smart downloads queued are dropped, ones the user queued stay`() {
        offer = { null }
        val mine = queued(1)
        val auto = queued(2).copy(origin = DownloadOrigin.Smart)
        stored = LayoverDownloadQueue.encode(listOf(mine, auto))
        LayoverDownloadQueue.resetForTests()

        LayoverDownloadQueue.dropAutoDownloadsNow()

        assertEquals(listOf("tt9:1:1"), LayoverDownloadQueue.pending.value.map { it.videoId })
        assertEquals(listOf("tt9:1:1"), LayoverDownloadQueue.decode(stored).map { it.videoId }, "persisted too")
    }

    @Test
    fun `unfinished smart downloads are cancelled, finished ones and the user's are kept`() {
        val smart = DownloadOrigin.Smart
        downloads += download("tt9:1:1", DownloadStatus.Failed).copy(origin = smart)
        downloads += download("tt9:1:2", DownloadStatus.Downloading).copy(origin = smart)
        downloads += download("tt9:1:3", DownloadStatus.Completed).copy(origin = smart)
        downloads += download("tt9:1:4", DownloadStatus.Failed)
        downloads += download("tt9:1:5", DownloadStatus.Downloading).copy(origin = DownloadOrigin.Season)

        LayoverDownloadQueue.dropAutoDownloadsNow()

        assertEquals(listOf("d-tt9:1:1", "d-tt9:1:2"), cancelled)
        assertEquals(listOf("tt9:1:3", "tt9:1:4", "tt9:1:5"), downloads.map { it.videoId })
    }

    @Test
    fun `nothing left by smart downloads changes nothing`() {
        offer = { null }
        LayoverDownloadQueue.add(listOf(queued(1)))
        val before = stored
        downloads += download("tt9:1:2", DownloadStatus.Failed)

        LayoverDownloadQueue.dropAutoDownloadsNow()

        assertEquals(before, stored)
        assertTrue(cancelled.isEmpty())
    }
}
