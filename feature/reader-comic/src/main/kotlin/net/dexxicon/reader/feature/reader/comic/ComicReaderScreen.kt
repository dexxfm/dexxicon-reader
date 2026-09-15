package net.dexxicon.reader.feature.reader.comic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
import net.dexxicon.reader.core.datastore.ReaderPageLayout
import net.dexxicon.reader.core.designsystem.component.pageSnapshot
import net.dexxicon.reader.core.designsystem.component.pageTurnGesture
import net.dexxicon.reader.core.designsystem.component.rememberPageTurnState
import net.dexxicon.reader.core.reader.ComicPanelDetector
import net.dexxicon.reader.core.reader.EdgeTapNavigator
import net.dexxicon.reader.core.reader.PanelSteppingNavigator
import net.dexxicon.reader.shared.reader.comic.ComicReaderScreen as SharedComicReaderScreen
import net.dexxicon.reader.shared.reader.comic.ComicReaderUiState
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.comic.navigator"

/** Tags for the two independent navigator fragments double-spread mode runs side by side —
 *  see [DoubleSpreadReader]'s own doc comment for why there are two at all. */
private const val NAV_FRAGMENT_TAG_LEADING = "dexxicon.comic.navigator.leading"
private const val NAV_FRAGMENT_TAG_TRAILING = "dexxicon.comic.navigator.trailing"

/** How long a page turn's own settle animation runs, roughly, before the new page's bitmap
 * is stable enough to analyse for panels. */
private const val PANEL_DETECTION_SETTLE_DELAY_MS = 120L

/**
 * Readium never configures this, so its comic pager defaults to 1 — only one page ahead is
 * pre-created and decoded at a time. Flipping faster than that refills shows a page that
 * hasn't finished loading yet (a black flash). Raised once the navigator is ready — see
 * [findViewPager].
 */
private const val COMIC_PAGE_PREFETCH_LIMIT = 4

/**
 * The comic reader's native embed point (Phase 4 of #183) — everything genuinely tied to
 * Readium's `ImageNavigatorFragment` and Android internals (the fragment itself, the page-turn
 * drag gesture, Smart Zoom's `Bitmap`-based panel detection and its `PhotoView`-reflection
 * zoom-lock) lives here; the chrome itself (top bar, page slider, settings sheet minus Smart
 * Zoom) is the shared `ComicReaderScreen` this composable wraps. Smart Zoom stays Android-only
 * — see that shared screen's own doc comment for why — appended via its `extraSettings` slot.
 */
@Composable
fun ComicReaderScreen(
    onBack: () -> Unit,
    viewModel: ComicReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    when (val s = state) {
        is ComicReaderState.Loading -> SharedComicReaderScreen(ComicReaderUiState.Loading, onBack)
        is ComicReaderState.Error -> SharedComicReaderScreen(ComicReaderUiState.Error(s.message), onBack)
        is ComicReaderState.Ready -> ReaderContent(
            state = s,
            onBack = onBack,
            onLocator = viewModel::onLocatorChanged,
            preferences = preferences,
            onUpdatePreferences = viewModel::updatePreferences,
        )
    }
}

/**
 * Owns everything that has to survive a live switch between single- and double-spread mode
 * (issue #188) — the current page, and the locator either mode's navigator(s) should resume
 * from — and otherwise just wires the shared chrome to whichever of [SingleSpreadReader]/
 * [DoubleSpreadReader] is currently showing. [ReaderPageLayout.AUTO] flips between them live as
 * the viewport crosses the same `>= 720dp` threshold EPUB's own "Page layout" setting already
 * uses ([net.dexxicon.reader.feature.reader.epub.EpubReaderScreen]'s `wideViewport`), so a
 * device fold/unfold mid-read has to hand off cleanly rather than reopening at the book's
 * original saved position.
 */
