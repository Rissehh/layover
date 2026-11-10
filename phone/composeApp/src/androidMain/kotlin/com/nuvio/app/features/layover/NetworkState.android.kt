package com.nuvio.app.features.layover

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nuvio.app.features.layover.downloads.LayoverWakeUp
import java.util.concurrent.atomic.AtomicBoolean

/** Application context for network checks; set from [LayoverStorage.initialize], the existing Layover init point. */
internal object LayoverNetwork {
    private var appContext: Context? = null
    private val reconnectRegistered = AtomicBoolean(false)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        registerReconnectCallback()
    }

    /**
     * Once per process: when the default network becomes validated (back online, or a new network), wake
     * the downloads that were waiting on it ([LayoverWakeUp.onNetworkValidated], debounced there).
     */
    private fun registerReconnectCallback() {
        val manager = appContext?.getSystemService(ConnectivityManager::class.java) ?: return
        if (!reconnectRegistered.compareAndSet(false, true)) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Only touched on the callback thread.
            private var validatedNetwork: Network? = null

            override fun onAvailable(network: Network) {
                // API 24/25 may not follow up with onCapabilitiesChanged.
                runCatching { manager.getNetworkCapabilities(network) }.getOrNull()?.let { onCapabilitiesChanged(network, it) }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!validated) {
                    if (network == validatedNetwork) validatedNetwork = null
                    return
                }
                if (network == validatedNetwork) return
                validatedNetwork = network
                LayoverWakeUp.onNetworkValidated()
            }

            override fun onLost(network: Network) {
                if (network == validatedNetwork) validatedNetwork = null
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        if (!registered) reconnectRegistered.set(false)
    }

    fun isUnmetered(): Boolean {
        val manager = appContext?.getSystemService(ConnectivityManager::class.java)
            ?: return true // not initialized yet: don't claim a Wi-Fi wait we can't prove
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
        return capabilities.isUnmetered()
    }

    fun quality(): NetworkQuality {
        val manager = appContext?.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkQuality.Validated // not initialized yet: don't change playback judging on a guess
        val capabilities = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
        return networkQualityOf(
            hasActiveNetwork = capabilities != null,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }
}

internal fun NetworkCapabilities?.isUnmetered(): Boolean =
    this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

actual fun isOnUnmeteredNetwork(): Boolean = LayoverNetwork.isUnmetered()
actual fun currentNetworkQuality(): NetworkQuality = LayoverNetwork.quality()
