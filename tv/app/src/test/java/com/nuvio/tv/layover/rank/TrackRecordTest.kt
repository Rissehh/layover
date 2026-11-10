package com.nuvio.tv.layover.rank

import com.nuvio.tv.layover.log.AttemptKind
import com.nuvio.tv.layover.log.AttemptOutcome
import com.nuvio.tv.layover.log.AttemptRecord
import com.nuvio.tv.layover.testStream
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

private const val DAY_MS = 24L * 60 * 60 * 1000

class TrackRecordTest {
    private val now = 200L * DAY_MS

    @After
    fun tearDown() {
        TrackRecord.resetForTests()
    }

    private fun record(
        addonId: String = "addon:a",
        height: Int? = 1080,
        group: String? = null,
        outcome: AttemptOutcome = AttemptOutcome.Played,
        at: Long = now - DAY_MS,
    ) = AttemptRecord(
        atEpochMs = at,
        kind = AttemptKind.Play,
        contentKey = "tt1|movie",
        addonId = addonId,
        addonName = "Addon",
        height = height,
        codec = null,
        sizeBytes = null,
        releaseGroup = group,
        timeToFirstFrameMs = null,
        outcome = outcome,
    )

    private fun repeated(
        n: Int,
        outcome: AttemptOutcome,
        addonId: String = "addon:a",
        height: Int? = 1080,
        group: String? = null,
        at: Long = now - DAY_MS,
    ) = List(n) { record(addonId, height, group, outcome, at) }

    private fun score(
        records: List<AttemptRecord>,
        addonId: String = "addon:a",
        height: Int? = 1080,
        group: String? = null,
    ) = scoreFrom(records, addonId, height, group, now)

    @Test
    fun `no records scores zero`() {
        assertEquals(0.0, score(emptyList()), 1e-9)
    }

    @Test
    fun `two attempts is under the threshold`() {
        assertEquals(0.0, score(repeated(2, AttemptOutcome.Played)), 1e-9)
        assertEquals(0.0, score(repeated(2, AttemptOutcome.Failed)), 1e-9)
    }

    @Test
    fun `ten successes score positive and ten failures score negative and lower`() {
        val good = score(repeated(10, AttemptOutcome.Played))
        val bad = score(repeated(10, AttemptOutcome.Failed))
        assertTrue(good > 0.0)
        assertTrue(bad < 0.0)
        assertTrue(bad < good)
        assertEquals(11.0 / 12.0 - 0.5, good, 1e-9)
    }

    @Test
    fun `every success and failure outcome counts`() {
        val wins = listOf(AttemptOutcome.Played, AttemptOutcome.Completed, AttemptOutcome.DownloadCompleted)
        val losses = listOf(
            AttemptOutcome.Failed,
            AttemptOutcome.Stalled,
            AttemptOutcome.SwitchedEarly,
            AttemptOutcome.DownloadFailed,
        )
        assertTrue(score(wins.map { record(outcome = it) }) > 0.0)
        assertTrue(score(losses.map { record(outcome = it) }) < 0.0)
    }

    @Test
    fun `records older than 90 days are ignored`() {
        val old = repeated(10, AttemptOutcome.Played, at = now - 91 * DAY_MS)
        assertEquals(0.0, score(old), 1e-9)
        val fresh = repeated(10, AttemptOutcome.Played, at = now - 89 * DAY_MS)
        assertTrue(score(fresh) > 0.0)
    }

    @Test
    fun `release group adds on top of the addon score`() {
        val history = repeated(10, AttemptOutcome.Played, group = "NTb")
        val withGroup = score(history, group = "NTb")
        val unknownGroup = score(history, group = "ZZZ")
        val noGroup = score(history, group = null)
        assertEquals(noGroup, unknownGroup, 1e-9)
        assertTrue(withGroup > noGroup)
        assertEquals(noGroup + 0.5 * (11.0 / 12.0 - 0.5), withGroup, 1e-9)
    }

    @Test
    fun `a failing release group drags the score down`() {
        val history = repeated(10, AttemptOutcome.Played, group = "GOOD") +
            repeated(10, AttemptOutcome.Failed, group = "BAD")
        assertTrue(score(history, group = "BAD") < score(history, group = "GOOD"))
    }

    @Test
    fun `same addon at a different height bucket does not share stats`() {
        val history = repeated(10, AttemptOutcome.Failed, height = 2160)
        assertTrue(score(history, height = 2160) < 0.0)
        assertEquals(0.0, score(history, height = 1080), 1e-9)
        assertEquals(0.0, score(history, height = null), 1e-9)
    }

    @Test
    fun `heights in one bucket share stats`() {
        val history = repeated(10, AttemptOutcome.Played, height = 1080) +
            repeated(5, AttemptOutcome.Played, height = 1200)
        assertEquals(score(history, height = 1080), score(history, height = 1439), 1e-9)
    }

    @Test
    fun `height buckets follow the controller table`() {
        assertEquals("4K", heightBucket(2160))
        assertEquals("4K", heightBucket(4320))
        assertEquals("1440p", heightBucket(2159))
        assertEquals("1440p", heightBucket(1440))
        assertEquals("1080p", heightBucket(1439))
        assertEquals("1080p", heightBucket(1080))
        assertEquals("720p", heightBucket(1079))
        assertEquals("720p", heightBucket(720))
        assertEquals("SD", heightBucket(719))
        assertEquals("SD", heightBucket(480))
        assertEquals("Unknown", heightBucket(null))
    }

    @Test
    fun `score reads the log through stream facts and recomputes when the list changes`() {
        var records = repeated(10, AttemptOutcome.Played)
        TrackRecord.recordsSource = { records }
        TrackRecord.clock = { now }
        // NuvioTV streams have no addon id: the track record keys on the addon name.
        val stream = testStream(
            name = "Test",
            addonName = "addon:a",
            filename = "Movie.2020.1080p.WEB-DL.x264-NTb.mkv",
        )
        val first = TrackRecord.score(stream)
        assertTrue(first > 0.0)
        assertEquals(first, TrackRecord.score(stream), 1e-9)

        records = repeated(10, AttemptOutcome.Failed)
        assertTrue(TrackRecord.score(stream) < 0.0)
    }
}