@Composable
private fun ReaderContent(
    state: ComicReaderState.Ready,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
    preferences: ReaderDisplayPreferences,
    onUpdatePreferences: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
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

    val wideViewport = LocalConfiguration.current.screenWidthDp.dp >= 720.dp
    // issue #204 — DOUBLE used to force double-spread at any width, even a phone-narrow one
    // where it's unusably cramped. The settings sheet now disables "Two-page" below 720dp to
    // match, but a screen can still narrow below that *while* DOUBLE is already the saved
    // preference (e.g. unfolding a foldable and folding it back), so this falls back to
    // single-page the same way AUTO would rather than trust the stale preference alone.
    val isDoubleSpread = when (preferences.pageLayout) {
        ReaderPageLayout.AUTO -> wideViewport
        ReaderPageLayout.SINGLE -> false
        ReaderPageLayout.DOUBLE -> wideViewport
    }

    // The authoritative resume page, resolved once up front by the ViewModel — see
    // [ComicReaderState.Ready.initialPage]'s own doc comment for why [state.initialLocator]
    // alone isn't reliable for this. Only ever used as a fallback below: once either mode's
    // own navigator is live, its real `currentLocator` position takes over.
    val fallbackPage = resumePageFor(state)

    var chromeVisible by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(fallbackPage) }
    // Seeds whichever mode is mounted (or re-mounted, on a live single/double switch) — kept
    // current from both modes' own locator flows so a switch resumes exactly where the reader
    // was, not back at the book's originally saved position.
    var resumeLocator by remember { mutableStateOf(state.initialLocator) }
    // The bottom slider drives navigation through this rather than calling into either mode
    // directly, since only one of them is mounted at a time and either could be swapped out
    // mid-drag by a live layout change.
    var jumpRequest by remember { mutableStateOf<Int?>(null) }

    fun goToPage(target: Int) {
        val clamped = target.coerceIn(1, state.pageCount)
        page = clamped
        jumpRequest = clamped
    }

    fun onModeLocatorChanged(locator: Locator) {
        resumeLocator = locator
        locator.locations.position?.let { page = it }
        onLocator(locator)
    }

    SharedComicReaderScreen(
        state = ComicReaderUiState.Ready(title = state.title, pageCount = state.pageCount),
        onBack = onBack,
        chromeVisible = chromeVisible,
        preferences = preferences,
        onUpdatePreferences = onUpdatePreferences,
        tapNavigationEnabled = !preferences.comicSmartZoom && !isDoubleSpread,
        tapNavigationDisabledReason = when {
            isDoubleSpread -> "Off in Two-page layout — swipe to turn pages."
            preferences.comicSmartZoom -> "Off while Smart zoom is on — swipe to step through panels instead."
            else -> null
        },
        isDoubleSpread = isDoubleSpread,
        canUseDoubleSpread = wideViewport,
        currentPage = page,
        onGoToPage = ::goToPage,
        extraSettings = {
            SmartZoomSetting(
                smartZoom = preferences.comicSmartZoom,
                onToggleSmartZoom = { enabled -> onUpdatePreferences { it.copy(comicSmartZoom = enabled) } },
                enabled = !isDoubleSpread,
                disabledReason = if (isDoubleSpread) {
                    "Off in Two-page layout — panel detection only looks at one page at a time."
                } else {
                    null
                },
            )
        },
        readerContent = {
            if (isDoubleSpread) {
                DoubleSpreadReader(
                    state = state,
                    fragmentManager = fragmentManager,
                    preferences = preferences,
                    initialLocator = resumeLocator,
                    fallbackPage = fallbackPage,
                    jumpRequest = jumpRequest,
                    onJumpHandled = { jumpRequest = null },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onLocatorChanged = ::onModeLocatorChanged,
                )
            } else {
                SingleSpreadReader(
                    state = state,
                    fragmentManager = fragmentManager,
                    preferences = preferences,
                    initialLocator = resumeLocator,
                    fallbackPage = fallbackPage,
                    jumpRequest = jumpRequest,
                    onJumpHandled = { jumpRequest = null },
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onLocatorChanged = ::onModeLocatorChanged,
                )
            }
        },
    )
}

/**
 * One page, full-width — today's original reading mode, unchanged in behaviour: Smart Zoom's
 * panel stepping, the PhotoView zoom-lock/Fit-Width reflection helpers, and edge-tap navigation
 * all still work exactly as before. Only how it's wired to the outside world changed for #188:
 * [initialLocator] (not always [ComicReaderState.Ready.initialLocator]) seeds a fresh navigator
 * so a live switch from double-spread resumes here rather than at the book's original saved
 * position, and page jumps arrive via [jumpRequest] rather than an owned `goToPage`.
 */
