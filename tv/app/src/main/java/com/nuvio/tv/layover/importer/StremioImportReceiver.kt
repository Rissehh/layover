package com.nuvio.tv.layover.importer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * One-time move from Stremio: reads the Stremio account's watch history and writes it into LayoverTV, so
 * Continue watching and the home screen's picks start from what was already watched.
 *
 * Only adb can send it (the receiver requires android.permission.DUMP, which apps can't hold):
 *   adb shell am broadcast -n com.layover.tv/com.nuvio.tv.layover.importer.StremioImportReceiver --es authKey KEY
 * The key is used for this one read and never stored. Result in logcat, tag LayoverImport.
 */
class StremioImportReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun watchProgress(): WatchProgressRepository
    }

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("authKey")?.trim().orEmpty()
        if (key.isEmpty()) {
            Log.w(TAG, "no authKey extra")
            return
        }
        val pending = goAsync()
        val repo = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).watchProgress()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entries = fetchLibrary(key).mapNotNull(::importEntry)
                val progress = entries.mapNotNull { it.progress }
                val completed = entries.mapNotNull { it.completed }
                if (progress.isNotEmpty()) repo.saveProgressBatch(progress, syncRemote = false)
                completed.forEach { repo.markAsCompleted(it, broadcastTrackingHistory = false) }
                // markAsCompleted stamps "now"; put Stremio's own date back, so the home screen's picks and
                // Continue watching order by when it was really watched.
                if (completed.isNotEmpty()) repo.saveProgressBatch(completed.map(::finishedAsOf), syncRemote = false)
                Log.i(TAG, "imported ${progress.size} in progress, ${completed.size} watched")
            } catch (e: Exception) {
                Log.e(TAG, "import failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun fetchLibrary(authKey: String): List<StremioLibraryItem> {
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem")
            .put("ids", JSONArray()).put("all", true).toString()
        val c = URL("https://api.strem.io/api/datastoreGet").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
            val json = JSONObject(stream.bufferedReader().readText())
            json.optJSONObject("error")?.let { throw IllegalStateException("Stremio: " + it.optString("message")) }
            val all = json.getJSONArray("result")
            return (0 until all.length()).mapNotNull { i ->
                val o = all.optJSONObject(i) ?: return@mapNotNull null
                val state = o.optJSONObject("state") ?: return@mapNotNull null
                StremioLibraryItem(
                    id = o.optString("_id"),
                    type = o.optString("type"),
                    name = o.optString("name"),
                    poster = o.optString("poster").takeIf { it.isNotBlank() && it != "null" },
                    videoId = state.optString("video_id").takeIf { it.isNotBlank() && it != "null" },
                    timeOffset = state.optLong("timeOffset"),
                    duration = state.optLong("duration"),
                    timesWatched = state.optInt("timesWatched"),
                    lastWatched = runCatching { Instant.parse(state.optString("lastWatched")).toEpochMilli() }
                        .getOrDefault(0L),
                )
            }
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val TAG = "LayoverImport"
    }
}
