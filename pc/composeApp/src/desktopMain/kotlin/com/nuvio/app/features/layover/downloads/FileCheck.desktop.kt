package com.nuvio.app.features.layover.downloads

import java.io.File
import java.net.URI

actual fun readFileHeader(localFileUri: String, count: Int): ByteArray? {
    val file = runCatching {
        if (localFileUri.startsWith("file:")) File(URI(localFileUri)) else File(localFileUri)
    }.getOrNull() ?: return null
    if (count <= 0) return ByteArray(0)
    if (!file.isFile) return null
    return runCatching { file.inputStream().use { it.readNBytes(count) } }.getOrNull()
}