@Composable
private fun SingleSpreadReader(
    state: ComicReaderState.Ready,
    fragmentManager: FragmentManager,
    preferences: ReaderDisplayPreferences,
    initialLocator: Locator?,
    fallbackPage: Int,
    jumpRequest: Int?,
    onJumpHandled: () -> Unit,
    onToggleChrome: () -> Unit,
    onLocatorChanged: (Locator) -> Unit,
) {
    var navigator by remember { mutableStateOf<ImageNavigatorFragment?>(null) }
    var pageView by remember { mutableStateOf<View?>(null) }
    var page by remember { mutableIntStateOf(initialLocator?.locations?.position ?: fallbackPage) }
    val tapNavEnabled by rememberUpdatedState(preferences.tapNavigation)
    val smartZoomEnabled by rememberUpdatedState(preferences.comicSmartZoom)
    val rtlEnabled by rememberUpdatedState(preferences.comicRightToLeft)

    // Smart zoom ("guided view"): the current page's detected panels, in reading order, and
    // which one (if any) is framed right now. -1 means "showing the whole page".
    var panels by remember { mutableStateOf<List<RectF>>(emptyList()) }
    var panelIndex by remember { mutableIntStateOf(-1) }
    var arrivingForward by remember { mutableStateOf(true) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(smartZoomEnabled) {
        if (!smartZoomEnabled) {
            panels = emptyList()
            panelIndex = -1
        }
    }

    // `Scaffold` subcomposes its content (this screen's `readerContent`, where the
    // `AndroidView` below actually adds the fragment) at *measure* time, which can happen
    // before a `DisposableEffect`'s own effect body has run — installing the factory in a
    // `remember` instead guarantees it's set during composition, strictly before anything
    // composed later in this function (including that deferred subcomposition) can run.
    remember(state.publication) {
        fragmentManager.fragmentFactory = ImageNavigatorFragment.createFactory(
            publication = state.publication,
            initialLocator = initialLocator,
        )
    }
    DisposableEffect(state.publication) {
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
            navigator = fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG) as? ImageNavigatorFragment
            if (navigator == null) kotlinx.coroutines.delay(50)
        }
    }

    LaunchedEffect(navigator) {
        if (navigator != null) {
            findViewPager(pageView)?.offscreenPageLimit = COMIC_PAGE_PREFETCH_LIMIT
        }
    }

    // Wraps the real navigator so "forward"/"backward" step through the current page's
    // panels first (smart zoom), falling through to a real page turn once they're
    // exhausted — everything below that would otherwise call `navigator` directly
    // (edge taps, the drag gesture) goes through this instead.
    val panelNavigator: PanelSteppingNavigator? = remember(navigator) {
        navigator?.let { nav ->
            PanelSteppingNavigator(
                delegate = nav,
                smartZoomEnabled = { smartZoomEnabled },
                panelCount = { panels.size },
                panelIndex = { panelIndex },
                stepTo = { panelIndex = it },
                onRealPageTurn = { forward -> arrivingForward = forward },
            )
        }
    }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { locator ->
            onLocatorChanged(locator)
            page = locator.locations.position ?: 1
            panels = emptyList()
            panelIndex = -1
            if (smartZoomEnabled) {
                val forward = arrivingForward
                kotlinx.coroutines.delay(PANEL_DETECTION_SETTLE_DELAY_MS)
                val detected = pageView?.pageSnapshot()?.asAndroidBitmap()?.let { bitmap ->
                    withContext(Dispatchers.Default) {
                        runCatching { ComicPanelDetector.detectPanels(bitmap, rtlEnabled) }
                            .getOrDefault(emptyList())
                    }
                } ?: emptyList()
                panels = detected
                panelIndex = if (detected.isNotEmpty()) {
                    if (forward) 0 else detected.lastIndex
                } else {
                    -1
                }
            }
        }
    }

    LaunchedEffect(jumpRequest) {
        val target = jumpRequest ?: return@LaunchedEffect
        navigator?.let { nav ->
            state.publication.readingOrder.getOrNull(target - 1)?.let { link -> nav.go(link, false) }
        }
        onJumpHandled()
    }

    DisposableEffect(panelNavigator) {
        val nav = panelNavigator
        val listener = nav?.let {
            EdgeTapNavigator(
                navigator = it,
                viewWidth = { it.publicationView.width },
                onCenterTap = onToggleChrome,
                // Edge-tap zones are measured against the page's laid-out width, which no
                // longer matches what's actually on screen once smart zoom has framed a
                // panel — a tap meant for "next panel" can miss the zone entirely or catch
                // the wrong one. Force every tap to just toggle chrome while it's active;
                // swipe (unaffected by this) is the reliable way to step through panels.
                enabled = { tapNavEnabled && !smartZoomEnabled },
                rightToLeft = { rtlEnabled },
            ).also { l -> it.addInputListener(l) }
        }
        onDispose { if (nav != null && listener != null) nav.removeInputListener(listener) }
    }

    // While a panel is framed, the underlying page view's own pinch-zoom would fight the
    // smart-zoom transform below — hand it back once we return to the full page.
    DisposableEffect(pageView, panelIndex >= 0) {
        setPagePhotoViewsZoomable(pageView, zoomable = panelIndex < 0)
        onDispose { }
    }

    // issue #183: "Fit"/"Width" — each page is a brand-new PhotoView (Readium recreates one
    // per page), so this has to re-apply on every page change, not just when the setting
    // itself changes. [setPagePhotoViewsFitWidth] is a no-op until that fresh PhotoView has
    // both laid out *and* finished decoding its image — retried on a short poll rather than a
    // single fixed delay, since a slow (cold-cache/network) decode can outlast any one delay.
    LaunchedEffect(pageView, page, preferences.fitMode) {
        val fitWidth = preferences.fitMode == ReaderFitMode.PAGE_WIDTH
        repeat(20) {
            kotlinx.coroutines.delay(50)
            if (setPagePhotoViewsFitWidth(pageView, fitWidth)) return@LaunchedEffect
        }
    }

    val targetPanel = panels.getOrNull(panelIndex)
    val (targetScale, targetTx, targetTy) = remember(targetPanel, boxSize) {
        panelZoomTransform(targetPanel, boxSize)
    }
    val zoomScale by animateFloatAsState(targetScale, label = "comicPanelZoomScale")
    val zoomTx by animateFloatAsState(targetTx, label = "comicPanelZoomTranslationX")
    val zoomTy by animateFloatAsState(targetTy, label = "comicPanelZoomTranslationY")

    val pageTurn = rememberPageTurnState()
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            // Drag the page with your finger; release past the sensitivity threshold to
            // turn it, a shorter drag slides back. Readium's image pager can't paginate
            // itself embedded in Compose, so the gesture drives the navigator directly.
            .pageTurnGesture(
                state = pageTurn,
                enabled = true,
                commitFraction = preferences.swipeSensitivity.commitFraction,
                snapshot = { pageView?.pageSnapshot() },
                onTurn = { forward ->
                    // goForward()/goBackward() pick +1 vs -1 from the system locale, not
                    // this book's own reading direction — flip which one a physically
                    // "forward" gesture calls so manga (right-to-left) actually turns the
                    // right way instead of just running Readium's (always-LTR-here) default.
                    val actuallyForward = forward != rtlEnabled
                    (
                        if (actuallyForward) panelNavigator?.goForward(false)
                        else panelNavigator?.goBackward(false)
                        ) == true
                },
                canTurn = { forward, touchX, touchY ->
                    pageView?.let { canTurnPastZoom(it, forward, touchX, touchY) } ?: true
                },
                atBoundary = { forward ->
                    // Same RTL inversion as onTurn — "forward" here is the raw physical
                    // gesture, not yet translated to reading-order direction.
                    val actuallyForward = forward != rtlEnabled
                    if (actuallyForward) page >= state.pageCount else page <= 1
                },
            ),
    ) {
        ComicPageContainer(
            fragmentManager = fragmentManager,
            tag = NAV_FRAGMENT_TAG,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoomScale
                scaleY = zoomScale
                translationX = zoomTx
                translationY = zoomTy
            },
            onViewCreated = { pageView = it },
        )
    }
}

