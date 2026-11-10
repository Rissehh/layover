package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadOrigin
import com.nuvio.app.features.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadButtonsTest {
    private fun ep(n: Int, available: Boolean = true, id: String = "tt9:1:$n") =
        MetaVideo(id = id, title = "Episode $n", season = 1, episode = n, available = available, thumbnail = "thumb$n")

    private val show = MetaDetails(
        id = "tt9",
        type = "series",
        name = "Show",
        poster = "poster",
        videos = listOf(ep(3), ep(1), ep(2)),
    )

    private val movie = MetaDetails(id = "tt1", type = "movie", name = "Movie", poster = "p", background = "b", logo = "l")

    private fun download(
        parentMetaId: String,
        season: Int?,
        episode: Int?,
        status: DownloadStatus,
        videoId: String = "v",
        downloaded: Long = 0L,
        total: Long? = null,
    ) = DownloadItem(
        id = "d-$parentMetaId-$season-$episode",
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = videoId,
        title = "x",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "s",
        providerName = "p",
        sourceUrl = "https://x",
        fileName = "f",
        status = status,
        downloadedBytes = downloaded,
        totalBytes = total,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    @Test
    fun `movie queues one manual item with the meta id as video id`() {
        val q = movie.queuedMovie(allowMetered = false, now = 5L)
        assertEquals("tt1", q.videoId)
        assertEquals("movie", q.contentType)
        assertEquals(DownloadOrigin.Manual, q.origin)
        assertEquals(null, q.seasonNumber)
        assertEquals("p", q.poster)
        assertEquals(5L, q.queuedAtEpochMs)
    }

    @Test
    fun `only movies get the movie button`() {
        assertTrue(movie.isLayoverMovie())
        assertFalse(show.isLayoverMovie())
        assertFalse(movie.copy(type = "tv").isLayoverMovie())
    }

    @Test
    fun `episode uses the same stream video id as playback`() {
        val q = show.queuedEpisode(ep(2), DownloadOrigin.Manual, allowMetered = true, now = 1L)
        assertEquals("tt9:1:2", q.videoId)
        assertEquals("series", q.contentType)
        assertEquals(1, q.seasonNumber)
        assertEquals(2, q.episodeNumber)
        assertEquals("Episode 2", q.episodeTitle)
        assertEquals("thumb2", q.episodeThumbnail)
        assertTrue(q.allowMetered)

        val blankId = show.queuedEpisode(ep(4, id = ""), DownloadOrigin.Manual, allowMetered = false, now = 1L)
        assertEquals("tt9:1:4", blankId.videoId)
    }

    @Test
    fun `button state follows downloads and the queue`() {
        val key = "tt9|1|2"
        val queued = show.queuedEpisode(ep(2), DownloadOrigin.Manual, false, 0L)

        assertEquals(LayoverDownloadState.Idle, downloadState(key, "tt9:1:2", emptyList(), emptyList()))
        assertEquals(LayoverDownloadState.Queued, downloadState(key, "tt9:1:2", emptyList(), listOf(queued)))
        assertEquals(
            LayoverDownloadState.NoSource(NO_SOURCE_FAILURE),
            downloadState(key, "tt9:1:2", emptyList(), listOf(queued.copy(failure = NO_SOURCE_FAILURE))),
        )
        assertEquals(
            LayoverDownloadState.Downloading(0.25f),
            downloadState(key, "tt9:1:2", listOf(download("tt9", 1, 2, DownloadStatus.Downloading, downloaded = 25, total = 100)), emptyList()),
        )
        assertEquals(
            LayoverDownloadState.Downloaded,
            downloadState(key, "tt9:1:2", listOf(download("tt9", 1, 2, DownloadStatus.Completed)), emptyList()),
        )
        assertEquals(
            LayoverDownloadState.Paused("d-tt9-1-2"),
            downloadState(key, "tt9:1:2", listOf(download("tt9", 1, 2, DownloadStatus.Paused)), emptyList()),
        )
        // A failed download can be queued again; a queued entry for it shows as queued.
        assertEquals(
            LayoverDownloadState.Idle,
            downloadState(key, "tt9:1:2", listOf(download("tt9", 1, 2, DownloadStatus.Failed)), emptyList()),
        )
        assertEquals(
            LayoverDownloadState.Queued,
            downloadState(key, "tt9:1:2", listOf(download("tt9", 1, 2, DownloadStatus.Failed)), listOf(queued)),
        )
        // Other episodes don't leak in.
        assertEquals(
            LayoverDownloadState.Idle,
            downloadState(key, "tt9:1:2", listOf(download("tt9", 1, 3, DownloadStatus.Completed)), emptyList()),
        )
    }

    @Test
    fun `season queues released episodes in episode order, skipping ones already handled`() {
        val episodes = listOf(ep(3), ep(1), ep(5, available = false), ep(4), ep(2))
        val downloads = listOf(
            download("tt9", 1, 1, DownloadStatus.Completed),
            download("tt9", 1, 4, DownloadStatus.Paused),
            download("tt9", 1, 3, DownloadStatus.Failed),
        )
        val pending = listOf(show.queuedEpisode(ep(2), DownloadOrigin.Manual, false, 0L))

        val toQueue = seasonToQueue(show, episodes, downloads, pending, allowMetered = false, now = 9L)

        assertEquals(listOf("tt9:1:3"), toQueue.map { it.videoId })
        assertTrue(toQueue.all { it.origin == DownloadOrigin.Season })
    }

    @Test
    fun `season with nothing handled queues every released episode in order`() {
        val toQueue = seasonToQueue(show, listOf(ep(3), ep(1), ep(2)), emptyList(), emptyList(), false, 0L)
        assertEquals(listOf("tt9:1:1", "tt9:1:2", "tt9:1:3"), toQueue.map { it.videoId })
    }

    @Test
    fun `season button state`() {
        val episodes = listOf(ep(1), ep(2))
        val done = listOf(download("tt9", 1, 1, DownloadStatus.Completed), download("tt9", 1, 2, DownloadStatus.Completed))
        assertEquals(SeasonDownloadState.Download, seasonDownloadState(show, episodes, emptyList(), emptyList()))
        assertEquals(SeasonDownloadState.Downloaded, seasonDownloadState(show, episodes, done, emptyList()))
        val oneQueued = listOf(done[0])
        val pending = listOf(show.queuedEpisode(ep(2), DownloadOrigin.Season, false, 0L))
        assertEquals(SeasonDownloadState.Queued, seasonDownloadState(show, episodes, oneQueued, pending))
        assertEquals(SeasonDownloadState.None, seasonDownloadState(show, listOf(ep(1, available = false)), emptyList(), emptyList()))
    }

    @Test
    fun `season with no-source episodes and nothing moving offers a retry, not a dead queued label`() {
        val episodes = listOf(ep(1), ep(2), ep(3))
        val done = listOf(download("tt9", 1, 1, DownloadStatus.Completed))
        val noSource = listOf(
            show.queuedEpisode(ep(2), DownloadOrigin.Season, false, 0L).copy(failure = NO_SOURCE_FAILURE),
            show.queuedEpisode(ep(3), DownloadOrigin.Season, false, 0L).copy(failure = NO_SOURCE_FAILURE),
        )

        assertEquals(SeasonDownloadState.Retry, seasonDownloadState(show, episodes, done, noSource))
        assertEquals(listOf("tt9:1:2", "tt9:1:3"), seasonToRetry(show, episodes, done, noSource))

        // While another episode is still queued, it reads as queued.
        val stillQueued = listOf(noSource[0], show.queuedEpisode(ep(3), DownloadOrigin.Season, false, 0L))
        assertEquals(SeasonDownloadState.Queued, seasonDownloadState(show, episodes, done, stillQueued))
    }

    @Test
    fun `a failed download being re-found shows as downloading, not as a fresh Download`() {
        val recovering = download("tt9", 1, 2, DownloadStatus.Failed, downloaded = 50, total = 100)
            .copy(errorMessage = FINDING_SOURCE_MESSAGE)
        assertEquals(LayoverDownloadState.Downloading(0.5f), downloadState("tt9|1|2", "tt9:1:2", listOf(recovering), emptyList()))
    }

    @Test
    fun `the download tap only says added when the queue took it`() {
        assertEquals("Added to downloads", addedToDownloadsToast(1))
        assertEquals("Already in downloads", addedToDownloadsToast(0))
    }
}
