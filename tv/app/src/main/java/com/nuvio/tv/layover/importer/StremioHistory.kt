package com.nuvio.tv.layover.importer

import com.nuvio.tv.domain.model.WatchProgress

/** The fields of one Stremio library item (api.strem.io datastoreGet, collection libraryItem) the import uses. */
data class StremioLibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val videoId: String?,
    /** Stremio's position in ms. 1 means "moved on to this episode", 0 means not started (or finished). */
    val timeOffset: Long,
    val duration: Long,
    val timesWatched: Int,
    val lastWatched: Long,
)

/** What one item becomes in LayoverTV: progress to save, and / or an episode or movie to mark watched. */
data class ImportedEntry(val progress: WatchProgress?, val completed: WatchProgress?)

private const val FINISHED = 0.9

/**
 * Maps a Stremio library item onto LayoverTV's watch history, or null when there is nothing to carry over
 * (opened but never played, not an IMDb title, not a movie or series).
 *
 * - A movie past 90%, or with position 0 and watched at least once: watched.
 * - A movie partly played: in progress.
 * - An episode Stremio already moved on to (position 1): the episode before it is watched, so LayoverTV's
 *   Next up offers this one.
 * - An episode past 90%, or at 0 and watched at least once: watched. Partly played: in progress.
 */
fun importEntry(item: StremioLibraryItem): ImportedEntry? {
    if (!item.id.startsWith("tt")) return null
    val finished = (item.duration > 0 && item.timeOffset >= item.duration * FINISHED) ||
        (item.timeOffset == 0L && item.timesWatched > 0)
    return when (item.type) {
        "movie" -> when {
            finished -> ImportedEntry(null, progress(item, item.id, null, null, item.duration))
            item.timeOffset > 1 -> ImportedEntry(progress(item, item.id, null, null, item.timeOffset), null)
            else -> null
        }
        "series" -> {
            val (season, episode) = seasonEpisode(item.videoId) ?: return null
            val videoId = item.videoId!!
            when {
                item.timeOffset == 1L -> {
                    if (episode <= 1) return null
                    val prev = episode - 1
                    ImportedEntry(null, progress(item, "${item.id}:$season:$prev", season, prev, 0))
                }
                finished -> ImportedEntry(null, progress(item, videoId, season, episode, item.duration))
                item.timeOffset > 1 -> ImportedEntry(progress(item, videoId, season, episode, item.timeOffset), null)
                else -> null
            }
        }
        else -> null
    }
}

/** A watched entry as LayoverTV stores finished progress (position = duration, 100%), keeping its own date. */
fun finishedAsOf(p: WatchProgress): WatchProgress {
    val duration = p.duration.takeIf { it > 1L } ?: 1L
    return p.copy(position = duration, duration = duration, progressPercent = 100f)
}

/** {season, episode} from a Stremio series video id "tt0944947:1:5". */
fun seasonEpisode(videoId: String?): Pair<Int, Int>? {
    val parts = videoId?.split(':') ?: return null
    if (parts.size < 3) return null
    val season = parts[parts.size - 2].toIntOrNull() ?: return null
    val episode = parts[parts.size - 1].toIntOrNull() ?: return null
    return season to episode
}

private fun progress(item: StremioLibraryItem, videoId: String, season: Int?, episode: Int?, position: Long) =
    WatchProgress(
        contentId = item.id,
        contentType = item.type,
        name = item.name,
        poster = item.poster,
        backdrop = "https://images.metahub.space/background/medium/${item.id}/img",
        logo = "https://images.metahub.space/logo/medium/${item.id}/img",
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = item.duration,
        lastWatched = item.lastWatched,
    )
