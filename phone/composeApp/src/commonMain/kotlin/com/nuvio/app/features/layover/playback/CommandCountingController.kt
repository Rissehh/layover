package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.player.PlayerEngineController

/**
 * Wraps an engine's controller so every command that can make the player buffer (seek,
 * audio/subtitle track changes, adding or clearing an external subtitle) calls [onCommand] first.
 * The engines count these into [com.nuvio.app.features.player.PlayerPlaybackSnapshot.playerCommandCount]
 * so the stall detector can tell app-caused buffering from a struggling stream. Everything else is
 * passed straight through.
 */
internal fun PlayerEngineController.countingCommands(onCommand: () -> Unit): PlayerEngineController =
    CommandCountingController(this, onCommand)

private class CommandCountingController(
    private val inner: PlayerEngineController,
    private val onCommand: () -> Unit,
) : PlayerEngineController by inner {
    override fun seekTo(positionMs: Long) {
        onCommand()
        inner.seekTo(positionMs)
    }

    override fun seekBy(offsetMs: Long) {
        onCommand()
        inner.seekBy(offsetMs)
    }

    override fun applyAudioLanguagePreferences(languages: List<String>) {
        onCommand()
        inner.applyAudioLanguagePreferences(languages)
    }

    override fun selectAudioTrack(index: Int) {
        onCommand()
        inner.selectAudioTrack(index)
    }

    override fun selectSubtitleTrack(index: Int) {
        onCommand()
        inner.selectSubtitleTrack(index)
    }

    override fun setSubtitleUri(url: String) {
        onCommand()
        inner.setSubtitleUri(url)
    }

    override fun clearExternalSubtitle() {
        onCommand()
        inner.clearExternalSubtitle()
    }

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        onCommand()
        inner.clearExternalSubtitleAndSelect(trackIndex)
    }
}