/**
 * Two pages side by side (issue #188). Readium's `ImageNavigatorFragment` has no built-in
 * spread/dual-page support (confirmed against the real runtime jar — its whole public surface
 * is single-page `go`/`goForward`/`goBackward`), so this runs two independent instances, one
 * per half of the screen, and keeps them in sync by hand: [leadingPage] is always the lower-
 * numbered page of the currently visible pair, pages pair up sequentially — (1,2), (3,4), … —
 * with no cover-offset special case, and [NAV_FRAGMENT_TAG_TRAILING] shows [leadingPage] again
 * (rather than a stale page, or going blank) on a trailing book with an odd page count, where
 * the final pair has no second page.
 *
 * A `FragmentManager` only supports one `fragmentFactory`, so both fragments are created from
 * the *same* factory — meaning right after creation both show whatever page that factory's own
 * `initialLocator` pointed to, until [goToSpread] immediately nudges each to its real target via
 * `.go()`.
 *
 * Smart Zoom is single-page-only (its panel detection runs against one page's own bitmap) and
 * is disabled while this mode is active — see [SmartZoomSetting]'s caller — so there's no
 * panel-stepping navigator here, just a plain forward/backward spread turn. Edge-tap navigation
 * is disabled for the same reason tap edges don't cleanly map onto two independent fragments'
 * touch targets; [EdgeTapNavigator] is still reused with `enabled = { false }` purely so any tap
 * on either page toggles chrome, the one behaviour it and its input-listener plumbing already
 * give us for free.
 */
