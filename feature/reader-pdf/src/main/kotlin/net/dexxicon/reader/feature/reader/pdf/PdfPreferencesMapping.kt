package net.dexxicon.reader.feature.reader.pdf

import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
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
