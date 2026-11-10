package com.nuvio.app.features.layover

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoverSettingsTest {
    @Test
    fun `decode null gives defaults`() {
        assertEquals(LayoverSettings(), LayoverSettingsRepository.decode(null))
    }

    @Test
    fun `decode garbage gives defaults`() {
        assertEquals(LayoverSettings(), LayoverSettingsRepository.decode("garbage"))
    }

    @Test
    fun `decode partial json keeps other fields default`() {
        val decoded = LayoverSettingsRepository.decode("{\"wifiOnly\":false}")
        assertEquals(LayoverSettings().copy(wifiOnly = false), decoded)
        assertEquals(false, decoded.wifiOnly)
    }

    @Test
    fun `encode then decode round trips non default settings`() {
        val x = LayoverSettings(
            wifiOnly = false,
            movieCapBytes = 1_000L,
            episodeCapBytes = 2_000L,
            preferredMaxHeight = 720,
            autoDeleteWatched = false,
            autoDeleteMovies = false,
            firstFrameTimeoutMs = 12_000,
            maxAttempts = 4,
            fallbackEnabled = false,
        )
        assertEquals(x, LayoverSettingsRepository.decode(LayoverSettingsRepository.encode(x)))
    }

    @Test
    fun `settings saved with the removed smart download count still load`() {
        val decoded = LayoverSettingsRepository.decode("{\"smartDownloadCount\":2,\"wifiOnly\":false}")
        assertEquals(LayoverSettings(wifiOnly = false), decoded)
    }

    @Test
    fun `maxAttempts is clamped to 1 through 10`() {
        assertEquals(10, LayoverSettingsRepository.decode("{\"maxAttempts\":99}").maxAttempts)
        assertEquals(1, LayoverSettingsRepository.decode("{\"maxAttempts\":0}").maxAttempts)
    }

    @Test
    fun `onProfileChanged reloads state from the new profile's stored settings`() {
        val original = LayoverSettingsRepository.loader
        try {
            LayoverSettingsRepository.loader = { "{\"wifiOnly\":false,\"maxAttempts\":3}" }
            LayoverSettingsRepository.onProfileChanged()
            assertEquals(
                LayoverSettings(wifiOnly = false, maxAttempts = 3),
                LayoverSettingsRepository.state.value,
            )

            LayoverSettingsRepository.loader = { null }
            LayoverSettingsRepository.onProfileChanged()
            assertEquals(LayoverSettings(), LayoverSettingsRepository.state.value)
        } finally {
            LayoverSettingsRepository.loader = original
            LayoverSettingsRepository.onProfileChanged()
        }
    }
}