@Composable
private fun DoubleSpreadReader(
    state: ComicReaderState.Ready,
    fragmentManager: FragmentManager,
    preferences: ReaderDisplayPreferences,
    initialLocator: Locator?,
    fallbackPage: Int,
    jumpRequest: Int?,
    onJumpHandled: () -> Unit,
    onToggleChrome: () -> Unit,
    onLocatorChanged: (Locator) -> Unit,
) {
    var navigatorA by remember { mutableStateOf<ImageNavigatorFragment?>(null) }
    var navigatorB by remember { mutableStateOf<ImageNavigatorFragment?>(null) }
    var pageViewA by remember { mutableStateOf<View?>(null) }
    var pageViewB by remember { mutableStateOf<View?>(null) }
    var leadingPage by remember {
        mutableIntStateOf(leadingPageFor(initialLocator?.locations?.position ?: fallbackPage))
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // Each page's own width/height ratio, read straight off its `Drawable.intrinsicWidth/
    // Height` (plain `ImageView` API, no reflection needed just to *read* this — unlike the
    // PhotoView-specific zoom manipulation single-page mode uses). Seeded to a typical
    // portrait comic/manga page ratio so the very first layout pass — before either page has
    // actually decoded — isn't zero-width; corrected the moment each side's real image loads,
    // and re-measured on every page turn in case a page genuinely differs.
    var pageAspectA by remember { mutableStateOf(0.7071f) }
    var pageAspectB by remember { mutableStateOf(0.7071f) }
    val rtlEnabled by rememberUpdatedState(preferences.comicRightToLeft)
    val pageCount = state.pageCount

    fun goToSpread(leading: Int) {
        val clampedLeading = leading.coerceIn(1, pageCount)
        val trailing = if (clampedLeading + 1 <= pageCount) clampedLeading + 1 else clampedLeading
        leadingPage = clampedLeading
        navigatorA?.let { nav -> state.publication.readingOrder.getOrNull(clampedLeading - 1)?.let { nav.go(it, false) } }
        navigatorB?.let { nav -> state.publication.readingOrder.getOrNull(trailing - 1)?.let { nav.go(it, false) } }
    }

    // See the equivalent `remember` in [SingleSpreadReader] — `Scaffold` subcomposes
    // `readerContent` (where these fragments actually get added) at measure time, which a
    // `DisposableEffect`'s own effect body isn't guaranteed to have already run before.
    remember(state.publication) {
        fragmentManager.fragmentFactory = ImageNavigatorFragment.createFactory(
            publication = state.publication,
            initialLocator = initialLocator,
        )
    }
    DisposableEffect(state.publication) {
        onDispose {
            if (!fragmentManager.isStateSaved) {
                listOf(NAV_FRAGMENT_TAG_LEADING, NAV_FRAGMENT_TAG_TRAILING).forEach { tag ->
                    fragmentManager.findFragmentByTag(tag)?.let { frag ->
                        fragmentManager.commit(allowStateLoss = true) { remove(frag) }
                    }
                }
            }
            navigatorA = null
            navigatorB = null
        }
    }

    LaunchedEffect(state.publication) {
        while (navigatorA == null || navigatorB == null) {
            navigatorA = navigatorA ?: fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG_LEADING) as? ImageNavigatorFragment
            navigatorB = navigatorB ?: fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG_TRAILING) as? ImageNavigatorFragment
            if (navigatorA == null || navigatorB == null) kotlinx.coroutines.delay(50)
        }
    }

    LaunchedEffect(navigatorA, navigatorB) {
        if (navigatorA != null && navigatorB != null) {
            goToSpread(leadingPage)
            findViewPager(pageViewA)?.offscreenPageLimit = COMIC_PAGE_PREFETCH_LIMIT
            findViewPager(pageViewB)?.offscreenPageLimit = COMIC_PAGE_PREFETCH_LIMIT
        }
    }

    LaunchedEffect(navigatorA) {
        navigatorA?.currentLocator?.collect { locator ->
            onLocatorChanged(locator)
            leadingPage = leadingPageFor(locator.locations.position ?: leadingPage)
        }
    }

    LaunchedEffect(jumpRequest) {
        val target = jumpRequest ?: return@LaunchedEffect
        goToSpread(leadingPageFor(target))
        onJumpHandled()
    }

    DisposableEffect(navigatorA) {
        val nav = navigatorA
        val listener = nav?.let {
            EdgeTapNavigator(
                navigator = it,
                viewWidth = { it.publicationView.width },
                onCenterTap = onToggleChrome,
                enabled = { false },
            ).also { l -> it.addInputListener(l) }
        }
        onDispose { if (nav != null && listener != null) nav.removeInputListener(listener) }
    }
    DisposableEffect(navigatorB) {
        val nav = navigatorB
        val listener = nav?.let {
            EdgeTapNavigator(
                navigator = it,
                viewWidth = { it.publicationView.width },
                onCenterTap = onToggleChrome,
                enabled = { false },
            ).also { l -> it.addInputListener(l) }
        }
        onDispose { if (nav != null && listener != null) nav.removeInputListener(listener) }
    }

    // No PhotoView zoom trickery needed for the "no seam" fill, unlike single-page's Fit/Width
    // — each container below is sized to match its own page's real aspect ratio exactly, so
    // Readium's own default fit-whole-page already fills it edge-to-edge. Poll-until-ready
    // (not a single fixed delay) because a fresh PhotoView's image can still be decoding well
    // past any one delay.
    LaunchedEffect(pageViewA, leadingPage) {
        repeat(20) {
            kotlinx.coroutines.delay(50)
            val aspect = readPageAspect(pageViewA) ?: return@repeat
            pageAspectA = aspect
            return@LaunchedEffect
        }
    }
    LaunchedEffect(pageViewB, leadingPage) {
        repeat(20) {
            kotlinx.coroutines.delay(50)
            val aspect = readPageAspect(pageViewB) ?: return@repeat
            pageAspectB = aspect
            return@LaunchedEffect
        }
    }

    fun advanceSpread(forward: Boolean): Boolean {
        val newLeading = if (forward) leadingPage + 2 else leadingPage - 2
        if (newLeading < 1 || newLeading > pageCount) return false
        goToSpread(newLeading)
        return true
    }

    // Both containers are sized to their own page's real aspect ratio — never forced to a flat
    // 50/50 split — and the pair is centred as one block: fill height if it then fits the
    // width, else shrink to fit width (still no seam, now letterboxed top/bottom instead of
    // left/right). Exactly how a real book/manga spread reads, and the only way two same-
    // height pages end up flush against each other with nothing in between.
    val boxWidthPx = boxSize.width.toFloat()
    val boxHeightPx = boxSize.height.toFloat()
    val combinedAspect = (pageAspectA + pageAspectB).coerceAtLeast(0.01f)
    val spreadHeightPx = if (boxWidthPx > 0f && boxHeightPx > 0f) {
        minOf(boxHeightPx, boxWidthPx / combinedAspect)
    } else {
        0f
    }
    val widthAPx = spreadHeightPx * pageAspectA
    val widthBPx = spreadHeightPx * pageAspectB
    val leftWidthPx = if (rtlEnabled) widthBPx else widthAPx
    val rightWidthPx = if (rtlEnabled) widthAPx else widthBPx
    val marginXPx = ((boxWidthPx - (leftWidthPx + rightWidthPx)) / 2f).coerceAtLeast(0f)

    val pageTurn = rememberPageTurnState()
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pageTurnGesture(
                state = pageTurn,
                enabled = true,
                commitFraction = preferences.swipeSensitivity.commitFraction,
                snapshot = {
                    val leftView = if (rtlEnabled) pageViewB else pageViewA
                    val rightView = if (rtlEnabled) pageViewA else pageViewB
                    spreadSnapshot(leftView, rightView)
                },
                onTurn = { forward ->
                    val actuallyForward = forward != rtlEnabled
                    advanceSpread(actuallyForward)
                },
                canTurn = { forward, touchX, touchY ->
                    val leftView = if (rtlEnabled) pageViewB else pageViewA
                    val rightView = if (rtlEnabled) pageViewA else pageViewB
                    canTurnPastZoomSpread(
                        leftView, rightView, marginXPx, leftWidthPx, rightWidthPx, forward, touchX, touchY,
                    )
                },
                atBoundary = { forward ->
                    val actuallyForward = forward != rtlEnabled
                    if (actuallyForward) leadingPage + 2 > pageCount else leadingPage <= 1
                },
            ),
    ) {
        val density = LocalDensity.current
        Row(
            Modifier
                .align(Alignment.Center)
                .width(with(density) { (widthAPx + widthBPx).toDp() })
                .height(with(density) { spreadHeightPx.toDp() }),
        ) {
            // First page of the pair sits on the reading direction's leading side — left for
            // LTR, right for RTL/manga — the second page trails it.
            if (rtlEnabled) {
                ComicPageContainer(
                    fragmentManager, NAV_FRAGMENT_TAG_TRAILING,
                    Modifier.width(with(density) { widthBPx.toDp() }).fillMaxHeight(),
                ) { pageViewB = it }
                ComicPageContainer(
                    fragmentManager, NAV_FRAGMENT_TAG_LEADING,
                    Modifier.width(with(density) { widthAPx.toDp() }).fillMaxHeight(),
                ) { pageViewA = it }
            } else {
                ComicPageContainer(
                    fragmentManager, NAV_FRAGMENT_TAG_LEADING,
                    Modifier.width(with(density) { widthAPx.toDp() }).fillMaxHeight(),
                ) { pageViewA = it }
                ComicPageContainer(
                    fragmentManager, NAV_FRAGMENT_TAG_TRAILING,
                    Modifier.width(with(density) { widthBPx.toDp() }).fillMaxHeight(),
                ) { pageViewB = it }
            }
        }
    }
}

