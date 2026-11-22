package net.dexxicon.reader.shared.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ReaderCustomThemeRow(
    title: String,
    selected: Boolean,
    background: Int,
    foreground: Int,
    onSelect: () -> Unit,
    onBackgroundChange: (Int) -> Unit,
    onForegroundChange: (Int) -> Unit,
) {
    var editingBackground by remember { mutableStateOf(false) }
    var editingForeground by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selected,
            onClick = onSelect,
            label = { Text(title) },
            leadingIcon = {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(background))
                        .semantics { contentDescription = "$title background color" },
                )
            },
        )
        TextButton(onClick = { editingBackground = true }) {
            Text("Background")
        }
        TextButton(onClick = { editingForeground = true }) {
            Text("Foreground")
        }
    }

    if (editingBackground) {
        ColorSelectionDialog(
            title = "$title background",
            color = background,
            onDismiss = { editingBackground = false },
            onSelect = {
                editingBackground = false
                onBackgroundChange(it)
            },
        )
    }
    if (editingForeground) {
        ColorSelectionDialog(
            title = "$title foreground",
            color = foreground,
            onDismiss = { editingForeground = false },
            onSelect = {
                editingForeground = false
                onForegroundChange(it)
            },
        )
    }
}

@Composable
private fun ColorSelectionDialog(
    title: String,
    color: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    var hexValue by remember(color) { mutableStateOf(color.toRgbHex()) }
    val parsedColor = hexValue.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)
        ?.let { (0xFF000000L or it).toInt() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose $title color") },
        text = {
            androidx.compose.foundation.layout.Column {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 48.dp, height = 36.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(parsedColor?.let { Color(it) } ?: Color.Transparent),
                    )
                    OutlinedTextField(
                        value = hexValue,
                        onValueChange = { input ->
                            hexValue = input.removePrefix("#")
                                .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                                .take(6)
                        },
                        label = { Text("Hex color") },
                        prefix = { Text("#") },
                        singleLine = true,
                        isError = parsedColor == null,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    COLOR_SWATCHES.forEach { swatch ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .clickable { hexValue = swatch.toRgbHex() }
                                .semantics { contentDescription = "Select color ${swatch.toRgbHex()}" },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsedColor?.let(onSelect) },
                enabled = parsedColor != null,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun Int.toRgbHex(): String = (this and 0x00FFFFFF).toString(16).uppercase().padStart(6, '0')

private val COLOR_SWATCHES = listOf(
    0xFF000000.toInt(), 0xFF434343.toInt(), 0xFF666666.toInt(), 0xFF999999.toInt(), 0xFFCCCCCC.toInt(),
    0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFFFF9900.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
    0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFF9900FF.toInt(), 0xFFFF00FF.toInt(), 0xFF783F04.toInt(),
    0xFF38761D.toInt(), 0xFF0B5394.toInt(), 0xFF351C75.toInt(), 0xFF741B47.toInt(), 0xFFCCBBAA.toInt(),
)
