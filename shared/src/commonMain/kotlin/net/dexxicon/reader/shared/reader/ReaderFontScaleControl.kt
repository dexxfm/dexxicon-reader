package net.dexxicon.reader.shared.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import kotlin.math.roundToInt

@Composable
internal fun ReaderFontScaleControl(
    fontScale: Double,
    onFontScaleChange: (Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Font size", style = MaterialTheme.typography.titleSmall)
            Text("${fontScaleLabel(fontScale)}×", style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = fontScale > ReaderDisplayPreferences.MIN_FONT_SCALE,
                onClick = {
                    onFontScaleChange(adjustFontScale(fontScale, -ReaderDisplayPreferences.FONT_SCALE_STEP))
                },
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease font size")
            }
            Slider(
                value = fontScale.toFloat(),
                onValueChange = {
                    onFontScaleChange(adjustFontScale(it.toDouble()))
                },
                valueRange = ReaderDisplayPreferences.MIN_FONT_SCALE.toFloat()..
                    ReaderDisplayPreferences.MAX_FONT_SCALE.toFloat(),
                steps = 24,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "Font size"
                        stateDescription = "${(fontScale * 100).roundToInt()} percent"
                    },
            )
            IconButton(
                enabled = fontScale < ReaderDisplayPreferences.MAX_FONT_SCALE,
                onClick = {
                    onFontScaleChange(adjustFontScale(fontScale, ReaderDisplayPreferences.FONT_SCALE_STEP))
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase font size")
            }
        }

    }
}

private fun adjustFontScale(fontScale: Double, change: Double = 0.0): Double =
    (((fontScale + change).coerceIn(
        ReaderDisplayPreferences.MIN_FONT_SCALE,
        ReaderDisplayPreferences.MAX_FONT_SCALE,
    ) * 10).roundToInt() / 10.0)

private fun fontScaleLabel(fontScale: Double): String =
    ((fontScale * 10).roundToInt() / 10.0).toString()
