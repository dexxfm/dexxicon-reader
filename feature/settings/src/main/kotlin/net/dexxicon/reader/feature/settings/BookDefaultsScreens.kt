package net.dexxicon.reader.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import net.dexxicon.reader.shared.di.AndroidAppContainer
import net.dexxicon.reader.shared.settings.AudiobookDefaultsScreen as SharedAudiobookDefaultsScreen
import net.dexxicon.reader.shared.settings.BookDefaultsScreen as SharedBookDefaultsScreen

/**
 * Phase 4 Stage H (issue #145) — thin platform entry points, same shape as
 * [SettingsScreen]'s own doc comment: `:shared`'s `AudiobookDefaultsScreen`/`BookDefaultsScreen`
 * hold the real logic and render.
 */
@Composable
fun AudiobookDefaultsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AndroidAppContainer.get(context) }
    SharedAudiobookDefaultsScreen(container = container, onBack = onBack)
}

@Composable
fun BookDefaultsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { AndroidAppContainer.get(context) }
    SharedBookDefaultsScreen(container = container, onBack = onBack)
}
