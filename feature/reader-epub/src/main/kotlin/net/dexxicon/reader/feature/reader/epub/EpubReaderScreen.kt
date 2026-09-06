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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.rememberUpdatedState
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
import net.dexxicon.reader.core.reader.EdgeTapNavigator
import net.dexxicon.reader.core.reader.ReaderDisplayPreferences
import net.dexxicon.reader.core.reader.ReaderTheme
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
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()

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
            highlights = highlights,
            onBack = onBack,
            onLocator = viewModel::onLocatorChanged,
            onUpdatePreferences = viewModel.updatePreferences,
            onAddHighlight = viewModel::addHighlight,
            onSetNote = viewModel::setNote,
            onSetColor = viewModel::setColor,
            onDeleteHighlight = viewModel::deleteHighlight,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: EpubReaderState.Ready,
    preferences: ReaderDisplayPreferences,
    highlights: List<net.dexxicon.reader.core.model.Highlight>,
    onBack: () -> Unit,
    onLocator: (Locator) -> Unit,
    onUpdatePreferences: suspend ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    onAddHighlight: (Locator) -> Unit,
    onSetNote: (String, String?) -> Unit,
    onSetColor: (String, net.dexxicon.reader.core.model.HighlightColor) -> Unit,
    onDeleteHighlight: (String) -> Unit,
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
    var showHighlights by remember { mutableStateOf(false) }
    var activeHighlightId by remember { mutableStateOf<String?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    val tapNavEnabled by rememberUpdatedState(preferences.tapNavigation)
    val navHolder = remember { arrayOfNulls<EpubNavigatorFragment?>(1) }
    val addHighlight by rememberUpdatedState(onAddHighlight)

    // Build the navigator fragment once per opened publication and set it as the factory
    // the FragmentManager will use to instantiate EpubNavigatorFragment by class.
    val initialPrefs = remember(state.publication) { preferences.toEpubPreferences(darkTheme) }
    DisposableEffect(state.publication) {
        fragmentManager.fragmentFactory = EpubNavigatorFactory(state.publication)
            .createFragmentFactory(
                initialLocator = state.initialLocator,
                initialPreferences = initialPrefs,
                configuration = org.readium.r2.navigator.epub.EpubNavigatorFragment.Configuration().apply {
                    decorationTemplates = org.readium.r2.navigator.html.HtmlDecorationTemplates.defaultTemplates()
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
        navigator?.currentLocator?.collect { onLocator(it) }
    }

    // Render highlight decorations and react to taps on them.
    LaunchedEffect(navigator, highlights) {
        val nav = navigator ?: return@LaunchedEffect
        val decorations = highlights.mapNotNull { h ->
            val locator = runCatching {
                org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(h.locatorJson))
            }.getOrNull() ?: return@mapNotNull null
            org.readium.r2.navigator.Decoration(
                id = h.id,
                locator = locator,
                style = org.readium.r2.navigator.Decoration.Style.Highlight(
                    tint = h.color.argb,
                    isActive = false,
                ),
            )
        }
        runCatching { nav.applyDecorations(decorations, "highlights") }
    }

    DisposableEffect(navigator) {
        val nav = navigator
        val listener = object : org.readium.r2.navigator.DecorableNavigator.Listener {
            override fun onDecorationActivated(
                event: org.readium.r2.navigator.DecorableNavigator.OnActivatedEvent,
            ): Boolean {
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

    LaunchedEffect(preferences, darkTheme, navigator) {
        navigator?.submitPreferences(preferences.toEpubPreferences(darkTheme))
    }

    Scaffold(
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
                        IconButton(onClick = { showHighlights = true }) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.Bookmarks,
                                contentDescription = "Highlights",
                            )
                        }
                        IconButton(onClick = { showToc = true }) {
                            Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = "Contents")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.TextFields, contentDescription = "Display settings")
                        }
                    },
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
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

            var resumeDismissed by remember { mutableStateOf(false) }
            val resumeLocator = state.remoteResumeLocator
            if (resumeLocator != null && !resumeDismissed) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Continue from ${((state.remoteResumePercent ?: 0.0) * 100).toInt()}% (synced)",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = {
                            navigator?.go(resumeLocator, true)
                            resumeDismissed = true
                        }) { Text("Jump") }
                        IconButton(onClick = { resumeDismissed = true }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }
        }
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

    if (showHighlights) {
        ModalBottomSheet(onDismissRequest = { showHighlights = false }) {
            HighlightList(
                highlights = highlights,
                onSelect = { h ->
                    runCatching {
                        org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(h.locatorJson))
                    }.getOrNull()?.let { navigator?.go(it, true) }
                    showHighlights = false
                },
                onEdit = { activeHighlightId = it.id; showHighlights = false },
            )
        }
    }

    val active = highlights.firstOrNull { it.id == activeHighlightId }
    if (active != null) {
        ModalBottomSheet(onDismissRequest = { activeHighlightId = null }) {
            HighlightEditor(
                highlight = active,
                onNote = { onSetNote(active.id, it) },
                onColor = { onSetColor(active.id, it) },
                onDelete = { onDeleteHighlight(active.id); activeHighlightId = null },
            )
        }
    }
}

@Composable
private fun HighlightList(
    highlights: List<net.dexxicon.reader.core.model.Highlight>,
    onSelect: (net.dexxicon.reader.core.model.Highlight) -> Unit,
    onEdit: (net.dexxicon.reader.core.model.Highlight) -> Unit,
) {
    if (highlights.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            Text("Select text in the book to add a highlight")
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        items(highlights, key = { it.id }) { h ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color(h.color.argb)),
                )
                Column(
                    Modifier.weight(1f).padding(start = 12.dp).clickableText { onSelect(h) },
                ) {
                    Text(
                        h.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!h.note.isNullOrBlank()) {
                        Text(
                            h.note!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { onEdit(h) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit highlight")
                }
            }
        }
    }
}

@Composable
private fun HighlightEditor(
    highlight: net.dexxicon.reader.core.model.Highlight,
    onNote: (String?) -> Unit,
    onColor: (net.dexxicon.reader.core.model.HighlightColor) -> Unit,
    onDelete: () -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(highlight.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 16.dp)) {
            net.dexxicon.reader.core.model.HighlightColor.entries.forEach { c ->
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color(c.argb))
                        .clickableText { onColor(c) },
                )
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text("  Delete")
            }
            TextButton(onClick = { onNote(note) }) { Text("Save note") }
        }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

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

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Tap edges to turn pages", Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = preferences.tapNavigation,
                onCheckedChange = { on -> onChange { it.copy(tapNavigation = on) } },
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
