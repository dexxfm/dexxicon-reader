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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
                    )
                }
            }
        },
    ) { padding ->
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
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
