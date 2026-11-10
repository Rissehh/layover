package com.nuvio.tv.layover.home

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.nuvio.tv.MainActivity
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Read-only feed for the Layover home screen (projects/tvhome), guarded by the signature permission
 * com.layover.permission.READ_HOME: only apps signed with the Layover key can query it.
 *
 * - content://com.layover.tv.home/continue: what the Android TV "Continue Watching" channel shows
 *   (in progress and next episodes), newest first, each with the intent that resumes it.
 * - content://com.layover.tv.home/history: every title watched, newest first, for recommendations.
 */
class HomeShelfProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun channelSync(): AndroidTvChannelSyncService
        fun watchProgress(): WatchProgressRepository
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val deps = EntryPointAccessors.fromApplication(ctx.applicationContext, Deps::class.java)
        return when (uri.lastPathSegment) {
            "continue" -> continueCursor(ctx, deps)
            "history" -> historyCursor(deps)
            else -> null
        }
    }

    private fun continueCursor(ctx: Context, deps: Deps): Cursor {
        val items = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                // The channel list comes from the Continue Watching cache, which LayoverTV's own home fills;
                // before it has run (just installed, or history imported) fall back to the raw progress.
                deps.channelSync().currentChannelItems().ifEmpty { deps.watchProgress().continueWatching.first() }
            }
        }.orEmpty()
        val cursor = MatrixCursor(CONTINUE_COLUMNS)
        items.take(MAX_ROWS).forEach { p ->
            cursor.addRow(arrayOf<Any?>(
                p.contentId, p.contentType, p.videoId, p.name, p.episodeTitle, p.season, p.episode,
                p.poster, p.backdrop, p.logo, p.position, p.duration, p.lastWatched,
                p.progressPercentage.toDouble(), playIntent(ctx, p).toUri(Intent.URI_INTENT_SCHEME)
            ))
        }
        return cursor
    }

    private fun historyCursor(deps: Deps): Cursor {
        val rows = runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                val progress = deps.watchProgress().allProgress.first()
                val watched = deps.watchProgress().watchedItems.first()
                historyRows(progress, watched)
            }
        }.orEmpty()
        val cursor = MatrixCursor(HISTORY_COLUMNS)
        rows.take(MAX_ROWS).forEach { cursor.addRow(arrayOf<Any?>(it.contentId, it.contentType, it.name, it.lastWatched, it.engagement)) }
        return cursor
    }

    /** Same extras as the launcher channel's programs (ProgramBuilder): opens LayoverTV's stream screen for it. */
    private fun playIntent(ctx: Context, p: WatchProgress): Intent =
        Intent(ctx, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("contentId", p.contentId)
            putExtra("contentType", p.contentType)
            putExtra("videoId", p.videoId)
            putExtra("name", p.name)
            putExtra("poster", p.poster)
            putExtra("backdrop", p.backdrop)
            putExtra("logo", p.logo)
            p.season?.let { putExtra("season", it) }
            p.episode?.let { putExtra("episode", it) }
            p.episodeTitle?.let { putExtra("episodeTitle", it) }
            putExtra("launchMode", "stream")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TIMEOUT_MS = 4_000L
        private const val MAX_ROWS = 60
        val CONTINUE_COLUMNS = arrayOf(
            "content_id", "content_type", "video_id", "name", "episode_title", "season", "episode",
            "poster", "backdrop", "logo", "position", "duration", "last_watched", "progress", "intent"
        )
        val HISTORY_COLUMNS = arrayOf("content_id", "content_type", "name", "last_watched", "engagement")
    }
}
