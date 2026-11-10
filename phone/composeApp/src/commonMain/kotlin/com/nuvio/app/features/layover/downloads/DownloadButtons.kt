package com.nuvio.app.features.layover.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadOrigin
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.layover.LayoverSettingsRepository
import com.nuvio.app.features.layover.log.layoverContentKey
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlin.math.roundToInt

// Pure state ------------------------------------------------------------------------------------

/** What a download button shows for one movie or episode. */
sealed interface LayoverDownloadState {
    data object Idle : LayoverDownloadState
    data object Queued : LayoverDownloadState
    data class NoSource(val message: String) : LayoverDownloadState
    data class Downloading(val progress: Float) : LayoverDownloadState
    data class Paused(val downloadId: String) : LayoverDownloadState
    data object Downloaded : LayoverDownloadState
}

/**
 * [contentKey] is the `DownloadItem.logicalContentKey` format (one download per key, enqueue replaces);
 * [videoId] matches queue entries. A Failed download shows Idle so one tap queues a fresh pick.
 */
internal fun downloadState(
    contentKey: String,
    videoId: String,
    downloads: List<DownloadItem>,
    pending: List<QueuedDownload>,
): LayoverDownloadState {
    val item = downloads.firstOrNull { it.logicalContentKey == contentKey }
    if (item?.status == DownloadStatus.Completed) return LayoverDownloadState.Downloaded
    // Downloading, or Failed but already being re-found / re-checked: it is still on its way.
    if (item != null && item.holdsSlot(emptySet())) return LayoverDownloadState.Downloading(item.progressFraction)
    pending.firstOrNull { it.videoId == videoId }?.let { queued ->
        return queued.failure?.let { LayoverDownloadState.NoSource(it) } ?: LayoverDownloadState.Queued
    }
    if (item?.status == DownloadStatus.Paused) return LayoverDownloadState.Paused(item.id)
    return LayoverDownloadState.Idle
}

/** Movies only: series, and live tv or channels (nothing to download), get no movie button. */
internal fun MetaDetails.isLayoverMovie(): Boolean =
    type != "series" && type != "tv" && type != "channel" && videos.none { it.season != null || it.episode != null }

internal fun MetaDetails.queuedMovie(allowMetered: Boolean, now: Long): QueuedDownload =
    QueuedDownload(
        contentType = type,
        videoId = id,
        parentMetaId = id,
        parentMetaType = type,
        title = name,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        origin = DownloadOrigin.Manual,
        allowMetered = allowMetered,
        queuedAtEpochMs = now,
    )

/** Same video id the episode's Play uses to ask addons for streams. */
internal fun MetaDetails.episodeVideoId(video: MetaVideo): String =
    video.id.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
        parentMetaId = id,
        seasonNumber = video.season,
        episodeNumber = video.episode,
        fallbackVideoId = video.id,
    )

internal fun MetaDetails.episodeContentKey(video: MetaVideo): String = layoverContentKey(id, video.season, video.episode)

internal fun MetaDetails.queuedEpisode(
    video: MetaVideo,
    origin: DownloadOrigin,
    allowMetered: Boolean,
    now: Long,
): QueuedDownload =
    QueuedDownload(
        contentType = type,
        videoId = episodeVideoId(video),
        parentMetaId = id,
        parentMetaType = type,
        title = name,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = video.season,
        episodeNumber = video.episode,
        episodeTitle = video.title,
        episodeThumbnail = video.thumbnail,
        origin = origin,
        allowMetered = allowMetered,
        queuedAtEpochMs = now,
    )

/** Episodes that can be downloaded: numbered (one download per season/episode) and released. */
internal fun downloadableEpisodes(episodes: List<MetaVideo>): List<MetaVideo> =
    episodes
        .filter { it.season != null && it.episode != null && it.available }
        .sortedBy { it.episode }

private fun MetaDetails.episodeState(video: MetaVideo, downloads: List<DownloadItem>, pending: List<QueuedDownload>) =
    downloadState(episodeContentKey(video), episodeVideoId(video), downloads, pending)

