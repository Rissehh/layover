package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttemptMonitorTest {
    private fun monitor(startedAt: Long = 0) = AttemptMonitor(firstFrameTimeoutMs = 8_000, startedAtMs = startedAt)

    private fun snap(
        positionMs: Long = 0,
        firstFrame: Boolean = true,
        buffering: Boolean = false,
        // 1: upstream applies the preferred audio language once per source, before the first frame.
        commands: Int = 1,
    ) = PlayerPlaybackSnapshot(
        isLoading = buffering,
        positionMs = positionMs,
        firstFrameRendered = firstFrame,
        isBuffering = buffering,
        playerCommandCount = commands,
    )

    /**
     * First frame at 3000 ms with playback from position 0 (wall = position + 3000). Like the real
     * player, the audio-preference command lands between load start and the first frame.
     */
    private fun AttemptMonitor.playingFrom3s(): AttemptMonitor = apply {
        onSnapshot(snap(firstFrame = false, commands = 0), 500)
        onSnapshot(snap(firstFrame = false), 1_000)
        onSnapshot(snap(), 3_000)
    }

    private fun AttemptMonitor.rebuffer(atMs: Long, forMs: Long, positionMs: Long = atMs - 3_000): Verdict {
        onSnapshot(snap(positionMs = positionMs - 250), atMs - 250)
        onSnapshot(snap(positionMs = positionMs, buffering = true), atMs)
        return onSnapshot(snap(positionMs = positionMs), atMs + forMs)
    }

    @Test
    fun `a two minute clip on a stream claiming gigabytes is a placeholder and fails`() {
        val m = AttemptMonitor(firstFrameTimeoutMs = 8_000, startedAtMs = 0, claimedSizeBytes = 2_500_000_000L)
        assertEquals(Verdict.Pending, m.onSnapshot(snap(firstFrame = false, commands = 0), 500))
        assertEquals(Verdict.Failed, m.onSnapshot(PlayerPlaybackSnapshot(durationMs = 120_000, firstFrameRendered = true, playerCommandCount = 1), 3_000))
        assertTrue(m.placeholder)
        assertTrue(m.firstFrameSeen)
    }

    @Test
    fun `a short title with no or a small claimed size is not a placeholder`() {
        for (claimed in listOf(null, 80_000_000L)) {
            val m = AttemptMonitor(firstFrameTimeoutMs = 8_000, startedAtMs = 0, claimedSizeBytes = claimed)
            assertEquals(Verdict.Pending, m.onSnapshot(PlayerPlaybackSnapshot(durationMs = 120_000, firstFrameRendered = true, playerCommandCount = 1), 3_000))
            assertFalse(m.placeholder)
        }
        val episode = AttemptMonitor(firstFrameTimeoutMs = 8_000, startedAtMs = 0, claimedSizeBytes = 2_500_000_000L)
        assertEquals(Verdict.Pending, episode.onSnapshot(PlayerPlaybackSnapshot(durationMs = 2_900_000, firstFrameRendered = true, playerCommandCount = 1), 3_000))
        assertFalse(episode.placeholder)
    }

    @Test
    fun `a short playback of a stream named as a sample fails whatever size it claims`() {
        val m = AttemptMonitor(firstFrameTimeoutMs = 8_000, startedAtMs = 0, claimedSizeBytes = 114_621_814L, isSample = true)
        assertEquals(Verdict.Failed, m.onSnapshot(PlayerPlaybackSnapshot(durationMs = 59_441, firstFrameRendered = true, playerCommandCount = 1), 3_000))
        assertTrue(m.placeholder)
    }

    @Test
    fun `a placeholder seen while offline is judged once back online`() {
        val m = AttemptMonitor(firstFrameTimeoutMs = 8_000, startedAtMs = 0, claimedSizeBytes = 2_500_000_000L)
        m.onConnectivity(online = false, nowMs = 100)
        val clip = PlayerPlaybackSnapshot(durationMs = 120_000, firstFrameRendered = true, playerCommandCount = 1)
        assertEquals(Verdict.Pending, m.onSnapshot(clip, 3_000))
        assertFalse(m.placeholder)
        m.onConnectivity(online = true, nowMs = 4_000)
        assertEquals(Verdict.Failed, m.onSnapshot(clip, 4_250))
    }

    @Test
    fun `no first frame within the timeout fails`() {
        val m = monitor()
        assertEquals(Verdict.Pending, m.onTick(7_999))
        assertEquals(Verdict.Failed, m.onTick(8_000))
    }

    @Test
    fun `the first frame is tracked`() {
        val m = monitor()
        assertFalse(m.firstFrameSeen)
        m.playingFrom3s()
        assertTrue(m.firstFrameSeen)
        assertEquals(Verdict.Healthy, m.onTick(123_000))
    }

    @Test
    fun `leaving the app during load is not a failure and coming back restarts the timeout`() {
        val m = monitor()
        m.onTick(2_000)
        assertEquals(Verdict.Pending, m.onStop(2_500))
        assertEquals(Verdict.Pending, m.onTick(20_000))
        assertEquals(Verdict.Pending, m.onStart(30_000))
        assertEquals(Verdict.Pending, m.onTick(37_999))
        assertEquals(Verdict.Failed, m.onTick(38_000))
    }

    @Test
    fun `leaving the app mid rebuffer closes the spell`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 27_000), 30_000)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 30_250)
        assertEquals(Verdict.Pending, m.onStop(33_000))
        m.onSnapshot(snap(positionMs = 27_000), 40_000) // the spell ends while away
        assertEquals(Verdict.Pending, m.onStart(100_000))
        assertEquals(Verdict.Pending, m.onTick(110_000))
        assertEquals(Verdict.Healthy, m.onTick(123_000))
    }

    @Test
    fun `a stall earned before leaving is acted on after coming back`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 27_000), 30_000)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 30_250)
        assertEquals(Verdict.Pending, m.onStop(42_000))
        assertEquals(Verdict.Stalled, m.onStart(50_000))
    }

    @Test
    fun `offline errors are not judged`() {
        val m = monitor()
        assertEquals(Verdict.Pending, m.onConnectivity(online = false, nowMs = 1_000))
        assertEquals(Verdict.Pending, m.onError(2_000))
        assertEquals(Verdict.Pending, m.onTick(30_000))
    }

    @Test
    fun `an offline error after the first frame is not judged`() {
        val m = monitor().playingFrom3s()
        m.onConnectivity(online = false, nowMs = 60_000)
        assertEquals(Verdict.Pending, m.onError(61_000))
        m.onConnectivity(online = true, nowMs = 70_000)
        assertEquals(Verdict.Pending, m.onTick(200_000))
    }

    @Test
    fun `offline time does not count toward the first frame timeout`() {
        val m = monitor()
        m.onConnectivity(online = false, nowMs = 1_000)
        assertEquals(Verdict.Pending, m.onTick(60_000))
        assertEquals(Verdict.Pending, m.onConnectivity(online = true, nowMs = 61_000))
        assertEquals(Verdict.Pending, m.onTick(68_999))
        assertEquals(Verdict.Failed, m.onTick(69_000))
    }

    @Test
    fun `after an offline error a player command back online is judged again with a fresh timeout`() {
        val m = monitor()
        m.onSnapshot(snap(firstFrame = false), 500)
        m.onConnectivity(online = false, nowMs = 1_000)
        m.onError(2_000)
        m.onConnectivity(online = true, nowMs = 10_000)
        m.onSnapshot(snap(firstFrame = false), 20_000)
        assertEquals(Verdict.Pending, m.onTick(30_000))
        // A player command (a seek, a track change) shows up as a bumped count.
        m.onSnapshot(snap(firstFrame = false, commands = 2), 31_000)
        assertEquals(Verdict.Pending, m.onTick(38_999))
        assertEquals(Verdict.Failed, m.onTick(39_000))
    }

    @Test
    fun `online errors still fail`() {
        val m = monitor()
        assertEquals(Verdict.Failed, m.onError(2_000))
    }

    @Test
    fun `a surface restart mid rebuffer does not leave the detector stuck`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 27_000), 30_000)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 30_250)
        assertEquals(Verdict.Pending, m.onSurfaceRestart(32_000))
        // The new surface's own load is not a rebuffer.
        m.onSnapshot(snap(firstFrame = false, positionMs = 27_000, buffering = true), 32_500)
        m.onSnapshot(snap(positionMs = 27_000), 34_000)
        assertEquals(Verdict.Pending, m.onTick(50_000))
        assertEquals(Verdict.Pending, m.onTick(100_000))
        assertEquals(Verdict.Healthy, m.onTick(123_000))
    }

    @Test
    fun `buffering that rises in the first frame snapshot is not a rebuffer`() {
        val m = monitor()
        m.onSnapshot(snap(firstFrame = false), 1_000)
        m.onSnapshot(snap(buffering = true), 3_000)
        m.onSnapshot(snap(positionMs = 250), 5_000)
        assertEquals(Verdict.Pending, m.rebuffer(atMs = 40_000, forMs = 1_000))
        assertEquals(Verdict.Healthy, m.onTick(123_000))
    }

    @Test
    fun `two genuine rebuffers still stall`() {
        val m = monitor().playingFrom3s()
        assertEquals(Verdict.Pending, m.rebuffer(atMs = 30_000, forMs = 1_000))
        m.onSnapshot(snap(positionMs = 35_000), 39_000)
        m.onSnapshot(snap(positionMs = 35_750), 39_750)
        assertEquals(Verdict.Stalled, m.onSnapshot(snap(positionMs = 36_000, buffering = true), 40_000))
    }

    @Test
    fun `a hang right after the first frame is not hidden by the audio preference command`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 2_000), 5_000)
        assertEquals(Verdict.Pending, m.onSnapshot(snap(positionMs = 2_000, buffering = true), 5_250))
        assertEquals(Verdict.Pending, m.onTick(15_250))
        assertEquals(Verdict.Stalled, m.onTick(15_251))
    }

    @Test
    fun `a spell that starts while settling and never ends reaches Stalled`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 17_000), 20_000)
        // The user seeks (command 2); the player buffers and never recovers.
        m.onSnapshot(snap(positionMs = 17_000, commands = 2), 20_100)
        m.onSnapshot(snap(positionMs = 17_000, buffering = true, commands = 2), 20_250)
        assertEquals(Verdict.Pending, m.onTick(24_000))
        // Grace ends at 24_250; the spell counts from there.
        assertEquals(Verdict.Pending, m.onTick(34_250))
        assertEquals(Verdict.Stalled, m.onTick(34_251))
    }

    @Test
    fun `a short command caused spell does not count`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 17_000), 20_000)
        m.onSnapshot(snap(positionMs = 17_000, buffering = true, commands = 2), 20_250)
        m.onSnapshot(snap(positionMs = 17_000, commands = 2), 22_000)
        // One genuine rebuffer later; had the subtitle reload counted, this would be the second.
        assertEquals(Verdict.Pending, m.rebuffer(atMs = 40_000, forMs = 1_000))
        assertEquals(Verdict.Healthy, m.onTick(123_000))
    }

    @Test
    fun `a hang that continues after coming back is counted as the same spell`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 27_000), 30_000)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 30_250)
        m.onStop(31_000)
        assertEquals(Verdict.Pending, m.onStart(50_000))
        // The player refills on return: 4 s grace, then 750 ms before leaving + 9_250 ms after the grace = 10_000.
        assertEquals(Verdict.Pending, m.onTick(63_250))
        assertEquals(Verdict.Stalled, m.onTick(63_251))
    }

    @Test
    fun `a spell that started in the background counts when coming back`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 27_000), 30_000)
        m.onStop(30_100)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 30_250)
        assertEquals(Verdict.Pending, m.onStart(50_000))
        // Counted from the end of the 4 s grace on return.
        assertEquals(Verdict.Pending, m.onTick(64_000))
        assertEquals(Verdict.Stalled, m.onTick(64_001))
    }

    @Test
    fun `a spell from the background that ends within the grace after coming back does not count`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 27_000), 30_000)
        m.onStop(30_100)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 30_250)
        m.onStart(50_000)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 52_000)
        m.onSnapshot(snap(positionMs = 27_000), 53_000)
        // One genuine rebuffer later; had the refill on return counted, this would be the second.
        assertEquals(Verdict.Pending, m.rebuffer(atMs = 80_000, forMs = 1_000, positionMs = 54_000))
        assertEquals(Verdict.Healthy, m.onTick(123_000))
    }

    @Test
    fun `two command caused spells that outlast the grace do not stall on count`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 17_000), 20_000)
        // Slow seek 1: buffers 5 s, 1 s past the grace.
        m.onSnapshot(snap(positionMs = 17_000, commands = 2), 20_100)
        m.onSnapshot(snap(positionMs = 17_000, buffering = true, commands = 2), 20_250)
        assertEquals(Verdict.Pending, m.onTick(24_500))
        assertEquals(Verdict.Pending, m.onSnapshot(snap(positionMs = 17_000, commands = 2), 25_250))
        m.onSnapshot(snap(positionMs = 42_000, commands = 2), 50_000)
        // Slow seek 2, same shape.
        m.onSnapshot(snap(positionMs = 42_000, commands = 3), 50_100)
        m.onSnapshot(snap(positionMs = 42_000, buffering = true, commands = 3), 50_250)
        assertEquals(Verdict.Pending, m.onTick(54_500))
        assertEquals(Verdict.Pending, m.onSnapshot(snap(positionMs = 42_000, commands = 3), 55_250))
        assertEquals(Verdict.Healthy, m.onTick(123_000))
    }

    @Test
    fun `a hang that continues after the network returns is counted as the same spell`() {
        val m = monitor().playingFrom3s()
        m.onSnapshot(snap(positionMs = 27_000), 30_000)
        m.onSnapshot(snap(positionMs = 27_000, buffering = true), 30_250)
        m.onConnectivity(online = false, nowMs = 31_000)
        assertEquals(Verdict.Pending, m.onTick(45_000))
        assertEquals(Verdict.Pending, m.onConnectivity(online = true, nowMs = 50_000))
        assertEquals(Verdict.Pending, m.onTick(63_250))
        assertEquals(Verdict.Stalled, m.onTick(63_251))
    }

    @Test
    fun `offline and suspended are reported for the Next source button`() {
        val m = monitor()
        assertFalse(m.offlineOrSuspended)
        m.onConnectivity(online = false, nowMs = 1_000)
        assertTrue(m.offlineOrSuspended)
        m.onError(2_000)
        m.onConnectivity(online = true, nowMs = 3_000)
        assertTrue(m.offlineOrSuspended)
    }
}
