package net.dexxicon.reader.shared.reader.comic

/** Comics have no highlights, bookmarks, or TOC — just pages, so this is narrower than
 *  [net.dexxicon.reader.shared.reader.epub.EpubReaderUiState]/
 *  [net.dexxicon.reader.shared.reader.pdf.PdfReaderUiState]. */
sealed interface ComicReaderUiState {
    data object Loading : ComicReaderUiState
    data class Error(val message: String) : ComicReaderUiState
    data class Ready(val title: String, val pageCount: Int) : ComicReaderUiState
}
