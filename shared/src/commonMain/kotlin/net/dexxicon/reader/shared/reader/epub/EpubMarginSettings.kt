package net.dexxicon.reader.shared.reader.epub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import kotlin.math.roundToInt

@Composable
internal fun EpubMarginSettings(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    Text("Page margins", style = MaterialTheme.typography.titleSmall)
    Text(
        "Reflowable EPUBs. Two-page spreads use the average left and right margin on each page. " +
            "Wide screens may add whitespace; scroll from the text area.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MarginSlider("Top", preferences.epubMarginTop) { value -> onChange { it.copy(epubMarginTop = value) } }
    MarginSlider("Bottom", preferences.epubMarginBottom) { value -> onChange { it.copy(epubMarginBottom = value) } }
    MarginSlider("Left", preferences.epubMarginLeft) { value -> onChange { it.copy(epubMarginLeft = value) } }
    MarginSlider("Right", preferences.epubMarginRight) { value -> onChange { it.copy(epubMarginRight = value) } }
    TextButton(onClick = {
        onChange {
            it.copy(
                epubMarginTop = ReaderDisplayPreferences.DEFAULT_EPUB_VERTICAL_MARGIN,
                epubMarginBottom = ReaderDisplayPreferences.DEFAULT_EPUB_VERTICAL_MARGIN,
                epubMarginLeft = ReaderDisplayPreferences.DEFAULT_EPUB_HORIZONTAL_MARGIN,
                epubMarginRight = ReaderDisplayPreferences.DEFAULT_EPUB_HORIZONTAL_MARGIN,
            )
        }
    }) { Text("Reset page margins") }
}

@Composable
private fun MarginSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$value%", style = MaterialTheme.typography.bodyMedium)
        }
        val max = ReaderDisplayPreferences.MAX_EPUB_MARGIN
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = value > 0, onClick = { onChange((value - 1).coerceIn(0, max)) }) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease ${label.lowercase()} margin")
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = 0f..max.toFloat(),
                steps = max - 1,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = "$label margin"
                    stateDescription = "$value percent"
                },
            )
            IconButton(enabled = value < max, onClick = { onChange((value + 1).coerceIn(0, max)) }) {
                Icon(Icons.Filled.Add, contentDescription = "Increase ${label.lowercase()} margin")
            }
        }
    }
}
