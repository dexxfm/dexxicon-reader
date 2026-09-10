package net.dexxicon.reader.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import net.dexxicon.reader.core.model.BookSummary

// Phase 0: a shared Compose Multiplatform flow — a list and a detail screen behind a
// NavHost (JetBrains' KMP navigation-compose). Renders identically on Android and iOS;
// :core:model (already KMP) supplies the data.
private val sample = listOf(
    BookSummary(id = "1", serverId = "demo", title = "Her Final Words", authors = listOf("Brianna Labuskes")),
    BookSummary(id = "2", serverId = "demo", title = "White Out", authors = listOf("Danielle Girard")),
    BookSummary(id = "3", serverId = "demo", title = "Press Reset", authors = listOf("Jason Schreier")),
    BookSummary(id = "4", serverId = "demo", title = "A Loyal Son of Terra", authors = listOf("Steven Mohan, Jr.")),
)

@Serializable private object ListRoute
@Serializable private data class DetailRoute(val id: String)

@Composable
fun App() {
    MaterialTheme {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = ListRoute) {
            composable<ListRoute> {
                BookListScreen(onOpen = { nav.navigate(DetailRoute(it.id)) })
            }
            composable<DetailRoute> { entry ->
                val id = entry.toRoute<DetailRoute>().id
                BookDetailScreen(
                    book = sample.firstOrNull { it.id == id },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookListScreen(onOpen: (BookSummary) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Dexxicon — shared UI") }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(sample) { book ->
                ListItem(
                    headlineContent = { Text(book.title) },
                    supportingContent = { Text(book.authorLine) },
                    modifier = Modifier.clickable { onOpen(book) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailScreen(book: BookSummary?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "Not found") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(book?.authorLine.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            Text(
                "This screen is served by the same commonMain code on Android and iOS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
