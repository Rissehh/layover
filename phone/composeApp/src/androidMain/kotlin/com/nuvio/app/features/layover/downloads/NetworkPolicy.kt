package com.nuvio.app.features.layover.downloads

import android.app.job.JobInfo
import androidx.work.NetworkType
import com.nuvio.app.features.downloads.DownloadItem

/**
 * Wi-Fi only downloads: a download that doesn't allow mobile data gets an UNMETERED network constraint,
 * so the system holds it until Wi-Fi is available (it waits, it does not fail).
 */
internal fun downloadNetworkType(item: DownloadItem): Int =
    if (item.allowMetered) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED

internal fun downloadWorkNetworkType(item: DownloadItem): NetworkType =
    if (item.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
