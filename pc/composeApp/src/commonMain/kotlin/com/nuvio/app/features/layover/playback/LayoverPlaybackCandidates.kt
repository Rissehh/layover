package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.layover.log.layoverContentKey
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState

/**
 * Hands the stream list from the streams screen to the player. The streams screen clears its list
 * when it leaves, so it is captured right before the player opens and taken once by the player.
 */
object LayoverPlaybackCandidates {
    private class Entry(val contentKey: String, val pool: List<StreamItem>)

    private var entry: Entry? = null

    /**
     * Upstream's autoplay candidates when there are any (their order and the user's autoplay source
     * filters), otherwise every stream on the screen in display order.
     */
    fun capture(metaId: String, season: Int?, episode: Int?, state: StreamsUiState) {
        val pool = state.autoPlayCandidates.ifEmpty { state.groups.flatMap { it.streams } }
        entry = Entry(layoverContentKey(metaId, season, episode), pool)
    }

    /** The captured list for [contentKey], at most once. */
    fun take(contentKey: String): List<StreamItem>? {
        val captured = entry ?: return null
        entry = null
        return captured.pool.takeIf { captured.contentKey == contentKey }
    }
}
