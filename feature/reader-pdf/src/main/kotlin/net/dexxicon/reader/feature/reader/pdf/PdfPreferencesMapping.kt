package net.dexxicon.reader.feature.reader.pdf

import androidx.compose.ui.graphics.Color
import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderFitMode
import net.dexxicon.reader.core.reader.ReaderTheme
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit

/**
 * Maps the shared reader preferences onto what the PDFium engine can actually honour:
 * page fit and the scroll axis. Page layout (two-page) and per-page recolouring aren't
 * supported by this engine, so they're left out of the PDF settings sheet.
 */
fun ReaderDisplayPreferences.toPdfiumPreferences(): PdfiumPreferences = PdfiumPreferences(
    fit = when (fitMode) {
        ReaderFitMode.PAGE_WIDTH -> Fit.WIDTH
        // PAGE_FIT / PAGE_HEIGHT / ACTUAL_SIZE — the engine only offers contain vs. width.
        else -> Fit.CONTAIN
    },
    // Paged = horizontal swipe between pages; scroll = continuous vertical.
    scrollAxis = if (scrollMode.scrolling) Axis.VERTICAL else Axis.HORIZONTAL,
)

/**
 * The colour behind the pages. PDFium renders page content as opaque bitmaps, so this only
 * shows in the page spacing, the margins and when zoomed out — not on the page itself.
 */
fun ReaderTheme.pdfSurfaceColor(systemInDark: Boolean): Color = when (this) {
    ReaderTheme.SYSTEM -> if (systemInDark) Color(0xFF101114) else Color(0xFFF6F6F6)
    ReaderTheme.LIGHT -> Color(0xFFF6F6F6)
    ReaderTheme.SEPIA -> Color(0xFFEFE6D3)
    ReaderTheme.GREY -> Color(0xFF3A3D42)
    ReaderTheme.DARK -> Color(0xFF101114)
}
