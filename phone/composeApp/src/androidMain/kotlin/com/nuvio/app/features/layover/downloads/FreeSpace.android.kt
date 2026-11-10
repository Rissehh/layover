package com.nuvio.app.features.layover.downloads

import android.content.Context
import java.io.File

/** Application context for the free-space check; set from LayoverStorage.initialize, the Layover init point. */
internal object LayoverFreeSpace {
    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** Same directory as AndroidDownloadScheduler.directory (filesDir/downloads); filesDir if it isn't made yet. */
    fun freeBytes(): Long? {
        val context = appContext ?: return null
        val downloads = File(context.filesDir, "downloads")
        val target = if (downloads.isDirectory) downloads else context.filesDir
        return runCatching { target.usableSpace }.getOrNull()
    }
}

actual fun downloadsFreeBytes(): Long? = LayoverFreeSpace.freeBytes()
