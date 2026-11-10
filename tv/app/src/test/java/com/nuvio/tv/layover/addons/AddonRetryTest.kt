package com.nuvio.tv.layover.addons

import com.nuvio.tv.domain.model.Addon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonRetryTest {

    private fun addon(version: String, enabled: Boolean = true) = Addon(
        id = "a", name = "A", version = version, description = null, logo = null, baseUrl = "https://a.example",
        catalogs = emptyList(), types = emptyList(), resources = emptyList(), enabled = enabled,
    )

    @Test fun aPlaceholderThatNeverLoadedIsRetried() {
        assertTrue(addon(version = "").isUnloaded())
    }

    @Test fun aLoadedAddonIsLeftAlone() {
        assertFalse(addon(version = "3.0.14").isUnloaded())
    }

    @Test fun aDisabledAddonIsLeftAlone() {
        assertFalse(addon(version = "", enabled = false).isUnloaded())
    }

    @Test fun retriesFollowThePhonesSchedule() {
        assertEquals(listOf(3_000L, 10_000L, 30_000L), RETRY_DELAYS_MS)
    }
}
