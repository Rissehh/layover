package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.layover.streams.StreamIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeSpaceTest {
    private val mb = 1024L * 1024

    @Test
    fun `room means the file plus a 200 MB margin, after what running downloads still need`() {
        assertTrue(hasRoomFor(neededBytes = 1_000 * mb, freeBytes = 1_200 * mb))
        assertFalse(hasRoomFor(neededBytes = 1_000 * mb, freeBytes = 1_200 * mb - 1))
        assertFalse(hasRoomFor(neededBytes = 1_000 * mb, freeBytes = 2_000 * mb, reservedBytes = 900 * mb))
        assertTrue(hasRoomFor(neededBytes = 1_000 * mb, freeBytes = 2_000 * mb, reservedBytes = 800 * mb))
    }

    @Test
    fun `unknown free space never blocks`() {
        assertTrue(hasRoomFor(neededBytes = Long.MAX_VALUE / 2, freeBytes = null))
    }

    @Test
    fun `no space errors are recognised`() {
        assertTrue(isNoSpaceError("write failed: ENOSPC (No space left on device)"))
        assertTrue(isNoSpaceError("no space left"))
        assertTrue(isNoSpaceError("ENOSPC"))
        assertFalse(isNoSpaceError("Download failed: HTTP 410"))
        assertFalse(isNoSpaceError(NO_SPACE_MESSAGE))
        assertFalse(isNoSpaceError(null))
    }

    private fun download(id: String, status: DownloadStatus, downloaded: Long, total: Long?, claimed: Long? = null, error: String? = null) =
        DownloadItem(
            id = id,
            contentType = "movie",
            parentMetaId = "tt1",
            parentMetaType = "movie",
            videoId = id,
            title = "Movie",
            streamTitle = "Comet",
            providerName = "Comet",
            sourceUrl = "https://comet.example/$id",
            fileName = "$id.mkv",
            status = status,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
            downloadedBytes = downloaded,
            totalBytes = total,
            errorMessage = error,
            streamIdentity = claimed?.let { StreamIdentity("addon", "movie", id, "$id.mkv", it, null, null, null) },
        )

    @Test
    fun `reserved bytes are what slot holders still have to write`() {
        val items = listOf(
            download("a", DownloadStatus.Downloading, downloaded = 300 * mb, total = 1_000 * mb),
            // No length from the server yet: the addon's claimed size stands in.
            download("b", DownloadStatus.Downloading, downloaded = 100 * mb, total = null, claimed = 600 * mb),
            // Unknown size: nothing to reserve.
            download("c", DownloadStatus.Downloading, downloaded = 50 * mb, total = null),
            // Holds no slot: paused, finished, out of space.
            download("d", DownloadStatus.Paused, downloaded = 0, total = 2_000 * mb),
            download("e", DownloadStatus.Completed, downloaded = 900 * mb, total = 900 * mb),
            download("f", DownloadStatus.Failed, downloaded = 10 * mb, total = 900 * mb, error = NO_SPACE_MESSAGE),
        )

        assertEquals(700 * mb + 500 * mb, reservedBytes(items, recovering = emptySet()))
    }
}
