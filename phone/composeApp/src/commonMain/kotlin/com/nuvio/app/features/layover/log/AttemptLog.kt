package com.nuvio.app.features.layover.log

import com.nuvio.app.features.layover.LayoverStorage
import com.nuvio.app.features.layover.streams.facts
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

@Serializable
enum class AttemptKind { Play, Download }

@Serializable
enum class AttemptOutcome { Played, Completed, Failed, Stalled, SwitchedEarly, DownloadCompleted, DownloadFailed }

@Serializable
data class AttemptRecord(
    val atEpochMs: Long,
    val kind: AttemptKind,
    val contentKey: String,
    val addonId: String,
    val addonName: String,
    val height: Int?,
    val codec: String?,
    val sizeBytes: Long?,
    val releaseGroup: String?,
    val timeToFirstFrameMs: Long?,
    val outcome: AttemptOutcome,
)

/** Same format as `DownloadItem.logicalContentKey`: "<metaId>|movie" or "<metaId>|<season>|<episode>". */
fun layoverContentKey(metaId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "${metaId.trim()}|$season|$episode" else "${metaId.trim()}|movie"

fun attemptRecord(
    stream: StreamItem,
    kind: AttemptKind,
    contentKey: String,
    outcome: AttemptOutcome,
    ttffMs: Long?,
    now: Long,
): AttemptRecord {
    val facts = stream.facts()
    return AttemptRecord(
        atEpochMs = now,
        kind = kind,
        contentKey = contentKey,
        addonId = stream.addonId,
        addonName = stream.addonName,
        height = facts.height,
        codec = facts.codec,
        sizeBytes = facts.sizeBytes,
        releaseGroup = facts.releaseGroup,
        timeToFirstFrameMs = ttffMs,
        outcome = outcome,
    )
}

object AttemptLog {
    internal const val MAX_RECORDS = 2000
    private const val storageKey = "attempt_log"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal var loader: () -> String? = { LayoverStorage.load(storageKey) }
    internal var saver: (String) -> Unit = { LayoverStorage.save(storageKey, it) }

    private val _records: MutableStateFlow<List<AttemptRecord>> by lazy {
        MutableStateFlow(decode(loader()))
    }

    /** Newest last. */
    val records: StateFlow<List<AttemptRecord>>
        get() = _records.asStateFlow()

    /** Reload from the (profile-scoped) storage key after the active profile changes. */
    fun onProfileChanged() {
        _records.value = decode(loader())
    }

    /** Safe to call from any thread (download callbacks). */
    fun add(record: AttemptRecord) {
        _records.update { (it + record).takeLast(MAX_RECORDS) }
        saver(encode(_records.value))
    }

    /**
     * Swaps the latest record equal to [old] for [new] (or appends [new] if [old] is gone). "Gone" also
     * covers a Played the playback glue never wrote because the network was unvalidated at the time: the
     * Completed that replaces it is then appended, so a finished play still counts once.
     */
    fun replace(old: AttemptRecord, new: AttemptRecord) {
        _records.update { records ->
            val index = records.lastIndexOf(old)
            if (index < 0) (records + new).takeLast(MAX_RECORDS)
            else records.toMutableList().also { it[index] = new }
        }
        saver(encode(_records.value))
    }

    fun clear() {
        _records.value = emptyList()
        saver(encode(emptyList()))
    }

    /** Records are decoded one by one so a single bad one (e.g. an unknown outcome) is skipped, not fatal. */
    internal fun decode(raw: String?): List<AttemptRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array
            .mapNotNull { element ->
                runCatching { json.decodeFromJsonElement(AttemptRecord.serializer(), element) }.getOrNull()
            }
            .takeLast(MAX_RECORDS)
    }

    internal fun encode(records: List<AttemptRecord>): String =
        json.encodeToString(ListSerializer(AttemptRecord.serializer()), records)
}
