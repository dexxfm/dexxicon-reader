package net.dexxicon.reader.feature.reader.comic

import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.designsystem.component.pageSnapshot
import net.dexxicon.reader.core.designsystem.component.pageTurnGesture
import net.dexxicon.reader.core.designsystem.component.rememberPageTurnState
import net.dexxicon.reader.core.reader.EdgeTapNavigator
import net.dexxicon.reader.core.reader.ReaderSwipeSensitivity
import org.readium.r2.navigator.image.ImageNavigatorFragment
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.comic.navigator"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    onBack: () -> Unit,
    viewModel: ComicReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tapNavigation by viewModel.tapNavigation.collectAsStateWithLifecycle()
    val swipeSensitivity by viewModel.swipeSensitivity.collectAsStateWithLifecycle()

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
        navigator?.currentLocator?.collect { locator ->
            onLocator(locator)
            page = locator.locations.position ?: 1
        }
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Drag the page with your finger; release past the sensitivity threshold to
                // turn it, a shorter drag slides back. Readium's image pager can't paginate
                // itself embedded in Compose, so the gesture drives the navigator directly.
                .pageTurnGesture(
                    state = pageTurn,
                    enabled = true,
                    commitFraction = swipeSensitivity.commitFraction,
                    snapshot = { pageView?.pageSnapshot() },
                    onTurn = { forward ->
                        (if (forward) navigator?.goForward(false) else navigator?.goBackward(false)) == true
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
                modifier = Modifier.fillMaxSize(),
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
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComicSettings(
    tapNavigation: Boolean,
    onToggleTapNavigation: (Boolean) -> Unit,
    swipeSensitivity: ReaderSwipeSensitivity,
    onSwipeSensitivity: (ReaderSwipeSensitivity) -> Unit,
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
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}
