package com.nuvio.app.features.layover.streams

import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadPickerTest {
    private val settings = LayoverSettings()

    private fun stream(
        id: String,
        description: String? = null,
        url: String? = "https://addon.example/$id.mkv",
        name: String? = id,
    ) = StreamItem(
        name = name,
        description = description,
        url = url,
        addonName = "Addon",
        addonId = "addon:test",
    )

    private val remux2160 = stream("remux2160", "Movie.2160p.BluRay.REMUX.HEVC-FGT\n💾 40 GB")
    private val p1080 = stream("p1080", "Show.1080p.WEB-DL\n💾 1.8 GB")
    private val p1080Unknown = stream("p1080unknown", "Show.1080p.WEB-DL")
    private val p720 = stream("p720", "Show.720p.WEB-DL\n💾 900 MB")
    private val big1080 = stream("big1080", "Show.1080p.WEB-DL\n💾 6 GB")
    private val m3u8 = stream("m3u8", "Show.1080p\n💾 1 GB", url = "https://addon.example/master.m3u8")
    private val magnet = stream("magnet", "Show.1080p\n💾 1 GB", url = "magnet:?xt=urn:btih:abc")
    private val noInfo = stream("AIOStreams", "Some.Title", name = "AIOStreams")

    private val all = listOf(remux2160, p1080, p1080Unknown, p720, big1080, m3u8, magnet, noInfo)

    @Test
    fun `episode order and exclusions`() {
        val result = pickDownloadCandidates(all, isEpisode = true, settings = settings)
        assertEquals(listOf(p1080, p1080Unknown, p720, noInfo), result)
    }

    @Test
    fun `score never moves a stream across tiers`() {
        val result = pickDownloadCandidates(all, isEpisode = true, settings = settings) { if (it === p720) 1.0 else 0.0 }
        assertEquals(listOf(p1080, p1080Unknown, p720, noInfo), result)
    }

    @Test
    fun `score reorders inside a tier and ties keep addon order`() {
        val a = stream("a", "Show.1080p\n💾 1 GB")
        val b = stream("b", "Show.1080p\n💾 1 GB")
        val c = stream("c", "Show.1080p\n💾 1 GB")
        val scores = mapOf(a to 0.0, b to 2.0, c to 0.0)
        val result = pickDownloadCandidates(listOf(a, b, c), isEpisode = true, settings = settings) { scores.getValue(it) }
        assertEquals(listOf(b, a, c), result)
    }

    @Test
    fun `movie cap is larger than episode cap`() {
        val threeGb = stream("threeGb", "Movie.1080p\n💾 3 GB")
        assertEquals(listOf(threeGb), pickDownloadCandidates(listOf(threeGb), isEpisode = false, settings = settings))
        val fiveGb = stream("fiveGb", "Movie.1080p\n💾 5 GB")
        assertEquals(listOf(fiveGb), pickDownloadCandidates(listOf(fiveGb), isEpisode = false, settings = settings))
        assertEquals(emptyList(), pickDownloadCandidates(listOf(fiveGb), isEpisode = true, settings = settings))
        val tooBig = stream("huge", "Movie.1080p\n💾 7 GB")
        assertEquals(emptyList(), pickDownloadCandidates(listOf(tooBig), isEpisode = false, settings = settings))
    }

    @Test
    fun `preferred max height is respected`() {
        val result = pickDownloadCandidates(
            listOf(p1080, p720),
            isEpisode = true,
            settings = settings.copy(preferredMaxHeight = 720),
        )
        // 1080p is above the preferred height: only a last resort, after every pick at or under it.
        assertEquals(listOf(p720, p1080), result)
    }

    @Test
    fun `samples are never picked`() {
        val sample = StreamItem(
            name = "s",
            description = "Show.1080p\n💾 107 MB",
            url = "https://addon.example/s.mkv",
            behaviorHints = StreamBehaviorHints(filename = "show.s01e02.1080p.web.h265-noma.sample.mkv"),
            addonName = "Addon",
            addonId = "addon:test",
        )
        val inTitle = stream("t", "Show.S01E02.1080p.Sample\n💾 90 MB")
        assertEquals(listOf(p1080), pickDownloadCandidates(listOf(sample, inTitle, p1080), isEpisode = true, settings = settings))
    }

    @Test
    fun `a little over the cap is picked when nothing fits, smallest first, before 4K`() {
        val p720Big = stream("p720Big", "Show.720p.BluRay\n💾 2.9 GB")
        val p720Smaller = stream("p720Smaller", "Show.720p.BluRay\n💾 2.3 GB")
        val p1080Over = stream("p1080Over", "Show.1080p.WEB\n💾 2.5 GB")
        val uhdSmall = stream("uhdSmall", "Show.2160p.x265\n💾 1.5 GB")
        val uhdAv1 = stream("uhdAv1", "Show.2160p.AV1\n💾 1.2 GB")
        val uhdBig = stream("uhdBig", "Show.2160p.x265\n💾 2.8 GB")
        val tooBig = stream("tooBig", "Show.720p.BluRay\n💾 3.5 GB")
        val result = pickDownloadCandidates(
            listOf(uhdBig, uhdAv1, uhdSmall, tooBig, p720Big, p1080Over, p720Smaller),
            isEpisode = true,
            settings = settings,
        ) { if (it === p720Big) 5.0 else 0.0 }
        assertEquals(listOf(p720Smaller, p1080Over, p720Big, uhdSmall), result)
    }

    @Test
    fun `anything within the cap beats an over-cap pick`() {
        val over = stream("over", "Show.1080p\n💾 2.5 GB")
        val unknown480 = stream("unknown480", "Show.480p")
        assertEquals(listOf(unknown480, over), pickDownloadCandidates(listOf(over, unknown480), isEpisode = true, settings = settings))
    }

    @Test
    fun `underscore separated remux is excluded`() {
        val remux = stream("Movie_1080p_x265_REMUX", "Movie_1080p_x265_REMUX\n💾 30 GB")
        assertEquals(listOf(p1080), pickDownloadCandidates(listOf(remux, p1080), isEpisode = false, settings = settings))
        val unsized = stream("Movie_1080p_REMUX", "Movie_1080p_REMUX")
        assertEquals(emptyList(), pickDownloadCandidates(listOf(unsized), isEpisode = false, settings = settings))
    }

    @Test
    fun `debrid stream with infoHash and https url is a candidate`() {
        val debrid = StreamItem(
            name = "d",
            description = "Show.1080p\n💾 1 GB",
            url = "https://rd.example/dl/abc.mkv",
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            addonName = "A",
            addonId = "addon:t",
        )
        assertEquals(listOf(debrid), pickDownloadCandidates(listOf(debrid), isEpisode = true, settings = settings))
    }

    @Test
    fun `empty input returns empty`() {
        assertEquals(emptyList(), pickDownloadCandidates(emptyList(), isEpisode = true, settings = settings))
    }
}
