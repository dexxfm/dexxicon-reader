package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import net.dexxicon.reader.core.designsystem.theme.CoverShapeMedium
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.AudiobookLaunchInfo
import net.dexxicon.reader.shared.OnOpenReader

/**
 * Phase 4 restructure (issue #126) — the one Book Detail render, replacing both platforms'
 * previous copies. Ports native `feature/catalog/BookDetailScreen.kt`'s full design (two-pane
 * layout, progress row, download button, details card, multi-server picker) verbatim — the
 * richer of the two prior versions, since [BookDetailState] now backs every field it needs on
 * both platforms; `:shared`'s previous version was narrower only because
 * `ReadingProgressRepository`/`DownloadRepository` weren't commonMain yet, not by design.
 *
 * [onOpenReader] (issue #99) is the single hand-off point out of Compose into a genuinely
 * native reading screen on both platforms — see its own doc comment. This composable resolves
 * the acquisition's auth header, the manga-genre check, and (audiobooks) the launch metadata
 * itself via [state], so neither platform's thin wrapper needs its own path back into the
 * catalog/auth layer just to open a book; native's wrapper can ignore every field but
 * `serverId`/`bookId`/`format` if its own reader screens already resolve the rest themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailContent(
    state: BookDetailState,
    onBack: () -> Unit,
    onOpenReader: OnOpenReader,
    modifier: Modifier = Modifier,
) {
    val download by state.download.collectAsState()
    val progress by state.progress.collectAsState()

    Scaffold(modifier = modifier) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BackPill(onBack)
                    Spacer(Modifier.height(12.dp))
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                }
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
                    progress = progress,
                    supportsDownloads = state.supportsDownloads,
                    onBack = onBack,
                    onOpen = { copy ->
                        val isAudio = detail.summary.format == ContentFormat.AUDIOBOOK
                        val acquisition = detail.acquisitions.firstOrNull { it.format == detail.summary.format }
                            ?: detail.primaryAcquisition
                            ?: return@DetailContent
                        val header = state.authHeaderFor(acquisition.href)
                        // issue #108 — same genre-tag check as Android's
                        // ComicReaderViewModel.mangaGenre.
                        val isManga = detail.categories.any { it.contains("manga", ignoreCase = true) }
                        // issue #114 — same metadata Android's PlayerViewModel.load() resolves
                        // from this exact BookDetail/BookDetail.audio.
                        val audiobook = detail.audio
                            ?.takeIf { isAudio }
                            ?.let {
                                AudiobookLaunchInfo(
                                    title = detail.summary.title,
                                    author = detail.summary.authorLine.takeIf { it.isNotBlank() },
                                    coverUrl = detail.summary.coverUrl,
                                    durationMs = it.durationMs,
                                    chapters = it.chapters,
                                )
                            }
                        onOpenReader(
                            copy.serverId, copy.bookId, detail.summary.format,
                            acquisition.href, header, isManga, audiobook,
                        )
                    },
                    onDownload = state::onDownload,
                    onRemoveDownload = state::onRemoveDownload,
                    onSetStatus = state::setReadingStatus,
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
    progress: ReadingProgress?,
    supportsDownloads: Boolean,
    onBack: () -> Unit,
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
                    BackPill(onBack)
                    Spacer(Modifier.height(12.dp))
                    HeroBlock(detail, copies, onSetStatus, stacked = true)
                    Spacer(Modifier.height(16.dp))
                    ActionButtons(
                        detail, copies, download, progress, supportsDownloads,
                        onOpen, onDownload, onRemoveDownload,
                    )
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
                    BackPill(onBack)
                    Spacer(Modifier.height(12.dp))
                    HeroBlock(detail, copies, onSetStatus, stacked = false)
                    Spacer(Modifier.height(20.dp))
                    ActionButtons(
                        detail, copies, download, progress, supportsDownloads,
                        onOpen, onDownload, onRemoveDownload,
                    )
                    Spacer(Modifier.height(20.dp))
                    AboutSection(detail)
                    Spacer(Modifier.height(20.dp))
                    DetailsSection(detail)
                }
            }
        }
    }
}

/** Phase 4 (issue #115) — replaces the standard TopAppBar's back arrow: a surface-colored
 * pill with an arrow icon *and* the word "Back", matching the mockup exactly (it has no top
 * app bar on this screen at all). */
