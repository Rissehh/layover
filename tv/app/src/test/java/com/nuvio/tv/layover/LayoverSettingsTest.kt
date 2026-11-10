package com.nuvio.tv.layover

import com.nuvio.tv.data.local.StreamAutoPlayMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoverSettingsTest {
    @After
    fun tearDown() {
        LayoverSettingsRepository.loader = { null }
        LayoverSettingsRepository.saver = { }
        LayoverSettingsRepository.reload()
    }

    @Test
    fun `defaults are fallback on with an 8 second first frame timeout`() {
        val defaults = LayoverSettingsRepository.decode(null)
        assertEquals(LayoverSettings(), defaults)
        assertEquals(true, defaults.fallbackEnabled)
        assertEquals(25_000L, defaults.firstFrameTimeoutMs)
        assertEquals(6, defaults.maxAttempts)
    }

    @Test
    fun `garbage and partial json fall back to defaults field by field`() {
        assertEquals(LayoverSettings(), LayoverSettingsRepository.decode("garbage"))
        assertEquals(LayoverSettings(fallbackEnabled = false), LayoverSettingsRepository.decode("{\"fallbackEnabled\":false}"))
        // Phone-only fields in a copied settings blob are ignored.
        assertEquals(LayoverSettings(maxAttempts = 3), LayoverSettingsRepository.decode("{\"wifiOnly\":false,\"maxAttempts\":3}"))
    }

    @Test
    fun `encode then decode round trips`() {
        val x = LayoverSettings(fallbackEnabled = false, firstFrameTimeoutMs = 12_000, maxAttempts = 4)
        assertEquals(x, LayoverSettingsRepository.decode(LayoverSettingsRepository.encode(x)))
    }

    @Test
    fun `values are clamped`() {
        assertEquals(10, LayoverSettingsRepository.decode("{\"maxAttempts\":99}").maxAttempts)
        assertEquals(1, LayoverSettingsRepository.decode("{\"maxAttempts\":0}").maxAttempts)
        assertEquals(3_000L, LayoverSettingsRepository.decode("{\"firstFrameTimeoutMs\":10}").firstFrameTimeoutMs)
        assertEquals(60_000L, LayoverSettingsRepository.decode("{\"firstFrameTimeoutMs\":999999}").firstFrameTimeoutMs)
    }

    @Test
    fun `update persists and reload reads it back`() {
        var stored: String? = null
        LayoverSettingsRepository.loader = { stored }
        LayoverSettingsRepository.saver = { stored = it }
        LayoverSettingsRepository.reload()
        LayoverSettingsRepository.update { it.copy(fallbackEnabled = false) }
        assertEquals(false, LayoverSettingsRepository.state.value.fallbackEnabled)
        LayoverSettingsRepository.reload()
        assertEquals(LayoverSettings(fallbackEnabled = false), LayoverSettingsRepository.state.value)
    }

    @Test
    fun `network policy judges unless offline and records only on a validated network`() {
        assertEquals(NetworkQuality.None, networkQualityOf(hasActiveNetwork = false, hasInternet = false, validated = false))
        assertEquals(NetworkQuality.None, networkQualityOf(hasActiveNetwork = true, hasInternet = false, validated = true))
        assertEquals(NetworkQuality.Unvalidated, networkQualityOf(hasActiveNetwork = true, hasInternet = true, validated = false))
        assertEquals(NetworkQuality.Validated, networkQualityOf(hasActiveNetwork = true, hasInternet = true, validated = true))
        assertEquals(FallbackNetworkPolicy(judge = false, record = false), NetworkQuality.None.fallbackPolicy())
        assertEquals(FallbackNetworkPolicy(judge = true, record = false), NetworkQuality.Unvalidated.fallbackPolicy())
        assertEquals(FallbackNetworkPolicy(judge = true, record = true), NetworkQuality.Validated.fallbackPolicy())
    }
}

class AutoplayMigrationTest {
    private var flag: String? = null
    private var mode = StreamAutoPlayMode.MANUAL
    private var sets = 0

    private fun migration(saveFlag: (String) -> Unit = { flag = it }) = LayoverAutoplayMigration(
        loadFlag = { flag },
        saveFlag = saveFlag,
        currentMode = { mode },
        setMode = {
            mode = it
            sets++
        },
    )

    @Test
    fun `first launch turns manual picking into first-stream autoplay`() = runBlocking {
        migration().run()
        assertEquals(StreamAutoPlayMode.FIRST_STREAM, mode)
        assertEquals("true", flag)
    }

    @Test
    fun `a later change back to manual is never overridden`() = runBlocking {
        migration().run()
        mode = StreamAutoPlayMode.MANUAL
        migration().run()
        assertEquals(StreamAutoPlayMode.MANUAL, mode)
        assertEquals(1, sets)
    }

    @Test
    fun `nothing changes when the flag cannot be stored`() = runBlocking {
        migration(saveFlag = { }).run()
        migration(saveFlag = { }).run()
        assertEquals(StreamAutoPlayMode.MANUAL, mode)
        assertEquals(0, sets)
    }

    @Test
    fun `an autoplay mode the user already chose is kept`() = runBlocking {
        mode = StreamAutoPlayMode.REGEX_MATCH
        migration().run()
        assertEquals(StreamAutoPlayMode.REGEX_MATCH, mode)
        assertEquals(0, sets)
        assertEquals("true", flag)
    }

    @Test
    fun `each profile has its own flag`() {
        assertEquals("autoplay_migrated_1", LayoverAutoplayMigration.flagKey(1))
        assertEquals("autoplay_migrated_2", LayoverAutoplayMigration.flagKey(2))
    }
}
