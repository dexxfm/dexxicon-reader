package net.dexxicon.reader.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.shared.reader.epub.TocEntry

@Composable
fun TocList(
    entries: List<TocEntry>,
    currentRef: String?,
    onSelect: (TocEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentIndex = entries.indexOfFirst { it.ref == currentRef }

    LaunchedEffect(entries, currentRef) {
        if (currentIndex >= 0) listState.scrollToItem(currentIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
    ) {
        if (entries.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    Text("No table of contents")
                }
            }
        }
        items(entries, key = { it.ref }) { entry ->
            val isCurrent = entry.ref == currentRef
            val colors = ButtonDefaults.textButtonColors(
                containerColor = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
            )
            TextButton(
                onClick = { onSelect(entry) },
                modifier = Modifier.fillMaxWidth(),
                colors = colors,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.title,
                        modifier = Modifier.weight(1f).padding(start = (entry.depth * 16).dp),
                    )
                    if (isCurrent) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Current chapter",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
