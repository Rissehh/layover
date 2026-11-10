package com.nuvio.app.features.layover.downloads

import java.io.File
import java.net.URI

/** Downloads live in app storage and are stored as `file:` URIs (`File.toURI()`); a bare path also works. */
actual fun readFileHeader(localFileUri: String, count: Int): ByteArray? = runCatching {
    val file = if (localFileUri.startsWith("file:")) File(URI(localFileUri)) else File(localFileUri)
    file.inputStream().use { input ->
        val buffer = ByteArray(count.coerceAtLeast(0))
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n == -1) break
            read += n
        }
        buffer.copyOf(read)
    }
}.getOrNull()
