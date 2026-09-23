package net.dexxicon.reader.feature.reader.epub

import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.reader.EdgeTapNavigator
import net.dexxicon.reader.shared.reader.epub.EpubReaderScreen as SharedEpubReaderScreen
import net.dexxicon.reader.shared.reader.epub.EpubReaderUiState
import net.dexxicon.reader.shared.reader.epub.TocEntry
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.epub.navigator"

/**
 * The EPUB reader's native embed point (Phase 2 of #183) — everything genuinely tied to
 * Readium's Android Toolkit (the navigator fragment itself, decoration rendering, edge-tap
 * navigation, the text-selection "Highlight" menu item, and translating a portable
 * [TocEntry]/[net.dexxicon.reader.core.model.Bookmark]/[net.dexxicon.reader.core.model.Highlight]
 * into a real [Locator]/[Link] jump) lives here; the chrome itself (top bar, TOC/bookmarks/
 * highlights/display-settings sheets) is the shared `EpubReaderScreen` this composable wraps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    onBack: () -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

    when (val s = state) {
        is EpubReaderState.Loading -> SharedEpubReaderScreen(EpubReaderUiState.Loading, onBack)
        is EpubReaderState.Error -> SharedEpubReaderScreen(EpubReaderUiState.Error(s.message), onBack)
        is EpubReaderState.Ready -> ReaderContent(
            state = s,
            preferences = prefs,
            highlights = highlights,
            bookmarks = bookmarks,
            onBack = onBack,
            onLocator = viewModel::onLocatorChanged,
            onUpdatePreferences = viewModel.updatePreferences,
            onAddHighlight = viewModel::addHighlight,
            onSetNote = viewModel::setNote,
            onSetColor = viewModel::setColor,
            onDeleteHighlight = viewModel::deleteHighlight,
            onAddBookmark = viewModel::addBookmark,
            onDeleteBookmark = viewModel::deleteBookmark,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: EpubReaderState.Ready,
    preferences: net.dexxicon.reader.core.datastore.ReaderDisplayPreferences,
    highlights: List<net.dexxicon.reader.core.model.Highlight>,
    bookmarks: List<net.dexxicon.reader.core.model.Bookmark>,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
    onUpdatePreferences: suspend ((net.dexxicon.reader.core.datastore.ReaderDisplayPreferences) -> net.dexxicon.reader.core.datastore.ReaderDisplayPreferences) -> Unit,
    onAddHighlight: (Locator) -> Unit,
    onSetNote: (String, String?) -> Unit,
    onSetColor: (String, net.dexxicon.reader.core.model.HighlightColor) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onAddBookmark: (Locator) -> Unit,
    onDeleteBookmark: (String) -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    val darkTheme = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    // issue #117: same 720dp breakpoint used elsewhere (App.kt's two-pane split, the
    // audiobook player's side-by-side layout) — the reader is always a full-screen route, so
    // the configuration's width is the viewport width, no BoxWithConstraints needed.
    val wideViewport = LocalConfiguration.current.screenWidthDp.dp >= 720.dp

    if (activity == null) {
        Surface(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("The reader needs a FragmentActivity host")
            }
        }
        return
    }

    val fragmentManager = activity.supportFragmentManager
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var activeHighlightId by remember { mutableStateOf<String?>(null) }
    var currentLocator by remember { mutableStateOf<Locator?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    val tapNavEnabled by rememberUpdatedState(preferences.tapNavigation)
    val navHolder = remember { arrayOfNulls<EpubNavigatorFragment?>(1) }
    val addHighlight by rememberUpdatedState(onAddHighlight)

    // The same flattened order backs both the shared TOC sheet (as portable TocEntry rows,
    // ref = index into this list as a string) and this embed's own "go to ref" resolution —
    // built once per publication so both stay in lockstep.
    val flatToc = remember(state.publication) { flatten(state.publication.tableOfContents) }
    val tocEntries = remember(flatToc) {
        flatToc.mapIndexed { index, (depth, link) -> TocEntry(depth, link.title ?: link.href.toString(), index.toString()) }
    }

    // Build the navigator fragment once per opened publication and set it as the factory
    // the FragmentManager will use to instantiate EpubNavigatorFragment by class.
    val initialPrefs = remember(state.publication) { preferences.toEpubPreferences(darkTheme, wideViewport) }
    DisposableEffect(state.publication) {
        fragmentManager.fragmentFactory = EpubNavigatorFactory(state.publication)
            .createFragmentFactory(
                initialLocator = state.initialLocator,
                initialPreferences = initialPrefs,
                configuration = EpubNavigatorFragment.Configuration().apply {
                    decorationTemplates = HtmlDecorationTemplates.defaultTemplates()
                    selectionActionModeCallback = HighlightSelectionCallback(
                        activity = activity,
                        navigator = { navHolder[0] },
                        onHighlight = { addHighlight(it) },
                    )
                },
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

    // Grab the fragment instance once it has been created so the chrome can drive it.
    LaunchedEffect(state.publication) {
        while (navigator == null) {
            navigator = fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG) as? EpubNavigatorFragment
            if (navigator == null) kotlinx.coroutines.delay(50)
        }
        navHolder[0] = navigator
    }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect {
            currentLocator = it
            onLocator(it)
        }
    }

    // Whether the current reading position is already bookmarked (our own bookmarks only —
    // foreign ones from a web reader don't carry a precise enough position to toggle).
    val currentBookmark = remember(bookmarks, currentLocator) {
        val here = currentLocator?.locations?.totalProgression ?: return@remember null
        bookmarks.filter { !it.isForeign }
            .minByOrNull { kotlin.math.abs(it.progression - here) }
            ?.takeIf { kotlin.math.abs(it.progression - here) < 0.001 }
    }

    fun goToBookmark(b: net.dexxicon.reader.core.model.Bookmark) {
        val nav = navigator ?: return
        if (b.isForeign) {
            // Chapter-level jump: match the label to a table-of-contents entry.
            flatToc.firstOrNull { (_, link) -> link.title?.equals(b.title, ignoreCase = true) == true }
                ?.let { (_, link) -> nav.go(link, true) }
        } else {
            runCatching { Locator.fromJSON(org.json.JSONObject(b.locatorJson)) }
                .getOrNull()?.let { nav.go(it, true) }
        }
    }

    // Render highlight decorations and react to taps on them.
    LaunchedEffect(navigator, highlights) {
        val nav = navigator ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { h ->
            val locator = state.publication.locatorOf(h) ?: return@mapNotNull null
            Decoration(
                id = h.id,
                locator = locator,
                style = Decoration.Style.Highlight(tint = h.color.argb, isActive = false),
            )
        }
        runCatching { nav.applyDecorations(decorations, "highlights") }
    }

    DisposableEffect(navigator) {
        val nav = navigator
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                activeHighlightId = event.decoration.id
                return true
            }
        }
        nav?.addDecorationListener("highlights", listener)
        onDispose { nav?.removeDecorationListener(listener) }
    }

    DisposableEffect(navigator) {
        val nav = navigator
        val listener = nav?.let {
            EdgeTapNavigator(
                navigator = it,
                viewWidth = { it.publicationView.width },
                onCenterTap = { chromeVisible = !chromeVisible },
                enabled = { tapNavEnabled },
            ).also { l -> it.addInputListener(l) }
        }
        onDispose { if (nav != null && listener != null) nav.removeInputListener(listener) }
    }

    LaunchedEffect(preferences, darkTheme, wideViewport, navigator) {
        navigator?.submitPreferences(preferences.toEpubPreferences(darkTheme, wideViewport))
    }

    SharedEpubReaderScreen(
        state = EpubReaderUiState.Ready(
            title = state.title,
            toc = tocEntries,
            remoteResumePercent = state.remoteResumePercent,
        ),
        onBack = onBack,
        preferences = preferences,
        bookmarks = bookmarks,
        highlights = highlights,
        currentBookmark = currentBookmark,
        chromeVisible = chromeVisible,
        activeHighlightId = activeHighlightId,
        onActiveHighlightChange = { activeHighlightId = it },
        onAddBookmark = { currentLocator?.let(onAddBookmark) },
        onDeleteBookmark = onDeleteBookmark,
        onGoToBookmark = ::goToBookmark,
        onGoToToc = { entry -> flatToc.getOrNull(entry.ref.toIntOrNull() ?: -1)?.let { (_, link) -> navigator?.go(link, true) } },
        onGoToHighlight = { h -> state.publication.locatorOf(h)?.let { navigator?.go(it, true) } },
        onSetNote = onSetNote,
        onSetColor = onSetColor,
        onDeleteHighlight = onDeleteHighlight,
        onJumpToRemoteResume = { state.remoteResumeLocator?.let { navigator?.go(it, true) } },
        onDismissRemoteResume = {},
        onUpdatePreferences = onUpdatePreferences,
        readerContent = {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
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
                                add(container.id, EpubNavigatorFragment::class.java, null, NAV_FRAGMENT_TAG)
                            }
                        }
                        container
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    )
}

private fun flatten(links: List<Link>, depth: Int = 0): List<Pair<Int, Link>> =
    links.flatMap { link -> listOf(depth to link) + flatten(link.children, depth + 1) }
