package com.nuvio.tv.layover.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.layover.LayoverSettingsRepository
import com.nuvio.tv.layover.rank.TrackRecord

/**
 * The stream upstream's "first stream" autoplay plays on LayoverTV: the best of [candidates]
 * by Layover's ranking ([rankCandidates]: debrid-cached first, AV1 last, track record, then the
 * addon order). Null when Layover's fallback is off or no candidate is a plain http(s) stream,
 * so upstream's own pick applies (torrents, media-server items and so on).
 */
object LayoverAutoplay {
    fun pick(candidates: List<Stream>): Stream? {
        if (!LayoverSettingsRepository.state.value.fallbackEnabled) return null
        return rankCandidates(candidates, TrackRecord::score).firstOrNull()
    }
}
