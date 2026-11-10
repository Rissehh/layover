package com.nuvio.tv.layover.log

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.layover.LayoverStorage
import com.nuvio.tv.layover.streams.facts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** Ported from Layover (phone) `log/AttemptLog.kt`. The TV only writes [AttemptKind.Play]. */
@Serializable
enum class AttemptKind { Play, Download }

@Serializable
enum class AttemptOutcome { Played, Completed, Failed, Stalled, SwitchedEarly, DownloadCompleted, DownloadFailed }

/**
 * One attempt to play one stream. NuvioTV streams carry no addon id, so [addonId] holds the
 * addon's display name (the key the track record groups by), same as [addonName].
 */
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

/** "<metaId>|movie" or "<metaId>|<season>|<episode>", same format as the phone. */
fun layoverContentKey(metaId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "${metaId.trim()}|$season|$episode" else "${metaId.trim()}|movie"

fun attemptRecord(
    stream: Stream,
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
        addonId = stream.addonName,
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

    /** Reload from storage (after [LayoverStorage.initialize], or in tests). */
    fun reload() {
        _records.value = decode(loader())
    }

    fun add(record: AttemptRecord) {
        _records.update { (it + record).takeLast(MAX_RECORDS) }
        saver(encode(_records.value))
    }

    /** Swaps the latest record equal to [old] for [new] (or appends [new] if [old] is gone). */
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
