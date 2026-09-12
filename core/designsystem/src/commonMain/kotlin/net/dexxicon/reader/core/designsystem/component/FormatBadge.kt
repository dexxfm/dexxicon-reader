package net.dexxicon.reader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.dexxicon.reader.core.designsystem.theme.Pill
import net.dexxicon.reader.core.model.ContentFormat

/**
 * Color-coded format legend. Each content type the app supports gets one fixed, saturated
 * colour (theme-independent, like a tag) so the badge on a cover means the same thing
 * everywhere:
 *
 * | Format    | Badge   | Colour   |
 * |-----------|---------|----------|
 * | EPUB      | EPUB    | green    |
 * | PDF       | PDF     | red      |
 * | Comic     | COMIC   | orange   |
 * | Audiobook | AUDIO   | purple   |
 * | FB2       | FB2     | teal     |
 * | MOBI      | MOBI    | brown    |
 * | AZW3      | AZW3    | indigo   |
 *
 * Unknown / unrecognised files get no badge.
 */
val ContentFormat.badgeColor: Color
    get() = when (this) {
        ContentFormat.EPUB -> Color(0xFF4CAF50)
        ContentFormat.PDF -> Color(0xFFE53935)
        ContentFormat.COMIC -> Color(0xFFFB8C00)
        ContentFormat.AUDIOBOOK -> Color(0xFF7E57C2)
        ContentFormat.FB2 -> Color(0xFF26A69A)
        ContentFormat.MOBI -> Color(0xFF8D6E63)
        ContentFormat.AZW3 -> Color(0xFF5C6BC0)
        ContentFormat.UNKNOWN -> Color(0xFF78909C)
    }

/** Short, cover-badge label. `null` when there is nothing useful to show. */
val ContentFormat.badgeText: String?
    get() = when (this) {
        ContentFormat.EPUB -> "EPUB"
        ContentFormat.PDF -> "PDF"
        ContentFormat.COMIC -> "COMIC"
        ContentFormat.AUDIOBOOK -> "AUDIO"
        ContentFormat.FB2 -> "FB2"
        ContentFormat.MOBI -> "MOBI"
        ContentFormat.AZW3 -> "AZW3"
        ContentFormat.UNKNOWN -> null
    }

/** Spelled-out name for the legend. */
val ContentFormat.legendName: String
    get() = when (this) {
        ContentFormat.EPUB -> "EPUB books"
        ContentFormat.PDF -> "PDF documents"
        ContentFormat.COMIC -> "Comics (CBZ / CBR)"
        ContentFormat.AUDIOBOOK -> "Audiobooks"
        ContentFormat.FB2 -> "FictionBook (FB2)"
        ContentFormat.MOBI -> "Kindle MOBI"
        ContentFormat.AZW3 -> "Kindle AZW3"
        ContentFormat.UNKNOWN -> "Other files"
    }

/** Formats that get a cover badge, in reading-priority order. */
val BADGE_FORMATS: List<ContentFormat> = listOf(
    ContentFormat.EPUB,
    ContentFormat.PDF,
    ContentFormat.COMIC,
    ContentFormat.AUDIOBOOK,
    ContentFormat.FB2,
    ContentFormat.MOBI,
    ContentFormat.AZW3,
)

/** The small solid-colour pill shown on a cover / in the legend. */
@Composable
fun FormatBadge(format: ContentFormat, modifier: Modifier = Modifier) {
    val text = format.badgeText ?: return
    Text(
        text = text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 10.sp,
        // Phase 4 (issue #115): the mockup's format tags are always a true pill
        // (border-radius:999px), including the small one on a cover — not the 4dp rect
        // this used before. FormatLegend's own reference-key swatch below is untouched: the
        // mockup has no legend screen to match it against.
        modifier = modifier
            .clip(Pill)
            .background(format.badgeColor)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Reference key mapping each badge colour to the format it stands for. */
@Composable
fun FormatLegend(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BADGE_FORMATS.forEach { format ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(format.badgeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        format.badgeText.orEmpty(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    format.legendName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}
