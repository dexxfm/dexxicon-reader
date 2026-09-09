package net.dexxicon.reader.feature.reader.epub

import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderFitMode
import net.dexxicon.reader.core.reader.ReaderPageLayout
import net.dexxicon.reader.core.reader.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme

// A soft neutral grey — sits between the white and black page options without going fully dark.
private val GREY_BACKGROUND = 0xFFC8CBCF.toInt()
private val GREY_TEXT = 0xFF17181A.toInt()

fun ReaderDisplayPreferences.toEpubPreferences(systemInDark: Boolean): EpubPreferences {
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
        columnCount = when (pageLayout) {
            ReaderPageLayout.AUTO -> ColumnCount.AUTO
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
