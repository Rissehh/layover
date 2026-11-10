package com.nuvio.app.features.layover.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsClock
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.layover.log.AttemptLog
import com.nuvio.app.features.layover.log.AttemptRecord
import com.nuvio.app.features.layover.rank.heightBucket
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_clear
import org.jetbrains.compose.resources.stringResource

/** Stream stats page: how each addon and quality has done, plus the latest failures. Updates live. */
internal fun LazyListScope.streamStatsContent(isTablet: Boolean) {
    item {
        val records by remember { AttemptLog.records }.collectAsStateWithLifecycle()
        val byAddon = remember(records) { statsByAddon(records) }
        if (byAddon.isEmpty()) StatsEmptyState() else StatsBody(records, byAddon, isTablet)
    }
}

@Composable
private fun StatsBody(records: List<AttemptRecord>, byAddon: List<StatRow>, isTablet: Boolean) {
    val downloads by remember { DownloadsRepository.uiState }.collectAsStateWithLifecycle()
    val byQuality = remember(records) { statsByQuality(records) }
    val failures = remember(records) { recentFailures(records) }
    val titles = remember(downloads.items) { downloads.items.associateBy { it.logicalContentKey } }
    val now = DownloadsClock.nowEpochMs()
    val sectionGap = if (isTablet) 24.dp else 20.dp

    Column {
        StatSection(title = "By addon", rows = byAddon, isTablet = isTablet)
        Spacer(modifier = Modifier.height(sectionGap))
        StatSection(title = "By quality", rows = byQuality, isTablet = isTablet)
        if (failures.isNotEmpty()) {
            Spacer(modifier = Modifier.height(sectionGap))
            SettingsSection(title = "Recent failures", isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    failures.forEachIndexed { index, record ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        StatLine(
                            title = failureTitle(record.contentKey, titles[record.contentKey]),
                            detail = failureDetail(record, now),
                            isTablet = isTablet,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(sectionGap))
        ClearHistoryGroup(isTablet = isTablet)
    }
}

@Composable
private fun StatsEmptyState() {
    val tokens = MaterialTheme.nuvio
    Text(
        text = "No stream history yet. Play or download something.",
        style = MaterialTheme.typography.bodyLarge,
        color = tokens.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

@Composable
private fun StatSection(title: String, rows: List<StatRow>, isTablet: Boolean) {
    SettingsSection(title = title, isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            rows.forEachIndexed { index, row ->
                if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                StatLine(title = row.label, detail = statDetail(row), isTablet = isTablet)
            }
        }
    }
}

@Composable
private fun ClearHistoryGroup(isTablet: Boolean) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(isTablet = isTablet) {
        SettingsNavigationRow(
            title = "Clear stream history",
            description = "Forget which sources worked and failed",
            isTablet = isTablet,
            onClick = { confirmClear = true },
        )
    }
    NuvioStatusModal(
        title = "Clear stream history?",
        message = "Layover forgets which sources worked. Rankings start fresh. Your downloads are not touched.",
        isVisible = confirmClear,
        destructive = true,
        confirmText = stringResource(Res.string.action_clear),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            AttemptLog.clear()
            confirmClear = false
        },
        onDismiss = { confirmClear = false },
    )
}

/** Read-only two-line row with the same padding as the settings navigation rows. */
@Composable
private fun StatLine(title: String, detail: String, isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}

private fun statDetail(row: StatRow): String {
    val attempts = if (row.attempts == 1) "1 attempt" else "${row.attempts} attempts"
    val start = row.avgTimeToFirstFrameMs?.let { " · starts in ${formatStartTime(it)}" }.orEmpty()
    return "$attempts · ${formatPercent(row.successRate)} worked$start · " +
        "${formatPercent(row.earlySwitchRate)} switched early"
}

private fun failureDetail(record: AttemptRecord, now: Long): String {
    val addon = record.addonName.ifBlank { record.addonId }
    return "$addon · ${heightBucket(record.height)} · ${failureLabel(record.outcome)} · " +
        formatRelativeTime(record.atEpochMs, now)
}

/** Download title (plus "S1E3" for episodes) when the content is in Downloads, else a fallback from the key. */
private fun failureTitle(contentKey: String, download: DownloadItem?): String {
    if (download == null || download.title.isBlank()) return fallbackTitle(contentKey)
    return episodeCode(contentKey)?.let { "${download.title} $it" } ?: download.title
}
