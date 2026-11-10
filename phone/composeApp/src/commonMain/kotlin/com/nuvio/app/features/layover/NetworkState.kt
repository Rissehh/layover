package com.nuvio.app.features.layover

/**
 * True when the active network is unmetered (Wi-Fi, ethernet). The Downloads UI uses it to tell
 * "waiting for Wi-Fi" apart from a transfer that is simply slow.
 */
expect fun isOnUnmeteredNetwork(): Boolean

/** What the active network can do, for the playback fallback. */
enum class NetworkQuality {
    /** No active network, or one without internet capability: every stream would fail. */
    None,

    /** Internet capability, not validated (captive portal, in-flight Wi-Fi, still checking). */
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
 * Unvalidated networks still get fallback, but their failures may be the network's (a captive
 * portal), so they don't go into the track record.
 */
data class FallbackNetworkPolicy(val judge: Boolean, val record: Boolean)

fun NetworkQuality.fallbackPolicy(): FallbackNetworkPolicy = when (this) {
    NetworkQuality.None -> FallbackNetworkPolicy(judge = false, record = false)
    NetworkQuality.Unvalidated -> FallbackNetworkPolicy(judge = true, record = false)
    NetworkQuality.Validated -> FallbackNetworkPolicy(judge = true, record = true)
}

/** The active network's [NetworkQuality] right now. */
expect fun currentNetworkQuality(): NetworkQuality
