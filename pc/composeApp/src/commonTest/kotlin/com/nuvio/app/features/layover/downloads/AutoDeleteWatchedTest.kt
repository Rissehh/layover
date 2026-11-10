package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoDeleteWatchedTest {
    private fun download(
        season: Int? = 1,
        episode: Int? = 5,
        status: DownloadStatus = DownloadStatus.Completed,
        updatedAt: Long = 1_000L,
        localFileUri: String? = "file:///downloads/show-s1e5.mkv",
        fileName: String = "show-s1e5.mkv",
    ) = DownloadItem(
        id = "d",
        contentType = if (season != null) "series" else "movie",
        parentMetaId = "tt9",
        parentMetaType = if (season != null) "series" else "movie",
        videoId = "tt9:1:5",
        title = "Show",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "s",
        providerName = "p",
        sourceUrl = "https://x",
        localFileUri = localFileUri,
        fileName = fileName,
        status = status,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = updatedAt,
    )

    private fun movie(status: DownloadStatus = DownloadStatus.Completed) = download(season = null, episode = null, status = status)

    private fun progress(
        season: Int?,
        episode: Int?,
        position: Long,
        updatedAt: Long = 5_000L,
        id: String = "tt9",
        duration: Long = 100L,
    ) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = id,
        parentMetaType = "series",
        videoId = "$id:$season:$episode",
        title = "Show",
        seasonNumber = season,
        episodeNumber = episode,
        lastPositionMs = position,
        durationMs = duration,
        lastUpdatedEpochMs = updatedAt,
    )

    // shouldAutoDelete -------------------------------------------------------------------------

    @Test
    fun `watched episode is deleted when auto-delete is on`() {
        assertTrue(shouldAutoDelete(download(), 0.95, LayoverSettings()))
    }

    @Test
    fun `half-watched episode is kept`() {
        assertFalse(shouldAutoDelete(download(), 0.5, LayoverSettings()))
    }

    @Test
    fun `watched movie is kept when movies are excluded`() {
        assertFalse(shouldAutoDelete(movie(), 0.95, LayoverSettings(autoDeleteMovies = false)))
        assertTrue(shouldAutoDelete(movie(), 0.95, LayoverSettings(autoDeleteMovies = true)))
    }

    @Test
    fun `nothing is deleted with auto-delete off, even movies`() {
        val off = LayoverSettings(autoDeleteWatched = false, autoDeleteMovies = true)
        assertFalse(shouldAutoDelete(download(), 1.0, off))
        assertFalse(shouldAutoDelete(movie(), 1.0, off))
    }

    @Test
    fun `only finished downloads are auto-deleted`() {
        assertFalse(shouldAutoDelete(download(status = DownloadStatus.Downloading), 1.0, LayoverSettings()))
        assertFalse(shouldAutoDelete(download(status = DownloadStatus.Paused), 1.0, LayoverSettings()))
        assertTrue(shouldAutoDelete(download(), 0.9, LayoverSettings()))
    }

    // autoDeleteAction (don't delete while playing) ----------------------------------------------

    @Test
    fun `autoDeleteAction defers a watched file while it plays and deletes it when it does not`() {
        assertEquals(AutoDeleteAction.Defer, autoDeleteAction(download(), 0.95, LayoverSettings(), playingThisFile = true))
        assertEquals(AutoDeleteAction.Delete, autoDeleteAction(download(), 0.95, LayoverSettings(), playingThisFile = false))
    }

    @Test
    fun `an unwatched file is kept whether or not it is playing`() {
        assertEquals(AutoDeleteAction.Keep, autoDeleteAction(download(), 0.5, LayoverSettings(), playingThisFile = true))
        assertEquals(AutoDeleteAction.Keep, autoDeleteAction(download(), 0.5, LayoverSettings(), playingThisFile = false))
    }

    @Test
    fun `playing file matches by uri or by file name`() {
        val item = download()
        assertTrue(isPlayingFile(item, "file:///downloads/show-s1e5.mkv"))
        assertTrue(isPlayingFile(item, "content://media/show-s1e5.mkv"))
        assertFalse(isPlayingFile(item, "https://rd.example/show-s1e5"))
        assertFalse(isPlayingFile(item, null))
    }

    // watchedFractionSinceDownload -------------------------------------------------------------

    @Test
    fun `progress counts only when made after the download finished`() {
        val item = download(updatedAt = 1_000L)
        assertEquals(0.95, watchedFractionSinceDownload(item, progress(1, 5, position = 95L, updatedAt = 2_000L), null), 0.001)
        // Downloaded again to rewatch: the old 95% must not delete it.
        assertEquals(0.0, watchedFractionSinceDownload(item, progress(1, 5, position = 95L, updatedAt = 500L), null))
    }

    @Test
    fun `a watched mark made after the download counts as fully watched`() {
        val item = download(updatedAt = 1_000L)
        assertEquals(1.0, watchedFractionSinceDownload(item, null, watchedMarkedAtMs = 2_000L))
        assertEquals(0.0, watchedFractionSinceDownload(item, null, watchedMarkedAtMs = 500L))
        assertEquals(0.0, watchedFractionSinceDownload(item, null, null))
    }
}
