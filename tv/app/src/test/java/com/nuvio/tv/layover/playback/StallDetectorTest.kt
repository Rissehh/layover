package com.nuvio.tv.layover.playback

import com.nuvio.tv.layover.playback.PlaybackEvent.BufferingEnd
import com.nuvio.tv.layover.playback.PlaybackEvent.BufferingStart
import com.nuvio.tv.layover.playback.PlaybackEvent.Error
import com.nuvio.tv.layover.playback.PlaybackEvent.FirstFrame
import com.nuvio.tv.layover.playback.PlaybackEvent.Started
import com.nuvio.tv.layover.playback.PlaybackEvent.Tick
import org.junit.Test
import org.junit.Assert.assertEquals

class StallDetectorTest {
    private fun detector() = StallDetector(firstFrameTimeoutMs = 8_000)

    @Test
    fun `first frame at 3000 is pending, then healthy at 123000`() {
        val d = detector()
        assertEquals(Verdict.Pending, d.on(Started(0)))
        assertEquals(Verdict.Pending, d.on(FirstFrame(3_000)))
        assertEquals(Verdict.Pending, d.on(Tick(60_000)))
        assertEquals(Verdict.Pending, d.on(Tick(122_999)))
        assertEquals(Verdict.Healthy, d.on(Tick(123_000)))
    }

    @Test
    fun `no first frame by 8000 fails`() {
        val d = detector()
        d.on(Started(0))
        assertEquals(Verdict.Pending, d.on(Tick(7_999)))
        assertEquals(Verdict.Failed, d.on(Tick(8_000)))
    }

    @Test
    fun `error at 2000 before the first frame fails`() {
        val d = detector()
        d.on(Started(0))
        assertEquals(Verdict.Failed, d.on(Error(2_000)))
    }

    @Test
    fun `two rebuffers at 30000 and 40000 stall`() {
        val d = detector()
        d.on(Started(0))
        d.on(FirstFrame(3_000))
        assertEquals(Verdict.Pending, d.on(BufferingStart(30_000)))
        assertEquals(Verdict.Pending, d.on(BufferingEnd(31_000)))
        assertEquals(Verdict.Stalled, d.on(BufferingStart(40_000)))
    }

    @Test
    fun `one 11000 ms rebuffer stalls`() {
        val d = detector()
        d.on(Started(0))
        d.on(FirstFrame(3_000))
        assertEquals(Verdict.Pending, d.on(BufferingStart(30_000)))
        assertEquals(Verdict.Stalled, d.on(BufferingEnd(41_000)))
    }

    @Test
    fun `an ongoing rebuffer stalls on a tick once it passes 10000 ms`() {
        val d = detector()
        d.on(Started(0))
        d.on(FirstFrame(3_000))
        d.on(BufferingStart(30_000))
        assertEquals(Verdict.Pending, d.on(Tick(40_000)))
        assertEquals(Verdict.Stalled, d.on(Tick(40_001)))
    }

    @Test
    fun `short rebuffers summing past 10000 ms stall`() {
        val d = StallDetector(firstFrameTimeoutMs = 8_000, maxRebuffers = 5)
        d.on(Started(0))
        d.on(FirstFrame(1_000))
        d.on(BufferingStart(10_000))
        assertEquals(Verdict.Pending, d.on(BufferingEnd(16_000)))
        d.on(BufferingStart(20_000))
        assertEquals(Verdict.Stalled, d.on(BufferingEnd(24_001)))
    }

    @Test
    fun `a rebuffer at 125000 is outside the window and the stream is healthy`() {
        val d = detector()
        d.on(Started(0))
        d.on(FirstFrame(3_000))
        assertEquals(Verdict.Healthy, d.on(BufferingStart(125_000)))
        assertEquals(Verdict.Healthy, d.on(BufferingEnd(140_000)))
        assertEquals(Verdict.Healthy, d.on(BufferingStart(150_000)))
    }

