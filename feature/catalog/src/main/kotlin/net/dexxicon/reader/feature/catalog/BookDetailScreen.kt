package net.dexxicon.reader.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    onRead: (serverId: String, bookId: String, format: ContentFormat) -> Unit = { _, _, _ -> },
    viewModel: BookDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.summary?.title.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
            }
            state.detail != null -> {
                val detail = state.detail!!
                val copies = state.copies.ifEmpty {
                    listOf(BookCopy(detail.summary.serverId, "Library", detail.summary.id))
                }
                DetailContent(
                    detail = detail,
                    copies = copies,
                    download = download,
                    onOpen = { copy -> onRead(copy.serverId, copy.bookId, detail.summary.format) },
                    onDownload = viewModel::onDownload,
                    onRemoveDownload = viewModel::onRemoveDownload,
                    onSetStatus = viewModel::setReadingStatus,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    detail: BookDetail,
    copies: List<BookCopy>,
    download: Download?,
    onOpen: (BookCopy) -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onSetStatus: (ReadingStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // On a tablet / unfolded foldable, put the cover + actions in a fixed side column
        // and let the description + details fill the rest; otherwise stack in one column.
        val twoPane = maxWidth >= 720.dp

        if (twoPane) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    HeroBlock(detail, copies, onSetStatus, stacked = true)
                    Spacer(Modifier.height(16.dp))
                    ActionButtons(detail, copies, download, onOpen, onDownload, onRemoveDownload)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    AboutSection(detail)
                    Spacer(Modifier.height(20.dp))
                    DetailsSection(detail)
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    HeroBlock(detail, copies, onSetStatus, stacked = false)
                    Spacer(Modifier.height(20.dp))
                    ActionButtons(detail, copies, download, onOpen, onDownload, onRemoveDownload)
                    Spacer(Modifier.height(20.dp))
                    AboutSection(detail)
                    Spacer(Modifier.height(20.dp))
                    DetailsSection(detail)
                }
            }
        }
    }
}

@Composable
private fun HeroBlock(
    detail: BookDetail,
    copies: List<BookCopy>,
    onSetStatus: (ReadingStatus) -> Unit,
    stacked: Boolean,
) {
    val s = detail.summary
    val cover: @Composable (Modifier) -> Unit = { m ->
        Box(
            m
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            s.coverUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    val titleColumn: @Composable () -> Unit = {
        Column {
            Text(s.title, style = MaterialTheme.typography.titleLarge)
            if (s.authorLine.isNotBlank()) {
                Text(
                    s.authorLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            s.series?.let {
                Text(
                    buildString {
                        append(it)
                        s.seriesIndex?.let { n -> append("  #${n.toString().removeSuffix(".0")}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(formatLabel(detail)) })
                ReadingStatusChip(detail.readingStatus, onSetStatus)
            }
            if (copies.size > 1) {
                Text(
                    "On ${copies.joinToString(", ") { it.serverName }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    if (stacked) {
        cover(Modifier.width(160.dp))
        Spacer(Modifier.height(12.dp))
        titleColumn()
    } else {
        Row {
            cover(Modifier.width(120.dp))
            Spacer(Modifier.width(16.dp))
            titleColumn()
        }
    }
}

@Composable
private fun ReadingStatusChip(current: ReadingStatus?, onSet: (ReadingStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current?.label ?: "Set status") },
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReadingStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.label) },
                    trailingIcon = {
                        if (status == current) Icon(Icons.Filled.Check, contentDescription = "current")
                    },
                    onClick = { open = false; onSet(status) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionButtons(
    detail: BookDetail,
    copies: List<BookCopy>,
    download: Download?,
    onOpen: (BookCopy) -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val format = detail.summary.format
    val isAudio = format == ContentFormat.AUDIOBOOK
    val canOpen = format == ContentFormat.EPUB ||
        format == ContentFormat.COMIC ||
        format == ContentFormat.PDF ||
        format == ContentFormat.AUDIOBOOK
    var showPicker by remember { mutableStateOf(false) }

    Button(
        onClick = {
            if (copies.size > 1) showPicker = true else onOpen(copies.first())
        },
        enabled = canOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Text(
            when {
                isAudio -> "  Play"
                canOpen -> "  Read"
                else -> "  Read (reader coming soon)"
            },
        )
    }
    Spacer(Modifier.height(8.dp))
    DownloadButton(download, onDownload, onRemoveDownload)

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (isAudio) "Play From" else "Read From",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                copies.forEach { copy ->
                    Button(
                        onClick = { showPicker = false; onOpen(copy) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("  ${copy.serverName}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(detail: BookDetail) {
    Text("About", style = MaterialTheme.typography.titleMedium)
    val description = detail.description?.takeIf { it.isNotBlank() }
    Text(
        description ?: "No description available.",
        style = MaterialTheme.typography.bodyMedium,
        color = if (description != null) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DetailsSection(detail: BookDetail) {
    val s = detail.summary
    Text("Details", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    MetaRow("Format", formatLabel(detail))
    MetaRow("Series", s.series?.let {
        buildString {
            append(it)
            s.seriesIndex?.let { n -> append(" #${n.toString().removeSuffix(".0")}") }
        }
    })
    MetaRow("Narrator", detail.narratorLine.takeIf { it.isNotBlank() })
    MetaRow("Publisher", detail.publisher)
    MetaRow("Published", detail.publishedDate)
    MetaRow("Language", detail.language)
    MetaRow("ISBN", detail.isbn)
    MetaRow("Pages", detail.pageCount?.toString())
    MetaRow("Categories", detail.categories.takeIf { it.isNotEmpty() }?.joinToString(", "))
    MetaRow("File size", detail.fileSizeBytes?.let(::formatFileSize))
}

/** The file's extension (`epub`, `cbz`, `m4b`…), falling back to the content-type name. */
private fun formatLabel(detail: BookDetail): String =
    detail.fileExtension?.takeIf { it.isNotBlank() } ?: detail.summary.format.name.lowercase()

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun DownloadButton(
    download: Download?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    when (download?.status) {
        DownloadStatus.DONE -> OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Text("  Downloaded — remove")
        }

        DownloadStatus.QUEUED, DownloadStatus.RUNNING -> Column(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                val pct = download.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                Text(if (download.status == DownloadStatus.RUNNING) "  Downloading$pct — cancel" else "  Queued — cancel")
            }
            val fraction = download.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }

        DownloadStatus.FAILED -> Column(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                Text("  Download failed — retry")
            }
            download.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        null -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CloudDownload, contentDescription = null)
            Text("  Make available offline")
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