/** "Download season": released episodes in episode order with nothing going on yet (paused ones keep their bytes). */
internal fun seasonToQueue(
    meta: MetaDetails,
    episodes: List<MetaVideo>,
    downloads: List<DownloadItem>,
    pending: List<QueuedDownload>,
    allowMetered: Boolean,
    now: Long,
): List<QueuedDownload> =
    downloadableEpisodes(episodes)
        .filter { meta.episodeState(it, downloads, pending) == LayoverDownloadState.Idle }
        .map { meta.queuedEpisode(it, DownloadOrigin.Season, allowMetered, now) }

/** Queue entries of this season that found no source (video ids), for "Retry season". */
internal fun seasonToRetry(
    meta: MetaDetails,
    episodes: List<MetaVideo>,
    downloads: List<DownloadItem>,
    pending: List<QueuedDownload>,
): List<String> =
    downloadableEpisodes(episodes)
        .filter { meta.episodeState(it, downloads, pending) is LayoverDownloadState.NoSource }
        .map { meta.episodeVideoId(it) }

enum class SeasonDownloadState { None, Download, Retry, Queued, Downloaded }

internal fun seasonDownloadState(
    meta: MetaDetails,
    episodes: List<MetaVideo>,
    downloads: List<DownloadItem>,
    pending: List<QueuedDownload>,
): SeasonDownloadState {
    val states = downloadableEpisodes(episodes).map { meta.episodeState(it, downloads, pending) }
    return when {
        states.isEmpty() -> SeasonDownloadState.None
        states.all { it == LayoverDownloadState.Downloaded } -> SeasonDownloadState.Downloaded
        states.any { it == LayoverDownloadState.Idle } -> SeasonDownloadState.Download
        // Nothing left moving, some found no source: offer a retry instead of a dead "Season queued".
        states.any { it is LayoverDownloadState.NoSource } &&
            states.none { it == LayoverDownloadState.Queued || it is LayoverDownloadState.Downloading } ->
            SeasonDownloadState.Retry
        else -> SeasonDownloadState.Queued
    }
}

// Taps -------------------------------------------------------------------------------------------

/** What a Download tap says: only "Added" when the queue actually took it (it skips ones already there). */
internal fun addedToDownloadsToast(added: Int): String =
    if (added > 0) "Added to downloads" else "Already in downloads"

private fun allowMeteredNow(): Boolean = !LayoverSettingsRepository.state.value.wifiOnly

private fun onDownloadTap(state: LayoverDownloadState, videoId: String, queued: () -> QueuedDownload) {
    when (state) {
        LayoverDownloadState.Idle -> {
            NuvioToastController.show(addedToDownloadsToast(LayoverDownloadQueue.add(listOf(queued()))))
        }
        is LayoverDownloadState.NoSource -> {
            LayoverDownloadQueue.retry(videoId)
            NuvioToastController.show("Looking for a source again")
        }
        is LayoverDownloadState.Paused -> {
            DownloadsRepository.resumeDownload(state.downloadId)
            NuvioToastController.show("Download resumed")
        }
        LayoverDownloadState.Queued -> NuvioToastController.show("Waiting for a free download slot")
        is LayoverDownloadState.Downloading -> NuvioToastController.show("Downloading")
        LayoverDownloadState.Downloaded -> NuvioToastController.show("Downloaded")
    }
}

private fun LayoverDownloadState.label(): String = when (this) {
    LayoverDownloadState.Idle -> "Download"
    LayoverDownloadState.Queued -> "Queued"
    is LayoverDownloadState.NoSource -> "$message. Tap to retry"
    is LayoverDownloadState.Downloading -> "Downloading ${(progress * 100).roundToInt()}%"
    is LayoverDownloadState.Paused -> "Paused. Tap to resume"
    LayoverDownloadState.Downloaded -> "Downloaded"
}

