package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.log.AttemptKind
import com.nuvio.app.features.layover.log.AttemptOutcome
import com.nuvio.app.features.layover.log.AttemptRecord
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FallbackControllerTest {
    private var now = 0L
    private val records = mutableListOf<AttemptRecord>()

    private val one = stream("one")
    private val two = stream("two")
    private val three = stream("three")

    private fun stream(id: String) = StreamItem(
        name = "Stream $id 1080p",
        url = "https://example.com/$id.mkv",
        addonName = "Addon $id",
        addonId = "addon:$id",
    )

    private fun controller(
        candidates: List<StreamItem> = listOf(one, two, three),
        settings: LayoverSettings = LayoverSettings(),
    ) = FallbackController(
        candidates = candidates,
        settings = settings,
        contentKey = "tt1|1|2",
        log = { records += it },
        clock = { now },
        replace = { old, new ->
            val index = records.lastIndexOf(old)
            if (index >= 0) records[index] = new else records += new
        },
    )

    private fun outcomes() = records.map { it.outcome }

    @Test
    fun `failed walks the candidates and logs each failure, then runs out`() {
        val c = controller()
        assertSame(one, c.current)

        now = 9_000
        assertSame(two, c.onVerdict(Verdict.Failed))
        assertSame(two, c.current)
        assertEquals(listOf(AttemptOutcome.Failed), outcomes())
        assertEquals("addon:one", records[0].addonId)
        assertEquals("tt1|1|2", records[0].contentKey)
        assertEquals(AttemptKind.Play, records[0].kind)
        assertNull(records[0].timeToFirstFrameMs)
        assertEquals(9_000, records[0].atEpochMs)

        assertSame(three, c.onVerdict(Verdict.Failed))
        assertFalse(c.exhausted)

        assertNull(c.onVerdict(Verdict.Failed))
        assertTrue(c.exhausted)
        assertEquals(listOf("addon:one", "addon:two", "addon:three"), records.map { it.addonId })
        assertEquals(List(3) { AttemptOutcome.Failed }, outcomes())
    }

    @Test
    fun `maxAttempts 2 stops after the second stream`() {
        val c = controller(settings = LayoverSettings(maxAttempts = 2))
        assertSame(two, c.onVerdict(Verdict.Failed))
        assertNull(c.onVerdict(Verdict.Failed))
        assertTrue(c.exhausted)
        assertEquals(2, records.size)
    }

    @Test
    fun `verdicts after running out do nothing`() {
        val c = controller(candidates = listOf(one))
        assertNull(c.onVerdict(Verdict.Failed))
        assertTrue(c.exhausted)
        assertNull(c.onVerdict(Verdict.Stalled))
        assertNull(c.onVerdict(Verdict.Failed))
        assertNull(c.userSwitched())
        assertEquals(1, records.size)
    }

    @Test
    fun `no candidates means nothing to play`() {
        val c = controller(candidates = emptyList())
        assertNull(c.current)
        assertTrue(c.exhausted)
        assertNull(c.onVerdict(Verdict.Failed))
        assertTrue(records.isEmpty())
    }

    @Test
    fun `pending changes nothing`() {
        val c = controller()
        assertNull(c.onVerdict(Verdict.Pending))
        assertSame(one, c.current)
        assertTrue(records.isEmpty())
    }

    @Test
    fun `stalled logs with time to first frame and moves on`() {
        val c = controller()
        now = 1_000
        c.onFirstFrame()
        now = 40_000
        assertSame(two, c.onVerdict(Verdict.Stalled))
        assertEquals(listOf(AttemptOutcome.Stalled), outcomes())
        assertEquals(1_000, records[0].timeToFirstFrameMs)
    }

    @Test
    fun `time to first frame is measured from the start of each attempt`() {
        val c = controller()
        now = 9_000
        c.onVerdict(Verdict.Failed)
        now = 11_500
        c.onFirstFrame()
        now = 50_000
        c.onVerdict(Verdict.Stalled)
        assertEquals(2_500, records[1].timeToFirstFrameMs)
    }

    @Test
    fun `healthy logs Played exactly once`() {
        val c = controller()
        now = 3_000
        c.onFirstFrame()
        now = 123_000
        assertNull(c.onVerdict(Verdict.Healthy))
        now = 183_000
        assertNull(c.onVerdict(Verdict.Healthy))
        now = 300_000
        assertNull(c.onVerdict(Verdict.Healthy))
        assertNull(c.onVerdict(Verdict.Healthy))
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
        assertEquals(3_000, records[0].timeToFirstFrameMs)
        assertSame(one, c.current)
    }

    @Test
    fun `userSwitched at 60 s logs SwitchedEarly and returns the next stream`() {
        val c = controller()
        now = 2_000
        c.onFirstFrame()
        now = 62_000
        assertSame(two, c.userSwitched())
        assertEquals(listOf(AttemptOutcome.SwitchedEarly), outcomes())
        assertEquals(2_000, records[0].timeToFirstFrameMs)
    }

    @Test
    fun `userSwitched at 200 s logs nothing extra`() {
        val c = controller()
        now = 2_000
        c.onFirstFrame()
        now = 122_000
        c.onVerdict(Verdict.Healthy)
        now = 182_000
        c.onVerdict(Verdict.Healthy)
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
        now = 202_000
        assertSame(two, c.userSwitched())
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
    }

    @Test
    fun `userSwitched after the early window without a verdict logs Played`() {
        val c = controller()
        now = 2_000
        c.onFirstFrame()
        now = 202_000
        assertSame(two, c.userSwitched())
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
    }

    @Test
    fun `userSwitched before the first frame logs SwitchedEarly`() {
        val c = controller()
        now = 4_000
        assertSame(two, c.userSwitched())
        assertEquals(listOf(AttemptOutcome.SwitchedEarly), outcomes())
        assertNull(records[0].timeToFirstFrameMs)
    }

    @Test
    fun `userSwitched on the last stream logs and runs out`() {
        val c = controller(candidates = listOf(one))
        now = 4_000
        assertNull(c.userSwitched())
        assertTrue(c.exhausted)
        assertEquals(listOf(AttemptOutcome.SwitchedEarly), outcomes())
    }

    @Test
    fun `hasNext says whether another source is left`() {
        val c = controller()
        assertTrue(c.hasNext)
        c.onVerdict(Verdict.Failed)
        assertTrue(c.hasNext)
        c.onVerdict(Verdict.Failed)
        assertFalse(c.hasNext)
        assertFalse(controller(candidates = listOf(one)).hasNext)
        assertFalse(controller(candidates = emptyList()).hasNext)
    }

    @Test
    fun `hasNext respects maxAttempts`() {
        val c = controller(settings = LayoverSettings(maxAttempts = 2))
        assertTrue(c.hasNext)
        c.onVerdict(Verdict.Failed)
        assertFalse(c.hasNext)
        assertFalse(controller(settings = LayoverSettings(maxAttempts = 1)).hasNext)
    }

    @Test
    fun `checking hasNext logs nothing and the stream keeps its own record`() {
        val c = controller(candidates = listOf(one))
        now = 3_000
        c.onFirstFrame()
        assertFalse(c.hasNext)
        assertTrue(records.isEmpty())
        assertFalse(c.exhausted)
        now = 200_000
        c.onVerdict(Verdict.Healthy)
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
    }

    @Test
    fun `a switch that must not be judged logs nothing but still moves on`() {
        val c = controller()
        now = 4_000
        assertSame(two, c.userSwitched(logOutcome = false))
        assertTrue(records.isEmpty())
        now = 5_000
        c.onFirstFrame()
        now = 200_000
        c.onVerdict(Verdict.Healthy)
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
        assertEquals("addon:two", records[0].addonId)
    }

    @Test
    fun `user switches count toward maxAttempts`() {
        val c = controller(settings = LayoverSettings(maxAttempts = 2))
        assertSame(two, c.userSwitched())
        assertNull(c.onVerdict(Verdict.Failed))
        assertTrue(c.exhausted)
    }

    @Test
    fun `reaching 90 percent replaces Played with Completed`() {
        val c = controller()
        now = 3_000
        c.onFirstFrame()
        now = 183_000
        c.onVerdict(Verdict.Healthy)
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
        now = 5_000_000
        c.onCompleted()
        c.onCompleted()
        assertEquals(listOf(AttemptOutcome.Completed), outcomes())
        assertEquals(3_000, records[0].timeToFirstFrameMs)
        c.close()
        assertEquals(listOf(AttemptOutcome.Completed), outcomes())
    }

    @Test
    fun `completed before Played is logged once and Played never follows`() {
        val c = controller()
        now = 3_000
        c.onFirstFrame()
        now = 100_000
        c.onCompleted()
        now = 200_000
        c.onVerdict(Verdict.Healthy)
        c.close()
        assertEquals(listOf(AttemptOutcome.Completed), outcomes())
    }

    @Test
    fun `an error after Played switches without a second record`() {
        val c = controller()
        now = 3_000
        c.onFirstFrame()
        now = 183_000
        c.onVerdict(Verdict.Healthy)
        now = 900_000
        assertSame(two, c.onVerdict(Verdict.Failed))
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
    }

    @Test
    fun `close after the first frame logs Played once`() {
        val c = controller()
        now = 3_000
        c.onFirstFrame()
        now = 60_000
        c.close()
        c.close()
        assertEquals(listOf(AttemptOutcome.Played), outcomes())
    }

    @Test
    fun `close before the first frame logs nothing`() {
        val c = controller()
        now = 3_000
        c.close()
        assertTrue(records.isEmpty())
    }

    @Test
    fun `each attempt gets its own record`() {
        val c = controller()
        now = 9_000
        c.onVerdict(Verdict.Failed)
        now = 10_000
        c.onFirstFrame()
        now = 200_000
        c.onVerdict(Verdict.Healthy)
        assertEquals(listOf(AttemptOutcome.Failed, AttemptOutcome.Played), outcomes())
        assertEquals(listOf("addon:one", "addon:two"), records.map { it.addonId })
    }
}
