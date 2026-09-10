package net.dexxicon.reader.feature.reader.comic

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.designsystem.component.pageSnapshot
import net.dexxicon.reader.core.designsystem.component.pageTurnGesture
import net.dexxicon.reader.core.designsystem.component.rememberPageTurnState
import net.dexxicon.reader.core.reader.ComicPanelDetector
import net.dexxicon.reader.core.reader.EdgeTapNavigator
import net.dexxicon.reader.core.reader.PanelSteppingNavigator
import net.dexxicon.reader.core.reader.ReaderSwipeSensitivity
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.comic.navigator"

/** How long a page turn's own settle animation runs, roughly, before the new page's bitmap
 * is stable enough to analyse for panels. */
private const val PANEL_DETECTION_SETTLE_DELAY_MS = 120L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    onBack: () -> Unit,
    viewModel: ComicReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tapNavigation by viewModel.tapNavigation.collectAsStateWithLifecycle()
    val swipeSensitivity by viewModel.swipeSensitivity.collectAsStateWithLifecycle()
    val smartZoom by viewModel.smartZoom.collectAsStateWithLifecycle()
    val rightToLeft by viewModel.rightToLeft.collectAsStateWithLifecycle()

    when (val s = state) {
        is ComicReaderState.Loading -> Center { CircularProgressIndicator() }
        is ComicReaderState.Error -> Center {
            Text(
                s.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        is ComicReaderState.Ready -> ReaderContent(
            state = s,
            onBack = onBack,
            onLocator = viewModel::onLocatorChanged,
            tapNavigation = tapNavigation,
            onToggleTapNavigation = viewModel::setTapNavigation,
            swipeSensitivity = swipeSensitivity,
            onSwipeSensitivity = viewModel::setSwipeSensitivity,
            smartZoom = smartZoom,
            onToggleSmartZoom = viewModel::setSmartZoom,
            rightToLeft = rightToLeft,
            onToggleRightToLeft = viewModel::setRightToLeft,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: ComicReaderState.Ready,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
    tapNavigation: Boolean,
    onToggleTapNavigation: (Boolean) -> Unit,
    swipeSensitivity: ReaderSwipeSensitivity,
    onSwipeSensitivity: (ReaderSwipeSensitivity) -> Unit,
    smartZoom: Boolean,
    onToggleSmartZoom: (Boolean) -> Unit,
    rightToLeft: Boolean,
    onToggleRightToLeft: (Boolean) -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    if (activity == null) {
        Center { Text("The reader needs a FragmentActivity host") }
        return
    }
    val fragmentManager = activity.supportFragmentManager
    var navigator by remember { mutableStateOf<ImageNavigatorFragment?>(null) }
    var pageView by remember { mutableStateOf<View?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    val tapNavEnabled by rememberUpdatedState(tapNavigation)
    val smartZoomEnabled by rememberUpdatedState(smartZoom)
    val rtlEnabled by rememberUpdatedState(rightToLeft)

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
                enabled = { tapNavEnabled },
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

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (chromeVisible) {
                TopAppBar(
                    title = { Text(state.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Reading settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (chromeVisible && state.pageCount > 1) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Page $page of ${state.pageCount}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = page.coerceIn(1, state.pageCount).toFloat(),
                        onValueChange = { v ->
                            val target = v.toInt().coerceIn(1, state.pageCount)
                            page = target
                            navigator?.let { nav ->
                                state.publication.readingOrder.getOrNull(target - 1)?.let { link ->
                                    nav.go(link, false)
                                }
                            }
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

        // Smart-zoom transform: scale + centre on the framed panel, or identity for the
        // whole page. Computed from the *previous* frame's box size, which is fine — it
        // only changes on rotation/fold, and the transform re-settles the next frame.
        val targetPanel = panels.getOrNull(panelIndex)
        val (targetScale, targetTx, targetTy) = remember(targetPanel, boxSize) {
            panelZoomTransform(targetPanel, boxSize)
        }
        val zoomScale by animateFloatAsState(targetScale, label = "comicPanelZoomScale")
        val zoomTx by animateFloatAsState(targetTx, label = "comicPanelZoomTranslationX")
        val zoomTy by animateFloatAsState(targetTy, label = "comicPanelZoomTranslationY")

        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { boxSize = it }
                // Drag the page with your finger; release past the sensitivity threshold to
                // turn it, a shorter drag slides back. Readium's image pager can't paginate
                // itself embedded in Compose, so the gesture drives the navigator directly.
                .pageTurnGesture(
                    state = pageTurn,
                    enabled = true,
                    commitFraction = swipeSensitivity.commitFraction,
                    snapshot = { pageView?.pageSnapshot() },
                    onTurn = { forward ->
                        (
                            if (forward) panelNavigator?.goForward(false)
                            else panelNavigator?.goBackward(false)
                            ) == true
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
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ComicSettings(
                tapNavigation = tapNavigation,
                onToggleTapNavigation = onToggleTapNavigation,
                swipeSensitivity = swipeSensitivity,
                onSwipeSensitivity = onSwipeSensitivity,
                smartZoom = smartZoom,
                onToggleSmartZoom = onToggleSmartZoom,
                rightToLeft = rightToLeft,
                onToggleRightToLeft = onToggleRightToLeft,
            )
        }
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicSettings(
    tapNavigation: Boolean,
    onToggleTapNavigation: (Boolean) -> Unit,
    swipeSensitivity: ReaderSwipeSensitivity,
    onSwipeSensitivity: (ReaderSwipeSensitivity) -> Unit,
    smartZoom: Boolean,
    onToggleSmartZoom: (Boolean) -> Unit,
    rightToLeft: Boolean,
    onToggleRightToLeft: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding()) {
        Text("Page-turn swipe", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReaderSwipeSensitivity.entries.forEach { s ->
                FilterChip(
                    selected = swipeSensitivity == s,
                    onClick = { onSwipeSensitivity(s) },
                    label = { Text(s.label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tap edges to turn pages", Modifier.weight(1f))
            Switch(checked = tapNavigation, onCheckedChange = onToggleTapNavigation)
        }
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
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Right-to-left (manga)", Modifier.weight(1f))
            Switch(checked = rightToLeft, onCheckedChange = onToggleRightToLeft)
        }
    }
}

/**
 * Readium's per-page image view (`PhotoView`, pulled in transitively) fights our own
 * smart-zoom transform with its own pinch-to-zoom unless it's turned off while a panel is
 * framed. There's no compile-time dependency on that class, so it's found by name and
 * toggled via reflection — best-effort: any failure here just leaves pinch-zoom as is.
 */
private fun setPagePhotoViewsZoomable(root: View?, zoomable: Boolean) {
    if (root == null) return
    val queue = ArrayDeque<View>()
    queue.add(root)
    while (queue.isNotEmpty()) {
        val view = queue.removeFirst()
        if (view.javaClass.simpleName == "PhotoView") {
            runCatching {
                view.javaClass.getMethod("setZoomable", Boolean::class.javaPrimitiveType)
                    .invoke(view, zoomable)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
