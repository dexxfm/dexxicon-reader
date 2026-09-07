package net.dexxicon.reader.feature.reader.pdf

import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.pdf.navigator"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
        is PdfReaderState.Ready -> ReaderContent(s, onBack, viewModel::onLocatorChanged)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: PdfReaderState.Ready,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    if (activity == null) {
        Center { Text("The reader needs a FragmentActivity host") }
        return
    }
    val fragmentManager = activity.supportFragmentManager
    var navigator by remember { mutableStateOf<PdfNavigatorFragment<*, *>?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var lastLocator by remember { mutableStateOf<Locator?>(null) }

    DisposableEffect(state.publication) {
        val factory = PdfNavigatorFactory(state.publication, PdfiumEngineProvider())
        fragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = state.initialLocator,
            initialPreferences = PdfiumPreferences(),
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
            navigator = fragmentManager.findFragmentByTag(NAV_FRAGMENT_TAG) as? PdfNavigatorFragment<*, *>
            if (navigator == null) kotlinx.coroutines.delay(50)
        }
    }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { locator ->
            onLocator(locator)
            lastLocator = locator
            page = locator.locations.position ?: 1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                            val base = lastLocator
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // A quick horizontal flick turns the page — the pdfium view's own swipe
                // doesn't work embedded in Compose. Watched in the Final pass without
                // consuming, so taps / pinch-zoom / scrolling still reach the page.
                .pointerInput(navigator) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        var dx = 0f
                        var dy = 0f
                        var pointers = 1
                        var lastTime = down.uptimeMillis
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            pointers = maxOf(pointers, event.changes.size)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            dx += change.positionChangeIgnoreConsumed().x
                            dy += change.positionChangeIgnoreConsumed().y
                            lastTime = change.uptimeMillis
                        }
                        val elapsed = lastTime - down.uptimeMillis
                        if (pointers == 1 && elapsed in 1..SWIPE_MAX_MS &&
                            abs(dx) >= size.width * SWIPE_MIN_FRACTION && abs(dx) > abs(dy) * 1.5f
                        ) {
                            if (dx < 0) navigator?.goForward(true) else navigator?.goBackward(true)
                        }
                    }
                },
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
                    container
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val SWIPE_MIN_FRACTION = 0.18f
private const val SWIPE_MAX_MS = 600L

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
