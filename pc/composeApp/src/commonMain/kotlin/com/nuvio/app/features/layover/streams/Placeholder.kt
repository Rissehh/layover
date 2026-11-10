package com.nuvio.app.features.layover.streams

/**
 * Debrid addons answer a pick whose file isn't ready with a short clip instead of the file
 * (a "Still downloading" slate: 2 minutes, about 5 MB), while the stream still claims the
 * real file's size, often several GB. A playback or a file that small against a claim that big is
 * that clip, not the title. Streams that claim no size (or a small one) are never judged by this,
 * except samples ([isSample]): a short playback of a stream named as a sample is that sample.
 */
internal const val PLACEHOLDER_MIN_CLAIMED_BYTES = 500L * 1024 * 1024

/** Slates run about 2 minutes; nothing claiming [PLACEHOLDER_MIN_CLAIMED_BYTES] is a real clip this short. */
internal const val PLACEHOLDER_MAX_DURATION_MS = 5 * 60_000L

/** Slates weigh about 5 MB; samples, which the picker skips by name, are around 100 MB. */
internal const val PLACEHOLDER_MAX_FILE_BYTES = 50L * 1024 * 1024

internal fun isPlaceholderClip(durationMs: Long, claimedSizeBytes: Long?, isSample: Boolean = false): Boolean =
    durationMs in 1..PLACEHOLDER_MAX_DURATION_MS && (isSample || (claimedSizeBytes ?: 0L) >= PLACEHOLDER_MIN_CLAIMED_BYTES)

internal fun isPlaceholderFile(actualBytes: Long, claimedSizeBytes: Long?): Boolean =
    actualBytes < PLACEHOLDER_MAX_FILE_BYTES && (claimedSizeBytes ?: 0L) >= PLACEHOLDER_MIN_CLAIMED_BYTES