/** The lower-numbered page of the sequential pair — (1,2), (3,4), … — that [page] belongs to. */
private fun leadingPageFor(page: Int): Int = ((page - 1) / 2) * 2 + 1

/**
 * [state]'s 1-based resume page, for seeding [SingleSpreadReader]/[DoubleSpreadReader].
 * [ComicReaderState.Ready.initialPage] is the authoritative source — resolved once, up front,
 * by `ComicReaderViewModel`. [ComicReaderState.Ready.initialLocator] itself isn't a reliable
 * fallback for this: when resuming to a specific page it's built via `Publication
 * .locatorFromLink` from a bare `Link`, which sets `href` only — `Locator.locations.position`
 * stays null, unlike a live `currentLocator` emission from an actual mounted navigator.
 */
private fun resumePageFor(state: ComicReaderState.Ready): Int =
    state.initialPage ?: state.initialLocator?.locations?.position ?: 1

/**
 * A `FragmentContainerView` hosting one `ImageNavigatorFragment`, added under [tag] the first
 * time this enters composition — shared by [SingleSpreadReader] (one of these) and
 * [DoubleSpreadReader] (two, side by side). Sizing is entirely the caller's own — [modifier]
 * is applied as-is, with no `fillMaxSize()` appended here, since [DoubleSpreadReader] needs an
 * exact width/height (matching each page's own aspect ratio, not just "half the screen") for
 * its two containers to sit flush with no seam.
 */
@Composable
private fun ComicPageContainer(
    fragmentManager: FragmentManager,
    tag: String,
    modifier: Modifier = Modifier,
    onViewCreated: (View) -> Unit,
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
            if (fragmentManager.findFragmentByTag(tag) == null && !fragmentManager.isStateSaved) {
                fragmentManager.commit {
                    setReorderingAllowed(true)
                    add(container.id, ImageNavigatorFragment::class.java, null, tag)
                }
            }
            container.also(onViewCreated)
        },
        modifier = modifier,
    )
}

