package net.dexxicon.reader.shared.reader.epub

import net.dexxicon.reader.shared.reader.ResetTextButton
import net.dexxicon.reader.shared.reader.ReaderThemeSetting
import net.dexxicon.reader.shared.reader.TocList
import net.dexxicon.reader.shared.SideColumnGap
import net.dexxicon.reader.shared.SideColumnControls
import net.dexxicon.reader.shared.SideColumnButton
import net.dexxicon.reader.shared.sideControls
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFontFamily
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import net.dexxicon.reader.core.datastore.ReaderScrollMode
import net.dexxicon.reader.core.datastore.ReaderTheme
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.core.model.HighlightColor
import net.dexxicon.reader.shared.reader.proportionalScaleLabel
import net.dexxicon.reader.shared.reader.ReaderProportionalScaleControl
import net.dexxicon.reader.shared.settings.SettingRow

/** issue #117: grey background, unchanged from Android's original standalone screen. */
private const val SEPIA_BACKGROUND = 0xFFF4ECD8.toInt()
private const val GREY_BACKGROUND = 0xFF3A3A3A.toInt()
private const val DARK_BACKGROUND = 0xFF121212.toInt()

/**
 * Phase 2 of the shared-reader-chrome redesign (issue #183) — the EPUB reader's chrome,
 * ported from `feature/reader-epub`'s original `EpubReaderScreen.kt`/`ReaderContent`. The
 * genuinely native pieces (the Readium navigator fragment/view controller itself, decoration
 * rendering, edge-tap navigation, the text-selection "Highlight" menu item, and translating a
 * [TocEntry.ref]/[Bookmark.locatorJson]/[Highlight.locatorJson] into a real jump) all stay
 * behind each platform's own embed point — [readerContent] renders that surface, and every
 * `onGoTo*` callback here is answered by native code holding the real navigator.
 *
 * [currentBookmark] is the bookmark (if any) whose position matches the current reading
 * location closely enough to be "here" — the native embed computes this the same way the
 * original screen did (closest [Bookmark.progression] within a small tolerance), since only
 * it tracks the live reading position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    state: EpubReaderUiState,
    onBack: () -> Unit,
    preferences: ReaderDisplayPreferences = ReaderDisplayPreferences(),
    bookmarks: List<Bookmark> = emptyList(),
    highlights: List<Highlight> = emptyList(),
    currentBookmark: Bookmark? = null,
    chromeVisible: Boolean = true,
    /** The highlight (if any) whose editor sheet is open — hoisted rather than owned
     * internally so a native decoration-tap listener (only it hears about a tap on a
     * rendered highlight) can open the editor too, the same way [chromeVisible] is hoisted
     * for the native edge-tap navigator to flip. */
    activeHighlightId: String? = null,
    onActiveHighlightChange: (String?) -> Unit = {},
    onAddBookmark: () -> Unit = {},
    onDeleteBookmark: (String) -> Unit = {},
    onGoToBookmark: (Bookmark) -> Unit = {},
    onGoToToc: (TocEntry) -> Unit = {},
    onGoToHighlight: (Highlight) -> Unit = {},
    onSetNote: (String, String?) -> Unit = { _, _ -> },
    onSetColor: (String, HighlightColor) -> Unit = { _, _ -> },
    onDeleteHighlight: (String) -> Unit = {},
    onJumpToRemoteResume: () -> Unit = {},
    onDismissRemoteResume: () -> Unit = {},
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit = {},
    readerContent: @Composable () -> Unit = {},
) {
    when (state) {
        is EpubReaderUiState.Loading -> Center { CircularProgressIndicator() }
        is EpubReaderUiState.Error -> Center {
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        is EpubReaderUiState.Ready -> ReaderContent(
            state = state,
            preferences = preferences,
            bookmarks = bookmarks,
            highlights = highlights,
            currentBookmark = currentBookmark,
            chromeVisible = chromeVisible,
            activeHighlightId = activeHighlightId,
            onActiveHighlightChange = onActiveHighlightChange,
            onBack = onBack,
            onAddBookmark = onAddBookmark,
            onDeleteBookmark = onDeleteBookmark,
            onGoToBookmark = onGoToBookmark,
            onGoToToc = onGoToToc,
            onGoToHighlight = onGoToHighlight,
            onSetNote = onSetNote,
            onSetColor = onSetColor,
            onDeleteHighlight = onDeleteHighlight,
            onJumpToRemoteResume = onJumpToRemoteResume,
            onDismissRemoteResume = onDismissRemoteResume,
            onUpdatePreferences = onUpdatePreferences,
            readerContent = readerContent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: EpubReaderUiState.Ready,
    preferences: ReaderDisplayPreferences,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    currentBookmark: Bookmark?,
    chromeVisible: Boolean,
    activeHighlightId: String?,
    onActiveHighlightChange: (String?) -> Unit,
    onBack: () -> Unit,
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onGoToBookmark: (Bookmark) -> Unit,
    onGoToToc: (TocEntry) -> Unit,
    onGoToHighlight: (Highlight) -> Unit,
    onSetNote: (String, String?) -> Unit,
    onSetColor: (String, HighlightColor) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onJumpToRemoteResume: () -> Unit,
    onDismissRemoteResume: () -> Unit,
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    readerContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHighlights by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var resumeDismissed by remember { mutableStateOf(false) }
    // Issue #343 — on the iPhone Duo the toolbar stands in the side column instead.
    // Issue #355 — held upright, the Duo puts them in the status band across the top instead.
    val sideColumn = sideControls()
    val toggleBookmark = { currentBookmark?.let { onDeleteBookmark(it.id) } ?: onAddBookmark() }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            if (chromeVisible && sideColumn == null) {
                TopAppBar(
                    // No title here — five action icons plus the BackPill already crowd this
                    // row with no room left for a title to render legibly (issue #117).
                    title = {},
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
                        IconButton(onClick = { showHighlights = true }) {
                            Icon(Icons.Filled.BorderColor, contentDescription = "Highlights")
                        }
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Contents")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.TextFields, contentDescription = "Display settings")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            readerContent()

            if (state.hasRemoteResume && !resumeDismissed) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Continue from ${((state.remoteResumePercent ?: 0.0) * 100).toInt()}% (synced)",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = {
                            onJumpToRemoteResume()
                            resumeDismissed = true
                        }) { Text("Jump") }
                        IconButton(onClick = {
                            resumeDismissed = true
                            onDismissRemoteResume()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }
    }
    // Issue #343 — the toolbar as round buttons in the side column: Back, then the actions.
    if (chromeVisible && sideColumn != null) {
        SideColumnControls(sideColumn) {
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
            SideColumnButton("highlighter", Icons.Filled.BorderColor, "Highlights", { showHighlights = true })
            SideColumnButton("list.bullet", Icons.AutoMirrored.Filled.ListAlt, "Contents", { showToc = true })
            SideColumnButton("textformat.size", Icons.Filled.TextFields, "Display settings", { showSettings = true })
        }
    }
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            TocList(
                entries = state.toc,
                currentRef = state.currentTocRef,
                onSelect = { entry -> onGoToToc(entry); showToc = false },
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            DisplaySettings(
                preferences = preferences,
                onChange = { transform -> scope.launch { onUpdatePreferences(transform) } },
            )
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
                    items(bookmarks, key = { it.id }) { b ->
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

    if (showHighlights) {
        ModalBottomSheet(onDismissRequest = { showHighlights = false }) {
            HighlightList(
                highlights = highlights,
                onSelect = { h -> onGoToHighlight(h); showHighlights = false },
                onEdit = { onActiveHighlightChange(it.id); showHighlights = false },
            )
        }
    }

    val active = highlights.firstOrNull { it.id == activeHighlightId }
    if (active != null) {
        ModalBottomSheet(onDismissRequest = { onActiveHighlightChange(null) }) {
            HighlightEditor(
                highlight = active,
                onNote = { onSetNote(active.id, it) },
                onColor = { onSetColor(active.id, it) },
                onDelete = { onDeleteHighlight(active.id); onActiveHighlightChange(null) },
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
    val subtitle = remember(bookmark.id) { bookmarkLocationLabel(bookmark) }
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
        Column(
            Modifier.weight(1f).padding(start = 12.dp).clickableText(onOpen),
        ) {
            Text(
                bookmark.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "Remove bookmark")
        }
    }
}

/**
 * A "where in the book" hint for a bookmark row: how far through the chapter, then Readium's
 * page number when the publication has a position list — e.g. "43% – Page 87". Null for
 * bookmarks made in a server's web reader (they carry no precise position). Parses
 * [Bookmark.locatorJson] with `kotlinx.serialization.json` rather than Android's
 * `org.json.JSONObject` (issue #183) so this, like the repositories themselves, is portable.
 */
private fun bookmarkLocationLabel(bookmark: Bookmark): String? {
    if (bookmark.isForeign) return null
    return runCatching {
        val locations = Json.parseToJsonElement(bookmark.locatorJson).jsonObject["locations"]?.jsonObject
        val chapterProgression = locations?.get("progression")?.jsonPrimitive?.double
        val percent = ((chapterProgression ?: bookmark.progression) * 100).toInt()
        val page = locations?.get("position")?.jsonPrimitive?.int ?: -1
        if (page > 0) "$percent% – Page $page" else "$percent%"
    }.getOrNull()
}

@Composable
private fun HighlightList(
    highlights: List<Highlight>,
    onSelect: (Highlight) -> Unit,
    onEdit: (Highlight) -> Unit,
) {
    if (highlights.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            Text("Select text in the book to add a highlight")
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        items(highlights, key = { it.id }) { h ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(h.color.argb)),
                )
                Column(
                    Modifier.weight(1f).padding(start = 12.dp).clickableText { onSelect(h) },
                ) {
                    Text(
                        h.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val note = h.note
                    if (!note.isNullOrBlank()) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { onEdit(h) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit highlight")
                }
            }
        }
    }
}

@Composable
private fun HighlightEditor(
    highlight: Highlight,
    onNote: (String?) -> Unit,
    onColor: (HighlightColor) -> Unit,
    onDelete: () -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note.orEmpty()) }
    var selectedColor by remember(highlight.id) { mutableStateOf(highlight.color) }
    val latestNote by rememberUpdatedState(note)

    // Persist the note when the sheet goes away (or the highlight changes) so an edit is
    // never lost just because the user dismissed without tapping "Save".
    androidx.compose.runtime.DisposableEffect(highlight.id) {
        onDispose {
            if (latestNote != highlight.note.orEmpty()) onNote(latestNote)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Text(highlight.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 16.dp)) {
            HighlightColor.entries.forEach { c ->
                val selected = c == selectedColor
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(c.argb))
                        .then(
                            if (selected) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickableText {
                            selectedColor = c
                            onColor(c)
                        },
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text("  Delete")
            }
            TextButton(onClick = { onNote(note) }) { Text("Save note") }
        }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplaySettings(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    var textExpanded by remember { mutableStateOf(true) }
    var pageExpanded by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .navigationBarsPadding(),
    ) {
        // issue #261 — hierarchical: the theme, then the page, then the text on it.
        ReaderThemeSetting(preferences, onChange)

        SettingSection(
            title = "Page",
            expanded = pageExpanded,
            onToggle = { pageExpanded = !pageExpanded },
        ) {
            SettingChips(
                title = "Page fit",
                entries = ReaderFitMode.entries,
                selected = preferences.fitMode,
                label = ::readerFitLabel,
                onSelect = { fit -> onChange { it.copy(fitMode = fit) } },
            )

            SettingChips(
                title = "Page layout",
                entries = ReaderPageLayout.entries,
                selected = preferences.pageLayout,
                label = ::readerPageLayoutLabel,
                onSelect = { layout -> onChange { it.copy(pageLayout = layout) } },
            )

            SettingChips(
                title = "Reading mode",
                // CONTINUOUS is scaffolding — kept out of the picker until a navigator honours it.
                entries = listOf(ReaderScrollMode.PAGED, ReaderScrollMode.SCROLL),
                selected = if (preferences.scrollMode == ReaderScrollMode.PAGED) {
                    ReaderScrollMode.PAGED
                } else {
                    ReaderScrollMode.SCROLL
                },
                label = ::readerScrollModeLabel,
                onSelect = { mode -> onChange { it.copy(scrollMode = mode) } },
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tap edges to turn pages", Modifier.weight(1f))
                Switch(
                    checked = preferences.tapNavigation,
                    onCheckedChange = { on -> onChange { it.copy(tapNavigation = on) } },
                )
            }
        }

        SettingSection(
            title = "Text",
            expanded = textExpanded,
            onToggle = { textExpanded = !textExpanded },
        ) {
            FontFamilySelector(
                selected = preferences.fontFamily,
                onSelect = { family -> onChange { it.copy(fontFamily = family) } },
            )
            ReaderProportionalScaleControl(
                title = "Font size",
                scale = preferences.fontScale,
                min = ReaderDisplayPreferences.MIN_FONT_SCALE,
                max = ReaderDisplayPreferences.MAX_FONT_SCALE,
                onDecreaseDescription = "Decrease font size",
                onIncreaseDescription = "Increase font size",
                onScaleChange = { scale -> onChange { it.copy(fontScale = scale) } },
            )
            ReaderProportionalScaleControl(
                title = "Line spacing",
                scale = preferences.lineSpacing ?: ReaderDisplayPreferences.DEFAULT_LINE_SPACING,
                min = ReaderDisplayPreferences.MIN_LINE_SPACING,
                max = ReaderDisplayPreferences.MAX_LINE_SPACING,
                onDecreaseDescription = "Decrease line spacing",
                onIncreaseDescription = "Increase line spacing",
                valueLabel = preferences.lineSpacing?.let { "${proportionalScaleLabel(it)}×" } ?: "Default",
                onScaleChange = { spacing -> onChange { it.copy(lineSpacing = spacing) } },
            )
            ReaderProportionalScaleControl(
                title = "Paragraph spacing",
                scale = preferences.paragraphSpacing ?: ReaderDisplayPreferences.DEFAULT_PARAGRAPH_SPACING,
                min = ReaderDisplayPreferences.MIN_PARAGRAPH_SPACING,
                max = ReaderDisplayPreferences.MAX_PARAGRAPH_SPACING,
                onDecreaseDescription = "Decrease paragraph spacing",
                onIncreaseDescription = "Increase paragraph spacing",
                valueLabel = preferences.paragraphSpacing?.let { "${proportionalScaleLabel(it)} rem" }
                    ?: "Default",
                onScaleChange = { spacing -> onChange { it.copy(paragraphSpacing = spacing) } },
            )
            SettingRow(
                title = "Paragraph indent",
                subtitle = if (preferences.paragraphIndent == null) "Readium default" else null,
            ) {
                Switch(
                    checked = preferences.paragraphIndent ?: false,
                    onCheckedChange = { enabled -> onChange { it.copy(paragraphIndent = enabled) } },
                )
            }
            SettingRow(
                title = "Justification",
                subtitle = if (preferences.justification == null) "Readium default" else null,
            ) {
                Switch(
                    checked = preferences.justification ?: false,
                    onCheckedChange = { enabled -> onChange { it.copy(justification = enabled) } },
                )
            }
            SettingRow(
                title = "Hyphenation",
                subtitle = if (preferences.hyphenation == null) "Readium default" else null,
            ) {
                Switch(
                    checked = preferences.hyphenation ?: false,
                    onCheckedChange = { enabled -> onChange { it.copy(hyphenation = enabled) } },
                )
            }
            ResetTextButton(preferences) { onChange(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontFamilySelector(
    selected: ReaderFontFamily?,
    onSelect: (ReaderFontFamily?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        OutlinedTextField(
            value = selected?.label ?: "Publisher default",
            onValueChange = {},
            readOnly = true,
            label = { Text("Font family") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Publisher default") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
            ReaderFontFamily.entries.forEach { family ->
                DropdownMenuItem(
                    text = { Text(family.label) },
                    onClick = {
                        expanded = false
                        onSelect(family)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        TextButton(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Start,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
        }
        if (expanded) content()
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
    leadingIcon: (@Composable (T) -> Unit)? = null,
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
                leadingIcon = leadingIcon?.let { icon -> { icon(entry) } },
                shape = Pill,
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

private fun readerPageLayoutLabel(layout: ReaderPageLayout): String = when (layout) {
    ReaderPageLayout.AUTO -> "Auto"
    ReaderPageLayout.SINGLE -> "Single"
    ReaderPageLayout.DOUBLE -> "Two-page"
}

private fun readerScrollModeLabel(mode: ReaderScrollMode): String = when (mode) {
    ReaderScrollMode.PAGED -> "Paged"
    ReaderScrollMode.SCROLL -> "Scroll"
    ReaderScrollMode.CONTINUOUS -> "Continuous"
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
