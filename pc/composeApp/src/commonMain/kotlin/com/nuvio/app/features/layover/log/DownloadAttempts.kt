package com.nuvio.app.features.layover.log

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem

/**
 * Attempt record for a download. A download only keeps the stream's label, subtitle and identity,
 * so this rebuilds a stand-in [StreamItem] from those and reuses [attemptRecord] for the facts.
 */
fun attemptRecordForDownload(item: DownloadItem, outcome: AttemptOutcome, now: Long): AttemptRecord {
    val identity = item.streamIdentity
    val stream = StreamItem(
        name = item.streamTitle,
        description = item.streamSubtitle,
        addonName = item.providerName,
        addonId = identity?.addonId ?: item.providerAddonId ?: item.providerName,
        behaviorHints = StreamBehaviorHints(
            filename = identity?.filename,
            videoSize = identity?.videoSize,
        ),
    )
    return attemptRecord(
        stream = stream,
        kind = AttemptKind.Download,
        contentKey = item.logicalContentKey,
        outcome = outcome,
        ttffMs = null,
        now = now,
    )
}
