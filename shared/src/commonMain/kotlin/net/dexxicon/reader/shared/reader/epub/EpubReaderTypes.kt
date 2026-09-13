package net.dexxicon.reader.shared.reader.epub

/**
 * One flattened table-of-contents row for the shared TOC sheet (Phase 2 of #183) — the
 * portable stand-in for Readium's `Link` (which isn't a KMP type; it only exists in the
 * Android and iOS Readium Toolkits separately). [ref] is an opaque token the native embed
 * point defined it from (this project's Android embed uses the row's index into its own
 * retained flattened `Link` list) and hands back unchanged to [ref]-taking callbacks so the
 * embed can resolve it back to a real navigable target — the shared chrome never inspects it.
 */
data class TocEntry(val depth: Int, val title: String, val ref: String)

/** The EPUB screen's top-level state — mirrors Android's original `EpubReaderState` (now the
 * native embed's own concern) but drops its `Publication`/`Locator` fields, which aren't KMP
 * types; [EpubReaderUiState.Ready] carries only what the shared chrome itself renders. */
sealed interface EpubReaderUiState {
    data object Loading : EpubReaderUiState
    data class Error(val message: String) : EpubReaderUiState
    data class Ready(
        val title: String,
        val toc: List<TocEntry>,
        /** A newer position from KOReader sync / another device, if any (0.0–1.0). */
        val remoteResumePercent: Double? = null,
        val hasRemoteResume: Boolean = remoteResumePercent != null,
    ) : EpubReaderUiState
}
