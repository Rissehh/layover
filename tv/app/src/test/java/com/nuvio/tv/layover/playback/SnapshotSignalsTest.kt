package com.nuvio.tv.layover.playback

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SnapshotSignalsTest {
    private fun snap(
        positionMs: Long = 0,
        firstFrame: Boolean = false,
        buffering: Boolean = false,
        commands: Int = 0,
    ) = PlayerPlaybackSnapshot(
        isLoading = buffering,
        positionMs = positionMs,
        firstFrameRendered = firstFrame,
        isBuffering = buffering,
        playerCommandCount = commands,
    )

    /** Plays from [fromMs] to [toMs] in 250 ms snapshots; wall time = position + [wallOffsetMs]. */
    private fun SnapshotSignals.play(fromMs: Long, toMs: Long, commands: Int = 0, wallOffsetMs: Long = 0): List<PlaybackEvent> {
        val events = mutableListOf<PlaybackEvent>()
        var position = fromMs
        while (position <= toMs) {
            events += map(snap(positionMs = position, firstFrame = true, commands = commands), position + wallOffsetMs)
            position += 250
        }
        return events
    }

    @Test
    fun `first frame is reported once`() {
        val s = SnapshotSignals()
        assertTrue(s.map(snap(), 100).isEmpty())
        assertEquals(listOf<PlaybackEvent>(PlaybackEvent.FirstFrame(200)), s.map(snap(firstFrame = true), 200))
        assertTrue(s.map(snap(positionMs = 250, firstFrame = true), 450).isEmpty())
    }

    @Test
    fun `buffering edges become start and end events`() {
        val s = SnapshotSignals()
        s.map(snap(positionMs = 10_000, firstFrame = true), 1_000)
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingStart(1_250)),
            s.map(snap(positionMs = 10_250, firstFrame = true, buffering = true), 1_250),
        )
        assertTrue(s.map(snap(positionMs = 10_250, firstFrame = true, buffering = true), 1_500).isEmpty())
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingEnd(4_000)),
            s.map(snap(positionMs = 10_250, firstFrame = true), 4_000),
        )
    }

    @Test
    fun `buffering edges come before the first frame in the same snapshot`() {
        val s = SnapshotSignals()
        s.map(snap(), 100)
        assertEquals(
            listOf(PlaybackEvent.BufferingStart(300), PlaybackEvent.FirstFrame(300)),
            s.map(snap(firstFrame = true, buffering = true), 300),
        )
    }

    @Test
    fun `re-prepare at the same position is not a rebuffer`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        // setSubtitleUri re-opens the source: command count goes up, position stays, player buffers.
        assertTrue(s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true, commands = 1), 20_250).isEmpty())
        assertTrue(s.map(snap(positionMs = 20_000, firstFrame = true, commands = 1), 22_000).isEmpty())
    }

    @Test
    fun `buffering starting 5 s after a seek is not a rebuffer`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        // Seek to 60 s (position jumps 40 s in 250 ms), playback resumes, then mpv pauses for cache 5 s later.
        assertTrue(s.play(60_000, 65_000, wallOffsetMs = -39_750).isEmpty())
        assertTrue(s.map(snap(positionMs = 65_000, firstFrame = true, buffering = true), 25_500).isEmpty())
        assertTrue(s.map(snap(positionMs = 65_000, firstFrame = true), 27_000).isEmpty())
    }

    @Test
    fun `buffering after a seek command without a visible jump is not a rebuffer`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        assertTrue(s.play(20_250, 24_000, commands = 1).isEmpty())
        assertTrue(s.map(snap(positionMs = 24_000, firstFrame = true, buffering = true, commands = 1), 24_250).isEmpty())
    }

    @Test
    fun `a genuine rebuffer after playback resumed still counts`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true, commands = 1), 20_250)
        s.map(snap(positionMs = 20_000, firstFrame = true, commands = 1), 22_000)
        // 15 s of normal playback after the command settles it.
        s.play(20_000, 35_000, commands = 1)
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingStart(35_250)),
            s.map(snap(positionMs = 35_000, firstFrame = true, buffering = true, commands = 1), 35_250),
        )
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingEnd(37_000)),
            s.map(snap(positionMs = 35_000, firstFrame = true, commands = 1), 37_000),
        )
    }

    @Test
    fun `dropping an open spell swallows its end`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true), 20_250)
        assertTrue(s.dropOpenSpell())
        assertFalse(s.dropOpenSpell())
        assertTrue(s.map(snap(positionMs = 20_000, firstFrame = true), 22_000).isEmpty())
    }

    @Test
    fun `reset forgets the previous surface`() {
        val s = SnapshotSignals()
        s.map(snap(firstFrame = true, buffering = true), 100)
        s.reset()
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.FirstFrame(300)),
            s.map(snap(firstFrame = true), 300),
        )
    }

    @Test
    fun `a spell that starts while settling counts from the end of its grace`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        assertTrue(s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true, commands = 1), 20_250).isEmpty())
        assertTrue(s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true, commands = 1), 24_000).isEmpty())
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingStart(24_250, countsAsRebuffer = false)),
            s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true, commands = 1), 24_500),
        )
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingEnd(30_000)),
            s.map(snap(positionMs = 20_000, firstFrame = true, commands = 1), 30_000),
        )
    }

    @Test
    fun `the grace end is also reported on a tick without a new snapshot`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true, commands = 1), 20_250)
        assertTrue(s.tick(24_249).isEmpty())
        assertEquals(listOf<PlaybackEvent>(PlaybackEvent.BufferingStart(24_250, countsAsRebuffer = false)), s.tick(26_000))
        assertTrue(s.tick(27_000).isEmpty())
    }

    @Test
    fun `commands and seeks before the first frame do not start settling`() {
        val s = SnapshotSignals()
        s.map(snap(), 100)
        s.map(snap(commands = 1), 500) // preferred audio language, applied once per source
        s.map(snap(positionMs = 1_800_000, commands = 1), 800) // resume seek
        s.map(snap(positionMs = 1_800_000, firstFrame = true, commands = 1), 3_000)
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingStart(5_250)),
            s.map(snap(positionMs = 1_802_000, firstFrame = true, buffering = true, commands = 1), 5_250),
        )
    }

    @Test
    fun `a reopened spell gets a grace and is reported from its end`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true), 20_250)
        assertTrue(s.dropOpenSpell())
        assertTrue(s.reopenSpell(50_000, countsAsRebuffer = false))
        assertFalse(s.reopenSpell(51_000, countsAsRebuffer = false))
        assertTrue(s.tick(53_999).isEmpty())
        assertEquals(listOf<PlaybackEvent>(PlaybackEvent.BufferingStart(54_000, countsAsRebuffer = false)), s.tick(54_000))
        assertEquals(
            listOf<PlaybackEvent>(PlaybackEvent.BufferingEnd(56_000)),
            s.map(snap(positionMs = 20_000, firstFrame = true), 56_000),
        )
    }

    @Test
    fun `a reopened spell that ends within its grace is never reported`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true), 20_250)
        s.dropOpenSpell()
        assertTrue(s.reopenSpell(50_000, countsAsRebuffer = true))
        assertTrue(s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true), 52_000).isEmpty())
        assertTrue(s.map(snap(positionMs = 20_000, firstFrame = true), 53_000).isEmpty())
        assertTrue(s.tick(60_000).isEmpty())
    }

    @Test
    fun `nothing to reopen once the spell ended or while it is still in its grace`() {
        val s = SnapshotSignals()
        s.play(10_000, 20_000)
        s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true), 20_250)
        s.dropOpenSpell()
        s.map(snap(positionMs = 20_000, firstFrame = true), 21_000)
        assertFalse(s.reopenSpell(30_000, countsAsRebuffer = true))
        s.map(snap(positionMs = 20_000, firstFrame = true, buffering = true, commands = 1), 30_250)
        assertFalse(s.reopenSpell(31_000, countsAsRebuffer = true))
    }
}
