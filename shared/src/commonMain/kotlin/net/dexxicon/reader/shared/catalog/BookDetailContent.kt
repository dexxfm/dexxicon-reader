package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import net.dexxicon.reader.core.designsystem.component.BackPill
import net.dexxicon.reader.core.designsystem.theme.CoverShapeMedium
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.core.model.BookCopy
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.DownloadStatus
import net.dexxicon.reader.core.model.ReadingProgress
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.OnOpenReader
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import net.dexxicon.reader.core.designsystem.component.CoverImage
import net.dexxicon.reader.core.designsystem.component.LocalCoverBadges
import net.dexxicon.reader.core.model.AggregatedBook
import net.dexxicon.reader.core.model.seriesNumberLabel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect

/**
 * Phase 4 restructure (issue #126) — the one Book Detail render, replacing both platforms'
 * previous copies. Ports native `feature/catalog/BookDetailScreen.kt`'s full design (two-pane
 * layout, progress row, download button, details card, multi-server picker) verbatim — the
 * richer of the two prior versions, since [BookDetailState] now backs every field it needs on
 * both platforms; `:shared`'s previous version was narrower only because
 * `ReadingProgressRepository`/`DownloadRepository` weren't commonMain yet, not by design.
 *
 * [onOpenReader] (issue #99) is the single hand-off point out of Compose into a genuinely
 * native reading screen on both platforms — see its own doc comment. [state] resolves the
 * acquisition's auth header, the manga-genre check, and (audiobooks) the launch metadata via
 * [net.dexxicon.reader.shared.openReader] (issue #130 — the same helper Home's "Continue
 * reading/listening" shelves use, rather than each resolving these independently), so neither
 * platform's thin wrapper needs its own path back into the catalog/auth layer just to open a
 * book; native's wrapper can ignore every field but `serverId`/`bookId`/`format` if its own
 * reader screens already resolve the rest themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailContent(
    state: BookDetailState,
    onBack: () -> Unit,
    onOpenReader: OnOpenReader,
    modifier: Modifier = Modifier,
    onOpenSeries: (String) -> Unit = {},
    onOpenBook: (serverId: String, bookId: String) -> Unit = { _, _ -> },
    /** issue #266 — this book's Highlights list; takes the title to show there. */
    onOpenHighlights: (title: String) -> Unit = {},
) {
    val download by state.download.collectAsState()
    val progress by state.progress.collectAsState()

    // issue #259 — the "save a copy" button's result.
    val snackbarHostState = remember { SnackbarHostState() }
    state.message?.let { text ->
        LaunchedEffect(text) {
            snackbarHostState.showSnackbar(text, withDismissAction = true)
            state.messageShown()
        }
    }
    val saveDestination by state.saveCopyDestination.collectAsState()
    val saveCopy = SaveCopyAction(saving = state.savingCopy, destination = saveDestination, onClick = state::saveCopy)
        .takeIf { state.canSaveCopy }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            // issue #301 — pull down to try again.
            state.error != null -> PullToRefreshBox(
                isRefreshing = state.retrying,
                onRefresh = state::retry,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // Scrollable so the pull gesture has something to drag.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewport = maxHeight
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = viewport)
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        BackPill(onBack)
                        Spacer(Modifier.height(12.dp))
                        Text(state.error ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pull down to try again",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        state.resolveReaderLaunch(detail, copy.serverId, copy.bookId, onOpenReader)
                    },
                    onDownload = state::onDownload,
                    onRemoveDownload = state::onRemoveDownload,
                    onSetStatus = state::setReadingStatus,
                    onSetRating = state::setRating,
                    seriesBooks = state.seriesBooks,
                    onOpenSeries = onOpenSeries,
                    onOpenBook = onOpenBook,
                    onOpenHighlights = { onOpenHighlights(detail.summary.title) },
                    saveCopy = saveCopy,
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
    onSetRating: (Int) -> Unit,
    seriesBooks: List<AggregatedBook>,
    onOpenSeries: (String) -> Unit,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
    onOpenHighlights: () -> Unit,
    saveCopy: SaveCopyAction?,
    modifier: Modifier = Modifier,
) {
    val seriesShelf: @Composable () -> Unit = {
        detail.summary.series?.takeIf { seriesBooks.isNotEmpty() }?.let { name ->
            Spacer(Modifier.height(20.dp))
            SeriesShelf(name, seriesBooks, onSeeAll = { onOpenSeries(name) }, onOpenBook = onOpenBook)
        }
    }
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
                    HeroBlock(detail, copies, onSetStatus, onSetRating, onOpenSeries, stacked = true)
                    Spacer(Modifier.height(16.dp))
                    ActionButtons(
                        detail, copies, download, progress, supportsDownloads,
                        onOpen, onDownload, onRemoveDownload, onOpenHighlights, saveCopy,
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
                    seriesShelf()
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
                    HeroBlock(detail, copies, onSetStatus, onSetRating, onOpenSeries, stacked = false)
                    Spacer(Modifier.height(20.dp))
                    ActionButtons(
                        detail, copies, download, progress, supportsDownloads,
                        onOpen, onDownload, onRemoveDownload, onOpenHighlights, saveCopy,
                    )
                    Spacer(Modifier.height(20.dp))
                    AboutSection(detail)
                    Spacer(Modifier.height(20.dp))
                    DetailsSection(detail)
                    seriesShelf()
                }
            }
        }
    }
}

