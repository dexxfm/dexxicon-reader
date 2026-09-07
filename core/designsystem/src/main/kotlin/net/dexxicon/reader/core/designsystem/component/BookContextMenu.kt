package net.dexxicon.reader.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import net.dexxicon.reader.core.model.DownloadStatus

/**
 * Long-press menu for a book item. Mirrors the actions on the detail screen so the common
 * ones don't need a round-trip.
 */
@Composable
fun BookContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    downloadStatus: DownloadStatus?,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onDetails: () -> Unit,
    onDownloadOrRemove: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Mark as read") },
            leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            onClick = { onDismiss(); onMarkRead() },
        )
        DropdownMenuItem(
            text = { Text("Mark as unread") },
            leadingIcon = { Icon(Icons.Outlined.Circle, contentDescription = null) },
            onClick = { onDismiss(); onMarkUnread() },
        )
        DropdownMenuItem(
            text = { Text("Book details") },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
            onClick = { onDismiss(); onDetails() },
        )
        val (label, icon) = when (downloadStatus) {
            null -> "Make available offline" to Icons.Filled.CloudDownload
            DownloadStatus.DONE -> "Remove download" to Icons.Filled.DeleteOutline
            else -> "Cancel download" to Icons.Filled.DeleteOutline
        }
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            onClick = { onDismiss(); onDownloadOrRemove() },
        )
    }
}
