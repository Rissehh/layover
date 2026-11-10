package com.nuvio.tv.layover.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRowsTest {

    private fun progress(id: String, season: Int?, position: Long, duration: Long, at: Long) = WatchProgress(
        contentId = id, contentType = if (season == null) "movie" else "series", name = "N$id", poster = null,
        backdrop = null, logo = null, videoId = id, season = season, episode = season?.let { 1 },
        episodeTitle = null, position = position, duration = duration, lastWatched = at,
    )

    @Test fun oneRowPerTitleWithItsBestProgressAndNewestDate() {
        val rows = historyRows(
            listOf(progress("tt1", 1, 100, 1000, at = 5), progress("tt1", 1, 900, 1000, at = 3), progress("tt2", null, 500, 1000, at = 9)),
            emptyList(),
        )
        assertEquals(listOf("tt2", "tt1"), rows.map { it.contentId })
        assertEquals(5L, rows[1].lastWatched)
        assertEquals(0.9, rows[1].engagement, 1e-6)
    }

    @Test fun aWatchedMarkCountsAsFinishedButKeepsTheProgressDate() {
        val rows = historyRows(
            listOf(progress("tt1", 1, 0, 1000, at = 5)),
            listOf(WatchedItem("tt1", "series", "N", 1, 1, watchedAt = 99)),
        )
        assertEquals(5L, rows.single().lastWatched)
        assertEquals(1.0, rows.single().engagement, 1e-6)
    }

    @Test fun aWatchedMarkAloneUsesItsOwnDate() {
        val rows = historyRows(emptyList(), listOf(WatchedItem("tt7", "movie", "M", watchedAt = 42)))
        assertEquals(42L, rows.single().lastWatched)
    }

    @Test fun nonImdbTitlesAreLeftOut() {
        assertEquals(0, historyRows(listOf(progress("kitsu:1", null, 500, 1000, at = 1)), emptyList()).size)
    }
}
