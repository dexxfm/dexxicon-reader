package net.dexxicon.reader.core.model

import kotlin.math.round

/**
 * A series position the way readers write it (issue #257): `3`, `3.5`, `0` — never `3.0`.
 * Both servers store the index as a number that's usually whole but can be fractional
 * (novellas slotted between volumes). Null for a missing, negative, or non-finite index.
 *
 * Hand-rolled rather than `String.format` — Kotlin/Native has no `String.format`.
 */
fun seriesNumberLabel(index: Double?): String? {
    if (index == null || !index.isFinite() || index < 0.0) return null
    val rounded = round(index * 100) / 100
    val whole = rounded.toLong()
    return if (rounded == whole.toDouble()) whole.toString() else rounded.toString()
}

/** "Mistborn #3" for list rows and accessibility labels (issue #257) — or just "#3" when the
 * series has no name. Null when there's no usable [index]. */
fun seriesPositionText(series: String?, index: Double?): String? {
    val number = seriesNumberLabel(index) ?: return null
    val name = series?.trim()?.takeIf { it.isNotEmpty() }
    return if (name != null) "$name #$number" else "#$number"
}
