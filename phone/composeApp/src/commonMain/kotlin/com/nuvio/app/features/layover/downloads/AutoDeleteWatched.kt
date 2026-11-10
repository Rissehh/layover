package com.nuvio.app.features.layover.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.LayoverSettingsRepository
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.normalizeWatchedMarkedAtEpochMs
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// Pure rules ------------------------------------------------------------------------------------

internal const val AUTO_DELETE_FRACTION = 0.9

/**
 * How much of [item] was watched after it finished downloading: a watched mark counts as 1.0, progress counts
 * as its fraction (at least 0.9 once upstream calls it complete). Progress or marks older than the download
 * count as 0, so a title downloaded again to rewatch isn't deleted on sight.
 */
internal fun watchedFractionSinceDownload(
    item: DownloadItem,
    progress: WatchProgressEntry?,
    watchedMarkedAtMs: Long?,
): Double {
    val since = item.updatedAtEpochMs
    if (watchedMarkedAtMs != null && watchedMarkedAtMs > since) return 1.0
    if (progress == null || progress.lastUpdatedEpochMs <= since) return 0.0
    val fraction = progress.progressFraction.toDouble()
    return if (progress.isEffectivelyCompleted) maxOf(fraction, AUTO_DELETE_FRACTION) else fraction
}

/** Episodes go when auto-delete is on; movies only when it also applies to movies. Finished downloads only. */
internal fun shouldAutoDelete(item: DownloadItem, completedFraction: Double, settings: LayoverSettings): Boolean {
    if (item.status != DownloadStatus.Completed) return false
    if (completedFraction < AUTO_DELETE_FRACTION) return false
    if (!settings.autoDeleteWatched) return false
    return item.isEpisode || settings.autoDeleteMovies
}

internal enum class AutoDeleteAction { Keep, Delete, Defer }

/** A watched file still open in the player is deferred; the next sweep (playback ending) deletes it. */
internal fun autoDeleteAction(
    item: DownloadItem,
    completedFraction: Double,
    settings: LayoverSettings,
    playingThisFile: Boolean,
): AutoDeleteAction = when {
    !shouldAutoDelete(item, completedFraction, settings) -> AutoDeleteAction.Keep
    playingThisFile -> AutoDeleteAction.Defer
    else -> AutoDeleteAction.Delete
}

/** True when the player's source is this download's file (same uri, or a uri ending in its file name). */
internal fun isPlayingFile(item: DownloadItem, playingSourceUrl: String?): Boolean {
    if (playingSourceUrl.isNullOrBlank()) return false
    if (playingSourceUrl == item.localFileUri) return true
    return item.fileName.isNotBlank() && playingSourceUrl.endsWith(item.fileName)
}

// Runtime -------------------------------------------------------------------------------------

/**
 * Deletes downloads once they're watched. Runs when the app comes to the foreground, when progress passes
 * 90%, and when the player lets go of a file. Layover never downloads anything on its own: only what the user taps
 * (an episode, a movie, a season) is downloaded. (Smart downloads, which queued upcoming episodes by
 * themselves, were removed on 2026-11-10; see [LayoverDownloadQueue] for the cleanup of their leftovers.)
 */
object AutoDeleteWatched {
    private val log = Logger.withTag("LayoverAutoDelete")
    private val job = SupervisorJob()
    private val swallowErrors = CoroutineExceptionHandler { _, error -> log.w(error) { "auto-delete failed" } }

    /** Source the player has open right now (a download's file uri while playing offline). */
    private val playingSourceUrl = MutableStateFlow<String?>(null)

    /** App came to the foreground. */
    fun refresh() = guarded { sweep() }

    /** WatchProgressRepository saved progress: delete the download once it's watched. */
    fun onProgressSaved(entry: WatchProgressEntry) = guarded {
        if (!entry.isEffectivelyCompleted && entry.progressFraction < AUTO_DELETE_FRACTION) return@guarded
        sweep()
    }

    /** The player opened [url]. */
    fun onPlaybackSource(url: String?) {
        playingSourceUrl.value = url
    }

    /** The player let go of [url]: a watched file deferred while it played can go now. */
    fun onPlaybackSourceReleased(url: String?) = guarded {
        playingSourceUrl.compareAndSet(url, null)
        sweep()
    }

    /** Called from upstream code paths (progress saves, the player): an auto-delete bug must never break them. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            log.w(error) { "auto-delete hook failed" }
        }
    }

    private fun sweep() {
        CoroutineScope(job + Dispatchers.Main + swallowErrors).launch { sweepNow() }
    }

    /** On Main. Deletes finished downloads watched since they downloaded, except the one playing. */
    private fun sweepNow() {
        val settings = LayoverSettingsRepository.state.value
        if (!settings.autoDeleteWatched) return
        DownloadsRepository.ensureLoaded()
        val progress = WatchProgressRepository.uiState.value
        val watched = WatchedRepository.uiState.value.items
        val playing = playingSourceUrl.value
        DownloadsRepository.uiState.value.items
            .filter { it.status == DownloadStatus.Completed }
            .forEach { item ->
                val entry = progress.progressForVideo(
                    videoId = item.videoId,
                    parentMetaId = item.parentMetaId,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                )
                val fraction = watchedFractionSinceDownload(item, entry, watchedMarkFor(item, watched))
                if (autoDeleteAction(item, fraction, settings, isPlayingFile(item, playing)) == AutoDeleteAction.Delete) {
                    log.i { "auto-deleting watched ${item.logicalContentKey}" }
                    DownloadsRepository.cancelDownload(item.id)
                }
            }
    }

    private fun watchedMarkFor(item: DownloadItem, watched: List<WatchedItem>): Long? =
        watched
            .filter {
                it.id.trim() == item.parentMetaId.trim() &&
                    it.season == item.seasonNumber &&
                    it.episode == item.episodeNumber
            }
            .maxOfOrNull { normalizeWatchedMarkedAtEpochMs(it.markedAtEpochMs) }
}
