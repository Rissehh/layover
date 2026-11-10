package com.nuvio.app.features.layover.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoverWakeUpTest {
    private var now = 0L
    private var ready = true
    private val calls = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        LayoverWakeUp.clock = { now }
        LayoverWakeUp.ready = { ready }
        LayoverWakeUp.retryUnreachable = { calls += "recover" }
        LayoverWakeUp.pump = { calls += "pump" }
        LayoverWakeUp.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        LayoverWakeUp.clock = { com.nuvio.app.features.downloads.DownloadsClock.nowEpochMs() }
        LayoverWakeUp.ready = { LayoverDownloadQueue.started }
        LayoverWakeUp.retryUnreachable = { DownloadRecovery.retryUnreachable() }
        LayoverWakeUp.pump = { LayoverDownloadQueue.pump() }
        LayoverWakeUp.resetForTests()
    }

    @Test
    fun `debounce fires once per window`() {
        val d = Debounce(5_000)
        assertTrue(d.tryFire(1_000))
        assertFalse(d.tryFire(1_001))
        assertFalse(d.tryFire(5_999))
        assertTrue(d.tryFire(6_000))
    }

    @Test
    fun `a validated network retries unreachable recoveries and pumps, repeats within 5 s ignored`() {
        LayoverWakeUp.onNetworkValidated()
        now = 4_999
        LayoverWakeUp.onNetworkValidated()
        assertEquals(listOf("recover", "pump"), calls)

        now = 5_000
        LayoverWakeUp.onNetworkValidated()
        assertEquals(listOf("recover", "pump", "recover", "pump"), calls)
    }

    @Test
    fun `coming to the foreground always wakes and does not use up the network wake-up`() {
        LayoverWakeUp.onForeground()
        LayoverWakeUp.onForeground()
        LayoverWakeUp.onNetworkValidated()
        assertEquals(6, calls.size)
    }

    @Test
    fun `nothing happens before the app has started the queue`() {
        ready = false
        LayoverWakeUp.onNetworkValidated()
        LayoverWakeUp.onForeground()
        assertEquals(emptyList(), calls)

        // The early callback did not use up the debounce window.
        ready = true
        LayoverWakeUp.onNetworkValidated()
        assertEquals(listOf("recover", "pump"), calls)
    }
}
