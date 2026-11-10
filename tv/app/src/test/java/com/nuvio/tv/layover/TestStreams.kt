package com.nuvio.tv.layover

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints

/** A NuvioTV [Stream] with only the fields Layover's tests care about. */
fun testStream(
    name: String? = null,
    title: String? = null,
    description: String? = null,
    url: String? = null,
    addonName: String = "Addon",
    filename: String? = null,
    videoSize: Long? = null,
    externalUrl: String? = null,
    ytId: String? = null,
    infoHash: String? = null,
): Stream = Stream(
    name = name,
    title = title,
    description = description,
    url = url,
    ytId = ytId,
    infoHash = infoHash,
    fileIdx = null,
    externalUrl = externalUrl,
    behaviorHints = StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        videoSize = videoSize,
        filename = filename,
    ),
    addonName = addonName,
    addonLogo = null,
)
