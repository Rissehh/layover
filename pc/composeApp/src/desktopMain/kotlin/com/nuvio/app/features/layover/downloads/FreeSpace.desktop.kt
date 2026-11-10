package com.nuvio.app.features.layover.downloads

import com.nuvio.app.core.storage.DesktopStorage

actual fun downloadsFreeBytes(): Long? =
    runCatching { DesktopStorage.rootDir.toFile().usableSpace }.getOrNull()?.takeIf { it > 0L }
