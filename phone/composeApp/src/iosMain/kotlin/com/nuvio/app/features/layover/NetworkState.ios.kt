package com.nuvio.app.features.layover

// Wi-Fi detection on iOS (NWPathMonitor) is part of the iPhone plan. Until then never report a Wi-Fi wait.
actual fun isOnUnmeteredNetwork(): Boolean = true

// NWPathMonitor is part of the iPhone plan; playback fallback is off on iOS until then anyway.
actual fun currentNetworkQuality(): NetworkQuality = NetworkQuality.Validated
