package com.nuvio.app.features.layover.streams

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** Enough about a stream to find the same file again after its debrid link expires. */
@Serializable
data class StreamIdentity(
    val addonId: String,
    val type: String,
    val videoId: String,
    val filename: String?,
    val videoSize: Long?,
    val infoHash: String?,
    val fileIdx: Int?,
    val bingeGroup: String?,
)

fun StreamItem.identity(type: String, videoId: String): StreamIdentity =
    StreamIdentity(
        addonId = addonId,
        type = type,
        videoId = videoId,
        filename = behaviorHints.filename,
        videoSize = behaviorHints.videoSize,
        infoHash = infoHash,
        fileIdx = fileIdx,
        bingeGroup = behaviorHints.bingeGroup,
    )

internal data class AddonTarget(val addonId: String, val addonName: String, val manifestUrl: String)

private const val ADDON_TIMEOUT_MS = 15_000L

object LayoverStreamSource {
    /**
     * Asks every enabled stream addon for [videoId] and concatenates the results in addon order.
     * Pass [forceRefresh] when re-finding a file so addons hand back fresh debrid links.
     */
    suspend fun fetch(type: String, videoId: String, forceRefresh: Boolean = false): List<StreamItem> =
        fetchResult(type, videoId, forceRefresh).streams

    /** Like [fetch], but also says whether any addon actually answered (see [StreamFetchResult]). */
    suspend fun fetchResult(type: String, videoId: String, forceRefresh: Boolean = false): StreamFetchResult {
        val targets = AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(type, videoId)) return@mapNotNull null
            // Same addonId/addonName/url derivation as PlayerStreamsRepository.fetchStreams, so ids match stored downloads.
            AddonTarget(
                addonId = addon.streamAddonInstanceId(manifest.id),
                addonName = addon.displayTitle.ifBlank { manifest.name },
                manifestUrl = manifest.transportUrl,
            )
        }
        return fetchResultWith(targets, type, videoId) { url -> fetchAddonResponseText(url, forceRefresh) }
    }

    internal suspend fun fetchWith(
        targets: List<AddonTarget>,
        type: String,
        videoId: String,
        http: suspend (url: String) -> String,
    ): List<StreamItem> = fetchResultWith(targets, type, videoId, http).streams

    internal suspend fun fetchResultWith(
        targets: List<AddonTarget>,
        type: String,
        videoId: String,
        http: suspend (url: String) -> String,
    ): StreamFetchResult = coroutineScope {
        val answers = targets
            .map { target -> async { fetchOne(target, type, videoId, http) } }
            .awaitAll()
        StreamFetchResult(
            streams = answers.filterNotNull().flatten(),
            anyAddonAnswered = targets.isEmpty() || answers.any { it != null },
        )
    }

    /** The addon's streams, or null when it failed, timed out or sent something unreadable. */
    private suspend fun fetchOne(
        target: AddonTarget,
        type: String,
        videoId: String,
        http: suspend (url: String) -> String,
    ): List<StreamItem>? {
        val url = buildAddonResourceUrl(
            manifestUrl = target.manifestUrl,
            resource = "stream",
            type = type,
            id = videoId,
        )
        return try {
            withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                StreamParser.parse(http(url), target.addonName, target.addonId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Streams from every addon asked, and whether at least one addon actually answered. False means every request
 * failed or timed out (offline, addons down): "no streams" then says nothing about the title. True when there was
 * no addon to ask at all, since asking again won't change that.
 */
data class StreamFetchResult(val streams: List<StreamItem>, val anyAddonAnswered: Boolean)

/** Every enabled addon has finished loading its manifest (none still refreshing) and at least one has one. */
internal fun List<ManagedAddon>.layoverAddonsSettled(): Boolean {
    val enabled = enabledAddons()
    return enabled.none { it.isRefreshing } && enabled.any { it.manifest != null }
}

/** Finds the stream that is the same file as [identity]: same addon, then filename, infoHash+fileIdx, size+bingeGroup. */
fun refind(identity: StreamIdentity, streams: List<StreamItem>): StreamItem? {
    val candidates = streams.filter { it.addonId == identity.addonId }
    identity.filename?.let { name ->
        candidates.firstOrNull { it.behaviorHints.filename == name }?.let { return it }
    }
    if (identity.infoHash != null && identity.fileIdx != null) {
        candidates.firstOrNull { it.infoHash == identity.infoHash && it.fileIdx == identity.fileIdx }?.let { return it }
    }
    if (identity.videoSize != null && identity.bingeGroup != null) {
        candidates.firstOrNull {
            it.behaviorHints.videoSize == identity.videoSize && it.behaviorHints.bingeGroup == identity.bingeGroup
        }?.let { return it }
    }
    return null
}
