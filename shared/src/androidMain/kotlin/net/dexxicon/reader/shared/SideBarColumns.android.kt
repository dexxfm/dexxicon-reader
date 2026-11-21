package net.dexxicon.reader.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

internal actual val platformHasSideBarColumns: Boolean = false

@Composable
internal actual fun systemSymbol(name: String): Painter? = null
