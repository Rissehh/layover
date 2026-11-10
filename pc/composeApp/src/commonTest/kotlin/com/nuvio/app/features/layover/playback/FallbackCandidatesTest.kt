package com.nuvio.app.features.layover.playback

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackCandidatesTest {
    private fun stream(id: String, url: String? = "https://example.com/$id.mkv", addonId: String = "addon:$id") =
        StreamItem(name = id, url = url, addonName = "Addon", addonId = addonId)

    private fun ids(streams: List<StreamItem>) = streams.map { it.name }

    @Test
    fun `only streams the player can open directly are candidates`() {
        assertTrue(stream("http", url = "http://example.com/a.mkv").isFallbackPlayable())
        assertTrue(stream("https").isFallbackPlayable())
        assertFalse(stream("magnet", url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567").isFallbackPlayable())
        assertFalse(stream("torrent", url = "torrent://0123456789abcdef0123456789abcdef01234567").isFallbackPlayable())
        assertFalse(stream("file", url = "file:///storage/emulated/0/movie.mkv").isFallbackPlayable())
        assertFalse(stream("content", url = "content://downloads/movie.mkv").isFallbackPlayable())
        assertFalse(StreamItem(name = "external", externalUrl = "https://example.com/watch", addonName = "A", addonId = "addon:x").isFallbackPlayable())
        assertFalse(StreamItem(name = "youtube", ytId = "dQw4w9WgXcQ", addonName = "A", addonId = "addon:x").isFallbackPlayable())
        assertFalse(StreamItem(name = "hash only", infoHash = "0123456789abcdef0123456789abcdef01234567", addonName = "A", addonId = "addon:x").isFallbackPlayable())
        assertFalse(stream("none", url = null).isFallbackPlayable())
    }

    @Test
    fun `the started stream comes first, then the rest by score, ties in upstream order`() {
        val pool = listOf(stream("a"), stream("b"), stream("c"), stream("d"), stream("e"))
        val scores = mapOf("a" to 0.0, "b" to -0.3, "c" to 0.4, "d" to 0.0, "e" to 0.4)
        val ranked = fallbackCandidates(pool, started = pool[0]) { scores.getValue(it.name!!) }
        assertEquals(listOf("a", "c", "e", "d", "b"), ids(ranked))
    }

    @Test
    fun `samples are never fallback candidates`() {
        val pool = listOf(stream("a"), stream("show.s02e01.2160p.web.h265-noma.sample.mkv"), stream("c"))
        assertEquals(listOf("a", "c"), ids(fallbackCandidates(pool, started = pool[0]) { 0.0 }))
    }

    @Test
    fun `a tapped stream deep in the list still starts the chain`() {
        val pool = listOf(stream("a"), stream("b"), stream("c"))
        val ranked = fallbackCandidates(pool, started = pool[2]) { 0.0 }
        assertEquals(listOf("c", "a", "b"), ids(ranked))
    }

    @Test
    fun `non playable entries are dropped`() {
        val pool = listOf(
            stream("magnet", url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            stream("a"),
            StreamItem(name = "youtube", ytId = "dQw4w9WgXcQ", addonName = "A", addonId = "addon:x"),
            stream("b"),
        )
        val ranked = fallbackCandidates(pool, started = pool[1]) { 0.0 }
        assertEquals(listOf("a", "b"), ids(ranked))
    }

    @Test
    fun `duplicate urls are tried once`() {
        val pool = listOf(stream("a"), stream("a2", url = "https://example.com/a.mkv"), stream("b"))
        val ranked = fallbackCandidates(pool, started = pool[0]) { 0.0 }
        assertEquals(listOf("a", "b"), ids(ranked))
    }

    @Test
    fun `a started stream missing from the pool is still first`() {
        val pool = listOf(stream("a"), stream("b"))
        val ranked = fallbackCandidates(pool, started = stream("x")) { 0.0 }
        assertEquals(listOf("x", "a", "b"), ids(ranked))
    }

    @Test
    fun `an empty pool leaves just the started stream`() {
        val ranked = fallbackCandidates(emptyList(), started = stream("x")) { 0.0 }
        assertEquals(listOf("x"), ids(ranked))
    }
}
