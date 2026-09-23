package net.dexxicon.reader.shared.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.designsystem.nav.FloatingNavClearance
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.HomeLayout
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.home.liveHomeLayout

/**
 * issue #254 — pinning a library, collection or smart shelf to Home as its own shelf. Pins live
 * in the same [HomeLayout] preference as the built-in shelves, so they reorder and hide from
 * Settings › Arrange Home like any other.
 */
class HomePins(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    val layout: StateFlow<HomeLayout> = container.liveHomeLayout()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeLayout())

    fun toggle(group: BookGroup) {
        scope.launch {
            val current = container.liveHomeLayout().first()
            container.appPreferences.setHomeLayout(
                if (current.isPinned(group)) current.unpinned(group) else current.pinned(group),
            )
        }
    }
}

data class CollectionsUiState(
    val collections: List<BookGroup> = emptyList(),
    val smart: List<BookGroup> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/** issue #254 — the Library's Collections tab: every server's collections and smart shelves. */
class CollectionsState(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()
    val pins = HomePins(container, scope)

    init {
        load()
        scope.launch { container.catalogRepository.serverIds.distinctUntilChanged().drop(1).collect { load() } }
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true) }
        load()
    }

    private fun load() {
        scope.launch {
            val collections = async { container.catalogRepository.groups(BookGroupKind.COLLECTION) }
            val smart = async { container.catalogRepository.groups(BookGroupKind.SMART) }
            val c = collections.await()
            val s = smart.await()
            _uiState.update {
                it.copy(
                    collections = (c as? Outcome.Success)?.value ?: it.collections,
                    smart = (s as? Outcome.Success)?.value ?: it.smart,
                    loading = false,
                    refreshing = false,
                    error = if (c is Outcome.Failure && s is Outcome.Failure) {
                        c.error.message ?: "Couldn't load collections"
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionsPane(state: CollectionsState, onOpenGroup: (BookGroup) -> Unit) {
    val uiState by state.uiState.collectAsState()
    val layout by state.pins.layout.collectAsState()
    val multiServer = (uiState.collections + uiState.smart).map { it.serverId }.distinct().size > 1

    PullToRefreshBox(
        isRefreshing = uiState.refreshing,
        onRefresh = state::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            uiState.loading -> PaneMessage { CircularProgressIndicator() }
            uiState.error != null -> PaneMessage {
                Text(uiState.error ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            uiState.collections.isEmpty() && uiState.smart.isEmpty() -> PaneMessage {
                Text(
                    "No collections or smart shelves on your servers yet. Create them in your " +
                        "server's web app and they'll show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = FloatingNavClearance),
            ) {
                groupSection("Collections", uiState.collections, multiServer, layout, state.pins, onOpenGroup)
                groupSection("Smart shelves", uiState.smart, multiServer, layout, state.pins, onOpenGroup)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groupSection(
    title: String,
    groups: List<BookGroup>,
    multiServer: Boolean,
    layout: HomeLayout,
    pins: HomePins,
    onOpenGroup: (BookGroup) -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "header:$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(groups, key = { it.key }) { group ->
        GroupRow(
            group = group,
            showServer = multiServer,
            pinned = layout.isPinned(group),
            onClick = { onOpenGroup(group) },
            onTogglePin = { pins.toggle(group) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun GroupRow(
    group: BookGroup,
    showServer: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val subtitle = listOfNotNull(
        group.bookCount?.let { if (it == 1) "1 book" else "$it books" },
        group.serverName?.takeIf { showServer },
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isEmpty()) null else {
            { Text(subtitle) }
        },
        leadingContent = {
            Icon(
                if (group.kind == BookGroupKind.SMART) Icons.Filled.AutoAwesome else Icons.Filled.CollectionsBookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { PinButton(pinned, onTogglePin) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** Pin/unpin a group as a Home shelf (issue #254). */
@Composable
internal fun PinButton(pinned: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = if (pinned) "Unpin from Home" else "Pin to Home",
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
