package com.nuvio.tv.layover.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StremioHistoryTest {

    private fun item(
        type: String, videoId: String?, offset: Long, duration: Long, times: Int = 0, id: String = "tt100",
    ) = StremioLibraryItem(id, type, "Title", null, videoId, offset, duration, times, 1_000L)

    @Test fun movieMostlyWatchedIsCompleted() {
        val e = importEntry(item("movie", "tt100", 6_800_000, 7_400_000))!!
        assertNull(e.progress)
        assertEquals("tt100", e.completed!!.videoId)
        assertNull(e.completed!!.season)
    }

    @Test fun movieAtZeroWatchedOnceIsCompleted() {
        assertEquals("tt100", importEntry(item("movie", "tt100", 0, 6_300_000, times = 1))!!.completed!!.contentId)
    }

    @Test fun moviePartlyPlayedIsInProgress() {
        val e = importEntry(item("movie", "tt100", 36_000, 6_400_000))!!
        assertEquals(36_000L, e.progress!!.position)
        assertNull(e.completed)
    }

    @Test fun movieOpenedButNotPlayedIsSkipped() {
        assertNull(importEntry(item("movie", "tt100", 0, 0)))
    }

    @Test fun episodeMovedOnMarksThePreviousOneWatched() {
        val e = importEntry(item("series", "tt100:2:1", 1, 1_441_000))
        assertNull(e)   // first episode of a season: no earlier episode in it to mark
        val e2 = importEntry(item("series", "tt100:1:5", 1, 1_441_000))!!
        assertEquals("tt100:1:4", e2.completed!!.videoId)
        assertEquals(1, e2.completed!!.season)
        assertEquals(4, e2.completed!!.episode)
    }

    @Test fun episodePartlyPlayedIsInProgress() {
        val e = importEntry(item("series", "tt100:1:11", 975_009, 1_437_118))!!
        assertEquals("tt100:1:11", e.progress!!.videoId)
        assertEquals(11, e.progress!!.episode)
    }

    @Test fun episodeInTheCreditsIsCompleted() {
        assertEquals("tt100:4:10", importEntry(item("series", "tt100:4:10", 5_268_986, 5_310_806))!!.completed!!.videoId)
    }

    @Test fun nonImdbAndOtherTypesAreSkipped() {
        assertNull(importEntry(item("movie", "kitsu:1", 50_000, 1_000_000, id = "kitsu:1")))
        assertNull(importEntry(item("other", "tt100", 50_000, 1_000_000)))
    }

    @Test fun seasonEpisodeParsesStremioIds() {
        assertEquals(1 to 11, seasonEpisode("tt13616990:1:11"))
        assertNull(seasonEpisode("tt13616990"))
        assertNull(seasonEpisode(null))
    }
}
