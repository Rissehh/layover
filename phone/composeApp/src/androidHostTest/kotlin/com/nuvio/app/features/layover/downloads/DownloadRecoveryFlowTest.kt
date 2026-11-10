package com.nuvio.app.features.layover.downloads

import android.os.Looper
import com.nuvio.app.features.downloads.AndroidDownloadScheduler
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadOrigin
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.log.AttemptOutcome
import com.nuvio.app.features.layover.log.AttemptRecord
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review focus 2: a debrid link that expires mid-download (403/404/410 after a pause or overnight)
 * re-finds the same file through its addon and resumes from the .part bytes instead of starting over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadRecoveryFlowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var scheduler: AndroidDownloadScheduler
    private val server = MockWebServer()
    private val logged = mutableListOf<AttemptRecord>()
    private var offered: List<StreamItem> = emptyList()
    private var answer: suspend () -> List<StreamItem>? = { offered }
    private val fetches = mutableListOf<Pair<String, String>>()
    private val half = readyTestVideo.size / 2

    private val originalFetch = DownloadRecovery.fetchStreams
    private val originalSettings = DownloadRecovery.settings
    private val originalLog = DownloadRecovery.logAttempt
    private val originalWork = DownloadRecovery.workDispatcher
    private val originalMain = DownloadRecovery.mainDispatcher

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/expired" -> MockResponse().setResponseCode(410)
                "/fresh", "/other" -> rangedVideo(request.getHeader("Range"))
                "/tiny" -> MockResponse().setBody("<html>not a video</html>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        scheduler = DownloadsPlatformDownloader.scheduler(context)
        DownloadsRepository.clearLocalState()
        DownloadRecovery.fetchStreams = { type, videoId -> fetches += type to videoId; answer() }
        DownloadRecovery.settings = { LayoverSettings() }
        DownloadRecovery.logAttempt = { logged += it }
        DownloadRecovery.workDispatcher = Dispatchers.Unconfined
        DownloadRecovery.mainDispatcher = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        DownloadRecovery.fetchStreams = originalFetch
        DownloadRecovery.settings = originalSettings
        DownloadRecovery.logAttempt = originalLog
        DownloadRecovery.workDispatcher = originalWork
        DownloadRecovery.mainDispatcher = originalMain
        server.shutdown()
    }

    @Test
    fun `an expired link mid-download re-finds the same file and resumes from the part bytes`() {
        // The addon still has the file and mints a fresh link for it.
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh", headers = mapOf("X-Token" to "fresh")))
        val failed = startAndExpire(fileHint = "Movie.2024.1080p.WEB-DL.mkv", partBytes = half)

        val resumed = current()
        assertEquals(listOf("movie" to "tt1"), fetches)
        assertEquals(failed.id, resumed.id)
        assertEquals(DownloadStatus.Downloading, resumed.status)
        assertEquals(url("/fresh"), resumed.sourceUrl)
        assertEquals(mapOf("X-Token" to "fresh"), resumed.sourceHeaders)
        assertEquals(failed.fileName, resumed.fileName)
        assertEquals(1, resumed.recoveryAttempts)
        // The bytes already on disk survived the failure and the swap.
        assertEquals(half.toLong(), partFile(failed).length())
        assertEquals(url("/fresh"), assertNotNull(scheduler.store.get(failed.fileName)).item.sourceUrl)

        runTransfer(failed.fileName)

        val expired = server.takeRequest()
        assertEquals("/expired", expired.path)
        val fresh = server.takeRequest()
        assertEquals("/fresh", fresh.path)
        assertEquals("bytes=$half-", fresh.getHeader("Range"))
        assertEquals("fresh", fresh.getHeader("X-Token"))
        assertContentEquals(readyTestVideo, File(scheduler.directory, failed.fileName).readBytes())
        assertEquals(DownloadStatus.Completed, current().status)
        assertTrue(logged.none { it.outcome == AttemptOutcome.DownloadFailed })
    }

    @Test
    fun `when the file is gone the next best stream replaces it with the same origin and data setting`() {
        offered = listOf(stream("Movie.2024.720p.WEB-DL.mkv", "/other", "Movie.2024.720p.WEB-DL\n💾 900 MB"))

        val failed = startAndExpire(
            fileHint = "Movie.2024.1080p.WEB-DL.mkv",
            partBytes = half,
            origin = DownloadOrigin.Smart,
            allowMetered = true,
        )

        val replacement = DownloadsRepository.uiState.value.items.single()
        assertNotEquals(failed.id, replacement.id)
        assertEquals(url("/other"), replacement.sourceUrl)
        assertEquals("Movie.2024.720p.WEB-DL.mkv", replacement.streamIdentity?.filename)
        assertEquals(DownloadOrigin.Smart, replacement.origin)
        assertTrue(replacement.allowMetered)
        assertEquals(1, replacement.recoveryAttempts)
        assertEquals(DownloadStatus.Downloading, replacement.status)
        assertEquals(failed.logicalContentKey, replacement.logicalContentKey)
        val record = logged.single()
        assertEquals(AttemptOutcome.DownloadFailed, record.outcome)
        assertEquals(failed.logicalContentKey, record.contentKey)
        assertEquals(1080, record.height)
    }

    @Test
    fun `nothing left gives up with a clear message, and retry picks again from fresh streams`() {
        val failed = startAndExpire(fileHint = "Movie.2024.1080p.WEB-DL.mkv", partBytes = half)

        val gaveUp = current()
        assertEquals(DownloadStatus.Failed, gaveUp.status)
        assertEquals("Source gone. Tap retry to pick another.", gaveUp.errorMessage)
        assertEquals(AttemptOutcome.DownloadFailed, logged.single().outcome)

        // The addon has the file again by the time the user taps retry.
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))
        DownloadsRepository.retryDownload(failed.id)
        idle()

        val retried = current()
        assertEquals(2, fetches.size)
        assertEquals(DownloadStatus.Downloading, retried.status)
        assertEquals(url("/fresh"), retried.sourceUrl)
        assertEquals(0, retried.recoveryAttempts)
        assertEquals(half.toLong(), partFile(failed).length())
        assertEquals(1, logged.size)
    }

    @Test
    fun `after two recoveries it gives up without asking the addons again`() {
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))

        startAndExpire(fileHint = "Movie.2024.1080p.WEB-DL.mkv", partBytes = half, recoveryAttempts = 2)

        assertTrue(fetches.isEmpty())
        assertEquals("Source gone. Tap retry to pick another.", current().errorMessage)
    }

    @Test
    fun `killed while finding the file again recovers on the next load instead of showing that message forever`() {
        answer = { awaitCancellation() }
        val failed = startAndExpire(fileHint = "Movie.2024.1080p.WEB-DL.mkv", partBytes = half)
        assertEquals("Download interrupted, finding the file again", current().errorMessage)

        // Process death: the in-flight fetch dies and the list is loaded again from storage.
        DownloadRecovery.cancelAllForTests()
        answer = { offered }
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))
        DownloadsRepository.onProfileChanged()
        idle()

        val recovered = current()
        assertEquals(2, fetches.size)
        assertEquals(failed.id, recovered.id)
        assertEquals(DownloadStatus.Downloading, recovered.status)
        assertEquals(url("/fresh"), recovered.sourceUrl)
        assertEquals(half.toLong(), partFile(failed).length())
    }

    @Test
    fun `a stale finding message never comes back when the addons can't be asked`() {
        answer = { awaitCancellation() }
        val failed = startAndExpire(fileHint = "Movie.2024.1080p.WEB-DL.mkv", partBytes = half)
        DownloadRecovery.cancelAllForTests()
        answer = { null }

        DownloadsRepository.retryDownload(failed.id)
        idle()

        assertEquals(2, fetches.size)
        assertEquals(DownloadStatus.Failed, current().status)
        // Unreachable addons are not "Source gone": nothing was spent and Retry recovers the same file later.
        assertEquals(ADDONS_UNREACHABLE_MESSAGE, current().errorMessage)
        assertEquals(failed.recoveryAttempts, current().recoveryAttempts)

        answer = { offered }
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))
        DownloadsRepository.retryDownload(failed.id)
        idle()

        assertEquals(DownloadStatus.Downloading, current().status)
        assertEquals(url("/fresh"), current().sourceUrl)
        assertEquals(half.toLong(), partFile(failed).length())
    }

    @Test
    fun `a recovery that throws ends in Source gone and a later retry still runs`() {
        DownloadRecovery.settings = { error("boom") }
        offered = listOf(stream("Movie.2024.720p.WEB-DL.mkv", "/other", "Movie.2024.720p.WEB-DL\n💾 900 MB"))
        val failed = startAndExpire(fileHint = "Movie.2024.1080p.WEB-DL.mkv", partBytes = half)

        assertEquals(DownloadStatus.Failed, current().status)
        assertEquals("Source gone. Tap retry to pick another.", current().errorMessage)

        DownloadRecovery.settings = { LayoverSettings() }
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))
        DownloadsRepository.retryDownload(failed.id)
        idle()

        assertEquals(2, fetches.size)
        assertEquals(DownloadStatus.Downloading, current().status)
        assertEquals(url("/fresh"), current().sourceUrl)
    }

    @Test
    fun `a failure while applying the plan ends in Source gone and a later retry is not swallowed`() {
        // OtherFile logs the old stream first; a throw there leaves the row mid-recovery unless it falls back.
        DownloadRecovery.logAttempt = { error("log storage broken") }
        offered = listOf(stream("Movie.2024.720p.WEB-DL.mkv", "/other", "Movie.2024.720p.WEB-DL\n💾 900 MB"))
        val failed = startAndExpire(fileHint = "Movie.2024.1080p.WEB-DL.mkv", partBytes = half)

        assertEquals(failed.id, current().id)
        assertEquals(DownloadStatus.Failed, current().status)
        assertEquals("Source gone. Tap retry to pick another.", current().errorMessage)

        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))
        DownloadsRepository.retryDownload(failed.id)
        idle()

        assertEquals(2, fetches.size)
        assertEquals(DownloadStatus.Downloading, current().status)
    }

    @Test
    fun `ready check failures and paused downloads are left alone`() {
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))
        val item = enqueue("Movie.2024.1080p.WEB-DL.mkv", DownloadOrigin.Manual, allowMetered = false, recoveryAttempts = 0)

        DownloadRecovery.onDownloadFailed(item.copy(status = DownloadStatus.Failed, errorMessage = FILE_CHECK_RETRYING_MESSAGE))
        DownloadRecovery.onDownloadFailed(item.copy(status = DownloadStatus.Failed, errorMessage = FILE_CHECK_FAILED_MESSAGE))
        DownloadRecovery.onDownloadFailed(item.copy(status = DownloadStatus.Paused))
        DownloadRecovery.onDownloadFailed(item.copy(status = DownloadStatus.Failed, streamIdentity = null))

        assertTrue(fetches.isEmpty())
        assertEquals(item.sourceUrl, current().sourceUrl)
    }

    // Final review fix 5: Retry after a second Ready check failure must not replay the same bad file.

    @Test
    fun `retry after the Ready check failed twice picks a different file and logs one failure`() {
        offered = listOf(
            stream("Movie.2024.1080p.WEB-DL.mkv", "/tiny"),
            stream("Movie.2024.720p.WEB-DL.mkv", "/other", "Movie.2024.720p.WEB-DL\n💾 900 MB"),
        )
        val item = enqueue("Movie.2024.1080p.WEB-DL.mkv", DownloadOrigin.Manual, allowMetered = false, recoveryAttempts = 0, path = "/tiny")

        runTransfer(item.fileName) // first check fails, the one clean retry starts
        assertEquals(DownloadStatus.Downloading, current().status)
        assertEquals(1, current().recoveryAttempts)
        runTransfer(item.fileName) // the clean retry fails too
        assertEquals(DownloadStatus.Failed, current().status)
        assertEquals("File check failed", current().errorMessage)

        DownloadsRepository.retryDownload(item.id)
        idle()

        val retried = current()
        assertEquals(1, fetches.size)
        assertNotEquals(url("/tiny"), retried.sourceUrl)
        assertEquals(url("/other"), retried.sourceUrl)
        assertEquals(DownloadStatus.Downloading, retried.status)
        assertEquals(1, logged.count { it.outcome == AttemptOutcome.DownloadFailed })
    }

    // Final review fix 3: the network coming back wakes recoveries that couldn't reach the addons.

    @Test
    fun `a reconnect re-finds a download that was waiting on unreachable addons`() {
        val item = enqueue("Movie.2024.1080p.WEB-DL.mkv", DownloadOrigin.Manual, allowMetered = false, recoveryAttempts = 0)
        DownloadsRepository.layoverUpdateItem(item.id) {
            it.copy(status = DownloadStatus.Failed, errorMessage = ADDONS_UNREACHABLE_MESSAGE)
        }
        offered = listOf(stream("Movie.2024.1080p.WEB-DL.mkv", "/fresh"))

        DownloadRecovery.retryUnreachable()
        idle()

        assertEquals(listOf("movie" to "tt1"), fetches)
        assertEquals(DownloadStatus.Downloading, current().status)
        assertEquals(url("/fresh"), current().sourceUrl)
        assertEquals(1, current().recoveryAttempts)
        assertTrue(logged.isEmpty())
    }

    // Final review fix 4b: a full phone is not a dead link.

    @Test
    fun `a no space failure starts no recovery, logs nothing, frees its slot, and retry resumes the same file`() {
        offered = listOf(stream("Movie.2024.720p.WEB-DL.mkv", "/other", "Movie.2024.720p.WEB-DL\n💾 900 MB"))
        val item = enqueue("Movie.2024.1080p.WEB-DL.mkv", DownloadOrigin.Manual, allowMetered = false, recoveryAttempts = 0)
        DownloadsRepository.layoverUpdateItem(item.id) {
            it.copy(status = DownloadStatus.Failed, errorMessage = "write failed: ENOSPC (No space left on device)")
        }

        DownloadRecovery.onDownloadFailed(current())
        idle()

        assertTrue(fetches.isEmpty())
        assertTrue(logged.isEmpty())
        assertEquals(DownloadStatus.Failed, current().status)
        assertEquals("Not enough space on the phone", current().errorMessage)
        assertEquals(0, activeDownloadCount(listOf(current()), DownloadRecovery.inFlightIds()))

        DownloadsRepository.retryDownload(item.id)
        idle()

        assertTrue(fetches.isEmpty())
        assertEquals(DownloadStatus.Downloading, current().status)
        assertEquals(item.sourceUrl, current().sourceUrl)
    }

    /** Enqueues a download on the expired url, puts [partBytes] on disk, then lets the transfer hit the 410. */
    private fun startAndExpire(
        fileHint: String,
        partBytes: Int,
        origin: DownloadOrigin = DownloadOrigin.Manual,
        allowMetered: Boolean = false,
        recoveryAttempts: Int = 0,
    ): DownloadItem {
        val item = enqueue(fileHint, origin, allowMetered, recoveryAttempts)
        scheduler.directory.mkdirs()
        partFile(item).writeBytes(readyTestVideo.copyOfRange(0, partBytes))
        runTransfer(item.fileName)
        return item
    }

    private fun enqueue(
        fileHint: String,
        origin: DownloadOrigin,
        allowMetered: Boolean,
        recoveryAttempts: Int,
        path: String = "/expired",
    ): DownloadItem {
        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt1", parentMetaId = "tt1", parentMetaType = "movie",
            title = "Movie", logo = null, poster = "https://img.example/poster.jpg", background = null,
            seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
            stream = stream(fileHint, path),
            allowMetered = allowMetered,
            origin = origin,
            recoveryAttempts = recoveryAttempts,
        )
        idle()
        return current()
    }

    private fun runTransfer(fileName: String) {
        runBlocking { scheduler.execute(assertNotNull(scheduler.store.get(fileName))) { } }
        idle()
    }

    private fun stream(
        filename: String,
        path: String,
        description: String = "Movie.2024.1080p.WEB-DL\n💾 1 GB",
        headers: Map<String, String>? = null,
    ) = StreamItem(
        name = "Comet 1080p",
        description = description,
        url = url(path),
        addonName = "Comet",
        addonId = "addon:comet",
        behaviorHints = StreamBehaviorHints(
            filename = filename,
            proxyHeaders = headers?.let { StreamProxyHeaders(request = it) },
        ),
    )

    private fun rangedVideo(range: String?): MockResponse {
        val total = readyTestVideo.size
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        val response = MockResponse().setBody(Buffer().write(readyTestVideo, start, total - start))
        return if (start > 0) {
            response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${total - 1}/$total")
        } else response
    }

    private fun url(path: String) = server.url(path).toString()
    private fun current(): DownloadItem = DownloadsRepository.uiState.value.items.single()
    private fun partFile(item: DownloadItem) = File(scheduler.directory, "${item.fileName}.part")
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
