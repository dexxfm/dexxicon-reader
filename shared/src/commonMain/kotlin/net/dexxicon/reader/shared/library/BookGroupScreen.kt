package net.dexxicon.reader.shared.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.core.designsystem.component.LocalCoverBadges
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.BookGroup
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer

/**
 * One library, collection, smart shelf or series as its own screen (issues #253, #254, #256):
 * the Library's books pane, fixed to [scope]. Collections and smart shelves get a pin button
 * that adds them to Home as a shelf ([pinnable]).
 */
@Composable
fun BookGroupScreen(
    container: AppContainer,
    scope: LibraryScope,
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onOpenBook: (AggregatedBook) -> Unit,
    onOpenReader: OnOpenReader,
    /** The group Home would pin, or null where pinning isn't offered (series). */
    pinnable: BookGroup? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val state = remember(scope) { LibraryState(container, coroutineScope, initialScope = scope) }
    val pins = remember { HomePins(container, coroutineScope) }
    val layout by pins.layout.collectAsState()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackPill(onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                pinnable?.let { group -> PinButton(layout.isPinned(group)) { pins.toggle(group) } }
            }
            // A series screen always numbers its covers, whatever the cover-badge setting — the
            // order is the whole point of it (same as Book Detail's series shelf).
            val badges = LocalCoverBadges.current
            CompositionLocalProvider(
                LocalCoverBadges provides if (scope.isSeries) badges.copy(showSeriesNumber = true) else badges,
            ) {
                LibraryBooksPane(state = state, onOpenBook = onOpenBook, onOpenReader = onOpenReader)
            }
        }
    }
}

/** "Collection · My Server" and so on — the line under a group screen's title. */
fun BookGroupKind.label(): String = when (this) {
    BookGroupKind.LIBRARY -> "Library"
    BookGroupKind.COLLECTION -> "Collection"
    BookGroupKind.SMART -> "Smart shelf"
    BookGroupKind.SERIES -> "Series"
}
