package com.nuvio.app.features.layover.downloads

// The iOS free-space check is part of the iPhone plan. Until then the guard is off there (no space check).
actual fun downloadsFreeBytes(): Long? = null
