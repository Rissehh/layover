package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.layover.streams.isPlaceholderFile

/** Anything smaller than this is an error page or a stub, not a movie or episode. */
internal const val MIN_READY_BYTES = 1_000_000L

internal const val FILE_CHECK_RETRYING_MESSAGE = "File check failed, retrying"
internal const val FILE_CHECK_FAILED_MESSAGE = "File check failed"

/** Not an IOException on purpose: Android's transfer retry must not treat a bad file as a network blip. */
internal class ReadyCheckFailedException(message: String) : Exception(message)

private val MKV_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
private val FTYP = "ftyp".encodeToByteArray()
private val RIFF = "RIFF".encodeToByteArray()
private const val TS_SYNC: Byte = 0x47

/**
 * The Ready check: is this downloaded file a real, complete video?
 * [header] is the first bytes of the file, [extension] is lowercase or not, without the dot.
 * [claimedBytes] is the size the addon claimed for the stream: a few MB against a claim of a real
 * file's size is the addon's "still downloading" clip ([isPlaceholderFile]), even though the server
 * reported (and sent) exactly that few MB.
 */
fun checkDownloadedFile(
    header: ByteArray,
    actualBytes: Long,
    expectedBytes: Long?,
    extension: String,
    claimedBytes: Long? = null,
): Boolean {
    if (expectedBytes != null && expectedBytes != actualBytes) return false
    if (actualBytes < MIN_READY_BYTES) return false
    if (isPlaceholderFile(actualBytes, claimedBytes)) return false
    return when (extension.lowercase()) {
        "mkv", "webm" -> header.hasAt(0, MKV_MAGIC)
        "mp4", "m4v", "mov" -> header.hasAt(4, FTYP)
        "avi" -> header.hasAt(0, RIFF)
        "ts" -> header.isNotEmpty() && header[0] == TS_SYNC
        else -> true
    }
}

/** First [count] bytes of a downloaded file, or null if it can't be read. */
expect fun readFileHeader(localFileUri: String, count: Int = 12): ByteArray?

/**
 * Extension the Ready check uses. The addon's filename hint wins (it describes the actual file);
 * then the url's own extension. Empty when neither has one, so the check falls back to size only
 * instead of guessing (the stored fileName defaults to .mp4 for extension-less urls).
 */
internal fun DownloadItem.readyCheckExtension(): String =
    streamIdentity?.filename?.videoExtension()
        ?: sourceUrl.substringBefore('?').substringBefore('#').substringAfterLast('/').videoExtension()
        ?: ""

/** The server's reported total wins; the addon's claimed size covers servers that send no length. */
internal fun DownloadItem.readyCheckExpectedBytes(reportedTotalBytes: Long?): Long? =
    reportedTotalBytes?.takeIf { it > 0L } ?: streamIdentity?.videoSize?.takeIf { it > 0L }

/** Reads the file header and runs [checkDownloadedFile] with this download's expected size and extension. */
internal fun DownloadItem.passesReadyCheck(localFileUri: String, actualBytes: Long, reportedTotalBytes: Long?): Boolean {
    val header = readFileHeader(localFileUri) ?: return false
    return checkDownloadedFile(
        header,
        actualBytes,
        readyCheckExpectedBytes(reportedTotalBytes),
        readyCheckExtension(),
        claimedBytes = streamIdentity?.videoSize,
    )
}

/** A failed check is retried once with a clean download; the message says whether that retry is still coming. */
internal fun fileCheckFailureMessage(recoveryAttempts: Int): String =
    if (recoveryAttempts == 0) FILE_CHECK_RETRYING_MESSAGE else FILE_CHECK_FAILED_MESSAGE

private fun String.videoExtension(): String? =
    substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }

private fun ByteArray.hasAt(offset: Int, magic: ByteArray): Boolean =
    size >= offset + magic.size && magic.indices.all { this[offset + it] == magic[it] }
