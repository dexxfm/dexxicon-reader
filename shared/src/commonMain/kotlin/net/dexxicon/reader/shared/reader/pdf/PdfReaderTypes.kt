package net.dexxicon.reader.shared.reader.pdf

import net.dexxicon.reader.shared.reader.epub.TocEntry

/**
 * The PDF screen's top-level state — same shape as
 * [net.dexxicon.reader.shared.reader.epub.EpubReaderUiState], with the fields Android's own
 * `PdfReaderState` doesn't have: no highlights (PDF has bookmarks only), no remote-resume
 * banner (the newer of the local/server position wins silently on open — see
 * `PdfProgressBridge`'s own doc comment), and [pageCount]/a visible [title] instead, since
 * unlike EPUB's chrome, PDF's top bar shows the book title.
 */
sealed interface PdfReaderUiState {
    data object Loading : PdfReaderUiState
    data class Error(val message: String) : PdfReaderUiState
    data class Ready(
        val title: String,
        val toc: List<TocEntry>,
        val pageCount: Int,
    ) : PdfReaderUiState
}
