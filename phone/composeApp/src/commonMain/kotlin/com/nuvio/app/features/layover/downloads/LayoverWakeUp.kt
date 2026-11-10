package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.downloads.DownloadsClock
import kotlinx.coroutines.flow.MutableStateFlow

/** Leading-edge debounce: the first trigger fires, repeats within [windowMs] of it are ignored. Thread-safe. */
internal class Debounce(private val windowMs: Long) {
    private val lastFiredAt = MutableStateFlow<Long?>(null)

    fun tryFire(nowMs: Long): Boolean {
        while (true) {
            val last = lastFiredAt.value
            if (last != null && nowMs - last in 0 until windowMs) return false
            if (lastFiredAt.compareAndSet(last, nowMs)) return true
        }
    }
}

/**
 * Wakes what was waiting on the network: addons whose manifest never loaded
 * ([AddonRepository.retryUnloaded]; without one an addon serves nothing all session), downloads whose
 * recovery couldn't reach the addons ([DownloadRecovery.retryUnreachable]) and queue entries whose
 * stream fetch came back empty-handed while offline (they stay queued with no failure, so a pump
 * picks them up). Runs when the app comes
 * back to the foreground and when the phone gains a validated network. Both callees post their work
 * to Main and respect the slot cap, so this is safe from any thread.
 */
object LayoverWakeUp {
    /** Network callbacks flap (Wi-Fi handover, captive portal checks): one wake-up per this window. */
    internal const val NETWORK_DEBOUNCE_MS = 5_000L

    // Seams for tests.
    internal var clock: () -> Long = { DownloadsClock.nowEpochMs() }
    /** The network callback fires at process start, before the app loaded the profile and its downloads. */
    internal var ready: () -> Boolean = { LayoverDownloadQueue.started }
    internal var retryAddons: () -> Unit = { AddonRepository.retryUnloaded() }
    internal var retryUnreachable: () -> Unit = { DownloadRecovery.retryUnreachable() }
    internal var pump: () -> Unit = { LayoverDownloadQueue.pump() }

    private var networkDebounce = Debounce(NETWORK_DEBOUNCE_MS)

    /** App back in the foreground. Not debounced: it is rare, and must not eat a network wake-up that follows. */
    fun onForeground() = wake()

    /** The phone gained a validated network. Repeats within [NETWORK_DEBOUNCE_MS] are ignored. */
    fun onNetworkValidated() {
        if (ready() && networkDebounce.tryFire(clock())) wake()
    }

    private fun wake() {
        if (!ready()) return
        runCatching { retryAddons() }
        runCatching { retryUnreachable() }
        runCatching { pump() }
    }

    internal fun resetForTests() {
        networkDebounce = Debounce(NETWORK_DEBOUNCE_MS)
    }
}
