package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadOrigin
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.layover.streams.StreamIdentity
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileCheckTest {
    private val mkvHeader = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
    private val mp4Header = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".encodeToByteArray()
    private val size = 700_000_000L

    @Test
    fun `a few MB against a claimed real file size is a placeholder clip`() {
        val slate = 4_838_178L
        assertFalse(checkDownloadedFile(mp4Header, slate, slate, "mp4", claimedBytes = 2_576_747_154L))
        assertTrue(checkDownloadedFile(mp4Header, slate, slate, "mp4", claimedBytes = null))
        assertTrue(checkDownloadedFile(mp4Header, slate, slate, "mp4", claimedBytes = 5_000_000L))
        // A season pack's claimed size against the real episode file is not a placeholder.
        assertTrue(checkDownloadedFile(mkvHeader, size, size, "mkv", claimedBytes = 486_000_000_000L))
    }

    @Test
    fun `valid mkv header with matching sizes is ready`() {
        assertTrue(checkDownloadedFile(mkvHeader, size, size, "mkv"))
    }

    @Test
    fun `mkv extension with an mp4 header is not ready`() {
        assertFalse(checkDownloadedFile(mp4Header, size, size, "mkv"))
    }

    @Test
    fun `sizes that differ are not ready`() {
        assertFalse(checkDownloadedFile(mkvHeader, size - 1, size, "mkv"))
    }

    @Test
    fun `a 500 KB file is not ready`() {
        assertFalse(checkDownloadedFile(mkvHeader, 500_000L, 500_000L, "mkv"))
    }

    @Test
    fun `unknown extension with a sane size is ready`() {
        assertTrue(checkDownloadedFile(ByteArray(12), size, size, "bin"))
    }

    @Test
    fun `unknown expected size with a valid header is ready`() {
        assertTrue(checkDownloadedFile(mkvHeader, size, null, "mkv"))
    }

    @Test
    fun `each container needs its own magic bytes`() {
        assertTrue(checkDownloadedFile(mkvHeader, size, null, "WEBM"))
        assertTrue(checkDownloadedFile(mp4Header, size, null, "mp4"))
        assertTrue(checkDownloadedFile(mp4Header, size, null, "m4v"))
        assertTrue(checkDownloadedFile(mp4Header, size, null, "mov"))
        assertFalse(checkDownloadedFile(mkvHeader, size, null, "mp4"))
        assertTrue(checkDownloadedFile("RIFF".encodeToByteArray() + ByteArray(8), size, null, "avi"))
        assertFalse(checkDownloadedFile(mkvHeader, size, null, "avi"))
        assertTrue(checkDownloadedFile(byteArrayOf(0x47) + ByteArray(11), size, null, "ts"))
        assertFalse(checkDownloadedFile(mkvHeader, size, null, "ts"))
    }

    @Test
    fun `a header shorter than the magic is not ready`() {
        assertFalse(checkDownloadedFile(byteArrayOf(0x1A, 0x45), size, null, "mkv"))
        assertFalse(checkDownloadedFile(ByteArray(0), size, null, "mp4"))
    }

    @Test
    fun `check extension prefers the addon filename, then the url, else none`() {
        val identity = StreamIdentity("addon", "movie", "tt1", "Movie.2020.1080p.mkv", null, null, null, null)
        assertEquals("mkv", item("https://rd.example/d/X/whatever.mp4", identity).readyCheckExtension())
        assertEquals("mp4", item("https://rd.example/d/X/Movie.MP4?token=1", null).readyCheckExtension())
        assertEquals("", item("https://comet.example/playback/abc123", null).readyCheckExtension())
    }

    @Test
    fun `expected size prefers the server's total over the addon's claimed size`() {
        val identity = StreamIdentity("addon", "movie", "tt1", null, 123L, null, null, null)
        assertEquals(456L, item("https://x/y.mkv", identity).readyCheckExpectedBytes(456L))
        assertEquals(123L, item("https://x/y.mkv", identity).readyCheckExpectedBytes(null))
        assertNull(item("https://x/y.mkv", null).readyCheckExpectedBytes(0L))
    }

    @Test
    fun `retry message only while a retry is still left`() {
        assertEquals("File check failed, retrying", fileCheckFailureMessage(recoveryAttempts = 0))
        assertEquals("File check failed", fileCheckFailureMessage(recoveryAttempts = 1))
    }

    @Test
    fun `old stored download json without layover fields still decodes`() {
        val old = """
            {"id":"a_1","contentType":"movie","parentMetaId":"tt1","parentMetaType":"movie","videoId":"tt1",
             "title":"Movie","streamTitle":"Stream","providerName":"Addon","sourceUrl":"https://x/y.mkv",
             "fileName":"Movie_a_1.mkv","status":"Completed","downloadedBytes":5,"totalBytes":5,
             "createdAtEpochMs":1,"updatedAtEpochMs":2}
        """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(DownloadItem.serializer(), old)
        assertEquals(DownloadStatus.Completed, decoded.status)
        assertNull(decoded.streamIdentity)
        assertFalse(decoded.allowMetered)
        assertFalse(decoded.verified)
        assertEquals(0, decoded.recoveryAttempts)
        assertEquals(DownloadOrigin.Manual, decoded.origin)
    }

    private fun item(url: String, identity: StreamIdentity?) = DownloadItem(
        id = "id",
        contentType = "movie",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        videoId = "tt1",
        title = "Movie",
        streamTitle = "Stream",
        providerName = "Addon",
        sourceUrl = url,
        fileName = "Movie_id.mp4",
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        streamIdentity = identity,
    )
}
