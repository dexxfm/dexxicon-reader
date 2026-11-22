package net.dexxicon.reader.feature.reader.epub

import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderFontFamily
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import net.dexxicon.reader.core.datastore.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.TextAlign

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
        ReaderTheme.SEPIA_DARK, ReaderTheme.MIDNIGHT, ReaderTheme.NORD -> Theme.LIGHT
        ReaderTheme.SYSTEM -> if (systemInDark) Theme.DARK else Theme.LIGHT
    }
    val resolvedFontFamily = when (fontFamily) {
        ReaderFontFamily.SERIF -> FontFamily.SERIF
        ReaderFontFamily.SANS_SERIF -> FontFamily.SANS_SERIF
        ReaderFontFamily.CURSIVE -> FontFamily.CURSIVE
        ReaderFontFamily.FANTASY -> FontFamily.FANTASY
        ReaderFontFamily.MONOSPACE -> FontFamily.MONOSPACE
        ReaderFontFamily.ACCESSIBLE_DFA -> FontFamily.ACCESSIBLE_DFA
        ReaderFontFamily.IA_WRITER_DUOSPACE -> FontFamily.IA_WRITER_DUOSPACE
        ReaderFontFamily.OPEN_DYSLEXIC -> FontFamily.OPEN_DYSLEXIC
        null -> null
    }
    val customColors = when (theme) {
        ReaderTheme.GREY -> Color(GREY_BACKGROUND) to Color(GREY_TEXT)
        ReaderTheme.SEPIA_DARK -> Color(0xFFC4B090.toInt()) to Color(0xFF2E2014.toInt())
        ReaderTheme.MIDNIGHT -> Color(0xFF0F172A.toInt()) to Color(0xFFEDF4FC.toInt())
        ReaderTheme.NORD -> Color(0xFF2E3440.toInt()) to Color(0xFFE3E9F5.toInt())
        else -> null
    }
    return EpubPreferences(
        fontFamily = resolvedFontFamily,
        fontSize = fontScale,
        lineHeight = lineSpacing,
        hyphens = hyphenation,
        paragraphIndent = paragraphIndent?.let { if (it) 1.0 else 0.0 },
        paragraphSpacing = paragraphSpacing,
        textAlign = justification?.let { if (it) TextAlign.JUSTIFY else TextAlign.START },
        // Readium applies these typography overrides only when publisher styles are disabled.
        publisherStyles = if (
            lineSpacing != null ||
            paragraphSpacing != null ||
            paragraphIndent != null ||
            justification != null ||
            hyphenation != null
        ) {
            false
        } else {
            null
        },
        theme = resolvedTheme,
        // These palettes aren't Readium built-ins; paint them over a neutral light base.
        backgroundColor = customColors?.first,
        textColor = customColors?.second,
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
