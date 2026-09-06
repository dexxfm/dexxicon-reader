package net.dexxicon.reader.feature.reader.epub

import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme

fun ReaderDisplayPreferences.toEpubPreferences(systemInDark: Boolean): EpubPreferences {
    val resolvedTheme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.SEPIA -> Theme.SEPIA
        ReaderTheme.DARK -> Theme.DARK
        ReaderTheme.SYSTEM -> if (systemInDark) Theme.DARK else Theme.LIGHT
    }
    return EpubPreferences(
        fontSize = fontScale,
        theme = resolvedTheme,
        scroll = scroll,
    )
}
