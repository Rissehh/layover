package com.nuvio.tv.layover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Layover's playback settings on the TV (the phone's download settings don't apply here).
 *
 * - [fallbackEnabled]: off = the player behaves exactly like upstream NuvioTV (no switching,
 *   no "Next source", no logging).
 * - [firstFrameTimeoutMs]: no picture this long after a source starts = that source failed. 25 s, not the
 *   phone's 8: the Sony needed 15 s to show a 4K HEVC episode resumed mid-way (2026-10-24), and 8 s threw
 *   away sources that were about to play.
 * - [maxAttempts]: sources tried per play, counting the first, before the sources panel opens.
 */
@Serializable
data class LayoverSettings(
    val fallbackEnabled: Boolean = true,
    val firstFrameTimeoutMs: Long = 25_000,
    val maxAttempts: Int = 6,
)

object LayoverSettingsRepository {
    private const val storageKey = "layover_settings"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal var loader: () -> String? = { LayoverStorage.load(storageKey) }
    internal var saver: (String) -> Unit = { LayoverStorage.save(storageKey, it) }

    private val _state: MutableStateFlow<LayoverSettings> by lazy {
        MutableStateFlow(decode(loader()))
    }

    val state: StateFlow<LayoverSettings>
        get() = _state.asStateFlow()

    /** Reload from storage (after [LayoverStorage.initialize], or in tests). */
    fun reload() {
        _state.value = decode(loader())
    }

    fun update(transform: (LayoverSettings) -> LayoverSettings) {
        val next = clamp(transform(_state.value))
        _state.value = next
        saver(encode(next))
    }

    internal fun decode(json: String?): LayoverSettings {
        if (json.isNullOrBlank()) return LayoverSettings()
        val parsed = runCatching { this.json.decodeFromString(LayoverSettings.serializer(), json) }
            .getOrDefault(LayoverSettings())
        return clamp(parsed)
    }

    internal fun encode(s: LayoverSettings): String = json.encodeToString(LayoverSettings.serializer(), s)

    private fun clamp(s: LayoverSettings): LayoverSettings = s.copy(
        firstFrameTimeoutMs = s.firstFrameTimeoutMs.coerceIn(3_000L, 60_000L),
        maxAttempts = s.maxAttempts.coerceIn(1, 10),
    )
}