private fun LayoverDownloadState.icon(): ImageVector = when (this) {
    LayoverDownloadState.Idle -> Icons.Rounded.Download
    LayoverDownloadState.Queued -> Icons.Rounded.Schedule
    is LayoverDownloadState.NoSource -> Icons.Rounded.ErrorOutline
    is LayoverDownloadState.Downloading -> Icons.Rounded.Downloading
    is LayoverDownloadState.Paused -> Icons.Rounded.Pause
    LayoverDownloadState.Downloaded -> Icons.Rounded.DownloadDone
}

// UI -------------------------------------------------------------------------------------------

/** The movie's Download button for the detail action row, or null when [meta] isn't a movie. */
@Composable
fun rememberLayoverMovieDownloadAction(meta: MetaDetails): DetailSecondaryAction? {
    val downloads by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    val pending by LayoverDownloadQueue.pending.collectAsStateWithLifecycle()
    if (!meta.isLayoverMovie()) return null
    val state = downloadState(layoverContentKey(meta.id, null, null), meta.id, downloads.items, pending)
    return DetailSecondaryAction(
        label = state.label(),
        icon = state.icon(),
        isActive = state == LayoverDownloadState.Downloaded,
        onClick = { onDownloadTap(state, meta.id) { meta.queuedMovie(allowMeteredNow(), DownloadsClock.nowEpochMs()) } },
    )
}

/** Round download icon for an episode card. Shows nothing for unnumbered or unreleased videos. */
@Composable
fun LayoverEpisodeDownloadIcon(meta: MetaDetails, video: MetaVideo, modifier: Modifier = Modifier) {
    if (downloadableEpisodes(listOf(video)).isEmpty()) return
    val downloads by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    val pending by LayoverDownloadQueue.pending.collectAsStateWithLifecycle()
    val state = meta.episodeState(video, downloads.items, pending)
    val videoId = meta.episodeVideoId(video)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button) {
                onDownloadTap(state, videoId) {
                    meta.queuedEpisode(video, DownloadOrigin.Manual, allowMeteredNow(), DownloadsClock.nowEpochMs())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (state is LayoverDownloadState.Downloading) {
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.size(30.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = 2.dp,
            )
        }
        Icon(
            imageVector = state.icon(),
            contentDescription = state.label(),
            tint = if (state == LayoverDownloadState.Downloaded) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** "Download season" pill under the season title. Queues every released episode not already handled. */
@Composable
fun LayoverSeasonDownloadButton(meta: MetaDetails, episodes: List<MetaVideo>, modifier: Modifier = Modifier) {
    val downloads by DownloadsRepository.uiState.collectAsStateWithLifecycle()
    val pending by LayoverDownloadQueue.pending.collectAsStateWithLifecycle()
    val state = seasonDownloadState(meta, episodes, downloads.items, pending)
    if (state == SeasonDownloadState.None) return
    val (label, icon) = when (state) {
        SeasonDownloadState.Downloaded -> "Season downloaded" to Icons.Rounded.DownloadDone
        SeasonDownloadState.Queued -> "Season queued" to Icons.Rounded.Downloading
        SeasonDownloadState.Retry -> "Retry season" to Icons.Rounded.Refresh
        else -> "Download season" to Icons.Rounded.Download
    }
    val shape = RoundedCornerShape(8.dp)
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(width = 1.dp, color = Color.White.copy(alpha = 0.2f), shape = shape)
                .clickable(
                    enabled = state == SeasonDownloadState.Download || state == SeasonDownloadState.Retry,
                    role = Role.Button,
                ) {
                    val currentDownloads = DownloadsRepository.uiState.value.items
                    val currentPending = LayoverDownloadQueue.pending.value
                    val items = seasonToQueue(
                        meta = meta,
                        episodes = episodes,
                        downloads = currentDownloads,
                        pending = currentPending,
                        allowMetered = allowMeteredNow(),
                        now = DownloadsClock.nowEpochMs(),
                    )
                    // Episodes that found no source last time get another go with the same tap.
                    val retried = LayoverDownloadQueue.retry(seasonToRetry(meta, episodes, currentDownloads, currentPending))
                    val count = LayoverDownloadQueue.add(items) + retried
                    NuvioToastController.show(if (count == 1) "Season queued (1 episode)" else "Season queued ($count episodes)")
                }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