/**
 * issue #256 — "More in <series>": the series' other volumes in series order, merged across
 * servers, as a scrollable shelf at the bottom of Book Detail. "See all" opens the series.
 */
@Composable
private fun SeriesShelf(
    name: String,
    books: List<AggregatedBook>,
    onSeeAll: () -> Unit,
    onOpenBook: (serverId: String, bookId: String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionHeader("More in $name") }
        TextButton(onClick = onSeeAll) { Text("See all") }
    }
    Spacer(Modifier.height(8.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(books, key = { it.key }) { book ->
            Column(
                Modifier
                    .width(96.dp)
                    .clickable { onOpenBook(book.primary.serverId, book.primary.bookId) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(
                            book.title,
                            seriesNumberLabel(book.seriesIndex)?.let { "book $it" },
                        ).joinToString(", ")
                    },
            ) {
                // Always numbered here, whatever the cover-badge setting — the position in the
                // series is the whole point of this shelf.
                CompositionLocalProvider(LocalCoverBadges provides LocalCoverBadges.current.copy(showSeriesNumber = true)) {
                    CoverImage(
                        coverUrl = book.coverUrl,
                        contentDescription = null,
                        format = book.format,
                        seriesIndex = book.seriesIndex,
                    )
                }
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroBlock(
    detail: BookDetail,
    copies: List<BookCopy>,
    onSetStatus: (ReadingStatus) -> Unit,
    onSetRating: (Int) -> Unit,
    onOpenSeries: (String) -> Unit,
    stacked: Boolean,
) {
    val s = detail.summary
    val cover: @Composable (Modifier) -> Unit = { m ->
        Box(
            m
                .aspectRatio(0.66f)
                .clip(CoverShapeMedium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CoverShapeMedium),
        ) {
            var failed by remember(s.coverUrl) { mutableStateOf(false) }
            if (s.coverUrl != null && !failed) {
                // issue #163 — a cover added/changed server-side after this URL was first (and
                // maybe unsuccessfully) fetched would otherwise never be seen again: Coil keys
                // its memory/disk cache by URL, and this app's cover URLs are a fixed
                // `/books/{id}/cover` with no cache-busting param. Detail is the one place a
                // user is likely to check "did my newly-added cover show up", so it always
                // re-validates over the network here (WRITE_ONLY skips the cache *read*, not
                // the write) rather than trusting a stale cached miss/old image — the refreshed
                // result still populates the shared cache for the Library grid's benefit.
                val context = LocalPlatformContext.current
                AsyncImage(
                    model = remember(s.coverUrl) {
                        ImageRequest.Builder(context)
                            .data(s.coverUrl)
                            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                            .diskCachePolicy(CachePolicy.WRITE_ONLY)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state -> failed = state is AsyncImagePainter.State.Error },
                )
            } else {
                // Same "no cover" treatment as the grid's CoverImage — a genuine load failure
                // (no cover on the server, a 404) reads identically to "there's just no cover".
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "No Cover",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                // issue #256 — tap the series to see the whole thing, in order.
                Text(
                    buildString {
                        append(it)
                        s.seriesIndex?.let { n -> append("  #${n.toString().removeSuffix(".0")}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClickLabel = "Open series") { onOpenSeries(it) }
                        .padding(vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, shape = Pill, label = { Text(formatLabel(detail)) })
                ReadingStatusChip(detail.readingStatus, onSetStatus)
            }
            Spacer(Modifier.height(6.dp))
            RatingRow(detail.rating, onSetRating)
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

/** Tappable 1–5 star row (issue #264) — tapping star N sets the rating to N; there's no way to
 *  clear a rating from here (see [BookDetailState.setRating]'s doc comment on why "clear" was
 *  scoped out of v1). */
@Composable
private fun RatingRow(rating: Int?, onSetRating: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (star in 1..5) {
            val filled = rating != null && star <= rating
            Icon(
                if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Rate $star star${if (star == 1) "" else "s"}",
                tint = if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable { onSetRating(star) }
                    .padding(6.dp)
                    .size(20.dp),
            )
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
    onOpenHighlights: () -> Unit,
    saveCopy: SaveCopyAction?,
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

    // issue #259 — audiobooks can span many files, so there's no single file to save a copy of.
    val copyAction = saveCopy?.takeIf { format != ContentFormat.AUDIOBOOK }
    if (supportsDownloads || copyAction != null) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (supportsDownloads) {
                Box(Modifier.weight(1f)) { DownloadButton(download, onDownload, onRemoveDownload) }
            }
            copyAction?.let { SaveCopyButton(it) }
        }
    }

    // issue #266 — EPUB only: both servers expose EPUB highlights as structured, listable
    // records; Grimmory's PDF annotations are one opaque blob for its own PDF.js viewer, so a
    // PDF list couldn't be offered on both servers alike.
    if (format == ContentFormat.EPUB) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenHighlights,
            shape = Pill,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            LeftAligned({ Icon(Icons.Filled.FormatQuote, contentDescription = null) }, "Highlights")
        }
    }

    ServerLinks(copies)

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

/** issue #260 — "View on <server>" for every copy whose server has a web app, opening that
 * book's own page there in the browser. One per copy, so a book merged from two servers
 * links to both. */
@Composable
private fun ServerLinks(copies: List<BookCopy>) {
    val uriHandler = LocalUriHandler.current
    copies.forEach { copy ->
        val url = copy.webUrl ?: return@forEach
        TextButton(
            onClick = { runCatching { uriHandler.openUri(url) } },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            LeftAligned(
                { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                "View on ${copy.serverName}",
            )
        }
    }
}

/** issue #259 — the "save a copy to Downloads" icon button's state, bundled so it passes
 * through Book Detail's layout as one value. */
data class SaveCopyAction(
    val saving: Boolean,
    /** "Downloads", or the folder picked in Settings. */
    val destination: String,
    val onClick: () -> Unit,
)

/** issue #259 — icon-only, beside "Make available offline": that keeps the app's own private
 * copy; this saves a copy of the file to Downloads (or the folder chosen in Settings) for use
 * outside the app. */
@Composable
private fun SaveCopyButton(action: SaveCopyAction) {
    OutlinedIconButton(
        onClick = action.onClick,
        enabled = !action.saving,
        modifier = Modifier.size(48.dp),
        // Same outline as the "Make available offline" pill beside it.
        border = ButtonDefaults.outlinedButtonBorder(enabled = !action.saving),
    ) {
        if (action.saving) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Download, contentDescription = "Save a copy to ${action.destination}")
        }
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
            shape = Pill,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            LeftAligned({ Icon(Icons.Filled.CheckCircle, contentDescription = null) }, "Downloaded — remove")
        }

        DownloadStatus.QUEUED, DownloadStatus.RUNNING -> Column(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onRemove,
                shape = Pill,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
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
            OutlinedButton(
                onClick = onDownload,
                shape = Pill,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
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

        null -> OutlinedButton(
            onClick = onDownload,
            shape = Pill,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            LeftAligned({ Icon(Icons.Filled.CloudDownload, contentDescription = null) }, "Make available offline")
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), Arrangement.spacedBy(12.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
