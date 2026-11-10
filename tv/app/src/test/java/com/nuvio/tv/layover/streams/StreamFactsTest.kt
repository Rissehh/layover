package com.nuvio.tv.layover.streams

import com.nuvio.tv.layover.testStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class StreamFactsTest {
    private fun stream(
        name: String? = null,
        title: String? = null,
        description: String? = null,
        url: String? = "https://addon.example/stream.mkv",
        filename: String? = null,
        videoSize: Long? = null,
    ) = testStream(
        name = name,
        title = title,
        description = description,
        url = url,
        filename = filename,
        videoSize = videoSize,
    )

    @Test
    fun `comet stream parses height size codec and group from hints`() {
        val file = "Show.S01E02.1080p.WEB-DL.DDP5.1.x265-NTb.mkv"
        val facts = stream(
            name = "[RD⚡] Comet 1080p",
            description = "$file\n💾 1.8 GB 👤 52",
            filename = file, videoSize = 1932735283,
        ).facts()
        assertEquals(1080, facts.height)
        assertEquals(1932735283L, facts.sizeBytes)
        assertEquals("hevc", facts.codec)
        assertEquals("NTb", facts.releaseGroup)
        assertFalse(facts.isRemux)
    }

    @Test
    fun `mediafusion stream parses 4k remux and size from description`() {
        val facts = stream(
            name = "MediaFusion | RD ⚡️ 4K",
            description = "📂 Movie.2023.2160p.UHD.BluRay.REMUX.HDR.HEVC-FGT\n💾 58.3 GB",
        ).facts()
        assertEquals(2160, facts.height)
        assertTrue(facts.isRemux)
        assertEquals(62599148339L, facts.sizeBytes)
        assertEquals("hevc", facts.codec)
        assertNull(facts.releaseGroup)
    }

    @Test
    fun `aiostreams stream with no quality or size yields all nulls`() {
        val facts = stream(name = "AIOStreams", description = "Some.Title").facts()
        assertNull(facts.height)
        assertNull(facts.sizeBytes)
        assertNull(facts.codec)
        assertNull(facts.releaseGroup)
        assertFalse(facts.isRemux)
    }

    @Test
    fun `comma decimal size is parsed`() {
        assertEquals(1610612736L, stream(description = "💾 1,5 GB").facts().sizeBytes)
    }

    @Test
    fun `size units are all binary`() {
        assertEquals(900L * 1024 * 1024, stream(description = "900 MB").facts().sizeBytes)
        assertEquals(900L * 1024 * 1024, stream(description = "900 MiB").facts().sizeBytes)
        assertEquals(2L * 1024 * 1024 * 1024, stream(description = "2 GiB").facts().sizeBytes)
    }

    @Test
    fun `videoSize of zero falls back to text`() {
        val facts = stream(description = "💾 2 GB", videoSize = 0).facts()
        assertEquals(2L * 1024 * 1024 * 1024, facts.sizeBytes)
    }

    @Test
    fun `height aliases and precedence`() {
        assertEquals(2160, stream(title = "Movie 4K").facts().height)
        assertEquals(2160, stream(title = "Movie UHD").facts().height)
        assertEquals(1440, stream(title = "Movie 1440p").facts().height)
        assertEquals(720, stream(title = "Movie 720p").facts().height)
        assertEquals(480, stream(title = "Movie 480p").facts().height)
        // An explicit resolution wins over a UHD/4K source label; 2160p still reads as 2160.
        assertEquals(1080, stream(title = "Movie.2023.1080p.UHD.BluRay.x265").facts().height)
        assertEquals(720, stream(title = "Movie 4K Remaster 720p").facts().height)
        assertEquals(2160, stream(title = "Movie.2023.2160p.UHD.BluRay.x265").facts().height)
        // filename beats title/description/name
        val facts = stream(
            name = "2160p",
            title = "1080p",
            filename = "Movie.720p.WEB.mkv",
        ).facts()
        assertEquals(720, facts.height)
    }

    @Test
    fun `codec rules`() {
        assertEquals("hevc", stream(title = "Movie H.265").facts().codec)
        assertEquals("hevc", stream(title = "Movie HEVC").facts().codec)
        assertEquals("avc", stream(title = "Movie x264").facts().codec)
        assertEquals("avc", stream(title = "Movie H264").facts().codec)
        assertEquals("av1", stream(title = "Movie AV1").facts().codec)
    }

    @Test
    fun `remux is case insensitive and word bounded`() {
        assertTrue(stream(title = "Movie.ReMux.1080p").facts().isRemux)
        assertFalse(stream(title = "Movie.NotRemuxed").facts().isRemux)
    }

    @Test
    fun `release group comes from filename only and must be alphanumeric`() {
        assertEquals("FGT", stream(filename = "Movie.2023.1080p.WEB-FGT.mkv").facts().releaseGroup)
        assertNull(stream(title = "Movie.1080p.WEB-FGT").facts().releaseGroup)
        assertNull(stream(filename = "Movie.1080p.mkv").facts().releaseGroup)
        assertNull(stream(filename = "Movie-A.mkv").facts().releaseGroup)
        assertNull(stream(filename = "Movie-Some Group.mkv").facts().releaseGroup)
    }

    @Test
    fun `underscore separated release name parses height codec and remux`() {
        val facts = stream(
            filename = "Movie_2160p_x265_REMUX.mkv",
        ).facts()
        assertEquals(2160, facts.height)
        assertEquals("hevc", facts.codec)
        assertTrue(facts.isRemux)
        assertEquals(1080, stream(title = "Movie_1080p_WEB").facts().height)
    }
}
