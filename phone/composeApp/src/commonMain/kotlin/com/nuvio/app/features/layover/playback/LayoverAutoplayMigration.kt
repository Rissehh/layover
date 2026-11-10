package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.layover.LayoverStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode

/**
 * One time per profile: tapping a title should just play it, so upstream's "pick a stream by hand"
 * default becomes "play the first stream". Runs once (flag in [LayoverStorage]); if the user later
 * sets manual picking again in Nuvio's playback settings, that sticks. An autoplay mode the user
 * already chose (regex match) is left alone.
 */
object LayoverAutoplayMigration {
    internal const val FLAG_KEY = "autoplay_migrated"

    internal var loadFlag: () -> String? = { LayoverStorage.load(FLAG_KEY) }
    internal var saveFlag: (String) -> Unit = { LayoverStorage.save(FLAG_KEY, it) }
    internal var currentMode: () -> StreamAutoPlayMode = {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState.value.streamAutoPlayMode
    }
    internal var setMode: (StreamAutoPlayMode) -> Unit = { PlayerSettingsRepository.setStreamAutoPlayMode(it) }

    fun run() {
        if (loadFlag() == "true") return
        // Flag first, and only migrate if it stuck: without a stored flag this would run again on
        // every launch and undo a later "manual" choice (also keeps it inert where storage isn't set up).
        saveFlag("true")
        if (loadFlag() != "true") return
        if (currentMode() == StreamAutoPlayMode.MANUAL) setMode(StreamAutoPlayMode.FIRST_STREAM)
    }

    internal fun resetForTests() {
        loadFlag = { LayoverStorage.load(FLAG_KEY) }
        saveFlag = { LayoverStorage.save(FLAG_KEY, it) }
        currentMode = {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState.value.streamAutoPlayMode
        }
        setMode = { PlayerSettingsRepository.setStreamAutoPlayMode(it) }
    }
}
