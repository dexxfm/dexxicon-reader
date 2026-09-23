package net.dexxicon.reader.shared.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.model.BookGroup

/**
 * issue #253 — the Books tab's "Library: All ▾" chip, narrowing the merged grid to one of the
 * servers' own libraries. Hidden when there's nothing to choose between (a single library on a
 * single server is the same thing as "All"). With more than one server, each server's
 * libraries are listed under its name.
 */
@Composable
internal fun LibraryPicker(state: LibraryState) {
    val libraries by state.libraries.collectAsState()
    val uiState by state.uiState.collectAsState()
    if (libraries.size < 2) return
    val selected = (uiState.scope as? LibraryScope.Groups)?.groups?.singleOrNull()
    val multiServer = libraries.map { it.serverId }.distinct().size > 1
    var open by remember { mutableStateOf(false) }

    Box(Modifier.padding(horizontal = 12.dp)) {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text("Library: ${selected?.name ?: "All"}") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All libraries") },
                onClick = { open = false; state.selectLibrary(null) },
            )
            libraries.groupBy { it.serverId }.values.forEach { serverLibraries ->
                HorizontalDivider()
                if (multiServer) {
                    Text(
                        serverLibraries.first().serverName.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                serverLibraries.forEach { library ->
                    DropdownMenuItem(
                        text = { Text(library.label()) },
                        onClick = { open = false; state.selectLibrary(library) },
                    )
                }
            }
        }
    }
}

private fun BookGroup.label(): String = bookCount?.let { "$name ($it)" } ?: name
