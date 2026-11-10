package com.nuvio.tv.layover.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import com.nuvio.tv.layover.LayoverSettings
import com.nuvio.tv.layover.LayoverSettingsRepository
import com.nuvio.tv.layover.testStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRankingTest {
    private fun stream(id: String, name: String = id, url: String? = "https://example.com/$id.mkv", addonName: String = "Addon") =
        testStream(name = name, url = url, addonName = addonName)

    private fun names(streams: List<Stream>) = streams.map { it.name }

    @After
    fun tearDown() {
        LayoverSettingsRepository.loader = { null }
        LayoverSettingsRepository.reload()
    }

    @Test
    fun `only plain http links the player can open by itself are playable`() {
        assertTrue(stream("http", url = "http://example.com/a.mkv").isFallbackPlayable())
        assertTrue(stream("https").isFallbackPlayable())
        assertFalse(stream("magnet", url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567").isFallbackPlayable())
        assertFalse(stream("torrent", url = "torrent://0123456789abcdef0123456789abcdef01234567").isFallbackPlayable())
        assertFalse(stream("torrent file", url = "https://example.com/a.torrent").isFallbackPlayable())
        assertFalse(stream("file", url = "file:///storage/emulated/0/movie.mkv").isFallbackPlayable())
        assertFalse(stream("none", url = null).isFallbackPlayable())
        // WatchHub / Crunchyroll "Subscription" entries are external links.
        assertFalse(testStream(name = "Subscription", externalUrl = "https://www.crunchyroll.com/watch/x").isFallbackPlayable())
        assertFalse(testStream(name = "Trailer", ytId = "dQw4w9WgXcQ").isFallbackPlayable())
        assertFalse(testStream(name = "hash only", infoHash = "0123456789abcdef0123456789abcdef01234567").isFallbackPlayable())
        // Debrid addons send an infoHash next to a direct link: the link is what plays.
        assertTrue(testStream(name = "rd", url = "https://rd.example/dl/a.mkv", infoHash = "0123456789abcdef0123456789abcdef01234567").isFallbackPlayable())
    }

    @Test
    fun `debrid cache markers from Torrentio, Comet, MediaFusion and AIOStreams`() {
        assertEquals(CacheState.Cached, stream("a", name = "[RD+] Torrentio\n4k").cacheState())
        assertEquals(CacheState.Uncached, stream("b", name = "[RD download] Torrentio\n4k").cacheState())
        assertEquals(CacheState.Cached, stream("c", name = "[RD⚡] Comet 2160p").cacheState())
        assertEquals(CacheState.Uncached, stream("d", name = "[RD⬇️] Comet 2160p").cacheState())
        assertEquals(CacheState.Cached, stream("e", name = "MediaFusion | ElfHosted RD ⚡️ 4K").cacheState())
        assertEquals(CacheState.Uncached, stream("f", name = "MediaFusion | RD ⏳ 4K").cacheState())
        assertEquals(CacheState.Cached, stream("g", name = "AIOStreams RD+ 1080p").cacheState())
        assertEquals(CacheState.Unknown, stream("h", name = "Some addon 1080p").cacheState())
        // DD+ (Dolby Digital Plus) and HDR10+ are not debrid markers.
        assertEquals(CacheState.Unknown, stream("i", name = "Movie 2160p DD+ 5.1 HDR10+").cacheState())
        assertEquals(CacheState.Unknown, stream("j", name = "Movie.2160p.DDP5.1").cacheState())
    }

    @Test
    fun `the cache status NuvioTV checked itself wins over labels`() {
        val checked = stream("a", name = "Plain name").copy(
            debridCacheStatus = StreamDebridCacheStatus("rd", "Real-Debrid", StreamDebridCacheState.CACHED),
        )
        assertEquals(CacheState.Cached, checked.cacheState())
        val notCached = stream("b", name = "[RD+] says cached").copy(
            debridCacheStatus = StreamDebridCacheStatus("rd", "Real-Debrid", StreamDebridCacheState.NOT_CACHED),
        )
        assertEquals(CacheState.Uncached, notCached.cacheState())
    }

    @Test
    fun `cached first, unlabelled next, uncached after, AV1 last, upstream order inside a tier`() {
        val pool = listOf(
            stream("uncached", name = "[RD download] 2160p"),
            stream("plain1", name = "Plain 1080p"),
            stream("av1", name = "[RD+] 2160p AV1"),
            stream("cached1", name = "[RD+] 2160p HEVC"),
            stream("plain2", name = "Plain 720p"),
            stream("cached2", name = "[RD+] 1080p"),
        )
        assertEquals(
            listOf("[RD+] 2160p HEVC", "[RD+] 1080p", "Plain 1080p", "Plain 720p", "[RD download] 2160p", "[RD+] 2160p AV1"),
            names(rankCandidates(pool) { 0.0 }),
        )
    }

    @Test
    fun `track record reorders inside a tier but not across tiers`() {
        val pool = listOf(
            stream("a", name = "[RD+] a", addonName = "Bad"),
            stream("b", name = "[RD+] b", addonName = "Good"),
            stream("c", name = "plain c", addonName = "Great"),
        )
        val scores = mapOf("Bad" to -0.4, "Good" to 0.3, "Great" to 0.7)
        assertEquals(listOf("[RD+] b", "[RD+] a", "plain c"), names(rankCandidates(pool) { scores.getValue(it.addonName) }))
    }

    @Test
    fun `no size cap and 4K is welcome`() {
        val pool = listOf(
            testStream(name = "[RD+] Remux 2160p", url = "https://example.com/big.mkv", videoSize = 80L * 1024 * 1024 * 1024),
            testStream(name = "[RD+] 720p", url = "https://example.com/small.mkv", videoSize = 900L * 1024 * 1024),
        )
        assertEquals(listOf("[RD+] Remux 2160p", "[RD+] 720p"), names(rankCandidates(pool) { 0.0 }))
    }

    @Test
    fun `samples, non playable entries and duplicate links are dropped`() {
        val pool = listOf(
            stream("a"),
            testStream(name = "show.s02e01.2160p.web.h265-noma.sample.mkv", url = "https://example.com/s.mkv"),
            testStream(name = "Sub", externalUrl = "https://www.crunchyroll.com/watch/x"),
            stream("magnet", url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            stream("a2", url = "https://example.com/a.mkv"),
            stream("b"),
        )
        assertEquals(listOf("a", "b"), names(rankCandidates(pool) { 0.0 }))
    }

    @Test
    fun `fallback candidates start with the stream that is playing`() {
        val pool = listOf(stream("a"), stream("b", name = "[RD+] b"), stream("c"))
        assertEquals(listOf("c", "[RD+] b", "a"), names(fallbackCandidates(pool, started = pool[2]) { 0.0 }))
        val outsider = stream("x")
        assertEquals(listOf("x", "[RD+] b", "a", "c"), names(fallbackCandidates(pool, started = outsider) { 0.0 }))
        assertEquals(listOf("x"), names(fallbackCandidates(emptyList(), started = outsider) { 0.0 }))
    }

    @Test
    fun `autoplay picks the best ranked http stream, or leaves it to upstream`() {
        LayoverSettingsRepository.loader = { null }
        LayoverSettingsRepository.reload()
        val pool = listOf(
            testStream(name = "Torrent", infoHash = "0123456789abcdef0123456789abcdef01234567"),
            stream("plain", name = "Plain 2160p"),
            stream("cached", name = "[RD+] 1080p"),
        )
        assertEquals("[RD+] 1080p", LayoverAutoplay.pick(pool)?.name)
        assertNull(LayoverAutoplay.pick(listOf(pool[0])))

        LayoverSettingsRepository.loader = { LayoverSettingsRepository.encode(LayoverSettings(fallbackEnabled = false)) }
        LayoverSettingsRepository.reload()
        assertNull(LayoverAutoplay.pick(pool))
    }

    @Test
    fun `isSample reads filename, title, description and name`() {
        assertTrue(testStream(filename = "Show.S01E01.sample.mkv").isSample())
        assertTrue(testStream(title = "Sample").isSample())
        assertFalse(testStream(name = "Samples of Life 1080p").isSample())
        assertFalse(testStream(name = "Show 1080p").isSample())
    }
}
