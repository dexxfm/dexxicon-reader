package net.dexxicon.reader.feature.reader.pdf

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.barteksc.pdfviewer.PDFView
import net.dexxicon.reader.core.designsystem.component.pageSnapshot
import net.dexxicon.reader.core.designsystem.component.pageTurnGesture
import net.dexxicon.reader.core.designsystem.component.rememberPageTurnState
import net.dexxicon.reader.shared.reader.epub.TocEntry
import net.dexxicon.reader.shared.reader.pdf.PdfReaderScreen as SharedPdfReaderScreen
import net.dexxicon.reader.shared.reader.pdf.PdfReaderUiState
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.pdf.navigator"

private typealias PdfNavigator = PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

/**
 * The PDF reader's native embed point (Phase 3 of #183) — everything genuinely tied to
 * Readium's PDFium Android adapter (the navigator fragment itself, the pdfium-specific
 * page-turn drag gesture, and translating a portable [TocEntry]/
 * [net.dexxicon.reader.core.model.Bookmark] into a real [Locator]/[Link] jump) lives here; the
 * chrome itself (top bar, TOC/bookmarks/display-settings sheets, page slider) is the shared
 * `PdfReaderScreen` this composable wraps.
 */
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
        is PdfReaderState.Loading -> SharedPdfReaderScreen(PdfReaderUiState.Loading, onBack)
        is PdfReaderState.Error -> SharedPdfReaderScreen(PdfReaderUiState.Error(s.message), onBack)
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
    preferences: net.dexxicon.reader.core.datastore.ReaderDisplayPreferences,
    bookmarks: List<net.dexxicon.reader.core.model.Bookmark>,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
    onUpdatePreferences: suspend ((net.dexxicon.reader.core.datastore.ReaderDisplayPreferences) -> net.dexxicon.reader.core.datastore.ReaderDisplayPreferences) -> Unit,
    onAddBookmark: (Locator) -> Unit,
    onDeleteBookmark: (String) -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    if (activity == null) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("The reader needs a FragmentActivity host")
            }
        }
        return
    }

    val fragmentManager = activity.supportFragmentManager
    var navigator by remember { mutableStateOf<PdfNavigator?>(null) }
    var pageView by remember { mutableStateOf<View?>(null) }
    var currentLocator by remember { mutableStateOf<Locator?>(null) }
    var page by remember { mutableIntStateOf(1) }

    val flatToc = remember(state.publication) { flatten(state.publication.tableOfContents) }
    val tocEntries = remember(flatToc) {
        flatToc.mapIndexed { index, (depth, link) -> TocEntry(depth, link.title ?: link.href.toString(), index.toString()) }
    }

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

    fun goToBookmark(b: net.dexxicon.reader.core.model.Bookmark) {
        val nav = navigator ?: return
        val target = b.pageNumber()?.let { pg ->
            state.publication.readingOrder.firstOrNull()
                ?.let(state.publication::locatorFromLink)
                ?.copyWithLocations(position = pg)
        } ?: runCatching { Locator.fromJSON(org.json.JSONObject(b.locatorJson)) }.getOrNull()
        target?.let { nav.go(it, false) }
    }

    fun goToPage(target: Int) {
        // Optimistic local update — same reason the original screen set `page = target`
        // before the navigator confirmed it: the slider should track the drag instantly,
        // not wait on the navigator's own (debounced-feeling) currentLocator flow.
        page = target
        val base = currentLocator
            ?: state.publication.readingOrder.firstOrNull()?.let(state.publication::locatorFromLink)
        base?.copyWithLocations(position = target)?.let { navigator?.go(it, false) }
    }

    SharedPdfReaderScreen(
        state = PdfReaderUiState.Ready(
            title = state.title,
            toc = tocEntries,
            pageCount = state.pageCount,
        ),
        onBack = onBack,
        preferences = preferences,
        bookmarks = bookmarks,
        currentBookmark = currentBookmark,
        currentPage = page,
        onAddBookmark = { currentLocator?.let(onAddBookmark) },
        onDeleteBookmark = onDeleteBookmark,
        onGoToBookmark = ::goToBookmark,
        onGoToToc = { entry -> flatToc.getOrNull(entry.ref.toIntOrNull() ?: -1)?.let { (_, link) -> navigator?.go(link, false) } },
        onGoToPage = ::goToPage,
        onUpdatePreferences = onUpdatePreferences,
        readerContent = {
            val pageTurn = rememberPageTurnState()
            Box(
                Modifier
                    .fillMaxSize()
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
                        atBoundary = { forward ->
                            if (forward) page >= state.pageCount else page <= 1
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
        },
    )
}

/** The page a bookmark points at, from its stored Readium locator. Null for foreign ones. */
private fun net.dexxicon.reader.core.model.Bookmark.pageNumber(): Int? = runCatching {
    org.json.JSONObject(locatorJson).optJSONObject("locations")?.optInt("position", -1)
        ?.takeIf { it > 0 }
}.getOrNull()

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
