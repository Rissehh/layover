package com.nuvio.app.features.layover.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class LayoverSettingsChoicesTest {
    @Test
    fun `movie cap labels map to binary byte values`() {
        assertEquals(2_147_483_648L, MOVIE_CAP_CHOICES.valueForLabel("2 GB"))
        assertEquals(4_294_967_296L, MOVIE_CAP_CHOICES.valueForLabel("4 GB"))
        assertEquals(8_589_934_592L, MOVIE_CAP_CHOICES.valueForLabel("8 GB"))
    }

    @Test
    fun `episode cap labels map to binary byte values`() {
        assertEquals(1_073_741_824L, EPISODE_CAP_CHOICES.valueForLabel("1 GB"))
        assertEquals(2_147_483_648L, EPISODE_CAP_CHOICES.valueForLabel("2 GB"))
        assertEquals(4_294_967_296L, EPISODE_CAP_CHOICES.valueForLabel("4 GB"))
    }

    @Test
    fun `quality labels map to max heights`() {
        assertEquals(720L, QUALITY_CHOICES.valueForLabel("720p"))
        assertEquals(1080L, QUALITY_CHOICES.valueForLabel("1080p"))
        assertEquals(2160L, QUALITY_CHOICES.valueForLabel("4K"))
    }

    @Test
    fun `timeout labels map to milliseconds`() {
        assertEquals(5_000L, TIMEOUT_CHOICES.valueForLabel("5 s"))
        assertEquals(8_000L, TIMEOUT_CHOICES.valueForLabel("8 s"))
        assertEquals(12_000L, TIMEOUT_CHOICES.valueForLabel("12 s"))
    }

    @Test
    fun `exact current value shows its own label`() {
        assertEquals("4 GB", MOVIE_CAP_CHOICES.nearest(4_294_967_296L).label)
    }

    @Test
    fun `value outside the option list shows the closest label`() {
        assertEquals("4 GB", MOVIE_CAP_CHOICES.nearest(5_000_000_000L).label)
        assertEquals("8 GB", MOVIE_CAP_CHOICES.nearest(50_000_000_000L).label)
        assertEquals("2 GB", MOVIE_CAP_CHOICES.nearest(1L).label)
        assertEquals("1080p", QUALITY_CHOICES.nearest(1440L).label)
        assertEquals("12 s", TIMEOUT_CHOICES.nearest(30_000L).label)
    }

    @Test
    fun `tie between two options picks the lower one`() {
        assertEquals("720p", QUALITY_CHOICES.nearest(900L).label)
    }
}
