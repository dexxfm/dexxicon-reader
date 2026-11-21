package net.dexxicon.reader.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIApplication
import platform.UIKit.UIWindowScene
import platform.UIKit.statusBarManager

internal actual val platformHasSideBarColumns: Boolean = true

/** The status bar's frame from the app's window scene; it's a side column when taller than wide. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun sideStatusBarBottom(): Double? {
    val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
        ?: return null
    val manager = scene.statusBarManager ?: return null
    return manager.statusBarFrame.useContents {
        if (size.height > size.width) origin.y + size.height else null
    }
}
