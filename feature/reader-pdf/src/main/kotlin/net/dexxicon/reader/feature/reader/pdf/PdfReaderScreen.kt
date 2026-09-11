package net.dexxicon.reader.feature.reader.pdf

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.barteksc.pdfviewer.PDFView
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.designsystem.component.pageSnapshot
import net.dexxicon.reader.core.designsystem.component.pageTurnGesture
import net.dexxicon.reader.core.designsystem.component.rememberPageTurnState
import net.dexxicon.reader.core.model.Bookmark
import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderFitMode
import net.dexxicon.reader.core.reader.ReaderScrollMode
import net.dexxicon.reader.core.reader.ReaderSwipeSensitivity
import net.dexxicon.reader.core.reader.ReaderTheme
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.pdf.navigator"

private typealias PdfNavigator = PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    when (val s = state) {
        is PdfReaderState.Loading -> Center { CircularProgressIndicator() }
        is PdfReaderState.Error -> Center {
            Text(
                s.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        is PdfReaderState.Ready -> ReaderContent(
            state = s,
            preferences = preferences,
            bookmarks = bookmarks,
            onBack = onBack,
            onLocator = viewModel::onLocatorChanged,
            onUpdatePreferences = viewModel.updatePreferences,
            onAddBookmark = viewModel::addBookmark,
            onDeleteBookmark = viewModel::deleteBookmark,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: PdfReaderState.Ready,
    preferences: ReaderDisplayPreferences,
    bookmarks: List<Bookmark>,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    onAddBookmark: (Locator) -> Unit,
    onDeleteBookmark: (String) -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    if (activity == null) {
        Center { Text("The reader needs a FragmentActivity host") }
        return
    }
    val scope = rememberCoroutineScope()
    val darkTheme = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val fragmentManager = activity.supportFragmentManager
    var navigator by remember { mutableStateOf<PdfNavigator?>(null) }
    var pageView by remember { mutableStateOf<View?>(null) }
    var currentLocator by remember { mutableStateOf<Locator?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val tocLinks = remember(state.publication) { flatten(state.publication.tableOfContents) }

    val initialPrefs = remember(state.publication) { preferences.toPdfiumPreferences() }
    DisposableEffect(state.publication) {
        val factory = PdfNavigatorFactory(state.publication, PdfiumEngineProvider())
        fragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = state.initialLocator,
            initialPreferences = initialPrefs,
            listener = null,
        )
        onDispose {
            if (!fragmentManager.isStateSaved) {
                fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG)?.let { frag ->
                    fragmentManager.commit(allowStateLoss = true) { remove(frag) }
                }
            }
            navigator = null
        }
    }

    LaunchedEffect(state.publication) {
        while (navigator == null) {
            @Suppress("UNCHECKED_CAST")
            navigator = fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG) as? PdfNavigator
            if (navigator == null) kotlinx.coroutines.delay(50)
        }
    }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { locator ->
            onLocator(locator)
            currentLocator = locator
            page = locator.locations.position ?: 1
        }
    }

    LaunchedEffect(preferences, navigator) {
        navigator?.submitPreferences(preferences.toPdfiumPreferences())
    }

    // The bookmark for the page we're on (ours only — a foreign one from a web reader
    // carries no usable page number).
    val currentBookmark = remember(bookmarks, page) {
        bookmarks.firstOrNull { !it.isForeign && it.pageNumber() == page }
    }

    fun goToBookmark(b: Bookmark) {
        val nav = navigator ?: return
        val target = b.pageNumber()?.let { pg ->
            state.publication.readingOrder.firstOrNull()
                ?.let(state.publication::locatorFromLink)
                ?.copyWithLocations(position = pg)
        } ?: runCatching { Locator.fromJSON(org.json.JSONObject(b.locatorJson)) }.getOrNull()
        target?.let { nav.go(it, false) }
    }

    Scaffold(
        containerColor = preferences.theme.pdfSurfaceColor(darkTheme),
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val locatorNow = currentLocator
                    IconButton(
                        enabled = locatorNow != null,
                        onClick = {
                            currentBookmark?.let { onDeleteBookmark(it.id) }
                                ?: locatorNow?.let(onAddBookmark)
                        },
                    ) {
                        Icon(
                            if (currentBookmark != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (currentBookmark != null) "Remove bookmark" else "Add bookmark",
                        )
                    }
                    IconButton(onClick = { showBookmarks = true }) {
                        Icon(Icons.Filled.Bookmarks, contentDescription = "Bookmarks")
                    }
                    if (tocLinks.isNotEmpty()) {
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
            if (state.pageCount > 1) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Page $page of ${state.pageCount}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = page.coerceIn(1, state.pageCount).toFloat(),
                        onValueChange = { v ->
                            val target = v.toInt().coerceIn(1, state.pageCount)
                            page = target
                            val base = currentLocator
                                ?: state.publication.readingOrder.firstOrNull()
                                    ?.let(state.publication::locatorFromLink)
                            base?.copyWithLocations(position = target)?.let { navigator?.go(it, false) }
                        },
                        valueRange = 1f..state.pageCount.toFloat(),
                        modifier = Modifier.semantics {
                            contentDescription = "Page slider"
                            stateDescription = "Page $page of ${state.pageCount}"
                        },
                    )
                }
            }
        },
    ) { padding ->
        val pageTurn = rememberPageTurnState()
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Drag the page with your finger; releasing past the sensitivity threshold
                // turns it, a shorter drag slides back. The pdfium view can't paginate
                // itself embedded in Compose, so the gesture drives the navigator directly.
                .pageTurnGesture(
                    state = pageTurn,
                    enabled = !preferences.scroll,
                    commitFraction = preferences.swipeSensitivity.commitFraction,
                    snapshot = { pageView?.pageSnapshot() },
                    onTurn = { forward ->
                        (if (forward) navigator?.goForward(false) else navigator?.goBackward(false)) == true
                    },
                    canTurn = { forward, _, _ ->
                        // A zoomed-in page still has room to pan this way — let the pan
                        // happen instead of turning; canScrollHorizontally is the same
                        // contract RecyclerView/ViewPager use to arbitrate exactly this.
                        findPdfView(pageView)?.canScrollHorizontally(if (forward) 1 else -1) ?: true
                    },
                ),
        ) {
            AndroidView(
                factory = { ctx ->
                    val container = FragmentContainerView(ctx).apply {
                        id = View.generateViewId()
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                    }
                    if (fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG) == null &&
                        !fragmentManager.isStateSaved
                    ) {
                        fragmentManager.commit {
                            setReorderingAllowed(true)
                            add(container.id, PdfNavigatorFragment::class.java, null, NAV_FRAGMENT_TAG)
                        }
                    }
                    container.also { pageView = it }
                },
                modifier = Modifier.fillMaxSize(),
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
                    items(bookmarks.sortedBy { it.pageNumber() ?: Int.MAX_VALUE }, key = { it.id }) { b ->
                        BookmarkRow(
                            bookmark = b,
                            onOpen = { goToBookmark(b); showBookmarks = false },
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
                items(tocLinks) { (depth, link) ->
                    TextButton(
                        onClick = { navigator?.go(link, false); showToc = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            link.title ?: link.href.toString(),
                            modifier = Modifier.fillMaxWidth().padding(start = (depth * 16).dp),
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
        SettingChips(
            title = "Background",
            entries = ReaderTheme.entries,
            selected = preferences.theme,
            label = ::readerThemeLabel,
            onSelect = { theme -> onChange { it.copy(theme = theme) } },
        )
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

private fun readerThemeLabel(theme: ReaderTheme): String = when (theme) {
    ReaderTheme.SYSTEM -> "System"
    ReaderTheme.LIGHT -> "White"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.GREY -> "Grey"
    ReaderTheme.DARK -> "Black"
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
private fun Bookmark.pageNumber(): Int? = runCatching {
    org.json.JSONObject(locatorJson).optJSONObject("locations")?.optInt("position", -1)
        ?.takeIf { it > 0 }
}.getOrNull()

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}

private fun flatten(links: List<Link>, depth: Int = 0): List<Pair<Int, Link>> =
    links.flatMap { link -> listOf(depth to link) + flatten(link.children, depth + 1) }

/**
 * The pdfium navigator's single [PDFView] instance, wherever it sits under [root] — unlike
 * the comic reader's per-page pager, there's exactly one for the whole viewport, so no
 * hit-testing by touch point is needed to pick the right one.
 */
private fun findPdfView(root: View?): PDFView? {
    if (root == null) return null
    if (root is PDFView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findPdfView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}
