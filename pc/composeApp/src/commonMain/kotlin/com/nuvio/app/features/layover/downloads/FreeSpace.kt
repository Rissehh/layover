package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem

/** Shown on a queue entry or a download that stopped because the phone is full. Holds no slot; Retry tries again. */
const val NO_SPACE_MESSAGE = "Not enough space on the phone"

/** Kept free on top of the download itself, so a full download never leaves the phone at zero bytes. */
internal const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024

/** Usable bytes in the directory the downloader writes to, or null when that can't be told (no guard then). */
expect fun downloadsFreeBytes(): Long?

/**
 * True when [neededBytes] plus [SPACE_MARGIN_BYTES] fits in [freeBytes], after the [reservedBytes] that
 * running downloads still have to write. Unknown free space never blocks.
 */
internal fun hasRoomFor(neededBytes: Long, freeBytes: Long?, reservedBytes: Long = 0L): Boolean =
    freeBytes == null || freeBytes - reservedBytes >= neededBytes + SPACE_MARGIN_BYTES

/** A transfer error that means the phone's storage is full ("ENOSPC (No space left on device)"). */
fun isNoSpaceError(message: String?): Boolean =
    message != null && (message.contains("No space", ignoreCase = true) || message.contains("ENOSPC"))

/** Bytes the downloads holding a slot still have to write; unknown sizes count as nothing. */
internal fun reservedBytes(downloads: List<DownloadItem>, recovering: Set<String>): Long =
    downloads.filter { it.holdsSlot(recovering) }.sumOf { item ->
        val total = item.totalBytes?.takeIf { it > 0L } ?: item.streamIdentity?.videoSize?.takeIf { it > 0L } ?: 0L
        (total - item.downloadedBytes).coerceAtLeast(0L)
    }
