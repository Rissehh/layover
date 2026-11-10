package com.nuvio.app.features.layover.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.layover.isOnUnmeteredNetwork
import kotlinx.coroutines.delay

/** What the Downloads screen needs to badge its rows, read once per screen. */
internal data class LayoverDownloadsEnv(
    val onUnmetered: Boolean = true,
    val recovering: Set<String> = emptySet(),
)

internal val LocalLayoverDownloadsEnv = compositionLocalOf { LayoverDownloadsEnv() }

/** How often the screen re-reads the network type. There's no network callback in common code. */
private const val NETWORK_POLL_MS = 3_000L

/** Wraps the Downloads screen so every row can work out its badge. */
@Composable
fun LayoverDownloadsEnvironment(content: @Composable () -> Unit) {
    val onUnmetered by produceState(initialValue = isOnUnmeteredNetwork()) {
        while (true) {
            value = isOnUnmeteredNetwork()
            delay(NETWORK_POLL_MS)
        }
    }
    val recovering by DownloadRecovery.inFlightIdsFlow.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalLayoverDownloadsEnv provides LayoverDownloadsEnv(onUnmetered, recovering),
        content = content,
    )
}

@Composable
fun layoverBadgeFor(item: DownloadItem): DownloadBadge {
    val env = LocalLayoverDownloadsEnv.current
    return badgeFor(item, env.onUnmetered, env.recovering)
}

/** The queue, as the Waiting section shows it. */
@Composable
fun rememberLayoverWaitingRows(): List<WaitingRow> {
    val pending by LayoverDownloadQueue.pending.collectAsStateWithLifecycle()
    return remember(pending) { waitingRows(pending) }
}

@Composable
fun LayoverDownloadBadgeChip(badge: DownloadBadge) {
    val color = when (badge) {
        DownloadBadge.Ready -> MaterialTheme.colorScheme.primary
        DownloadBadge.Downloading -> MaterialTheme.colorScheme.tertiary
        DownloadBadge.WaitingForWifi, DownloadBadge.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
        DownloadBadge.Failed -> MaterialTheme.colorScheme.error
    }
    Text(
        text = badge.label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun LayoverUseMobileDataButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(text = "Use mobile data", style = MaterialTheme.typography.labelMedium)
    }
}

/** Queued downloads with no file yet, above upstream's list. Nothing when the queue is empty. */
fun LazyListScope.layoverWaitingSection(rows: List<WaitingRow>) {
    if (rows.isEmpty()) return
    item(key = "layover_waiting_title") {
        Text(
            text = WAITING_STATUS,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
    items(items = rows, key = { "layover_waiting_${it.videoId}" }) { row ->
        WaitingRowView(
            row = row,
            onRetry = { LayoverDownloadQueue.retry(row.videoId) },
            onRemove = { LayoverDownloadQueue.remove(row.videoId) },
        )
    }
}

@Composable
private fun WaitingRowView(
    row: WaitingRow,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = row.poster,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 40.dp, height = 60.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = row.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.canRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.canRetry) {
                IconButton(onClick = onRetry) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "Retry")
                }
            }
            IconButton(onClick = onRemove) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Remove from queue")
            }
        }
    }
}
