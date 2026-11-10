package com.nuvio.tv.layover

import com.nuvio.tv.data.local.StreamAutoPlayMode

/**
 * One time per profile: opening a title should just play it, so upstream's "pick a stream by
 * hand" default becomes "play the first stream" (which LayoverTV's selector turns into "play the
 * best-ranked stream"). Runs once (flag in [LayoverStorage]); if the user later sets manual
 * picking again in the playback settings, that sticks. A regex autoplay mode the user already
 * chose is left alone.
 */
class LayoverAutoplayMigration(
    private val loadFlag: () -> String?,
    private val saveFlag: (String) -> Unit,
    private val currentMode: suspend () -> StreamAutoPlayMode,
    private val setMode: suspend (StreamAutoPlayMode) -> Unit,
) {
    suspend fun run() {
        if (loadFlag() == "true") return
        // Flag first, and only migrate if it stuck: without a stored flag this would run again on
        // every launch and undo a later "manual" choice.
        saveFlag("true")
        if (loadFlag() != "true") return
        if (currentMode() == StreamAutoPlayMode.MANUAL) setMode(StreamAutoPlayMode.FIRST_STREAM)
    }

    companion object {
        fun flagKey(profileId: Int) = "autoplay_migrated_$profileId"
    }
}
