package net.dexxicon.reader.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.dexxicon.reader.core.model.BookSummary

// Phase 0: a shared Compose Multiplatform screen. It reads from :core:model (already KMP)
// and renders identically on Android and iOS — the milestone this spike is proving.
private val sample = listOf(
    BookSummary(id = "1", serverId = "demo", title = "Her Final Words", authors = listOf("Brianna Labuskes")),
    BookSummary(id = "2", serverId = "demo", title = "White Out", authors = listOf("Danielle Girard")),
    BookSummary(id = "3", serverId = "demo", title = "Press Reset", authors = listOf("Jason Schreier")),
    BookSummary(id = "4", serverId = "demo", title = "A Loyal Son of Terra", authors = listOf("Steven Mohan, Jr.")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Dexxicon — shared UI") }) },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(sample) { book ->
                    ListItem(
                        headlineContent = { Text(book.title) },
                        supportingContent = { Text(book.authorLine) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
