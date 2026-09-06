package net.dexxicon.reader.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus

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
            state.detail != null -> DetailContent(
                detail = state.detail!!,
                download = download,
                onRead = {
                    val d = state.detail!!.summary
                    onRead(d.serverId, d.id, d.format)
                },
                onDownload = viewModel::onDownload,
                onRemoveDownload = viewModel::onRemoveDownload,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DetailContent(
    detail: BookDetail,
    download: Download?,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = detail.summary
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row {
            Box(
                Modifier
                    .width(120.dp)
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
            Spacer(Modifier.width(16.dp))
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
                AssistChip(onClick = {}, label = { Text(formatLabel(detail)) })
            }
        }

        Spacer(Modifier.height(20.dp))
        val isAudio = s.format == ContentFormat.AUDIOBOOK
        val canOpen = s.format == ContentFormat.EPUB ||
            s.format == ContentFormat.COMIC ||
            s.format == ContentFormat.PDF ||
            s.format == ContentFormat.AUDIOBOOK
        Button(
            onClick = onRead,
            enabled = canOpen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text(
                when {
                    isAudio -> "  Listen"
                    canOpen -> "  Read"
                    else -> "  Read (reader coming soon)"
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        DownloadButton(download, onDownload, onRemoveDownload)

        Spacer(Modifier.height(20.dp))
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

        Spacer(Modifier.height(20.dp))
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
