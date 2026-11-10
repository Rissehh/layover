package com.nuvio.app.features.layover.streams

import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.playback.fallbackCandidates
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageTest {
    private fun stream(id: String, description: String?, filename: String? = null) = StreamItem(
        name = id,
        description = description,
        url = "https://addon.example/$id.mkv",
        addonName = "Addon",
        addonId = "addon:test",
        behaviorHints = StreamBehaviorHints(filename = filename),
    )

    // isForeignOnly ------------------------------------------------------------------------------

    @Test
    fun `untagged releases count as English`() {
        assertFalse(stream("a", "Love.Is.Blind.S09E02.1080p.WEB-DL.x264-GRP").isForeignOnly())
        assertFalse(stream("b", null).isForeignOnly())
    }

    @Test
    fun `dub tags in the name, filename or description are foreign-only`() {
        assertTrue(stream("fr", "Show.S01E01.FRENCH.1080p.WEB").isForeignOnly())
        assertTrue(stream("vff", "Show S01E01 VFF 1080p").isForeignOnly())
        assertTrue(stream("it", null, filename = "Show.S01E01.iTA.1080p.WEB-DL.mkv").isForeignOnly())
        assertTrue(stream("de", "Show.S01E01.German.DL.1080p").isForeignOnly())
        assertTrue(stream("es", "Show.S01E01.1080p.Castellano").isForeignOnly())
        assertTrue(stream("sub", "Show.S01E01.VOSTFR.1080p").isForeignOnly())
    }

    @Test
    fun `flag emojis count, English flags keep it`() {
        assertTrue(stream("frflag", "Show 1080p\n🇫🇷").isForeignOnly())
        assertFalse(stream("both", "Show 1080p\n🇬🇧 / 🇫🇷").isForeignOnly())
    }

    @Test
    fun `MULTI, dual audio or an English tag next to a dub keeps it`() {
        assertFalse(stream("multi", "Show.S01E01.MULTi.1080p.WEB").isForeignOnly())
        assertFalse(stream("dual", "Show.S01E01.iTA.ENG.1080p").isForeignOnly())
        assertFalse(stream("dualaudio", "Show S01E01 1080p Dual Audio Hindi").isForeignOnly())
    }

    @Test
    fun `language words inside other words do not count`() {
        assertFalse(stream("a", "Frenchmans.Creek.1080p.WEB").isForeignOnly())
        assertFalse(stream("c", "Vitamin.Story.1080p.WEB-DL").isForeignOnly())
    }

    @Test
    fun `a language word in the title itself moves every result alike, so nothing changes`() {
        val a = stream("a", "The.Italian.Job.2003.1080p.BluRay")
        val b = stream("b", "The.Italian.Job.2003.720p.WEB")
        assertEquals(listOf(a, b), listOf(a, b).foreignOnlyLast())
    }

    // Orderings ----------------------------------------------------------------------------------

    private val french1080 = stream("french1080", "Show.S01E01.FRENCH.1080p.WEB\n💾 1.5 GB")
    private val english720 = stream("english720", "Show.S01E01.720p.WEB\n💾 800 MB")
    private val english1080 = stream("english1080", "Show.S01E01.1080p.WEB\n💾 1.6 GB")

    @Test
    fun `foreignOnlyLast keeps order otherwise and never drops anything`() {
        assertEquals(listOf(english720, english1080, french1080), listOf(french1080, english720, english1080).foreignOnlyLast())
        assertEquals(listOf(french1080), listOf(french1080).foreignOnlyLast())
    }

    @Test
    fun `a download takes English 720p over a French 1080p, and the dub only when nothing else fits`() {
        val picked = pickDownloadCandidates(listOf(french1080, english720), isEpisode = true, settings = LayoverSettings())
        assertEquals(listOf(english720, french1080), picked)
        assertEquals(listOf(french1080), pickDownloadCandidates(listOf(french1080), isEpisode = true, settings = LayoverSettings()))
    }

    @Test
    fun `fallback tries English sources before dubs, whatever the track record`() {
        val order = fallbackCandidates(
            pool = listOf(english1080, french1080, english720),
            started = english1080,
            score = { if (it === french1080) 1.0 else 0.0 },
        )
        assertEquals(listOf(english1080, english720, french1080), order)
    }

    @Test
    fun `autoplay first stream skips a dub listed first`() {
        val picked = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(french1080, english1080),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )
        assertEquals(english1080, picked)
    }
}
