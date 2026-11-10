package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.AndroidDownloadScheduler
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.downloadItem
import com.nuvio.app.features.layover.streams.StreamIdentity
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Smallest body that passes the Ready check: MKV magic bytes padded to 1 MiB. */
internal val readyTestVideo: ByteArray = ByteArray(1_048_576).also {
    it[0] = 0x1A; it[1] = 0x45; it[2] = 0xDF.toByte(); it[3] = 0xA3.toByte()
}

internal fun readyVideoResponse(): MockResponse = MockResponse().setBody(Buffer().write(readyTestVideo))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadyCheckSchedulerTest {
    private val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())

    @Test
    fun `a real video completes and is marked verified`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(readyVideoResponse())
            val item = downloadItem(server.url("/video.mkv").toString(), "ready").copy(fileName = "ready.mkv")
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            val done = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Completed, done.status)
            assertTrue(done.verified)
            assertContentEquals(readyTestVideo, File(scheduler.directory, item.fileName).readBytes())
        }
    }

    @Test
    fun `a tiny error body is never completed, and no file is left behind`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>link expired</html>"))
            val item = downloadItem(server.url("/video.mkv").toString(), "tiny").copy(fileName = "tiny.mkv")
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            val failed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Failed, failed.status)
            assertEquals("File check failed, retrying", failed.errorMessage)
            assertFalse(failed.verified)
            assertNull(failed.localFileUri)
            assertFalse(File(scheduler.directory, item.fileName).exists())
            assertFalse(File(scheduler.directory, "${item.fileName}.part").exists())
        }
    }

    @Test
    fun `a body cut short with no content length fails against the addon's size`(): Unit = runBlocking {
        MockWebServer().use { server ->
            // Chunked: the server sends no Content-Length, so only the addon's videoSize can catch the short file.
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(readyTestVideo), 65_536))
            val identity = StreamIdentity("addon", "movie", "tt1", "Movie.mkv", readyTestVideo.size + 1_000L, null, null, null)
            val item = downloadItem(server.url("/play").toString(), "short")
                .copy(fileName = "short.mkv", streamIdentity = identity, recoveryAttempts = 1)
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            val failed = assertNotNull(scheduler.store.get(item.fileName)).item
            assertEquals(DownloadStatus.Failed, failed.status)
            assertEquals("File check failed", failed.errorMessage)
            assertFalse(File(scheduler.directory, item.fileName).exists())
        }
    }

    @Test
    fun `the wrong container for the file name is not completed`(): Unit = runBlocking {
        MockWebServer().use { server ->
            val mp4 = readyTestVideo.copyOf().also { it.fill(0, 0, 4); "ftyp".encodeToByteArray().copyInto(it, 4) }
            server.enqueue(MockResponse().setBody(Buffer().write(mp4)))
            val item = downloadItem(server.url("/Movie.mkv").toString(), "wrong").copy(fileName = "wrong.mkv")
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            assertEquals(DownloadStatus.Failed, assertNotNull(scheduler.store.get(item.fileName)).item.status)
        }
    }
}
