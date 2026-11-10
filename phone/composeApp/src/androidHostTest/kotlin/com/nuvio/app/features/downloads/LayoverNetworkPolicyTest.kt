package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.nuvio.app.features.layover.LayoverStorage
import com.nuvio.app.features.layover.downloads.downloadNetworkType
import com.nuvio.app.features.layover.downloads.downloadWorkNetworkType
import com.nuvio.app.features.layover.isOnUnmeteredNetwork
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoverNetworkPolicyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val wifiOnly = downloadItem().copy(allowMetered = false)
    private val mobileOk = downloadItem().copy(allowMetered = true)

    @Test
    fun `wifi only downloads require an unmetered network, mobile data ones accept any`() {
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, downloadNetworkType(wifiOnly))
        assertEquals(JobInfo.NETWORK_TYPE_ANY, downloadNetworkType(mobileOk))
        assertEquals(NetworkType.UNMETERED, downloadWorkNetworkType(wifiOnly))
        assertEquals(NetworkType.CONNECTED, downloadWorkNetworkType(mobileOk))
    }

    @Test
    fun `a download item defaults to wifi only`() {
        assertFalse(downloadItem().allowMetered)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, downloadNetworkType(downloadItem()))
    }

    @Test
    fun `the user initiated job carries the network type, so a queued download waits instead of failing`() {
        val scheduler = AndroidDownloadScheduler(context)
        val store = AndroidDownloadStore(File(context.cacheDir, "policy-job"))

        val waiting = scheduler.buildJob(store.begin(wifiOnly.copy(fileName = "wait.mkv")))
        val free = scheduler.buildJob(store.begin(mobileOk.copy(fileName = "free.mkv")))

        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, waiting.networkType)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, free.networkType)
        assertTrue(waiting.isUserInitiated)
    }

    @Test
    fun `enqueueing a wifi only download schedules an unmetered job`() {
        val scheduler = AndroidDownloadScheduler(context)
        val transfer = scheduler.enqueue(wifiOnly.copy(fileName = "queued.mkv"))

        // Robolectric does not track namespaced jobs, so check the JobInfo enqueue() hands to the system.
        val pending = scheduler.buildJob(transfer)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, pending.networkType)
        // Queued is not failed: nothing marks it failed just because mobile data is all there is.
        assertEquals(DownloadStatus.Downloading, scheduler.store.get("queued.mkv")?.item?.status)
    }

    @Test
    fun `rescheduling after pause with the override replaces the unmetered job`() {
        val scheduler = AndroidDownloadScheduler(context)
        val first = scheduler.enqueue(wifiOnly.copy(fileName = "override.mkv"))
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, scheduler.buildJob(first).networkType)

        // What DownloadsRepository.useMobileData does: stop the running transfer, then enqueue the updated item.
        scheduler.pause("override.mkv")
        val second = scheduler.enqueue(first.item.copy(allowMetered = true))

        assertNotEquals(first.generation, second.generation)
        assertEquals(first.jobId, second.jobId)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, scheduler.buildJob(second).networkType)
        assertTrue(scheduler.store.get("override.mkv")!!.item.allowMetered)
    }

    @Test
    fun `useMobileData on a waiting download reschedules it with any network and keeps it downloading`() {
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsRepository.clearLocalState()
        val stream = StreamItem(
            name = "Movie",
            description = "Movie.1080p.WEB-DL",
            url = "https://addon.example/movie.mkv",
            addonName = "Addon",
            addonId = "addon:test",
        )
        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt1", parentMetaId = "tt1", parentMetaType = "movie",
            title = "Movie", logo = null, poster = null, background = null,
            seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
            stream = stream, allowMetered = false,
        )
        val queued = DownloadsRepository.uiState.value.items.single()
        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        val before = assertNotNull(scheduler.store.get(queued.fileName))
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, scheduler.buildJob(before).networkType)

        DownloadsRepository.useMobileData(queued.id)

        val item = DownloadsRepository.uiState.value.items.single()
        val after = assertNotNull(scheduler.store.get(queued.fileName))
        assertTrue(item.allowMetered)
        assertEquals(DownloadStatus.Downloading, item.status)
        assertNotEquals(before.generation, after.generation)
        assertTrue(after.item.allowMetered)
        assertEquals(DownloadStatus.Downloading, after.item.status)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, scheduler.buildJob(after).networkType)

        DownloadsRepository.clearLocalState()
    }

    @Test
    @Config(sdk = [33])
    fun `the WorkManager request carries the network type below Android 14`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = AndroidDownloadScheduler(context)

        val waiting = scheduler.enqueue(wifiOnly.copy(fileName = "work-wait.mkv"))
        val free = scheduler.enqueue(mobileOk.copy(fileName = "work-free.mkv"))

        fun networkOf(transfer: AndroidDownloadTransfer): NetworkType {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("nuvio-download-${transfer.jobId}").get()
            return infos.single().constraints.requiredNetworkType
        }
        assertEquals(NetworkType.UNMETERED, networkOf(waiting))
        assertEquals(NetworkType.CONNECTED, networkOf(free))
    }

    @Test
    fun `unmetered detection follows the active network`() {
        LayoverStorage.initialize(context)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)

        shadow.setNetworkCapabilities(manager.activeNetwork, capabilities(notMetered = false))
        assertFalse(isOnUnmeteredNetwork())

        shadow.setNetworkCapabilities(manager.activeNetwork, capabilities(notMetered = true))
        assertTrue(isOnUnmeteredNetwork())

        shadow.setActiveNetworkInfo(null)
        assertFalse(isOnUnmeteredNetwork())
    }

    private fun capabilities(notMetered: Boolean): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(caps)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (notMetered) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return caps
    }
}
