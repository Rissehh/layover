package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus

/** The one-word state a row in the Downloads tab shows. */
enum class DownloadBadge(val label: String) {
    Ready("Ready"),
    Downloading("Downloading"),
    WaitingForWifi("Waiting for Wi-Fi"),
    Waiting("Waiting"),
    Failed("Failed"),
}

/**
 * Badge for one download. Completed counts as Ready (the Ready check keeps unverified files from reaching
 * Completed; pre-Layover downloads count as Ready). A Failed item that still holds a slot (finding the file
 * again, the Ready check's one retry) is shown as Downloading, since it is about to transfer again; one waiting
 * for the addons to be reachable shows as Waiting. [recovering] is DownloadRecovery's in-flight set.
 */
fun badgeFor(
    item: DownloadItem,
    onUnmetered: Boolean,
    recovering: Set<String> = emptySet(),
): DownloadBadge = when (item.status) {
    DownloadStatus.Completed -> DownloadBadge.Ready
    DownloadStatus.Downloading ->
        if (!item.allowMetered && !onUnmetered) DownloadBadge.WaitingForWifi else DownloadBadge.Downloading
    DownloadStatus.Paused -> DownloadBadge.Waiting
    DownloadStatus.Failed -> when {
        item.errorMessage == ADDONS_UNREACHABLE_MESSAGE -> DownloadBadge.Waiting
        item.holdsSlot(recovering) -> DownloadBadge.Downloading
        else -> DownloadBadge.Failed
    }
}

/** Which action buttons a download row shows (delete is always there). */
data class RowActions(
    val pause: Boolean = false,
    val resume: Boolean = false,
    val retry: Boolean = false,
    val play: Boolean = false,
    val useMobileData: Boolean = false,
)

/**
 * Upstream's actions per status, adjusted for the badge: a Failed item that is recovering on its own gets no
 * Retry (it would race the recovery), and "Use mobile data" appears only while waiting for Wi-Fi.
 */
fun rowActionsFor(item: DownloadItem, badge: DownloadBadge): RowActions = when (item.status) {
    DownloadStatus.Downloading -> RowActions(pause = true, useMobileData = badge == DownloadBadge.WaitingForWifi)
    DownloadStatus.Paused -> RowActions(resume = true)
    DownloadStatus.Completed -> RowActions(play = true)
    DownloadStatus.Failed -> if (badge == DownloadBadge.Downloading) RowActions() else RowActions(retry = true)
}

/** One entry in the Downloads tab's Waiting section (a queued download with no file yet). */
data class WaitingRow(
    val videoId: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val status: String,
    val canRetry: Boolean,
)

internal const val WAITING_STATUS = "Waiting"

/** The queue as the Waiting section shows it, in queue order. */
fun waitingRows(pending: List<QueuedDownload>): List<WaitingRow> = pending.map { entry ->
    val season = entry.seasonNumber
    val episode = entry.episodeNumber
    val subtitle = if (season != null && episode != null) {
        listOfNotNull("S$season E$episode", entry.episodeTitle?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" • ")
    } else {
        null
    }
    WaitingRow(
        videoId = entry.videoId,
        title = entry.title,
        subtitle = subtitle,
        poster = entry.poster ?: entry.background,
        status = entry.failure ?: WAITING_STATUS,
        canRetry = entry.failure != null,
    )
}
