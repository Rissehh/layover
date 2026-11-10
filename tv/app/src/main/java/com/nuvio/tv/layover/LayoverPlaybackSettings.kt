package com.nuvio.tv.layover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.screens.settings.SliderSettingsItem

/** Layover's rows in Settings > Playback, under the stream auto-play rows. */
@Composable
fun LayoverPlaybackSettings() {
    val settings by LayoverSettingsRepository.state.collectAsState()
    SettingsToggleRow(
        title = stringResource(R.string.layover_setting_fallback),
        subtitle = stringResource(R.string.layover_setting_fallback_sub),
        checked = settings.fallbackEnabled,
        onToggle = { LayoverSettingsRepository.update { it.copy(fallbackEnabled = !it.fallbackEnabled) } },
    )
    if (settings.fallbackEnabled) {
        val seconds = (settings.firstFrameTimeoutMs / 1000L).toInt()
        SliderSettingsItem(
            title = stringResource(R.string.layover_setting_first_frame_timeout),
            subtitle = stringResource(R.string.layover_setting_first_frame_timeout_sub),
            value = seconds,
            valueText = "${seconds}s",
            minValue = 4,
            maxValue = 30,
            step = 1,
            onValueChange = { value -> LayoverSettingsRepository.update { it.copy(firstFrameTimeoutMs = value * 1000L) } },
        )
    }
}
