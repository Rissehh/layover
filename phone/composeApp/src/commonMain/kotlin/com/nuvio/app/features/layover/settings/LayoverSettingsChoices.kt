package com.nuvio.app.features.layover.settings

import kotlin.math.abs

/** One option in a Layover settings chip row. All values widen to Long so one mapping serves every row. */
internal data class LayoverChoice(val label: String, val value: Long)

private const val GIB = 1_073_741_824L

internal val MOVIE_CAP_CHOICES = listOf(
    LayoverChoice("2 GB", 2 * GIB),
    LayoverChoice("4 GB", 4 * GIB),
    LayoverChoice("8 GB", 8 * GIB),
)

internal val EPISODE_CAP_CHOICES = listOf(
    LayoverChoice("1 GB", 1 * GIB),
    LayoverChoice("2 GB", 2 * GIB),
    LayoverChoice("4 GB", 4 * GIB),
)

internal val QUALITY_CHOICES = listOf(
    LayoverChoice("720p", 720),
    LayoverChoice("1080p", 1080),
    LayoverChoice("4K", 2160),
)

internal val TIMEOUT_CHOICES = listOf(
    LayoverChoice("5 s", 5_000),
    LayoverChoice("8 s", 8_000),
    LayoverChoice("12 s", 12_000),
)

/** The option closest to [current]; on a tie the lower option wins. Used so an off-list stored value still shows a label. */
internal fun List<LayoverChoice>.nearest(current: Long): LayoverChoice =
    minWith(compareBy<LayoverChoice> { abs(it.value - current) }.thenBy { it.value })

internal fun List<LayoverChoice>.valueForLabel(label: String): Long =
    first { it.label == label }.value