    @Test
    fun `buffering before the first frame is not a rebuffer`() {
        val d = detector()
        d.on(Started(0))
        assertEquals(Verdict.Pending, d.on(BufferingStart(500)))
        assertEquals(Verdict.Pending, d.on(BufferingEnd(2_500)))
        assertEquals(Verdict.Pending, d.on(FirstFrame(3_000)))
        assertEquals(Verdict.Pending, d.on(BufferingStart(30_000)))
        assertEquals(Verdict.Pending, d.on(BufferingEnd(31_000)))
        assertEquals(Verdict.Healthy, d.on(Tick(123_000)))
    }

    @Test
    fun `buffering that started before the first frame and runs past it does not count`() {
        val d = detector()
        d.on(Started(0))
        d.on(BufferingStart(500))
        d.on(FirstFrame(3_000))
        assertEquals(Verdict.Pending, d.on(Tick(20_000)))
        assertEquals(Verdict.Pending, d.on(BufferingEnd(20_000)))
    }

    @Test
    fun `a late first frame cannot rescue a failed attempt`() {
        val d = detector()
        d.on(Started(0))
        assertEquals(Verdict.Failed, d.on(Tick(9_000)))
        assertEquals(Verdict.Failed, d.on(FirstFrame(9_500)))
        assertEquals(Verdict.Failed, d.on(Tick(200_000)))
    }

    @Test
    fun `a stalled verdict sticks`() {
        val d = detector()
        d.on(Started(0))
        d.on(FirstFrame(3_000))
        d.on(BufferingStart(30_000))
        d.on(BufferingEnd(31_000))
        assertEquals(Verdict.Stalled, d.on(BufferingStart(40_000)))
        assertEquals(Verdict.Stalled, d.on(Tick(200_000)))
    }

    @Test
    fun `an error after the first frame fails, even once healthy`() {
        val early = detector()
        early.on(Started(0))
        early.on(FirstFrame(3_000))
        assertEquals(Verdict.Failed, early.on(Error(50_000)))

        val late = detector()
        late.on(Started(0))
        late.on(FirstFrame(3_000))
        assertEquals(Verdict.Healthy, late.on(Tick(130_000)))
        assertEquals(Verdict.Failed, late.on(Error(600_000)))
    }

    @Test
    fun `a second Started before the first frame restarts the timeout`() {
        val d = detector()
        d.on(Started(0))
        assertEquals(Verdict.Pending, d.on(Tick(7_000)))
        d.on(Started(7_000))
        assertEquals(Verdict.Pending, d.on(Tick(14_999)))
        assertEquals(Verdict.Failed, d.on(Tick(15_000)))
    }

    @Test
    fun `without Started there is no first frame timeout`() {
        val d = detector()
        assertEquals(Verdict.Pending, d.on(Tick(60_000)))
    }

    @Test
    fun `a spell that does not count as a rebuffer only adds buffering time`() {
        val d = detector()
        d.on(Started(0))
        d.on(FirstFrame(3_000))
        d.on(BufferingStart(30_000))
        d.on(BufferingEnd(31_000))
        assertEquals(Verdict.Pending, d.on(BufferingStart(50_000, countsAsRebuffer = false)))
        assertEquals(Verdict.Pending, d.on(BufferingEnd(52_000)))
        assertEquals(Verdict.Stalled, d.on(BufferingStart(60_000)))
    }

    @Test
    fun `two uncounted spells within the window do not stall, a long one still does`() {
        val d = detector()
        d.on(Started(0))
        d.on(FirstFrame(3_000))
        d.on(BufferingStart(24_250, countsAsRebuffer = false))
        d.on(BufferingEnd(25_250))
        d.on(BufferingStart(54_250, countsAsRebuffer = false))
        assertEquals(Verdict.Pending, d.on(BufferingEnd(55_250)))
        assertEquals(Verdict.Healthy, d.on(Tick(123_000)))

        val e = detector()
        e.on(Started(0))
        e.on(FirstFrame(3_000))
        e.on(BufferingStart(24_250, countsAsRebuffer = false))
        assertEquals(Verdict.Pending, e.on(Tick(34_250)))
        assertEquals(Verdict.Stalled, e.on(Tick(34_251)))
    }
}
