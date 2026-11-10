package com.nuvio.app.features.layover.streams

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LayoverStreamSourceTest {
    private fun payload(vararg names: String): String =
        names.joinToString(prefix = """{"streams":[""", postfix = "]}", separator = ",") {
            """{"name":"$it","url":"https://cdn.example/$it.mkv"}"""
        }

    private fun target(id: String) = AddonTarget(
        addonId = "addon:$id",
        addonName = id,
        manifestUrl = "https://$id.example/manifest.json",
    )

    @Test
    fun `failing addon contributes nothing and order is kept`() = runTest {
        val targets = listOf(target("a"), target("b"), target("c"))
        val result = LayoverStreamSource.fetchWith(targets, "movie", "tt1") { url ->
            when {
                url.contains("a.example") -> payload("a1", "a2")
                url.contains("b.example") -> error("boom")
                else -> payload("c1")
            }
        }
        assertEquals(listOf("a1", "a2", "c1"), result.map { it.name })
        assertEquals("addon:a", result.first().addonId)
        assertEquals("a", result.first().addonName)
    }

    @Test
    fun `unparseable payload contributes nothing`() = runTest {
        val result = LayoverStreamSource.fetchWith(listOf(target("a"), target("b")), "movie", "tt1") { url ->
            if (url.contains("a.example")) "not json" else payload("b1")
        }
        assertEquals(listOf("b1"), result.map { it.name })
    }

    @Test
    fun `addon slower than 15 seconds is dropped using virtual time`() = runTest {
        val targets = listOf(target("slow"), target("fast"))
        val result = LayoverStreamSource.fetchWith(targets, "movie", "tt1") { url ->
            if (url.contains("slow.example")) {
                delay(20_000)
                payload("slow1")
            } else {
                payload("fast1")
            }
        }
        assertEquals(listOf("fast1"), result.map { it.name })
    }

    @Test
    fun `every addon failing or timing out means nobody answered`() = runTest {
        val result = LayoverStreamSource.fetchResultWith(listOf(target("a"), target("b")), "movie", "tt1") { url ->
            if (url.contains("a.example")) error("offline") else { delay(20_000); payload("b1") }
        }
        assertEquals(StreamFetchResult(emptyList(), anyAddonAnswered = false), result)
    }

    @Test
    fun `one addon answering with nothing is still an answer`() = runTest {
        val result = LayoverStreamSource.fetchResultWith(listOf(target("a"), target("b")), "movie", "tt1") { url ->
            if (url.contains("a.example")) error("offline") else payload()
        }
        assertEquals(StreamFetchResult(emptyList(), anyAddonAnswered = true), result)
    }

    @Test
    fun `no addon to ask counts as answered, asking again changes nothing`() = runTest {
        val result = LayoverStreamSource.fetchResultWith(emptyList(), "movie", "tt1") { error("never called") }
        assertEquals(StreamFetchResult(emptyList(), anyAddonAnswered = true), result)
    }

    @Test
    fun `builds the stremio stream url for each addon`() = runTest {
        val seen = mutableListOf<String>()
        LayoverStreamSource.fetchWith(listOf(target("a")), "series", "tt1:2:3") { url ->
            seen += url
            payload()
        }
        assertEquals(listOf("https://a.example/stream/series/tt1%3A2%3A3.json"), seen)
    }

    private fun stream(
        addonId: String = "addon:a",
        filename: String? = null,
        size: Long? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        bingeGroup: String? = null,
        name: String = "s",
    ) = StreamItem(
        name = name,
        url = "https://cdn.example/$name",
        infoHash = infoHash,
        fileIdx = fileIdx,
        addonName = "A",
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup, videoSize = size, filename = filename),
    )

    @Test
    fun `identity copies hints and stream fields`() {
        val s = stream(filename = "x.mkv", size = 123L, infoHash = "abc", fileIdx = 4, bingeGroup = "bg")
        val id = s.identity("movie", "tt1")
        assertEquals(
            StreamIdentity("addon:a", "movie", "tt1", "x.mkv", 123L, "abc", 4, "bg"),
            id,
        )
    }

    @Test
    fun `refind prefers filename over a size match on another stream`() {
        val sizeMatch = stream(name = "size", size = 100L, bingeGroup = "bg")
        val nameMatch = stream(name = "name", filename = "x.mkv", size = 999L)
        val id = stream(filename = "x.mkv", size = 100L, bingeGroup = "bg").identity("movie", "tt1")
        assertEquals("name", refind(id, listOf(sizeMatch, nameMatch))?.name)
    }

    @Test
    fun `refind falls back to infoHash and fileIdx then size and binge group`() {
        val hash = stream(name = "hash", infoHash = "abc", fileIdx = 2)
        val wrongIdx = stream(name = "wrongIdx", infoHash = "abc", fileIdx = 3)
        val sized = stream(name = "sized", size = 100L, bingeGroup = "bg")
        val byHash = stream(infoHash = "abc", fileIdx = 2).identity("movie", "tt1")
        assertEquals("hash", refind(byHash, listOf(wrongIdx, hash, sized))?.name)

        val bySize = stream(size = 100L, bingeGroup = "bg").identity("movie", "tt1")
        assertEquals("sized", refind(bySize, listOf(wrongIdx, sized))?.name)
        assertNotNull(refind(bySize, listOf(sized)))
    }

    @Test
    fun `refind never matches a different addon`() {
        val other = stream(addonId = "addon:b", filename = "x.mkv")
        val id = stream(filename = "x.mkv").identity("movie", "tt1")
        assertNull(refind(id, listOf(other)))
    }

    @Test
    fun `refind returns null when nothing matches`() {
        val id = stream(filename = "x.mkv", size = 100L, bingeGroup = "bg").identity("movie", "tt1")
        val candidates = listOf(
            stream(filename = "y.mkv"),
            stream(size = 100L, bingeGroup = "other"),
            stream(size = 200L, bingeGroup = "bg"),
        )
        assertNull(refind(id, candidates))
    }
}