@Composable
private fun BackPill(onBack: () -> Unit) {
    Surface(
        onClick = onBack,
        shape = Pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                "BACK",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
                .clip(CoverShapeMedium)
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
                AssistChip(onClick = {}, shape = Pill, label = { Text(formatLabel(detail)) })
                ReadingStatusChip(detail.readingStatus, onSetStatus)
            }
            val serverNames = copies.map { it.serverName }.distinct()
            if (serverNames.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    serverNames.forEach { ServerChip(it) }
                }
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

/** A compact, non-interactive take on [AssistChip] — names the server a book lives on. */
@Composable
private fun ServerChip(name: String) {
    Row(
        modifier = Modifier
            .clip(Pill)
            .border(1.dp, MaterialTheme.colorScheme.outline, Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.Dns,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(name, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ReadingStatusChip(current: ReadingStatus?, onSet: (ReadingStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            shape = Pill,
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
    progress: ReadingProgress?,
    supportsDownloads: Boolean,
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

    // Phase 4 (issue #115) — the "Modernist" base design system's own explicit rule: button
    // labels are flush left, never centered, even in a button wider than its label. M3's
    // Button centers its content Row by default with no exposed override, so [LeftAligned]'s
    // fill-width inner Row (Start-aligned) is what actually left-aligns it — same helper
    // every button on this screen uses, see [DownloadButton].
    Button(
        onClick = {
            if (copies.size > 1) showPicker = true else onOpen(copies.first())
        },
        enabled = canOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) {
        LeftAligned(
            { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            when {
                isAudio -> "Play"
                canOpen -> "Read"
                else -> "Read (reader coming soon)"
            },
        )
    }

    ProgressRow(detail, progress)

    if (supportsDownloads) {
        Spacer(Modifier.height(8.dp))
        DownloadButton(download, onDownload, onRemoveDownload)
    }

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
                        LeftAligned(
                            { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            copy.serverName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Phase 4 (issue #115) — the mockup's progress row (a pill track + a "42% · 4h 52m left"
 * style line) directly under the Play/Read button. [ReadingProgress] only carries a
 * percentage, no duration — for audiobooks the remaining time is computed from
 * [BookDetail.audio]'s total duration, matching what the mockup shows; other formats just get
 * a plain "NN% read". Hidden entirely below 1% (nothing started yet), rather than showing an
 * empty bar. */
@Composable
private fun ProgressRow(detail: BookDetail, progress: ReadingProgress?) {
    val pct = progress?.percent?.coerceIn(0.0, 1.0) ?: return
    if (pct < 0.01) return
    val isAudio = detail.summary.format == ContentFormat.AUDIOBOOK
    val label = buildString {
        append((pct * 100).toInt())
        append("% ")
        val durationMs = detail.audio?.durationMs
        if (isAudio && durationMs != null && durationMs > 0) {
            val remainingMin = ((durationMs * (1 - pct)) / 60_000).toInt()
            append("· ")
            if (remainingMin >= 60) {
                append(remainingMin / 60).append("h ").append(remainingMin % 60).append("m left")
            } else {
                append(remainingMin).append("m left")
            }
        } else {
            append(if (isAudio) "listened" else "read")
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LinearProgressIndicator(
            progress = { pct.toFloat() },
            modifier = Modifier.weight(1f).height(6.dp).clip(Pill),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Phase 4 (issue #115) — the mockup's small-caps section header ("ABOUT", "DETAILS"), shared
 * by both sections below instead of each rolling its own Text style. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AboutSection(detail: BookDetail) {
    SectionHeader("About")
    val description = detail.description?.takeIf { it.isNotBlank() }
    Text(
        description ?: "No description available.",
        style = MaterialTheme.typography.bodyMedium,
        color = if (description != null) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun DetailsSection(detail: BookDetail) {
    val s = detail.summary
    SectionHeader("Details")
    Spacer(Modifier.height(8.dp))
    // Phase 4 (issue #115) — the mockup wraps every meta row in one rounded card with divider
    // lines between rows, instead of bare label/value pairs floating on the page.
    val rows = listOfNotNull(
        "Format" to formatLabel(detail),
        s.series?.let {
            "Series" to buildString {
                append(it)
                s.seriesIndex?.let { n -> append(" #${n.toString().removeSuffix(".0")}") }
            }
        },
        detail.narratorLine.takeIf { it.isNotBlank() }?.let { "Narrator" to it },
        detail.publisher?.let { "Publisher" to it },
        detail.publishedDate?.let { "Published" to it },
        detail.language?.let { "Language" to it },
        detail.isbn?.let { "ISBN" to it },
        detail.pageCount?.let { "Pages" to it.toString() },
        detail.categories.takeIf { it.isNotEmpty() }?.let { "Categories" to it.joinToString(", ") },
        detail.fileSizeBytes?.let { "File size" to formatFileSize(it) },
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                MetaRow(label, value)
                if (index != rows.lastIndex) HorizontalDivider()
            }
        }
    }
}

/** The file's extension (`epub`, `cbz`, `m4b`…), falling back to the content-type name. */
private fun formatLabel(detail: BookDetail): String =
    detail.fileExtension?.takeIf { it.isNotBlank() } ?: detail.summary.format.name.lowercase()

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${oneDecimal(bytes / 1_000_000_000.0)} GB"
    bytes >= 1_000_000 -> "${oneDecimal(bytes / 1_000_000.0)} MB"
    bytes >= 1_000 -> "${kotlin.math.round(bytes / 1_000.0).toLong()} KB"
    else -> "$bytes B"
}

/** `"%.1f".format(value)`'s JVM-only `java.util.Formatter` isn't available on Kotlin/Native —
 * this is the portable equivalent (always one decimal digit, half-up rounded). */
private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}

/** Phase 4 (issue #115) — the mockup's flush-left button-label rule (see [ActionButtons]' doc
 * comment) applies to every pill button on this screen, not just Play/Read. Shared here since
 * [DownloadButton] has four differently-labeled variants that all need it. */
@Composable
private fun RowScope.LeftAligned(
    icon: @Composable () -> Unit,
    label: String,
    style: androidx.compose.ui.text.TextStyle? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        // Not defaulted to LocalTextStyle.current directly: Text's own no-arg default already
        // does that, and passing an explicit TextStyle here (even TextStyle.Unspecified) would
        // bypass that inheritance instead of merging with it.
        if (style != null) Text(label, style = style) else Text(label)
    }
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
            LeftAligned({ Icon(Icons.Filled.CheckCircle, contentDescription = null) }, "Downloaded — remove")
        }

        DownloadStatus.QUEUED, DownloadStatus.RUNNING -> Column(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                val pct = download.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                LeftAligned(
                    { Icon(Icons.Filled.Delete, contentDescription = null) },
                    if (download.status == DownloadStatus.RUNNING) "Downloading$pct — cancel" else "Queued — cancel",
                )
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
                LeftAligned({ Icon(Icons.Filled.ErrorOutline, contentDescription = null) }, "Download failed — retry")
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
            LeftAligned({ Icon(Icons.Filled.CloudDownload, contentDescription = null) }, "Make available offline")
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
