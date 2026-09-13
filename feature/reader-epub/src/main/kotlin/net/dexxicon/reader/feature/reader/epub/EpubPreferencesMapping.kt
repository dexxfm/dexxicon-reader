package net.dexxicon.reader.feature.reader.epub

import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import net.dexxicon.reader.core.datastore.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme

// A soft neutral grey — sits between the white and black page options without going fully dark.
// Not private: EpubReaderScreen's Background chip swatches reuse this exact value so the
// preview dot always matches what "Grey" actually renders.
val GREY_BACKGROUND = 0xFFC8CBCF.toInt()
private val GREY_TEXT = 0xFF17181A.toInt()

// Approximates Readium's built-in Theme.SEPIA/Theme.DARK page colours for the same reason —
// Readium doesn't expose these as named constants, so the swatch just has to look right next
// to the actual rendered page.
val SEPIA_BACKGROUND = 0xFFF5ECDC.toInt()
val DARK_BACKGROUND = 0xFF000000.toInt()

fun ReaderDisplayPreferences.toEpubPreferences(systemInDark: Boolean, wideViewport: Boolean = false): EpubPreferences {
    val resolvedTheme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.SEPIA -> Theme.SEPIA
        ReaderTheme.GREY -> Theme.LIGHT
        ReaderTheme.DARK -> Theme.DARK
        ReaderTheme.SYSTEM -> if (systemInDark) Theme.DARK else Theme.LIGHT
    }
    return EpubPreferences(
        fontSize = fontScale,
        theme = resolvedTheme,
        // Grey isn't one of Readium's built-in themes; paint it on with explicit colours.
        backgroundColor = if (theme == ReaderTheme.GREY) Color(GREY_BACKGROUND) else null,
        textColor = if (theme == ReaderTheme.GREY) Color(GREY_TEXT) else null,
        scroll = scroll,
        // issue #117: AUTO defers to Readium's own column logic, which doesn't split into two
        // columns on a wide viewport by itself — force TWO past the breakpoint instead. A
        // manual SINGLE/DOUBLE choice always wins regardless of viewport width.
        columnCount = when (pageLayout) {
            ReaderPageLayout.AUTO -> if (wideViewport) ColumnCount.TWO else ColumnCount.AUTO
            ReaderPageLayout.SINGLE -> ColumnCount.ONE
            ReaderPageLayout.DOUBLE -> ColumnCount.TWO
        },
        // Reflowable text has no literal "fit"; approximate it with the margin width.
        pageMargins = when (fitMode) {
            ReaderFitMode.PAGE_WIDTH -> 0.5
            ReaderFitMode.PAGE_FIT -> 1.0
            ReaderFitMode.PAGE_HEIGHT -> 1.0
            ReaderFitMode.ACTUAL_SIZE -> 1.6
        },
    )
}
