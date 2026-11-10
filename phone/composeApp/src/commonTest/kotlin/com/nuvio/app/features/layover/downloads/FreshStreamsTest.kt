package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.layover.streams.StreamFetchResult
import com.nuvio.app.features.layover.streams.layoverAddonsSettled
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared "ask the addons" step behind link recovery and the download queue. */
class FreshStreamsTest {
    private fun manifest(id: String) = AddonManifest(
        id = id,
        name = id,
        description = "",
        version = "1",
        resources = emptyList(),
        types = listOf("movie"),
        transportUrl = "https://$id.example/manifest.json",
    )

    private fun loaded(id: String) = ManagedAddon(manifestUrl = "https://$id.example/manifest.json", manifest = manifest(id))
    private fun loading(id: String) = ManagedAddon(manifestUrl = "https://$id.example/manifest.json", isRefreshing = true)

    private val stream = StreamItem(name = "s", url = "https://x/s.mkv", addonName = "a", addonId = "a")

    @Test
    fun `addons are settled only when none is still loading and one has a manifest`() {
        assertFalse(listOf(loaded("a"), loading("b")).layoverAddonsSettled(), "partial load")
        assertFalse(listOf(loading("a")).layoverAddonsSettled())
        assertFalse(listOf(ManagedAddon(manifestUrl = "https://a.example/manifest.json")).layoverAddonsSettled(), "none loaded")
        assertTrue(listOf(loaded("a"), ManagedAddon(manifestUrl = "https://b.example/m.json", errorMessage = "down")).layoverAddonsSettled())
        assertTrue(listOf(loaded("a"), loading("b").copy(enabled = false)).layoverAddonsSettled(), "disabled addons don't count")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `partial addon load waits, then gives up after the timeout`() = runTest {
        val addons = MutableStateFlow(listOf(loaded("a"), loading("b")))
        val waiting = async { awaitAddonsSettled(addons, timeoutMs = 30_000) }
        advanceTimeBy(29_000)
        addons.value = listOf(loaded("a"), loaded("b"))
        assertTrue(waiting.await())

        val stuck = MutableStateFlow(listOf(loaded("a"), loading("b")))
        assertFalse(awaitAddonsSettled(stuck, timeoutMs = 30_000))
    }

    @Test
    fun `addons not ready means no answer`() = runTest {
        assertNull(freshStreamsOrNull(addonsReady = { false }, fetch = { error("must not fetch") }))
    }

    @Test
    fun `every addon request failing means no answer, not an empty answer`() = runTest {
        assertNull(freshStreamsOrNull({ true }, { StreamFetchResult(emptyList(), anyAddonAnswered = false) }))
    }

    @Test
    fun `addons that answered with nothing is an empty answer`() = runTest {
        assertEquals(emptyList(), freshStreamsOrNull({ true }, { StreamFetchResult(emptyList(), anyAddonAnswered = true) }))
        assertEquals(listOf(stream), freshStreamsOrNull({ true }, { StreamFetchResult(listOf(stream), anyAddonAnswered = true) }))
    }
}
