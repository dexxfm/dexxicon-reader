package net.dexxicon.reader.shared.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import net.dexxicon.reader.core.model.HomeLayout
import net.dexxicon.reader.core.model.HomeShelf
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.home.liveHomeLayout
import net.dexxicon.reader.shared.home.title
import net.dexxicon.reader.shared.library.label

/**
 * Settings › Arrange Home (issue #255): drag Home's shelves into any order and switch any of
 * them off. Same long-press drag handle as the Servers list (`App.kt`'s `ReorderableServers`),
 * plus "Move up"/"Move down" accessibility actions on each row, since a drag gesture alone
 * isn't reachable from a screen reader.
 */
@Composable
fun HomeLayoutScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { SettingsState(container, scope) }
    val layout by remember { container.liveHomeLayout() }.collectAsState(initial = null)
    DefaultsScaffold("Arrange Home", onBack) {
        Text(
            "Press and hold a handle to drag a shelf. A shelf that's switched off stays off " +
                "Home until you switch it back on; an empty one never shows either way. Pin " +
                "collections and smart shelves from Library › Collections to add them here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        layout?.let { ReorderableSections(layout = it, onChange = state::setHomeLayout) }
    }
}

@Composable
private fun ReorderableSections(layout: HomeLayout, onChange: (HomeLayout) -> Unit) {
    // A local copy so the drag reflows instantly; re-synced from upstream when not dragging.
    var order by remember(layout) { mutableStateOf(layout.order) }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<String, Int>() }

    Column(Modifier.fillMaxWidth()) {
        order.forEachIndexed { index, shelf ->
            val dragging = dragIndex == index
            val shown = shelf.key !in layout.hidden
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { rowHeights[shelf.key] = it.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .offset { IntOffset(0, if (dragging) dragDelta.roundToInt() else 0) }
                    .then(if (dragging) Modifier.shadow(6.dp) else Modifier)
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surface,
                    )
                    .semantics {
                        customActions = buildList {
                            if (index > 0) {
                                add(CustomAccessibilityAction("Move up") { onChange(layout.moved(index, index - 1)); true })
                            }
                            if (index < order.lastIndex) {
                                add(CustomAccessibilityAction("Move down") { onChange(layout.moved(index, index + 1)); true })
                            }
                        }
                    },
            ) {
                ListItem(
                    headlineContent = { Text(shelf.title) },
                    supportingContent = shelf.caption(shown)?.let { caption -> { Text(caption) } },
                    leadingContent = {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Keyed on [layout]: the gesture handler outlives recompositions,
                            // so without re-installing it whenever the layout changes it would
                            // keep writing back the layout (and `order` state) from when the row
                            // first appeared — e.g. re-hiding a shelf switched back on since.
                            modifier = Modifier.pointerInput(layout) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragIndex = index; dragDelta = 0f },
                                    onDragEnd = {
                                        if (dragIndex != null) onChange(layout.copy(order = order))
                                        dragIndex = null
                                        dragDelta = 0f
                                    },
                                    onDragCancel = {
                                        order = layout.order
                                        dragIndex = null
                                        dragDelta = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val cur = dragIndex ?: return@detectDragGesturesAfterLongPress
                                        dragDelta += amount.y
                                        val h = rowHeights[order[cur].key] ?: return@detectDragGesturesAfterLongPress
                                        if (dragDelta > h / 2 && cur < order.lastIndex) {
                                            order = order.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                            dragIndex = cur + 1
                                            dragDelta -= h
                                        } else if (dragDelta < -h / 2 && cur > 0) {
                                            order = order.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                            dragIndex = cur - 1
                                            dragDelta += h
                                        }
                                    },
                                )
                            },
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // issue #254 — a pin can be removed from here as well as from
                            // the Library, without hunting for it there.
                            if (shelf is HomeShelf.Pinned) {
                                IconButton(onClick = { onChange(layout.unpinned(shelf.group)) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Unpin ${shelf.group.name}")
                                }
                            }
                            Switch(
                                checked = shown,
                                onCheckedChange = { onChange(layout.withVisibility(shelf.key, it)) },
                            )
                        }
                    },
                )
            }
        }
    }
}

/** What a shelf's row says under its name: a pinned shelf's kind and server, and whether
 *  it's switched off. */
private fun HomeShelf.caption(shown: Boolean): String? {
    val parts = buildList {
        if (this@caption is HomeShelf.Pinned) add("Pinned ${group.kind.label().lowercase()}")
        if (!shown) add("hidden from Home")
    }
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.takeIf { it.isNotEmpty() }
}
