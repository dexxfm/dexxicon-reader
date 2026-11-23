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
internal fun ReaderProportionalScaleControl(
    title: String,
    scale: Double,
    min: Double,
    max: Double,
    onDecreaseDescription: String,
    onIncreaseDescription: String,
    valueLabel: String = "${proportionalScaleLabel(scale)}×",
    onScaleChange: (Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = scale > min,
                onClick = {
                    onScaleChange(adjustScale(scale, -ReaderDisplayPreferences.SCALE_STEP, min, max))
                },
            ) {
                Icon(Icons.Filled.Remove, contentDescription = onDecreaseDescription)
            }
            Slider(
                value = scale.toFloat(),
                onValueChange = {
                    onScaleChange(adjustScale(it.toDouble(), min = min, max = max))
                },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (((max - min) / ReaderDisplayPreferences.SCALE_STEP).roundToInt() - 1)
                    .coerceAtLeast(0),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = title
                        stateDescription = valueLabel
                    },
            )
            IconButton(
                enabled = scale < max,
                onClick = {
                    onScaleChange(adjustScale(scale, ReaderDisplayPreferences.SCALE_STEP, min, max))
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = onIncreaseDescription)
            }
        }
    }
}

private fun adjustScale(scale: Double, change: Double = 0.0, min: Double, max: Double): Double =
    (((scale + change).coerceIn(min, max) * 10).roundToInt() / 10.0)

internal fun proportionalScaleLabel(scale: Double): String =
    ((scale * 10).roundToInt() / 10.0).toString()
