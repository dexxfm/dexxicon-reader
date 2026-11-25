package net.dexxicon.reader.shared.reader.epub

import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import kotlin.math.max
import kotlin.math.abs

data class EpubMarginGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val spreadPageMargin: Double,
    val spread: Boolean,
)

enum class EpubMarginGesture { TAP, FORWARD, BACKWARD, NONE }

fun classifyMarginGesture(
    deltaX: Float,
    deltaY: Float,
    maxDistance: Float,
    touchSlop: Float,
    viewportWidth: Float,
): EpubMarginGesture = when {
    maxDistance <= touchSlop -> EpubMarginGesture.TAP
    abs(deltaX) >= viewportWidth * 0.18f && abs(deltaX) >= abs(deltaY) * 1.5f ->
        if (deltaX < 0f) EpubMarginGesture.FORWARD else EpubMarginGesture.BACKWARD
    else -> EpubMarginGesture.NONE
}

fun epubMarginGeometry(
    preferences: ReaderDisplayPreferences,
    width: Float,
    height: Float,
    reflowable: Boolean,
    verticalText: Boolean = false,
): EpubMarginGeometry {
    val spread = !verticalText && !preferences.scroll && when (preferences.pageLayout) {
        ReaderPageLayout.SINGLE -> false
        ReaderPageLayout.DOUBLE -> true
        ReaderPageLayout.AUTO -> width > height
    }
    if (!reflowable) return EpubMarginGeometry(0f, 0f, 0f, 0f, 0.0, spread)
    val left = preferences.epubMarginLeft.coerceIn(0, ReaderDisplayPreferences.MAX_EPUB_MARGIN)
    val right = preferences.epubMarginRight.coerceIn(0, ReaderDisplayPreferences.MAX_EPUB_MARGIN)
    return EpubMarginGeometry(
        left = if (spread) 0f else width * left / 100f,
        top = height * preferences.epubMarginTop.coerceIn(0, ReaderDisplayPreferences.MAX_EPUB_MARGIN) / 100f,
        right = if (spread) 0f else width * right / 100f,
        bottom = height * preferences.epubMarginBottom.coerceIn(0, ReaderDisplayPreferences.MAX_EPUB_MARGIN) / 100f,
        spreadPageMargin = if (spread) max(12.0, width * (left + right) / 400.0) else 0.0,
        spread = spread,
    )
}
