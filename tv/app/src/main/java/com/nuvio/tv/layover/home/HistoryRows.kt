package com.nuvio.tv.layover.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/** One title in the watch history: the newest time it was watched and how far in it got (0..1, 1 = finished). */
data class HistoryRow(
    val contentId: String,
    val contentType: String,
    val name: String,
    val lastWatched: Long,
    val engagement: Double,
)

/**
 * Collapses episode-level progress and watched marks into one row per title, newest first.
 * Engagement is the best progress seen on any of its episodes; a watched mark counts as finished.
 * The date is the newest progress date; a watched mark's date only counts for titles without any
 * progress (marking watched in bulk, or an import, stamps the time of marking, not of watching).
 */
fun historyRows(progress: List<WatchProgress>, watched: List<WatchedItem>): List<HistoryRow> {
    val byId = LinkedHashMap<String, HistoryRow>()
    fun add(row: HistoryRow, useDate: Boolean) {
        if (!row.contentId.startsWith("tt")) return
        val old = byId[row.contentId]
        byId[row.contentId] = if (old == null) row else old.copy(
            name = old.name.ifBlank { row.name },
            lastWatched = if (useDate) maxOf(old.lastWatched, row.lastWatched) else old.lastWatched,
            engagement = maxOf(old.engagement, row.engagement),
        )
    }
    progress.forEach {
        add(HistoryRow(it.contentId, it.contentType, it.name, it.lastWatched, it.progressPercentage.toDouble()), useDate = true)
    }
    watched.forEach { add(HistoryRow(it.contentId, it.contentType, it.title, it.watchedAt, 1.0), useDate = false) }
    return byId.values.sortedByDescending { it.lastWatched }
}
