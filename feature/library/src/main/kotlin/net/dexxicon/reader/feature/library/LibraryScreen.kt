package net.dexxicon.reader.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.shared.di.AndroidAppContainer
import net.dexxicon.reader.shared.home.HomeContent
import net.dexxicon.reader.shared.home.HomeState

/**
 * Phase 4 Stage C (issue #130) — a thin platform entry point now: [HomeState] + [HomeContent]
 * (`:shared`) hold the real logic and render, identical to `:shared`'s own `home/HomeScreen.kt`
 * wrapper. No Hilt, no `hiltViewModel()`, for this screen at all — `LibraryViewModel` is gone;
 * [AndroidAppContainer] supplies the same process-lifetime, non-Hilt `AppContainer` `:shared`
 * itself is built from (same pattern Book Detail's `BookDetailScreen.kt` established, issue
 * #126).
 *
 * [onContinue]'s simple `(serverId, bookId, format) -> Unit` shape (native's own reader screens
 * already resolve everything else independently via their own Hilt-injected repos) is a strict
 * subset of [net.dexxicon.reader.shared.OnOpenReader]'s richer one — the [onOpenReader] adapter
 * below just discards the fields native doesn't need, same as `BookDetailScreen.kt`'s own
 * `onRead` adapter.
 */
@Composable
fun LibraryScreen(
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onContinue: (serverId: String, bookId: String, format: ContentFormat) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember { AndroidAppContainer.get(context) }
    val state = remember { HomeState(container, scope) }
    HomeContent(
        state = state,
        onOpenBook = onOpenBook,
        onOpenReader = { sid, bid, format, _, _, _, _ -> onContinue(sid, bid, format) },
    )
}
