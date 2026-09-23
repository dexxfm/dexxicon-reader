package net.dexxicon.reader.shared.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.nav.FloatingNavClearance
import net.dexxicon.reader.core.model.SeriesEntry
import net.dexxicon.reader.core.model.seriesKey
import net.dexxicon.reader.shared.di.AppContainer

data class SeriesListUiState(
    val query: String = "",
    val series: List<SeriesEntry> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val refreshing: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
)

/**
 * issue #256 — the Library's Series tab: every server's series, merged by name, searchable and
 * paged the same way the Books tab pages books.
 */
@OptIn(FlowPreview::class)
class SeriesListState(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(SeriesListUiState())
    val uiState: StateFlow<SeriesListUiState> = _uiState.asStateFlow()
    private var nextPage = 0

    init {
        reload()
        scope.launch {
            _uiState.map { it.query }.distinctUntilChanged().drop(1).debounce(350).collect { reload() }
        }
        scope.launch {
            container.catalogRepository.serverIds.distinctUntilChanged().drop(1).collect { reload() }
        }
    }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun reload() {
        nextPage = 0
        _uiState.update { it.copy(loading = true, error = null, series = emptyList(), endReached = false) }
        fetch(replace = true)
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        nextPage = 0
        _uiState.update { it.copy(refreshing = true, error = null, endReached = false) }
        fetch(replace = true)
    }

    fun loadMore() {
        val s = _uiState.value
        if (s.loading || s.loadingMore || s.endReached || s.error != null || s.series.isEmpty()) return
        _uiState.update { it.copy(loadingMore = true) }
        fetch(replace = false)
    }

    private fun fetch(replace: Boolean) {
        scope.launch {
            val query = _uiState.value.query.takeIf { it.isNotBlank() }
            when (val result = container.catalogRepository.series(query, nextPage)) {
                is Outcome.Success -> {
                    nextPage += 1
                    _uiState.update {
                        it.copy(
                            series = merge(if (replace) emptyList() else it.series, result.value.series),
                            endReached = !result.value.hasMore,
                            loading = false,
                            loadingMore = false,
                            refreshing = false,
                            error = null,
                        )
                    }
                }
                is Outcome.Failure -> _uiState.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        refreshing = false,
                        error = result.error.message ?: "Couldn't load series",
                    )
                }
            }
        }
    }

    /** Pages are merged across servers only approximately (see `CatalogRepository.series`), so
     *  a series already listed can arrive again from another server on a later page — fold it
     *  into the existing row instead of listing it twice. */
    private fun merge(existing: List<SeriesEntry>, incoming: List<SeriesEntry>): List<SeriesEntry> {
        val byKey = LinkedHashMap<String, SeriesEntry>()
        (existing + incoming).forEach { entry ->
            val key = seriesKey(entry.name)
            val prev = byKey[key]
            byKey[key] = if (prev == null) {
                entry
            } else {
                prev.copy(
                    groups = (prev.groups + entry.groups).distinctBy { it.key },
                    authors = (prev.authors + entry.authors).distinct(),
                    coverUrl = prev.coverUrl ?: entry.coverUrl,
                )
            }
        }
        return byKey.values.sortedBy { seriesKey(it.name) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeriesPane(state: SeriesListState, onOpenSeries: (SeriesEntry) -> Unit) {
    val uiState by state.uiState.collectAsState()
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            uiState.series.isNotEmpty() && last >= uiState.series.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore, uiState.loadingMore) { if (shouldLoadMore) state.loadMore() }

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = uiState.query,
            onValueChange = state::onQueryChange,
            placeholder = { Text("Search series…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        PullToRefreshBox(
            isRefreshing = uiState.refreshing,
            onRefresh = state::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                uiState.loading -> PaneMessage { CircularProgressIndicator() }
                uiState.error != null && uiState.series.isEmpty() -> PaneMessage {
                    Text(uiState.error ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                uiState.series.isEmpty() -> PaneMessage {
                    Text(
                        if (uiState.query.isNotBlank()) "No series match “${uiState.query}”." else "No series on your servers yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = FloatingNavClearance),
                ) {
                    items(uiState.series, key = { seriesKey(it.name) }) { entry ->
                        SeriesRow(entry, onClick = { onOpenSeries(entry) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesRow(entry: SeriesEntry, onClick: () -> Unit) {
    val subtitle = buildString {
        entry.bookCount?.let { append(if (it == 1) "1 book" else "$it books") }
        if (entry.authors.isNotEmpty()) append(if (isEmpty()) "" else " · ").append(entry.authors.take(2).joinToString(", "))
        if (entry.groups.size > 1) append(" · On ${entry.groups.size} servers")
    }
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isEmpty()) null else {
            { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            Box(Modifier.width(44.dp)) { CoverImage(entry.coverUrl, contentDescription = null) }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** Centred and scrollable, so pull-to-refresh still works on the empty/error states. */
@Composable
internal fun PaneMessage(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
