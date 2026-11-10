package com.nuvio.app.features.layover.downloads

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.layover.LayoverSettings
import com.nuvio.app.features.layover.streams.StreamIdentity
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DownloadRecoveryTest {
    private val settings = LayoverSettings()

    private val identity = StreamIdentity(
        addonId = "addon:comet",
        type = "movie",
        videoId = "tt1",
        filename = "Movie.2024.1080p.WEB-DL.x264-GRP.mkv",
        videoSize = 2_000_000_000L,
        infoHash = "abc",
        fileIdx = 0,
        bingeGroup = null,
    )

    private fun item(recoveryAttempts: Int = 0) = DownloadItem(
        id = "d1",
        contentType = "movie",
        parentMetaId = "tt1",
        parentMetaType = "movie",
        videoId = "tt1",
        title = "Movie",
        streamTitle = "Comet 1080p",
        providerName = "Comet",
        providerAddonId = "addon:comet",
        sourceUrl = "https://comet.example/resolve/abc/0",
        fileName = "Movie_d1.mkv",
        status = DownloadStatus.Failed,
        errorMessage = "Download failed: HTTP 410",
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        streamIdentity = identity,
        recoveryAttempts = recoveryAttempts,
    )

    private fun stream(
        filename: String?,
        url: String?,
        description: String = "Movie.2024.1080p.WEB-DL\n💾 1.9 GB",
        addonId: String = "addon:comet",
        headers: Map<String, String>? = null,
    ) = StreamItem(
        name = "Comet 1080p",
        description = description,
        url = url,
        addonName = "Comet",
        addonId = addonId,
        behaviorHints = StreamBehaviorHints(
            filename = filename,
            proxyHeaders = headers?.let { StreamProxyHeaders(request = it) },
        ),
    )

    private val sameFile = stream(
        filename = identity.filename,
        url = "https://comet.example/resolve/abc/0?fresh=1",
        headers = mapOf("User-Agent" to "Layover"),
    )
    private val other1080 = stream("Movie.2024.1080p.BluRay.x264-OTHER.mkv", "https://comet.example/resolve/def/0")
    private val other720 = stream(
        "Movie.2024.720p.WEB-DL.x264-GRP.mkv",
        "https://comet.example/resolve/ghi/0",
        description = "Movie.2024.720p.WEB-DL\n💾 900 MB",
    )

    @Test
    fun `same file found with a new url resumes it with that url`() = runTest {
        val plan = planRecovery(item(), listOf(other1080, sameFile, other720), settings)

        assertEquals(
            RecoveryPlan.SameFile("https://comet.example/resolve/abc/0?fresh=1", mapOf("User-Agent" to "Layover")),
            plan,
        )
    }

    @Test
    fun `same file is still valid when the addon hands back the same resolve url`() = runTest {
        val again = stream(identity.filename, item().sourceUrl)

        val plan = planRecovery(item(), listOf(again), settings)

        assertEquals(RecoveryPlan.SameFile(item().sourceUrl, emptyMap()), plan)
    }

    @Test
    fun `file gone but other eligible streams picks the top candidate`() = runTest {
        val plan = planRecovery(item(), listOf(other720, other1080), settings)

        assertEquals(RecoveryPlan.OtherFile(other1080), plan)
    }

    @Test
    fun `other file is never the old file, even from another addon or when it can no longer download`() = runTest {
        // Same file, but the addon now only offers it as a stream that can't be downloaded.
        val sameButHls = stream(identity.filename, "https://comet.example/hls/master.m3u8")
        // Same release name from a different addon resolves to the same dead debrid file.
        val sameNameElsewhere = stream(identity.filename, "https://mf.example/resolve/abc", addonId = "addon:mediafusion")
        // Same url as the failed download.
        val sameUrl = stream("Renamed.mkv", item().sourceUrl, addonId = "addon:aio")

        val plan = planRecovery(item(), listOf(sameButHls, sameNameElsewhere, sameUrl, other720), settings)

        val other = assertIs<RecoveryPlan.OtherFile>(plan)
        assertEquals(other720, other.stream)
        assertNotEquals(identity.filename, other.stream.behaviorHints.filename)
    }

    @Test
    fun `no streams gives up`() = runTest {
        assertEquals(RecoveryPlan.GiveUp, planRecovery(item(), emptyList(), settings))
    }

    @Test
    fun `only ineligible streams gives up`() = runTest {
        val remux = stream("Movie.2024.2160p.REMUX.mkv", "https://comet.example/resolve/r/0", "Movie.2024.2160p.BluRay.REMUX\n💾 60 GB")

        assertEquals(RecoveryPlan.GiveUp, planRecovery(item(), listOf(remux), settings))
    }

    @Test
    fun `two recoveries already used gives up even when the file is there`() = runTest {
        assertEquals(RecoveryPlan.GiveUp, planRecovery(item(recoveryAttempts = 2), listOf(sameFile, other1080), settings))
    }

    @Test
    fun `a download with no stored identity gives up`() = runTest {
        val legacy = item().copy(streamIdentity = null)

        assertEquals(RecoveryPlan.GiveUp, planRecovery(legacy, listOf(sameFile, other1080), settings))
    }

    @Test
    fun `manual retry runs the picker again and may land on the old file`() {
        assertEquals(
            RecoveryPlan.SameFile(sameFile.url!!, mapOf("User-Agent" to "Layover")),
            planManualRetry(item(recoveryAttempts = 2), listOf(sameFile, other720), settings),
        )
        assertEquals(
            RecoveryPlan.OtherFile(other1080),
            planManualRetry(item(recoveryAttempts = 2), listOf(other720, other1080), settings),
        )
        assertEquals(RecoveryPlan.GiveUp, planManualRetry(item(), emptyList(), settings))
    }

    // Final review fixes.

    @Test
    fun `recovery ranks streams inside a picker tier by their track record`() = runTest {
        val otherAddon1080 = stream("Movie.2024.1080p.WEB-DL.x264-NEW.mkv", "https://aio.example/resolve/x", addonId = "addon:aio")
        val score: (StreamItem) -> Double = { if (it.addonId == "addon:aio") 1.0 else 0.0 }

        assertEquals(RecoveryPlan.OtherFile(otherAddon1080), planRecovery(item(), listOf(other1080, otherAddon1080), settings, score))
        assertEquals(RecoveryPlan.OtherFile(otherAddon1080), planManualRetry(item(), listOf(other1080, otherAddon1080), settings, score))
        assertEquals(RecoveryPlan.OtherFile(other1080), planRecovery(item(), listOf(other1080, otherAddon1080), settings) { 0.0 })
    }

    @Test
    fun `retry after a failed Ready check skips the old file even at the attempt cap`() {
        val plan = planOtherFile(item(recoveryAttempts = 2), listOf(sameFile, other720), settings) { 0.0 }

        assertEquals(RecoveryPlan.OtherFile(other720), plan)
        assertEquals(RecoveryPlan.GiveUp, planOtherFile(item(), listOf(sameFile), settings) { 0.0 })
    }

    @Test
    fun `a reconnect retries only downloads waiting on unreachable addons that are not already recovering`() {
        val waiting = item().copy(id = "a", errorMessage = ADDONS_UNREACHABLE_MESSAGE)
        val recovering = item().copy(id = "b", errorMessage = ADDONS_UNREACHABLE_MESSAGE)
        val gone = item().copy(id = "c", errorMessage = SOURCE_GONE_MESSAGE)
        val legacy = item().copy(id = "d", errorMessage = ADDONS_UNREACHABLE_MESSAGE, streamIdentity = null)
        val running = item().copy(id = "e", status = DownloadStatus.Downloading, errorMessage = ADDONS_UNREACHABLE_MESSAGE)

        assertEquals(listOf(waiting), unreachableToRetry(listOf(waiting, recovering, gone, legacy, running), inFlight = setOf("b")))
    }
}
