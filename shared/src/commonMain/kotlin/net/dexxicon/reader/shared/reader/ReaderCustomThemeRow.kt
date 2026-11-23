package net.dexxicon.reader.shared.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** issue #261 — RobinD42's colour wheel and hex field, for the custom themes' colours. */
@Composable
fun ColorSelectionDialog(
    title: String,
    color: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    var hexValue by remember(color) { mutableStateOf(color.toRgbHex()) }
    var hsv by remember(color) { mutableStateOf(color.toHsv()) }
    val parsedColor = hexValue.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)
        ?.let { (0xFF000000L or it).toInt() }
    fun updateHsv(next: HsvColor) {
        hsv = next
        hexValue = Color.hsv(next.hue, next.saturation, next.brightness).toArgb().toRgbHex()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose $title color") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                            hexValue.takeIf { it.length == 6 }?.toLongOrNull(16)?.let {
                                hsv = (0xFF000000L or it).toInt().toHsv()
                            }
                        },
                        label = { Text("Hex color") },
                        prefix = { Text("#") },
                        singleLine = true,
                        isError = parsedColor == null,
                    )
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ColorWheel(
                        hsv = hsv,
                        onChange = ::updateHsv,
                        modifier = Modifier.sizeIn(maxWidth = 200.dp).fillMaxWidth().aspectRatio(1f),
                    )
                }
                Text("Brightness", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = hsv.brightness,
                    onValueChange = { updateHsv(hsv.copy(brightness = it)) },
                    modifier = Modifier.semantics { contentDescription = "Brightness" },
                )
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
                                .clickable {
                                    hexValue = swatch.toRgbHex()
                                    hsv = swatch.toHsv()
                                }
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

private data class HsvColor(val hue: Float, val saturation: Float, val brightness: Float)

@Composable
private fun ColorWheel(hsv: HsvColor, onChange: (HsvColor) -> Unit, modifier: Modifier = Modifier) {
    val currentHsv by rememberUpdatedState(hsv)
    val currentOnChange by rememberUpdatedState(onChange)
    fun select(position: Offset, width: Float, height: Float) {
        val radius = minOf(width, height) / 2f
        if (radius <= 0f) return
        val dx = position.x - width / 2f
        val dy = position.y - height / 2f
        val distance = sqrt(dx * dx + dy * dy)
        val hue = if (distance == 0f) currentHsv.hue else {
            ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
        }
        currentOnChange(currentHsv.copy(hue = hue, saturation = (distance / radius).coerceIn(0f, 1f)))
    }

    Canvas(
        modifier
            .semantics { contentDescription = "Color wheel" }
            .pointerInput(Unit) {
                detectTapGestures { select(it, size.width.toFloat(), size.height.toFloat()) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { select(it, size.width.toFloat(), size.height.toFloat()) },
                    onDrag = { change, _ ->
                        select(change.position, size.width.toFloat(), size.height.toFloat())
                        change.consume()
                    },
                )
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                center = center,
            ),
            radius = radius,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center = center, radius = radius),
            radius = radius,
        )
        val angle = hsv.hue * PI.toFloat() / 180f
        val marker = center + Offset(cos(angle), sin(angle)) * (radius * hsv.saturation)
        drawCircle(Color.Black, radius = 7.dp.toPx(), center = marker, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color.White, radius = 7.dp.toPx(), center = marker, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun Int.toHsv(): HsvColor {
    val red = ((this shr 16) and 0xFF) / 255f
    val green = ((this shr 8) and 0xFF) / 255f
    val blue = (this and 0xFF) / 255f
    val max = maxOf(red, green, blue)
    val delta = max - minOf(red, green, blue)
    val hue = when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta) % 6f)
        max == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return HsvColor((hue + 360f) % 360f, if (max == 0f) 0f else delta / max, max)
}

private fun Int.toRgbHex(): String = (this and 0x00FFFFFF).toString(16).uppercase().padStart(6, '0')

private val COLOR_SWATCHES = listOf(
    0xFF000000.toInt(), 0xFF434343.toInt(), 0xFF666666.toInt(), 0xFF999999.toInt(), 0xFFCCCCCC.toInt(),
    0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFFFF9900.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
    0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFF9900FF.toInt(), 0xFFFF00FF.toInt(), 0xFF783F04.toInt(),
    0xFF38761D.toInt(), 0xFF0B5394.toInt(), 0xFF351C75.toInt(), 0xFF741B47.toInt(), 0xFFCCBBAA.toInt(),
)
