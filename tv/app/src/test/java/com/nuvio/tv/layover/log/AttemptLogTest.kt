package com.nuvio.tv.layover.log

import com.nuvio.tv.layover.testStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class AttemptLogTest {
    private var stored: String? = null

    @Before
    fun setUp() {
        stored = null
        AttemptLog.loader = { stored }
        AttemptLog.saver = { stored = it }
        AttemptLog.reload()
    }

    @After
    fun tearDown() {
        stored = null
        AttemptLog.reload()
    }

    private fun record(
        at: Long,
        outcome: AttemptOutcome = AttemptOutcome.Played,
    ) = AttemptRecord(
        atEpochMs = at,
        kind = AttemptKind.Play,
        contentKey = "tt1|movie",
        addonId = "addon:test",
        addonName = "Addon",
        height = 1080,
        codec = "hevc",
        sizeBytes = 1_000L,
        releaseGroup = "NTb",
        timeToFirstFrameMs = 1_200L,
        outcome = outcome,
    )

    @Test
    fun `adding 2001 records keeps 2000 and drops the oldest`() {
        repeat(AttemptLog.MAX_RECORDS + 1) { AttemptLog.add(record(it.toLong())) }
        val records = AttemptLog.records.value
        assertEquals(AttemptLog.MAX_RECORDS, records.size)
        assertEquals(1L, records.first().atEpochMs)
        assertEquals(AttemptLog.MAX_RECORDS.toLong(), records.last().atEpochMs)
        assertEquals(AttemptLog.MAX_RECORDS, AttemptLog.decode(stored).size)
    }

    @Test
    fun `encode then decode round trips records`() {
        val original = listOf(
            record(1, AttemptOutcome.Played),
            record(2, AttemptOutcome.Stalled).copy(height = null, codec = null, sizeBytes = null, releaseGroup = null, timeToFirstFrameMs = null),
            record(3, AttemptOutcome.DownloadCompleted).copy(kind = AttemptKind.Download),
        )
        assertEquals(original, AttemptLog.decode(AttemptLog.encode(original)))
    }

    @Test
    fun `unknown outcome strings are skipped not fatal`() {
        val good = AttemptLog.encode(listOf(record(1), record(3)))
        val bad = AttemptLog.encode(listOf(record(2))).removeSurrounding("[", "]").also { check("\"Played\"" in it) }.replace("\"Played\"", "\"Exploded\"")
        val mixed = good.dropLast(1).replace("},{", "},$bad,{") + "]"
        assertEquals(listOf(1L, 3L), AttemptLog.decode(mixed).map { it.atEpochMs })
    }

    @Test
    fun `decode null garbage and non array gives empty`() {
        assertEquals(emptyList<AttemptRecord>(), AttemptLog.decode(null))
        assertEquals(emptyList<AttemptRecord>(), AttemptLog.decode("garbage"))
        assertEquals(emptyList<AttemptRecord>(), AttemptLog.decode("{\"a\":1}"))
    }

    @Test
    fun `add persists and reload reads storage again`() {
        AttemptLog.add(record(5))
        AttemptLog.reload()
        assertEquals(listOf(5L), AttemptLog.records.value.map { it.atEpochMs })
        stored = null
        AttemptLog.reload()
        assertEquals(emptyList<AttemptRecord>(), AttemptLog.records.value)
    }

    @Test
    fun `replace swaps the latest matching record in place and persists`() {
        val played = record(2)
        AttemptLog.add(record(1))
        AttemptLog.add(played)
        AttemptLog.add(record(3, AttemptOutcome.Failed))
        AttemptLog.replace(played, played.copy(atEpochMs = 9, outcome = AttemptOutcome.Completed))
        assertEquals(listOf(1L, 9L, 3L), AttemptLog.records.value.map { it.atEpochMs })
        assertEquals(AttemptOutcome.Completed, AttemptLog.records.value[1].outcome)
        assertEquals(AttemptLog.records.value, AttemptLog.decode(stored))
    }

    @Test
    fun `replace appends when the old record is gone`() {
        AttemptLog.add(record(1))
        AttemptLog.replace(record(2), record(5, AttemptOutcome.Completed))
        assertEquals(listOf(1L, 5L), AttemptLog.records.value.map { it.atEpochMs })
    }

    @Test
    fun `clear empties state and storage`() {
        AttemptLog.add(record(1))
        AttemptLog.clear()
        assertEquals(emptyList<AttemptRecord>(), AttemptLog.records.value)
        assertEquals(emptyList<AttemptRecord>(), AttemptLog.decode(stored))
    }

    @Test
    fun `attemptRecord fills facts from the stream`() {
        val file = "Show.S01E02.1080p.WEB-DL.DDP5.1.x265-NTb.mkv"
        val stream = testStream(
            name = "Comet 1080p",
            url = "https://addon.example/s.mkv",
            addonName = "Comet",
            filename = file,
            videoSize = 1_932_735_283,
        )
        val r = attemptRecord(stream, AttemptKind.Play, "tt1|1|2", AttemptOutcome.Failed, null, 42L)
        assertEquals(42L, r.atEpochMs)
        assertEquals(AttemptKind.Play, r.kind)
        assertEquals("tt1|1|2", r.contentKey)
        assertEquals("Comet", r.addonId)
        assertEquals("Comet", r.addonName)
        assertEquals(1080, r.height)
        assertEquals("hevc", r.codec)
        assertEquals(1_932_735_283L, r.sizeBytes)
        assertEquals("NTb", r.releaseGroup)
        assertNull(r.timeToFirstFrameMs)
        assertEquals(AttemptOutcome.Failed, r.outcome)
    }

    @Test
    fun `layoverContentKey matches download logical key format`() {
        assertEquals("tt1|movie", layoverContentKey("tt1", null, null))
        assertEquals("tt1|2|5", layoverContentKey("tt1", 2, 5))
        assertEquals("tt1|movie", layoverContentKey("tt1", 2, null))
        assertEquals("tt1|movie", layoverContentKey("tt1", null, 5))
    }
}