/** Scale + translation (in px, about the view's own centre) that frames [panel] — a
 * page-fraction rect — inside a [boxSize]-px viewport. Identity when there's no panel to
 * frame or the viewport hasn't been measured yet. */
private fun panelZoomTransform(panel: RectF?, boxSize: IntSize): Triple<Float, Float, Float> {
    if (panel == null || boxSize.width <= 0 || boxSize.height <= 0) return Triple(1f, 0f, 0f)
    val w = boxSize.width.toFloat()
    val h = boxSize.height.toFloat()
    val panelW = (panel.right - panel.left) * w
    val panelH = (panel.bottom - panel.top) * h
    if (panelW <= 0f || panelH <= 0f) return Triple(1f, 0f, 0f)
    val scale = (minOf(w / panelW, h / panelH) * 0.94f).coerceIn(1f, 3f)
    val panelCenterX = (panel.left + panel.right) / 2f * w
    val panelCenterY = (panel.top + panel.bottom) / 2f * h
    val translationX = scale * (w / 2f - panelCenterX)
    val translationY = scale * (h / 2f - panelCenterY)
    return Triple(scale, translationX, translationY)
}

/** Android's one settings row the shared chrome doesn't know about — see
 * [SharedComicReaderScreen]'s doc comment for why Smart Zoom stays out of `:shared`.
 * [enabled]/[disabledReason] mirror the shared chrome's own tap-navigation-disabled pattern —
 * used to grey this out while double-spread mode is active (see the caller). */
@Composable
private fun SmartZoomSetting(
    smartZoom: Boolean,
    onToggleSmartZoom: (Boolean) -> Unit,
    enabled: Boolean = true,
    disabledReason: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Smart zoom",
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                disabledReason
                    ?: "Step through each panel in order. Falls back to the full page when " +
                        "panels can't be detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = smartZoom && enabled, onCheckedChange = onToggleSmartZoom, enabled = enabled)
    }
}

/**
 * The `ViewPager` Readium's comic navigator pages through, wherever it sits under [root] —
 * unlike `PhotoView`, this one's a real compile-time dependency (`R2ViewPager` extends the
 * plain AndroidX `ViewPager`), so no reflection is needed once it's found.
 */
