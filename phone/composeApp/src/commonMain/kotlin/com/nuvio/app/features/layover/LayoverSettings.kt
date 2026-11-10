package com.nuvio.app.features.layover

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LayoverSettings(
    val wifiOnly: Boolean = true,
    val movieCapBytes: Long = 4_294_967_296,
    val episodeCapBytes: Long = 2_147_483_648,
    val preferredMaxHeight: Int = 1080,
    val autoDeleteWatched: Boolean = true,
    val autoDeleteMovies: Boolean = true,
    val firstFrameTimeoutMs: Long = 8_000,
    val maxAttempts: Int = 6,
    val fallbackEnabled: Boolean = true,
)

object LayoverSettingsRepository {
    private const val storageKey = "layover_settings"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal var loader: () -> String? = { LayoverStorage.load(storageKey) }

    private val _state: MutableStateFlow<LayoverSettings> by lazy {
        MutableStateFlow(decode(loader()))
    }

    val state: StateFlow<LayoverSettings>
        get() = _state.asStateFlow()

    /** Reload from the (profile-scoped) storage key after the active profile changes. */
    fun onProfileChanged() {
        _state.value = decode(loader())
    }

    fun update(transform: (LayoverSettings) -> LayoverSettings) {
        val next = clamp(transform(_state.value))
        _state.value = next
        LayoverStorage.save(storageKey, encode(next))
    }

    internal fun decode(json: String?): LayoverSettings {
        if (json.isNullOrBlank()) return LayoverSettings()
        val parsed = runCatching { this.json.decodeFromString<LayoverSettings>(json) }
            .getOrDefault(LayoverSettings())
        return clamp(parsed)
    }

    internal fun encode(s: LayoverSettings): String = json.encodeToString(LayoverSettings.serializer(), s)

    private fun clamp(s: LayoverSettings): LayoverSettings = s.copy(
        maxAttempts = s.maxAttempts.coerceIn(1, 10),
    )
}
