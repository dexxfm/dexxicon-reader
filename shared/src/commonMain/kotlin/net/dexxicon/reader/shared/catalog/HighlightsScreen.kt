package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.PendingReaderJump
import net.dexxicon.reader.core.data.ReaderJumpTarget
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.Highlight
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.openReader
import net.dexxicon.reader.shared.toBookDetail

/**
 * issue #266 — every highlight in one EPUB, like BookLore's own web Highlights tab: search
 * (text and notes), one column of cards, and a tap opens the reader at that highlight.
 * Local-first — it shows what's already on the device straight away, then merges in whatever
 * the server has ([net.dexxicon.reader.core.data.HighlightRepository.syncFromServer]).
 */
class HighlightsState(
    private val container: AppContainer,
    private val serverId: String,
    private val bookId: String,
    private val scope: CoroutineScope,
) {
    val highlights: StateFlow<List<Highlight>?> = container.highlightRepository.observe(serverId, bookId)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    var query by mutableStateOf("")
    var syncing by mutableStateOf(true)
        private set

    init {
        scope.launch {
            runCatching { container.highlightRepository.syncFromServer(serverId, bookId) }
            syncing = false
        }
    }

    /** Opens the reader at [highlight] — see [PendingReaderJump] for why it's a one-off jump
     *  rather than a change to where the book resumes. */
    fun open(highlight: Highlight, onOpenReader: OnOpenReader) {
        scope.launch {
            val detail: BookDetail = (container.catalogRepository.detail(serverId, bookId) as? Outcome.Success)?.value
                ?: container.downloadRepository.get(serverId, bookId)
                    ?.takeIf { it.status == DownloadStatus.DONE }
                    ?.toBookDetail()
                ?: return@launch
            PendingReaderJump.set(
                serverId,
                bookId,
                ReaderJumpTarget(
                    locatorJson = highlight.locatorJson.takeIf { it.isNotBlank() && it != "{}" },
                    cfi = highlight.cfi,
                    progression = highlight.progression.takeIf { it > 0.0 },
                    text = highlight.text,
                ),
            )
            container.openReader(detail, serverId, bookId, onOpenReader)
        }
    }
}

/** Search matches the highlighted text, the note, and the chapter title. */
internal fun List<Highlight>.matching(query: String): List<Highlight> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { h ->
        h.text.contains(q, ignoreCase = true) ||
            h.note?.contains(q, ignoreCase = true) == true ||
            h.chapterTitle?.contains(q, ignoreCase = true) == true
    }
}

/** The page to show for [highlight]: the server's own page number (KOReader), else the
 *  Readium position in its locator — close to a printed page in Readium's paginated EPUBs. */
internal fun pageOf(highlight: Highlight): Int? = highlight.pageNumber ?: runCatching {
    Json.parseToJsonElement(highlight.locatorJson).jsonObject["locations"]?.jsonObject
        ?.get("position")?.jsonPrimitive?.int
}.getOrNull()

@OptIn(ExperimentalTime::class)
internal fun formatHighlightDate(millis: Long): String? {
    if (millis <= 0L) return null
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[date.month.ordinal]
    return "$month ${date.day}, ${date.year}"
}

@Composable
fun HighlightsScreen(
    container: AppContainer,
    serverId: String,
    bookId: String,
    bookTitle: String,
    onBack: () -> Unit,
    onOpenReader: OnOpenReader,
) {
    val scope = rememberCoroutineScope()
    val state = remember(serverId, bookId) { HighlightsState(container, serverId, bookId, scope) }
    val all by state.highlights.collectAsState()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackPill(onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Highlights", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        bookTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextField(
                value = state.query,
                onValueChange = { state.query = it },
                placeholder = { Text("Search highlights and notes…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

            val list = all
            val shown = list?.matching(state.query).orEmpty()
            when {
                list == null || (list.isEmpty() && state.syncing) -> Message { CircularProgressIndicator() }
                list.isEmpty() -> Message {
                    Text(
                        "No highlights in this book yet. Highlight text while reading and it'll show up here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                shown.isEmpty() -> Message {
                    Text(
                        "No highlights match “${state.query.trim()}”.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            if (shown.size == list.size) countLabel(list.size) else "${shown.size} of ${countLabel(list.size)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(shown, key = { it.id }) { highlight ->
                        HighlightCard(highlight, onClick = { state.open(highlight, onOpenReader) })
                    }
                }
            }
        }
    }
}

private fun countLabel(n: Int) = if (n == 1) "1 highlight" else "$n highlights"

@Composable
private fun HighlightCard(highlight: Highlight, onClick: () -> Unit) {
    val details = listOfNotNull(
        highlight.chapterTitle?.takeIf { it.isNotBlank() },
        pageOf(highlight)?.let { "p. $it" },
        formatHighlightDate(highlight.createdAt),
    ).joinToString(" · ")
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open in the book", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(highlight.text)
                    highlight.note?.takeIf { it.isNotBlank() }?.let { append(". Note: ").append(it) }
                    if (details.isNotEmpty()) append(". ").append(details)
                }
            },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // The highlight's own colour, down the leading edge.
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(Color(highlight.color.argb), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
            )
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Three lines, then "…" — the full text is one tap away in the book.
                Text(
                    highlight.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                highlight.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (details.isNotEmpty()) {
                    Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Message(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.widthIn(max = 480.dp)) { content() } }
}