private fun findViewPager(root: View?): ViewPager? {
    if (root == null) return null
    if (root is ViewPager) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findViewPager(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

/**
 * Readium's per-page image view (`PhotoView`, pulled in transitively) fights our own
 * smart-zoom transform with its own pinch-to-zoom unless it's turned off while a panel is
 * framed. There's no compile-time dependency on that class, so it's found by name and
 * toggled via reflection — best-effort: any failure here just leaves pinch-zoom as is.
 */
private fun setPagePhotoViewsZoomable(root: View?, zoomable: Boolean) {
    if (root == null) return
    findPhotoViews(root).forEach { view ->
        runCatching {
            view.javaClass.getMethod("setZoomable", Boolean::class.javaPrimitiveType)
                .invoke(view, zoomable)
        }
    }
}

/**
 * PhotoView's own scale type stays fixed at whatever Readium's `ImageNavigatorFragment`
 * configures it with (fit-whole-page) — there's no navigator-level API to change it, so
 * "Width" is applied on top of that base fit as a relative zoom instead: `getMinimumScale()`
 * is PhotoView's own scale unit for "fit, unzoomed" (usually `1f`), and the ratio between the
 * image's own fit-to-width and fit-to-whole-page pixel scales tells us how much further to
 * zoom from there. When width is already the fit's constraining dimension (the common case
 * for a portrait comic page in a portrait-ish viewport), that ratio is 1 — already fit to
 * width by default, nothing to do. Reflection, same reasoning as [setPagePhotoViewsZoomable]:
 * no compile-time dependency on PhotoView from this module.
 */
/** Returns true once at least one found `PhotoView` had a real, laid-out drawable to scale —
 * callers poll on that rather than trusting a single fixed delay (see the call sites). */
private fun setPagePhotoViewsFitWidth(root: View?, fitWidth: Boolean): Boolean {
    if (root == null) return false
    val views = findPhotoViews(root)
    if (views.isEmpty()) return false
    var appliedAny = false
    views.forEach { view ->
        runCatching {
            val setScale = view.javaClass.getMethod(
                "setScale", Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            val minScale = view.javaClass.getMethod("getMinimumScale").invoke(view) as Float
            if (!fitWidth) {
                setScale.invoke(view, minScale, false)
                appliedAny = true
                return@runCatching
            }
            val drawable = (view as? android.widget.ImageView)?.drawable ?: return@runCatching
            val imgW = drawable.intrinsicWidth.toFloat()
            val imgH = drawable.intrinsicHeight.toFloat()
            if (imgW <= 0f || imgH <= 0f || view.width <= 0 || view.height <= 0) return@runCatching
            val fitCenterScale = minOf(view.width / imgW, view.height / imgH)
            val fitWidthScale = view.width / imgW
            if (fitCenterScale <= 0f) return@runCatching
            setScale.invoke(view, minScale * (fitWidthScale / fitCenterScale), false)
            appliedAny = true
        }
    }
    return appliedAny
}

/**
 * [root]'s first `PhotoView` with an actual decoded image, as a plain width/height ratio —
 * used to size [DoubleSpreadReader]'s two containers to each page's own real shape (see
 * [readPageAspect]'s callers) rather than a flat 50/50 split. Plain `ImageView` API, unlike
 * [setPagePhotoViewsFitWidth]'s PhotoView-specific zoom reflection — reading intrinsic size
 * needs none of that.
 */
private fun readPageAspect(root: View?): Float? {
    if (root == null) return null
    findPhotoViews(root).forEach { view ->
        val drawable = (view as? android.widget.ImageView)?.drawable ?: return@forEach
        val w = drawable.intrinsicWidth.toFloat()
        val h = drawable.intrinsicHeight.toFloat()
        if (w > 0f && h > 0f) return w / h
    }
    return null
}

/**
 * True when a swipe starting at ([touchX], [touchY]) — in [root]'s own coordinate space,
 * same as the enclosing `pageTurnGesture`'s pointer events — should turn the page rather
 * than pan a pinch-zoomed panel further in [forward]'s direction. The pager preloads
 * neighbour pages off-screen, so this hit-tests to the *touched* `PhotoView` rather than
 * just taking the first one found.
 *
 * A `PhotoView` reports [getDisplayRect] flush with its own edges once there's no more
 * image left to reveal that way — true at rest scale (nothing to pan at all) just as much
 * as when zoomed in and panned as far as it goes, so this needs no separate "is it zoomed"
 * check. Defaults to true (never blocks a turn) if the view can't be found or queried.
 */
private fun canTurnPastZoom(root: View, forward: Boolean, touchX: Float, touchY: Float): Boolean {
    val photoView = findPhotoViews(root).firstOrNull { it.localBoundsIn(root).contains(touchX, touchY) }
        ?: return true
    return runCatching {
        val rect = photoView.javaClass.getMethod("getDisplayRect").invoke(photoView) as? RectF
            ?: return@runCatching true
        val slack = 2f // px of float drift after a pan/fling settles
        if (forward) rect.right <= photoView.width + slack else rect.left >= -slack
    }.getOrDefault(true)
}

/**
 * Double-spread version of [canTurnPastZoom]: picks whichever of [leftView]/[rightView] the
 * touch actually landed in — [touchX]/[touchY] are in the enclosing spread `Box`'s coordinate
 * space. Unlike a flat 50/50 split, the two containers can be different widths (each sized to
 * its own page's aspect ratio) and centred with a margin on either side, so the left
 * container's own span is `[marginX, marginX + leftWidth)`, not `[0, boxWidth/2)` — a touch
 * landing in either outer margin (nothing rendered there) hits neither and just falls through
 * to the default "allow the turn".
 */
private fun canTurnPastZoomSpread(
    leftView: View?,
    rightView: View?,
    marginX: Float,
    leftWidth: Float,
    rightWidth: Float,
    forward: Boolean,
    touchX: Float,
    touchY: Float,
): Boolean {
    val boundaryX = marginX + leftWidth
    val view = when {
        touchX < marginX -> null
        touchX < boundaryX -> leftView
        touchX < boundaryX + rightWidth -> rightView
        else -> null
    }
    val localX = when (view) {
        leftView -> touchX - marginX
        rightView -> touchX - boundaryX
        else -> touchX
    }
    return view?.let { canTurnPastZoom(it, forward, localX, touchY) } ?: true
}

private fun findPhotoViews(root: View): List<View> {
    val found = mutableListOf<View>()
    val queue = ArrayDeque<View>()
    queue.add(root)
    while (queue.isNotEmpty()) {
        val view = queue.removeFirst()
        if (view.javaClass.simpleName == "PhotoView") found += view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
    }
    return found
}

/** [this]'s bounds in [ancestor]'s own coordinate space — window coordinates cancel out
 * however many plain `ViewGroup`s (the pager, its page containers, …) sit in between. */
private fun View.localBoundsIn(ancestor: View): RectF {
    val loc = IntArray(2).also { getLocationInWindow(it) }
    val ancestorLoc = IntArray(2).also { ancestor.getLocationInWindow(it) }
    val left = (loc[0] - ancestorLoc[0]).toFloat()
    val top = (loc[1] - ancestorLoc[1]).toFloat()
    return RectF(left, top, left + width, top + height)
}

/** A still of [left] and [right] drawn side by side, sized to their combined bounds — the
 * double-spread equivalent of [net.dexxicon.reader.core.designsystem.component.pageSnapshot],
 * which only captures one view. Null if either isn't laid out yet. */
private fun spreadSnapshot(left: View?, right: View?): ImageBitmap? {
    if (left == null || right == null) return null
    if (left.width <= 0 || left.height <= 0 || right.width <= 0 || right.height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(
            left.width + right.width,
            maxOf(left.height, right.height),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        left.draw(canvas)
        canvas.translate(left.width.toFloat(), 0f)
        right.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
