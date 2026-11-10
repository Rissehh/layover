package com.nuvio.tv.layover.playback

/**
 * What one engine (ExoPlayer or libmpv) looks like at one moment, read by the player glue every
 * 250 ms. Only the fields the attempt judging needs; same names as the phone port so its tests
 * carry over.
 *
 * - [isBuffering]: the player is waiting for data (ExoPlayer STATE_BUFFERING, libmpv
 *   paused-for-cache). Not set while the user has paused.
 * - [playerCommandCount]: how many app commands that can make the player buffer (seek, track
 *   change) were issued for this surface so far.
 */
data class PlayerPlaybackSnapshot(
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val firstFrameRendered: Boolean = false,
    val isBuffering: Boolean = false,
    val playerCommandCount: Int = 0,
)
