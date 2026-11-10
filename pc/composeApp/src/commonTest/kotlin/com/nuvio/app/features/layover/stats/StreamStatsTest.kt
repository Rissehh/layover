package com.nuvio.app.features.layover.stats

import com.nuvio.app.features.layover.log.AttemptKind
import com.nuvio.app.features.layover.log.AttemptOutcome
import com.nuvio.app.features.layover.log.AttemptRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MINUTE_MS = 60L * 1000

class StreamStatsTest {
    private fun record(
        addonId: String = "addon:a",
        addonName: String = "Addon A",
        height: Int? = 1080,
        outcome: AttemptOutcome = AttemptOutcome.Played,
        ttff: Long? = null,
        at: Long = 1_000L,
        kind: AttemptKind = AttemptKind.Play,
        contentKey: String = "tt1|movie",
    ) = AttemptRecord(
        atEpochMs = at,
        kind = kind,
        contentKey = contentKey,
        addonId = addonId,
        addonName = addonName,
        height = height,
        codec = null,
        sizeBytes = null,
        releaseGroup = null,
        timeToFirstFrameMs = ttff,
        outcome = outcome,
    )

    @Test
    fun `addon rows count attempts and successes per addon`() {
        val records = listOf(
            record(outcome = AttemptOutcome.Played),
            record(outcome = AttemptOutcome.Completed),
            record(outcome = AttemptOutcome.Failed),
            record(outcome = AttemptOutcome.SwitchedEarly),
            record(addonId = "addon:b", addonName = "Addon B", outcome = AttemptOutcome.DownloadCompleted, kind = AttemptKind.Download),
        )

        val rows = statsByAddon(records)

        assertEquals(listOf("Addon A", "Addon B"), rows.map { it.label })
        val a = rows[0]
        assertEquals(4, a.attempts)
        assertEquals(0.5, a.successRate)
        assertEquals(0.25, a.earlySwitchRate)
        val b = rows[1]
        assertEquals(1, b.attempts)
        assertEquals(1.0, b.successRate)
        assertEquals(0.0, b.earlySwitchRate)
    }

    @Test
    fun `rows are sorted by attempts descending`() {
        val records = listOf(
            record(addonId = "addon:small", addonName = "Small"),
            record(addonId = "addon:big", addonName = "Big"),
            record(addonId = "addon:big", addonName = "Big"),
            record(addonId = "addon:big", addonName = "Big"),
        )

        assertEquals(listOf("Big", "Small"), statsByAddon(records).map { it.label })
    }

    @Test
    fun `addon label falls back to the id when the name is blank and groups by id`() {
        val records = listOf(
            record(addonId = "addon:x", addonName = " "),
            record(addonId = "addon:x", addonName = " "),
        )

        val rows = statsByAddon(records)

        assertEquals(1, rows.size)
        assertEquals("addon:x", rows[0].label)
        assertEquals(2, rows[0].attempts)
    }

    @Test
    fun `an empty log gives no rows`() {
        assertTrue(statsByAddon(emptyList()).isEmpty())
        assertTrue(statsByQuality(emptyList()).isEmpty())
    }

    @Test
    fun `average time to first frame ignores records without one`() {
        val records = listOf(
            record(ttff = 2_000L),
            record(ttff = 3_000L),
            record(ttff = null, outcome = AttemptOutcome.DownloadCompleted, kind = AttemptKind.Download),
            record(ttff = null, outcome = AttemptOutcome.Failed),
        )

        assertEquals(2_500L, statsByAddon(records).single().avgTimeToFirstFrameMs)
    }

    @Test
    fun `average time to first frame is null when no record has one`() {
        val records = listOf(record(ttff = null), record(ttff = null, outcome = AttemptOutcome.Failed))

        assertNull(statsByAddon(records).single().avgTimeToFirstFrameMs)
    }

    @Test
    fun `quality rows bucket by height`() {
        val records = listOf(
            record(height = 2160),
            record(height = 1080),
            record(height = 1080),
            record(height = 720),
            record(height = 480),
            record(height = null),
            record(height = 1440),
        )

        val rows = statsByQuality(records).associateBy { it.label }

        assertEquals(setOf("4K", "1440p", "1080p", "720p", "SD", "Unknown"), rows.keys)
        assertEquals(2, rows.getValue("1080p").attempts)
        assertEquals(1, rows.getValue("4K").attempts)
        assertEquals(1, rows.getValue("720p").attempts)
        assertEquals(1, rows.getValue("SD").attempts)
        assertEquals(1, rows.getValue("Unknown").attempts)
        assertEquals("1080p", statsByQuality(records).first().label)
    }

    @Test
    fun `recent failures are newest first, skip early switches and successes, and are capped`() {
        val records = (1..30).map { i -> record(outcome = AttemptOutcome.Failed, at = i * MINUTE_MS) } +
            record(outcome = AttemptOutcome.SwitchedEarly, at = 100 * MINUTE_MS) +
            record(outcome = AttemptOutcome.Played, at = 101 * MINUTE_MS) +
            record(outcome = AttemptOutcome.Stalled, at = 50 * MINUTE_MS) +
            record(outcome = AttemptOutcome.DownloadFailed, at = 40 * MINUTE_MS, kind = AttemptKind.Download)

        val failures = recentFailures(records)

        assertEquals(20, failures.size)
        assertEquals(AttemptOutcome.Stalled, failures[0].outcome)
        assertEquals(AttemptOutcome.DownloadFailed, failures[1].outcome)
        assertEquals(30 * MINUTE_MS, failures[2].atEpochMs)
        assertEquals(failures.sortedByDescending { it.atEpochMs }, failures)
        assertEquals(3, recentFailures(records, limit = 3).size)
    }

    @Test
    fun `time to first frame formats as seconds with one decimal`() {
        assertEquals("2.4 s", formatStartTime(2_400L))
        assertEquals("0.9 s", formatStartTime(940L))
        assertEquals("12.0 s", formatStartTime(12_000L))
        assertEquals("-", formatStartTime(null))
    }

    @Test
    fun `percentages round to whole numbers`() {
        assertEquals("67%", formatPercent(2.0 / 3.0))
        assertEquals("0%", formatPercent(0.0))
        assertEquals("100%", formatPercent(1.0))
    }

    @Test
    fun `relative time uses minutes, hours and days`() {
        val now = 10L * 24 * 60 * MINUTE_MS
        assertEquals("just now", formatRelativeTime(now - 20_000L, now))
        assertEquals("5 min ago", formatRelativeTime(now - 5 * MINUTE_MS, now))
        assertEquals("3 h ago", formatRelativeTime(now - 3 * 60 * MINUTE_MS, now))
        assertEquals("2 d ago", formatRelativeTime(now - 2 * 24 * 60 * MINUTE_MS, now))
        assertEquals("just now", formatRelativeTime(now + MINUTE_MS, now))
    }

    @Test
    fun `failure outcomes read as plain words`() {
        assertEquals("Failed to start", failureLabel(AttemptOutcome.Failed))
        assertEquals("Stalled", failureLabel(AttemptOutcome.Stalled))
        assertEquals("Download failed", failureLabel(AttemptOutcome.DownloadFailed))
    }

    @Test
    fun `content key falls back to episode code or movie with the meta id`() {
        assertEquals("tt123 S1E3", fallbackTitle("tt123|1|3"))
        assertEquals("Movie tt123", fallbackTitle("tt123|movie"))
        assertEquals("odd-key", fallbackTitle("odd-key"))
        assertEquals("S1E3", episodeCode("tt123|1|3"))
        assertNull(episodeCode("tt123|movie"))
    }
}
