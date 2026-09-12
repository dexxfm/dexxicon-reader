package net.dexxicon.reader.shared.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.ktor.http.Url
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.BookDetail
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.ReadingStatus
import net.dexxicon.reader.shared.AudiobookLaunchInfo
import net.dexxicon.reader.shared.OnOpenReader
import net.dexxicon.reader.core.designsystem.theme.CoverShapeMedium
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.shared.di.AppContainer

/**
 * Read-only-ish book detail — title, authors, format, description, a reading status the user
 * can change (issue #84), and (issue #99) a "Read" action that hands off to a native reader —
 * see [OnOpenReader]'s doc comment for why that's a platform-supplied callback rather than a
 * screen this file owns. `BookActions`'s download/remove stays out of scope — genuinely
 * Android-only (WorkManager), unrelated to reading itself.
 *
 * Phase 4 (issue #115) — ported the same pill back button, cover radius, pill tags, and
 * left-aligned action button as native's `feature/catalog/BookDetailScreen.kt`. Deliberately
 * missing that screen's reading-progress row: `ReadingProgressRepository` (and everything it
 * depends on — `NativeProgressSync`, `KoSyncRepository`, `LibrarySeeder`) is `androidMain`-only
 * in `core:data` today, so there's no commonMain data source for it yet — a real
 * cross-platform port, not a styling gap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    container: AppContainer,
    serverId: String,
    bookId: String,
    onBack: () -> Unit,
    onOpenReader: OnOpenReader,
) {
    var detail by remember(serverId, bookId) { mutableStateOf<BookDetail?>(null) }
    var error by remember(serverId, bookId) { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId, bookId) {
        when (val result = container.catalogRepository.detail(serverId, bookId)) {
            is Outcome.Success -> detail = result.value
            is Outcome.Failure -> error = result.error.message ?: "Couldn't load this book"
        }
    }

    Scaffold { padding ->
        val currentError = error
        val currentDetail = detail
        when {
            currentError != null -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BackPill(onBack, Modifier.padding(16.dp))
                Text(
                    currentError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
            }
            currentDetail == null -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BackPill(onBack, Modifier.padding(16.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    AsyncImage(
                        model = currentDetail.summary.coverUrl,
                        contentDescription = currentDetail.summary.title,
                        imageLoader = container.imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(CoverShapeMedium),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(currentDetail.summary.title, style = MaterialTheme.typography.titleLarge)
                        if (currentDetail.summary.authorLine.isNotBlank()) {
                            Text(
                                currentDetail.summary.authorLine,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FormatTag(currentDetail.summary.format)
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ReadingStatus.entries.toList()) { status ->
                        FilterChip(
                            selected = status == currentDetail.readingStatus,
                            onClick = {
                                container.readingStatusActions.setReadingStatus(serverId, bookId, status)
                                detail = currentDetail.copy(readingStatus = status)
                            },
                            shape = Pill,
                            label = { Text(status.label) },
                        )
                    }
                }

                // issue #99 — the acquisition's URL + a freshly-resolved auth header are
                // plain data by the time they leave :shared; the platform host never needs
                // its own path back into the auth/network layer just to open a book.
                currentDetail.primaryAcquisition?.let { acquisition ->
                    val isAudio = currentDetail.summary.format == ContentFormat.AUDIOBOOK
                    Button(
                        onClick = {
                            val header = container.authHeaderProvider.authHeader(Url(acquisition.href))
                            // issue #108 — same genre-tag check as Android's
                            // ComicReaderViewModel.mangaGenre, resolved here so the platform
                            // reader never needs its own path back into catalog data for it.
                            val isManga = currentDetail.categories.any { it.contains("manga", ignoreCase = true) }
                            // issue #114 — same metadata Android's PlayerViewModel.load()
                            // resolves from this exact BookDetail/BookDetail.audio, bundled
                            // for the native audiobook player; null for every other format.
                            val audiobook = currentDetail.audio
                                ?.takeIf { isAudio }
                                ?.let {
                                    AudiobookLaunchInfo(
                                        title = currentDetail.summary.title,
                                        author = currentDetail.summary.authorLine.takeIf { it.isNotBlank() },
                                        coverUrl = currentDetail.summary.coverUrl,
                                        durationMs = it.durationMs,
                                        chapters = it.chapters,
                                    )
                                }
                            onOpenReader(
                                serverId, bookId, currentDetail.summary.format,
                                acquisition.href, header, isManga, audiobook,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp),
                    ) {
                        LeftAligned({ Icon(Icons.Filled.PlayArrow, contentDescription = null) }, if (isAudio) "Play" else "Read")
                    }
                }

                currentDetail.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Same pill treatment as native's BackPill — a surface-colored stadium with an arrow icon
 * and the word "Back". */
@Composable
private fun BackPill(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onBack,
        shape = Pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.width(18.dp))
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

/** The format's outline pill tag — matches native's `AssistChip(shape = Pill)` for the same
 * role, as plain text since this file has no chip-menu behavior on it. */
@Composable
private fun FormatTag(format: ContentFormat) {
    Text(
        format.name.lowercase(),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(Pill)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), Pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Same flush-left button-label rule as native (the "Modernist" base design system's own
 * explicit rule — M3's Button centers its content Row with no exposed override). */
@Composable
private fun RowScope.LeftAligned(icon: @Composable () -> Unit, label: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(label)
    }
}
