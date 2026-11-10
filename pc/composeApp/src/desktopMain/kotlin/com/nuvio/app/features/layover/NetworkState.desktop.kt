package com.nuvio.app.features.layover

// A PC is on a home connection: never wait for Wi-Fi, and never treat a failure as "the network is down"
// (the fallback then judges the source, which is what a desktop needs).
actual fun isOnUnmeteredNetwork(): Boolean = true

actual fun currentNetworkQuality(): NetworkQuality = NetworkQuality.Validated
