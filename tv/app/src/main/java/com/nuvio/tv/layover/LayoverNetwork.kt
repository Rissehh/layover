package com.nuvio.tv.layover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What the active network can do, for the playback fallback. Ported from Layover (phone). */
enum class NetworkQuality {
    /** No active network, or one without internet capability: every stream would fail. */
    None,

    /** Internet capability, not validated (captive portal, still checking). */
    Unvalidated,

    /** Internet capability and validated. */
    Validated,
}

fun networkQualityOf(hasActiveNetwork: Boolean, hasInternet: Boolean, validated: Boolean): NetworkQuality = when {
    !hasActiveNetwork || !hasInternet -> NetworkQuality.None
    !validated -> NetworkQuality.Unvalidated
    else -> NetworkQuality.Validated
}

/**
 * [judge]: watch streams and switch away from bad ones. [record]: write attempt-log records.
 * Unvalidated networks still get fallback, but their failures may be the network's, so they don't
 * go into the track record.
 */
data class FallbackNetworkPolicy(val judge: Boolean, val record: Boolean)

fun NetworkQuality.fallbackPolicy(): FallbackNetworkPolicy = when (this) {
    NetworkQuality.None -> FallbackNetworkPolicy(judge = false, record = false)
    NetworkQuality.Unvalidated -> FallbackNetworkPolicy(judge = true, record = false)
    NetworkQuality.Validated -> FallbackNetworkPolicy(judge = true, record = true)
}

object LayoverNetwork {
    @Volatile
    private var manager: ConnectivityManager? = null

    fun initialize(context: Context) {
        manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    }

    /** The active network right now; Validated when not initialized (never change judging on a guess). */
    fun quality(): NetworkQuality {
        val cm = manager ?: return NetworkQuality.Validated
        val capabilities = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        return networkQualityOf(
            hasActiveNetwork = capabilities != null,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }
}
