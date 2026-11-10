package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadOrigin
import com.nuvio.app.features.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadBadgeTest {

    private fun item(
        status: DownloadStatus,
        allowMetered: Boolean = false,
        verified: Boolean = false,
        errorMessage: String? = null,
        recoveryAttempts: Int = 0,
    ) = DownloadItem(
        id = "d1",
        contentType = "movie",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        videoId = "tt1",
        title = "Movie",
        streamTitle = "1080p",
        providerName = "Comet",
        sourceUrl = "https://example.com/a.mkv",
        fileName = "a.mkv",
        status = status,
        errorMessage = errorMessage,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        allowMetered = allowMetered,
        verified = verified,
        recoveryAttempts = recoveryAttempts,
    )

    // badgeFor: one test per row of the rule ------------------------------------------------------

    @Test
    fun `completed is Ready`() {
        assertEquals(DownloadBadge.Ready, badgeFor(item(DownloadStatus.Completed, verified = true), onUnmetered = true))
    }

    @Test
    fun `completed from before Layover (not verified) is still Ready`() {
        assertEquals(DownloadBadge.Ready, badgeFor(item(DownloadStatus.Completed, verified = false), onUnmetered = false))
    }

    @Test
    fun `downloading Wi-Fi only while on mobile data is WaitingForWifi`() {
        assertEquals(
            DownloadBadge.WaitingForWifi,
            badgeFor(item(DownloadStatus.Downloading, allowMetered = false), onUnmetered = false),
        )
    }

    @Test
    fun `downloading Wi-Fi only while on Wi-Fi is Downloading`() {
        assertEquals(
            DownloadBadge.Downloading,
            badgeFor(item(DownloadStatus.Downloading, allowMetered = false), onUnmetered = true),
        )
    }

    @Test
    fun `downloading with mobile data allowed is Downloading even on mobile data`() {
        assertEquals(
            DownloadBadge.Downloading,
            badgeFor(item(DownloadStatus.Downloading, allowMetered = true), onUnmetered = false),
        )
    }

    @Test
    fun `paused is Waiting`() {
        assertEquals(DownloadBadge.Waiting, badgeFor(item(DownloadStatus.Paused), onUnmetered = true))
    }

    @Test
    fun `failed is Failed`() {
        assertEquals(
            DownloadBadge.Failed,
            badgeFor(item(DownloadStatus.Failed, errorMessage = "HTTP 500"), onUnmetered = true),
        )
    }

    @Test
    fun `failed with source gone is Failed`() {
        assertEquals(
            DownloadBadge.Failed,
            badgeFor(item(DownloadStatus.Failed, errorMessage = SOURCE_GONE_MESSAGE), onUnmetered = true),
        )
    }

    @Test
    fun `failed while finding the file again shows as Downloading`() {
        assertEquals(
            DownloadBadge.Downloading,
            badgeFor(item(DownloadStatus.Failed, errorMessage = FINDING_SOURCE_MESSAGE), onUnmetered = true),
        )
    }

    @Test
    fun `failed but in recovery flight shows as Downloading`() {
        assertEquals(
            DownloadBadge.Downloading,
            badgeFor(item(DownloadStatus.Failed, errorMessage = "HTTP 403"), onUnmetered = true, recovering = setOf("d1")),
        )
    }

    @Test
    fun `failed Ready check on its one retry shows as Downloading`() {
        assertEquals(
            DownloadBadge.Downloading,
            badgeFor(
                item(DownloadStatus.Failed, errorMessage = FILE_CHECK_RETRYING_MESSAGE, recoveryAttempts = 0),
                onUnmetered = true,
            ),
        )
    }

    @Test
    fun `failed Ready check after the retry is Failed`() {
        assertEquals(
            DownloadBadge.Failed,
            badgeFor(item(DownloadStatus.Failed, errorMessage = FILE_CHECK_FAILED_MESSAGE, recoveryAttempts = 1), onUnmetered = true),
        )
    }

    @Test
    fun `addons unreachable is Waiting, not Failed or Downloading`() {
        assertEquals(
            DownloadBadge.Waiting,
            badgeFor(item(DownloadStatus.Failed, errorMessage = ADDONS_UNREACHABLE_MESSAGE), onUnmetered = true),
        )
    }

    // Labels ----------------------------------------------------------------------------------------

    @Test
    fun `badge labels`() {
        assertEquals(
            listOf("Ready", "Downloading", "Waiting for Wi-Fi", "Waiting", "Failed"),
            DownloadBadge.entries.map { it.label },
        )
    }

    // Row actions ------------------------------------------------------------------------------------

    @Test
    fun `use mobile data shows only for WaitingForWifi`() {
        val waiting = item(DownloadStatus.Downloading, allowMetered = false)
        assertTrue(rowActionsFor(waiting, DownloadBadge.WaitingForWifi).useMobileData)
        assertFalse(rowActionsFor(waiting, DownloadBadge.Downloading).useMobileData)
        DownloadBadge.entries.filter { it != DownloadBadge.WaitingForWifi }.forEach { badge ->
            assertFalse(rowActionsFor(item(DownloadStatus.Downloading), badge).useMobileData, "badge $badge")
        }
    }

    @Test
    fun `failed keeps upstream retry`() {
        val actions = rowActionsFor(item(DownloadStatus.Failed, errorMessage = SOURCE_GONE_MESSAGE), DownloadBadge.Failed)
        assertEquals(RowActions(retry = true), actions)
    }

    @Test
    fun `addons unreachable waiting offers retry`() {
        val actions = rowActionsFor(item(DownloadStatus.Failed, errorMessage = ADDONS_UNREACHABLE_MESSAGE), DownloadBadge.Waiting)
        assertEquals(RowActions(retry = true), actions)
    }

    @Test
    fun `recovering item offers no retry or pause while it finds the file`() {
        val actions = rowActionsFor(item(DownloadStatus.Failed, errorMessage = FINDING_SOURCE_MESSAGE), DownloadBadge.Downloading)
        assertEquals(RowActions(), actions)
    }

    @Test
    fun `downloading offers pause, waiting for Wi-Fi adds use mobile data`() {
        assertEquals(RowActions(pause = true), rowActionsFor(item(DownloadStatus.Downloading), DownloadBadge.Downloading))
        assertEquals(
            RowActions(pause = true, useMobileData = true),
            rowActionsFor(item(DownloadStatus.Downloading), DownloadBadge.WaitingForWifi),
        )
    }

    @Test
    fun `paused offers resume and ready offers play`() {
        assertEquals(RowActions(resume = true), rowActionsFor(item(DownloadStatus.Paused), DownloadBadge.Waiting))
        assertEquals(RowActions(play = true), rowActionsFor(item(DownloadStatus.Completed), DownloadBadge.Ready))
    }

    // Waiting section ------------------------------------------------------------------------------------

    private fun queued(
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
        poster: String? = "poster.jpg",
        background: String? = null,
        failure: String? = null,
    ) = QueuedDownload(
        contentType = if (season == null) "movie" else "series",
        videoId = videoId,
        parentMetaId = "tt9",
        parentMetaType = if (season == null) "movie" else "series",
        title = "Show",
        logo = null,
        poster = poster,
        background = background,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        episodeThumbnail = null,
        origin = DownloadOrigin.Season,
        allowMetered = false,
        queuedAtEpochMs = 0L,
        failure = failure,
    )

    @Test
    fun `waiting rows keep queue order`() {
        val rows = waitingRows(listOf(queued("b"), queued("a"), queued("c")))
        assertEquals(listOf("b", "a", "c"), rows.map { it.videoId })
    }

    @Test
    fun `episode row shows season and episode with the episode title`() {
        val row = waitingRows(listOf(queued("e", season = 1, episode = 2, episodeTitle = "Pilot"))).single()
        assertEquals("Show", row.title)
        assertEquals("S1 E2 • Pilot", row.subtitle)
    }

    @Test
    fun `episode row without an episode title shows just the code`() {
        val row = waitingRows(listOf(queued("e", season = 3, episode = 10, episodeTitle = " "))).single()
        assertEquals("S3 E10", row.subtitle)
    }

    @Test
    fun `movie row has no subtitle`() {
        assertNull(waitingRows(listOf(queued("m"))).single().subtitle)
    }

    @Test
    fun `poster falls back to the background`() {
        assertEquals("poster.jpg", waitingRows(listOf(queued("m"))).single().poster)
        assertEquals("bg.jpg", waitingRows(listOf(queued("m", poster = null, background = "bg.jpg"))).single().poster)
    }

    @Test
    fun `entry without failure says Waiting and offers no retry`() {
        val row = waitingRows(listOf(queued("m"))).single()
        assertEquals("Waiting", row.status)
        assertFalse(row.canRetry)
    }

    @Test
    fun `entry with failure shows the failure and offers retry`() {
        val row = waitingRows(listOf(queued("m", failure = NO_SOURCE_FAILURE))).single()
        assertEquals(NO_SOURCE_FAILURE, row.status)
        assertTrue(row.canRetry)
    }
}
