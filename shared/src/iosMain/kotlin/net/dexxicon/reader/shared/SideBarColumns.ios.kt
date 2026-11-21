package net.dexxicon.reader.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIImageSymbolConfiguration
import platform.UIKit.UIImageSymbolWeightRegular
import platform.posix.memcpy

internal actual val platformHasSideBarColumns: Boolean = true

@Composable
internal actual fun systemSymbol(name: String): Painter? =
    remember(name) { renderSymbol(name)?.let { BitmapPainter(it) } }

/**
 * Draws an SF Symbol into a bitmap Compose can show: black on transparent, so `Icon`'s tint
 * colours it like any other glyph. Rendered at 3x so it stays sharp at the 24dp it's shown at.
 */
@OptIn(ExperimentalForeignApi::class)
private fun renderSymbol(name: String) = runCatching {
    val config = UIImageSymbolConfiguration.configurationWithPointSize(24.0, UIImageSymbolWeightRegular)
    val symbol = UIImage.systemImageNamed(name, config) ?: return@runCatching null
    val (width, height) = symbol.size.useContents { width to height }
    val side = maxOf(width, height)
    val format = UIGraphicsImageRendererFormat.preferredFormat().apply { scale = 3.0 }
    val renderer = UIGraphicsImageRenderer(size = CGSizeMake(side, side), format = format)
    val image = renderer.imageWithActions { _ ->
        symbol.drawInRect(CGRectMake((side - width) / 2, (side - height) / 2, width, height))
    }
    val data = UIImagePNGRepresentation(image) ?: return@runCatching null
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
