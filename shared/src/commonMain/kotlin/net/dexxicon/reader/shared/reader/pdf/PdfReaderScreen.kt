package net.dexxicon.reader.shared.reader.pdf

import net.dexxicon.reader.shared.reader.ReaderThemeSetting
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import net.dexxicon.reader.shared.sideControls
import net.dexxicon.reader.shared.SideColumnGap
import net.dexxicon.reader.shared.SideColumnControls
import net.dexxicon.reader.shared.SideColumnButton
import androidx.compose.ui.graphics.luminance
import net.dexxicon.reader.core.designsystem.theme.LocalDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderScrollMode
import net.dexxicon.reader.core.datastore.ReaderSwipeSensitivity
import net.dexxicon.reader.core.datastore.ReaderTheme
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.shared.reader.epub.TocEntry

/**
 * Phase 3 of the shared-reader-chrome redesign (issue #183) — the PDF reader's chrome, ported
 * from `feature/reader-pdf`'s original `PdfReaderScreen.kt`/`ReaderContent`. Simpler than
 * EPUB's: no highlights (PDF bookmarks only) and no remote-resume banner (see
 * [PdfProgressBridge]'s own doc comment for why). Native centre taps toggle [chromeVisible].
 *
 * The page-turn drag gesture (Android's `pageTurnGesture`, built on `PDFView
 * .canScrollHorizontally`) stays entirely native — like EPUB's edge-tap navigation, it has no
 * portable equivalent and doesn't need one; [readerContent] is just the raw page surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    state: PdfReaderUiState,
    onBack: () -> Unit,
    preferences: ReaderDisplayPreferences = ReaderDisplayPreferences(),
    bookmarks: List<Bookmark> = emptyList(),
    currentBookmark: Bookmark? = null,
    currentPage: Int = 1,
    chromeVisible: Boolean = true,
    onAddBookmark: () -> Unit = {},
    onDeleteBookmark: (String) -> Unit = {},
    onGoToBookmark: (Bookmark) -> Unit = {},
    onGoToToc: (TocEntry) -> Unit = {},
    onGoToPage: (Int) -> Unit = {},
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit = {},
    readerContent: @Composable () -> Unit = {},
) {
    when (state) {
        is PdfReaderUiState.Loading -> Center { CircularProgressIndicator() }
        is PdfReaderUiState.Error -> Center {
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        is PdfReaderUiState.Ready -> ReaderContent(
            state = state,
            onBack = onBack,
            preferences = preferences,
            bookmarks = bookmarks,
            currentBookmark = currentBookmark,
            currentPage = currentPage,
            chromeVisible = chromeVisible,
            onAddBookmark = onAddBookmark,
            onDeleteBookmark = onDeleteBookmark,
            onGoToBookmark = onGoToBookmark,
            onGoToToc = onGoToToc,
            onGoToPage = onGoToPage,
            onUpdatePreferences = onUpdatePreferences,
            readerContent = readerContent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: PdfReaderUiState.Ready,
    onBack: () -> Unit,
    preferences: ReaderDisplayPreferences,
    bookmarks: List<Bookmark>,
    currentBookmark: Bookmark?,
    currentPage: Int,
    chromeVisible: Boolean,
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onGoToBookmark: (Bookmark) -> Unit,
    onGoToToc: (TocEntry) -> Unit,
    onGoToPage: (Int) -> Unit,
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    readerContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showBookmarks by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // issue #308 — the app's theme, not the phone's.
    val darkTheme = LocalDarkTheme.current
    val surface = preferences.theme.pdfSurfaceColor(darkTheme, preferences)
    // Issue #355 — on the iPhone Duo the toolbar stands in the side column or the status band.
    val sideControls = sideControls()
    val toggleBookmark = { currentBookmark?.let { onDeleteBookmark(it.id) } ?: onAddBookmark() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = surface,
        // issue #308 — the page-count label sits straight on the reading background, which can be
        // dark even in a light app (Grey, Black), so pick its colour from the background itself.
        contentColor = if (surface.luminance() < 0.5f) Color(0xFFE6E7EA) else Color(0xFF1B1C1F),
        topBar = {
            if (chromeVisible && sideControls == null) TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = { BackPill(onBack) },
                actions = {
                    IconButton(onClick = toggleBookmark) {
                        Icon(
                            if (currentBookmark != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (currentBookmark != null) "Remove bookmark" else "Add bookmark",
                        )
                    }
                    IconButton(onClick = { showBookmarks = true }) {
                        Icon(Icons.Filled.Bookmarks, contentDescription = "Bookmarks")
                    }
                    if (state.toc.isNotEmpty()) {
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Contents")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Display settings")
                    }
                },
            )
        },
        bottomBar = {
            if (chromeVisible && state.pageCount > 1) {
                // issue #306 — clear of the navigation bar (3-button nav drew over the slider) and,
                // in landscape, the camera cutout.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Page $currentPage of ${state.pageCount}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = currentPage.coerceIn(1, state.pageCount).toFloat(),
                        onValueChange = { v -> onGoToPage(v.toInt().coerceIn(1, state.pageCount)) },
                        valueRange = 1f..state.pageCount.toFloat(),
                        modifier = Modifier.semantics {
                            contentDescription = "Page slider"
                            stateDescription = "Page $currentPage of ${state.pageCount}"
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            readerContent()
        }
    }
    if (chromeVisible && sideControls != null) {
        SideColumnControls(sideControls) {
            SideColumnButton("chevron.left", Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            SideColumnGap()
            SideColumnButton(
                symbol = if (currentBookmark != null) "bookmark.fill" else "bookmark",
                fallback = if (currentBookmark != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (currentBookmark != null) "Remove bookmark" else "Add bookmark",
                onClick = toggleBookmark,
                active = currentBookmark != null,
            )
            SideColumnButton("bookmark.square", Icons.Filled.Bookmarks, "Bookmarks", { showBookmarks = true })
            if (state.toc.isNotEmpty()) {
                SideColumnButton("list.bullet", Icons.AutoMirrored.Filled.ListAlt, "Contents", { showToc = true })
            }
            SideColumnButton("slider.horizontal.3", Icons.Filled.Tune, "Display settings", { showSettings = true })
        }
    }
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            if (bookmarks.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    Text("Tap the bookmark icon to save your place")
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    items(bookmarks.sortedBy { it.pageNumber() ?: Int.MAX_VALUE }, key = { it.id }) { b ->
                        BookmarkRow(
                            bookmark = b,
                            onOpen = { onGoToBookmark(b); showBookmarks = false },
                            onDelete = { onDeleteBookmark(b.id) },
                        )
                    }
                }
            }
        }
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                items(state.toc) { entry ->
                    TextButton(
                        onClick = { onGoToToc(entry); showToc = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            entry.title,
                            modifier = Modifier.fillMaxWidth().padding(start = (entry.depth * 16).dp),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            PdfDisplaySettings(
                preferences = preferences,
                onChange = { transform -> scope.launch { onUpdatePreferences(transform) } },
            )
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            bookmark.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp).clickable(onClick = onOpen),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Remove bookmark")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfDisplaySettings(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .navigationBarsPadding(),
    ) {
        // issue #261 — the theme picker shared by every reader (and Book defaults).
        ReaderThemeSetting(preferences, onChange)
        SettingChips(
            title = "Page fit",
            // The engine only distinguishes fit-page from fit-width.
            entries = listOf(ReaderFitMode.PAGE_FIT, ReaderFitMode.PAGE_WIDTH),
            selected = if (preferences.fitMode == ReaderFitMode.PAGE_WIDTH) {
                ReaderFitMode.PAGE_WIDTH
            } else {
                ReaderFitMode.PAGE_FIT
            },
            label = ::readerFitLabel,
            onSelect = { fit -> onChange { it.copy(fitMode = fit) } },
        )
        SettingChips(
            title = "Reading mode",
            entries = listOf(ReaderScrollMode.PAGED, ReaderScrollMode.SCROLL),
            selected = if (preferences.scrollMode == ReaderScrollMode.PAGED) {
                ReaderScrollMode.PAGED
            } else {
                ReaderScrollMode.SCROLL
            },
            label = ::readerScrollModeLabel,
            onSelect = { mode -> onChange { it.copy(scrollMode = mode) } },
        )
        if (preferences.scrollMode == ReaderScrollMode.PAGED) {
            SettingChips(
                title = "Page-turn swipe",
                entries = ReaderSwipeSensitivity.entries,
                selected = preferences.swipeSensitivity,
                label = { it.label },
                onSelect = { s -> onChange { it.copy(swipeSensitivity = s) } },
            )
        }
        Text(
            "The page colour applies to the margins and spacing — the PDF engine can't recolour " +
                "the page content itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingChips(
    title: String,
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            FilterChip(
                selected = selected == entry,
                onClick = { onSelect(entry) },
                label = { Text(label(entry)) },
            )
        }
    }
}

private fun readerFitLabel(fit: ReaderFitMode): String = when (fit) {
    ReaderFitMode.PAGE_FIT -> "Fit"
    ReaderFitMode.PAGE_WIDTH -> "Width"
    ReaderFitMode.PAGE_HEIGHT -> "Height"
    ReaderFitMode.ACTUAL_SIZE -> "Actual size"
}

private fun readerScrollModeLabel(mode: ReaderScrollMode): String = when (mode) {
    ReaderScrollMode.PAGED -> "Paged"
    ReaderScrollMode.SCROLL -> "Scroll"
    ReaderScrollMode.CONTINUOUS -> "Continuous"
}

/** The page a bookmark points at, from its stored Readium locator. Null for foreign ones. */
private fun Bookmark.pageNumber(): Int? = pageFromLocatorJson(locatorJson)

/**
 * The colour behind the pages — pure Compose, no Readium dependency, so (unlike EPUB's own
 * preferences mapping) this lives here instead of being duplicated per native embed. PDFium/
 * PDFKit both render page content as opaque bitmaps, so this only shows in the page spacing,
 * the margins and when zoomed out — not on the page itself.
 */
private fun ReaderTheme.pdfSurfaceColor(appInDark: Boolean, preferences: ReaderDisplayPreferences): Color = when (this) {
    ReaderTheme.SYSTEM -> if (appInDark) Color(0xFF101114) else Color(0xFFF6F6F6)
    ReaderTheme.LIGHT -> Color(0xFFF6F6F6)
    ReaderTheme.SEPIA -> Color(0xFFEFE6D3)
    ReaderTheme.GREY -> Color(0xFF3A3D42)
    ReaderTheme.DARK -> Color(0xFF101114)
    // issue #261 — the painted palettes, from the one place they're defined.
    ReaderTheme.SEPIA_DARK, ReaderTheme.MIDNIGHT, ReaderTheme.NORD, ReaderTheme.CUSTOM_1, ReaderTheme.CUSTOM_2 ->
        Color(preferences.copy(theme = this).paintedColors!!.background)
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
