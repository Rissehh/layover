package com.nuvio.app.features.layover.log

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.layover.streams.StreamIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadAttemptRecordTest {
    private val identity = StreamIdentity(
        addonId = "addon:comet",
        type = "series",
        videoId = "tt9:1:2",
        filename = "Show.S01E02.1080p.WEB-DL.x265-NTb.mkv",
        videoSize = 1_500_000_000L,
        infoHash = "abc",
        fileIdx = 3,
        bingeGroup = null,
    )

    private fun item(identity: StreamIdentity?, providerAddonId: String?) = DownloadItem(
        id = "d1",
        contentType = "series",
        parentMetaId = "tt9",
        parentMetaType = "series",
        videoId = "tt9:1:2",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 2,
        streamTitle = "Comet 1080p",
        streamSubtitle = "Show.S01E02.1080p.WEB-DL.x265-NTb.mkv",
        providerName = "Comet",
        providerAddonId = providerAddonId,
        sourceUrl = "https://rd.example/d/X/file.mkv",
        fileName = "Show_S01E02_d1.mkv",
        status = DownloadStatus.Completed,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        streamIdentity = identity,
    )

    @Test
    fun `download record carries the stream's facts and the download content key`() {
        val record = attemptRecordForDownload(item(identity, "addon:other"), AttemptOutcome.DownloadCompleted, now = 42L)

        assertEquals(42L, record.atEpochMs)
        assertEquals(AttemptKind.Download, record.kind)
        assertEquals("tt9|1|2", record.contentKey)
        assertEquals("addon:comet", record.addonId)
        assertEquals("Comet", record.addonName)
        assertEquals(1080, record.height)
        assertEquals(1_500_000_000L, record.sizeBytes)
        assertEquals("NTb", record.releaseGroup)
        assertNull(record.timeToFirstFrameMs)
        assertEquals(AttemptOutcome.DownloadCompleted, record.outcome)
    }

    @Test
    fun `addon id falls back to provider addon id, then provider name`() {
        assertEquals("addon:other", attemptRecordForDownload(item(null, "addon:other"), AttemptOutcome.DownloadFailed, 1L).addonId)
        assertEquals("Comet", attemptRecordForDownload(item(null, null), AttemptOutcome.DownloadFailed, 1L).addonId)
    }
}
