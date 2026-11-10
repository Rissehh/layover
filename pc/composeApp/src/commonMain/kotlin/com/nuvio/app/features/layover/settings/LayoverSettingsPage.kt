package com.nuvio.app.features.layover.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.layover.LayoverSettingsRepository
import com.nuvio.app.features.settings.SettingsChipRow
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow

/**
 * The single Layover settings page.
 *
 * [onStreamStatsClick] opens the stream stats screen (Task 15). While it is null the row shows
 * "Coming soon" and is disabled.
 */
internal fun LazyListScope.layoverSettingsContent(
    isTablet: Boolean,
    onStreamStatsClick: (() -> Unit)? = null,
) {
    item {
        val settings by remember { LayoverSettingsRepository.state }.collectAsStateWithLifecycle()
        SettingsSection(title = "Downloads", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Wi-Fi only",
                    description = "Wait for Wi-Fi instead of using mobile data",
                    checked = settings.wifiOnly,
                    isTablet = isTablet,
                    onCheckedChange = { v -> LayoverSettingsRepository.update { it.copy(wifiOnly = v) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                LayoverChoiceRow(
                    title = "Movie size cap",
                    description = "Skip movie sources bigger than this",
                    choices = MOVIE_CAP_CHOICES,
                    current = settings.movieCapBytes,
                    isTablet = isTablet,
                    onSelected = { v -> LayoverSettingsRepository.update { it.copy(movieCapBytes = v) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                LayoverChoiceRow(
                    title = "Episode size cap",
                    description = "Skip episode sources bigger than this",
                    choices = EPISODE_CAP_CHOICES,
                    current = settings.episodeCapBytes,
                    isTablet = isTablet,
                    onSelected = { v -> LayoverSettingsRepository.update { it.copy(episodeCapBytes = v) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                LayoverChoiceRow(
                    title = "Max quality",
                    description = "Highest resolution to download",
                    choices = QUALITY_CHOICES,
                    current = settings.preferredMaxHeight.toLong(),
                    isTablet = isTablet,
                    onSelected = { v -> LayoverSettingsRepository.update { it.copy(preferredMaxHeight = v.toInt()) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Auto-delete watched",
                    description = "Remove a download once you have watched it",
                    checked = settings.autoDeleteWatched,
                    isTablet = isTablet,
                    onCheckedChange = { v -> LayoverSettingsRepository.update { it.copy(autoDeleteWatched = v) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Also delete movies",
                    description = "Off keeps watched movies, only episodes are removed",
                    checked = settings.autoDeleteMovies,
                    enabled = settings.autoDeleteWatched,
                    isTablet = isTablet,
                    onCheckedChange = { v -> LayoverSettingsRepository.update { it.copy(autoDeleteMovies = v) } },
                )
            }
        }
    }
    item { Spacer(modifier = Modifier.height(if (isTablet) 24.dp else 20.dp)) }
    item {
        val settings by remember { LayoverSettingsRepository.state }.collectAsStateWithLifecycle()
        SettingsSection(title = "Streaming", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Auto fallback",
                    description = "If a source fails, try the next best one",
                    checked = settings.fallbackEnabled,
                    isTablet = isTablet,
                    onCheckedChange = { v -> LayoverSettingsRepository.update { it.copy(fallbackEnabled = v) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                LayoverChoiceRow(
                    title = "First-frame timeout",
                    description = "How long a source gets to start playing before the next is tried",
                    choices = TIMEOUT_CHOICES,
                    current = settings.firstFrameTimeoutMs,
                    isTablet = isTablet,
                    onSelected = { v -> LayoverSettingsRepository.update { it.copy(firstFrameTimeoutMs = v) } },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Stream stats",
                    description = if (onStreamStatsClick != null) "Which sources and addons worked for you" else "Coming soon",
                    enabled = onStreamStatsClick != null,
                    isTablet = isTablet,
                    onClick = { onStreamStatsClick?.invoke() },
                )
            }
        }
    }
}

@Composable
private fun LayoverChoiceRow(
    title: String,
    description: String,
    choices: List<LayoverChoice>,
    current: Long,
    isTablet: Boolean,
    onSelected: (Long) -> Unit,
) {
    val selected = choices.nearest(current)
    SettingsChipRow(title = title, description = description, isTablet = isTablet) {
        choices.forEach { choice ->
            Chip(
                label = choice.label,
                selected = choice == selected,
                onClick = { onSelected(choice.value) },
            )
        }
    }
}
