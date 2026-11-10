package com.nuvio.app.features.layover.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

@OptIn(ExperimentalForeignApi::class)
actual fun readFileHeader(localFileUri: String, count: Int): ByteArray? {
    val path = if (localFileUri.startsWith("file:")) NSURL(string = localFileUri).path ?: return null else localFileUri
    if (count <= 0) return ByteArray(0)
    val file = fopen(path, "rb") ?: return null
    try {
        val buffer = ByteArray(count)
        val read = buffer.usePinned { pinned ->
            fread(pinned.addressOf(0), 1.convert(), count.convert(), file).toInt()
        }
        return buffer.copyOf(read)
    } finally {
        fclose(file)
    }
}
