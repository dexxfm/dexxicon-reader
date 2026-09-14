package net.dexxicon.reader.feature.reader.comic

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderFitMode
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
    var navigator by remember { mutableStateOf<ImageNavigatorFragment?>(null) }
    var pageView by remember { mutableStateOf<View?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(1) }
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

    DisposableEffect(state.publication) {
        fragmentManager.fragmentFactory = ImageNavigatorFragment.createFactory(
            publication = state.publication,
            initialLocator = state.initialLocator,
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
            onLocator(locator)
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

    DisposableEffect(panelNavigator) {
        val nav = panelNavigator
        val listener = nav?.let {
            EdgeTapNavigator(
                navigator = it,
                viewWidth = { it.publicationView.width },
                onCenterTap = { chromeVisible = !chromeVisible },
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
    // itself changes. The short delay gives that fresh PhotoView one layout pass to measure
    // its own real size first — [setPagePhotoViewsFitWidth] is a no-op otherwise.
    LaunchedEffect(pageView, page, preferences.fitMode) {
        kotlinx.coroutines.delay(50)
        setPagePhotoViewsFitWidth(pageView, fitWidth = preferences.fitMode == ReaderFitMode.PAGE_WIDTH)
    }

    fun goToPage(target: Int) {
        // Optimistic local update — same reason PDF's own goToPage does this: the slider
        // should track the drag instantly, not wait on the navigator's currentLocator flow.
        page = target
        navigator?.let { nav ->
            state.publication.readingOrder.getOrNull(target - 1)?.let { link -> nav.go(link, false) }
        }
    }

    val targetPanel = panels.getOrNull(panelIndex)
    val (targetScale, targetTx, targetTy) = remember(targetPanel, boxSize) {
        panelZoomTransform(targetPanel, boxSize)
    }
    val zoomScale by animateFloatAsState(targetScale, label = "comicPanelZoomScale")
    val zoomTx by animateFloatAsState(targetTx, label = "comicPanelZoomTranslationX")
    val zoomTy by animateFloatAsState(targetTy, label = "comicPanelZoomTranslationY")

    SharedComicReaderScreen(
        state = ComicReaderUiState.Ready(title = state.title, pageCount = state.pageCount),
        onBack = onBack,
        chromeVisible = chromeVisible,
        preferences = preferences,
        onUpdatePreferences = onUpdatePreferences,
        tapNavigationEnabled = !smartZoomEnabled,
        tapNavigationDisabledReason = if (smartZoomEnabled) {
            "Off while Smart zoom is on — swipe to step through panels instead."
        } else {
            null
        },
        currentPage = page,
        onGoToPage = ::goToPage,
        extraSettings = {
            SmartZoomSetting(
                smartZoom = preferences.comicSmartZoom,
                onToggleSmartZoom = { enabled -> onUpdatePreferences { it.copy(comicSmartZoom = enabled) } },
            )
        },
        readerContent = {
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
                                add(container.id, ImageNavigatorFragment::class.java, null, NAV_FRAGMENT_TAG)
                            }
                        }
                        container.also { pageView = it }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoomScale
                            scaleY = zoomScale
                            translationX = zoomTx
                            translationY = zoomTy
                        },
                )
            }
        },
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
 * [SharedComicReaderScreen]'s doc comment for why Smart Zoom stays out of `:shared`. */
@Composable
private fun SmartZoomSetting(smartZoom: Boolean, onToggleSmartZoom: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Smart zoom")
            Text(
                "Step through each panel in order. Falls back to the full page when " +
                    "panels can't be detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = smartZoom, onCheckedChange = onToggleSmartZoom)
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
private fun setPagePhotoViewsFitWidth(root: View?, fitWidth: Boolean) {
    if (root == null) return
    findPhotoViews(root).forEach { view ->
        runCatching {
            val setScale = view.javaClass.getMethod(
                "setScale", Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            val minScale = view.javaClass.getMethod("getMinimumScale").invoke(view) as Float
            if (!fitWidth) {
                setScale.invoke(view, minScale, false)
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
        }
    }
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
