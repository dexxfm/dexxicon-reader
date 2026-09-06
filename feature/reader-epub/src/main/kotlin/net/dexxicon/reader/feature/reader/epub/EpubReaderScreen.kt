package net.dexxicon.reader.feature.reader.epub

import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

private const val NAV_FRAGMENT_TAG = "dexxicon.epub.navigator"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderScreen(
    onBack: () -> Unit,
    viewModel: EpubReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    when (val s = state) {
        is EpubReaderState.Loading -> Center { CircularProgressIndicator() }
        is EpubReaderState.Error -> Center {
            Text(
                s.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        is EpubReaderState.Ready -> ReaderContent(
            state = s,
            preferences = prefs,
            onBack = onBack,
            onLocator = viewModel::onLocatorChanged,
            onUpdatePreferences = viewModel.updatePreferences,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: EpubReaderState.Ready,
    preferences: ReaderDisplayPreferences,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val darkTheme = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    if (activity == null) {
        Center { Text("The reader needs a FragmentActivity host") }
        return
    }

    val fragmentManager = activity.supportFragmentManager
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Build the navigator fragment once per opened publication and set it as the factory
    // the FragmentManager will use to instantiate EpubNavigatorFragment by class.
    val initialPrefs = remember(state.publication) { preferences.toEpubPreferences(darkTheme) }
    DisposableEffect(state.publication) {
        fragmentManager.fragmentFactory = EpubNavigatorFactory(state.publication)
            .createFragmentFactory(
                initialLocator = state.initialLocator,
                initialPreferences = initialPrefs,
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
    }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collect { onLocator(it) }
    }

    LaunchedEffect(preferences, darkTheme, navigator) {
        navigator?.submitPreferences(preferences.toEpubPreferences(darkTheme))
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
                actions = {
                    IconButton(onClick = { showToc = true }) {
                        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Contents")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.TextFields, contentDescription = "Display settings")
                    }
                },
            )
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
                        add(container.id, EpubNavigatorFragment::class.java, null, NAV_FRAGMENT_TAG)
                    }
                }
                container
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }

    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            TableOfContents(
                links = flatten(state.publication.tableOfContents),
                onSelect = { link ->
                    navigator?.go(link, true)
                    showToc = false
                },
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
}

@Composable
private fun TableOfContents(links: List<Pair<Int, Link>>, onSelect: (Link) -> Unit) {
    if (links.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { Text("No table of contents") }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        items(links) { (depth, link) ->
            TextButton(onClick = { onSelect(link) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    link.title ?: link.href.toString(),
                    modifier = Modifier.fillMaxWidth().padding(start = (depth * 16).dp),
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun DisplaySettings(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Text size", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = preferences.fontScale.toFloat(),
            onValueChange = { v -> onChange { it.copy(fontScale = v.toDouble()) } },
            valueRange = 0.6f..2.4f,
            steps = 8,
        )

        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Row {
            ReaderTheme.entries.forEach { theme ->
                FilterChip(
                    selected = preferences.theme == theme,
                    onClick = { onChange { it.copy(theme = theme) } },
                    label = { Text(theme.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Row(Modifier.padding(top = 12.dp)) {
            FilterChip(
                selected = !preferences.scroll,
                onClick = { onChange { it.copy(scroll = false) } },
                label = { Text("Paged") },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = preferences.scroll,
                onClick = { onChange { it.copy(scroll = true) } },
                label = { Text("Scroll") },
            )
        }
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { content() }
    }
}

private fun flatten(links: List<Link>, depth: Int = 0): List<Pair<Int, Link>> =
    links.flatMap { link -> listOf(depth to link) + flatten(link.children, depth + 1) }
