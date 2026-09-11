package net.dexxicon.reader.core.media

import android.os.Bundle
import net.dexxicon.reader.core.model.Chapter

private const val KEY_CHAPTER_STARTS_MS = "dexxicon.chapterStartsMs"
private const val KEY_CHAPTER_TITLES = "dexxicon.chapterTitles"

/**
 * Packs [chapters] into a [Bundle] so they can ride along as [MediaMetadata][
 * androidx.media3.common.MediaMetadata] extras on the now-playing [MediaItem][
 * androidx.media3.common.MediaItem] — [ChapterMetadataUpdater] reads them straight back off
 * `player.currentMediaItem` without a second catalog lookup. No-op (returns [this] unchanged)
 * when [chapters] is empty, so a book with no chapter markers carries no extras at all.
 */
fun Bundle.putChapters(chapters: List<Chapter>): Bundle = apply {
    if (chapters.isEmpty()) return@apply
    putLongArray(KEY_CHAPTER_STARTS_MS, chapters.map { it.startMs }.toLongArray())
    putStringArray(KEY_CHAPTER_TITLES, chapters.map { it.title }.toTypedArray())
}

/** The inverse of [putChapters]; empty when [this] is null or carries no chapter extras. */
fun Bundle?.getChapters(): List<Chapter> {
    if (this == null) return emptyList()
    val starts = getLongArray(KEY_CHAPTER_STARTS_MS) ?: return emptyList()
    val titles = getStringArray(KEY_CHAPTER_TITLES) ?: return emptyList()
    if (starts.size != titles.size) return emptyList()
    return starts.indices.map { Chapter(title = titles[it], startMs = starts[it]) }
}

private val CHAPTER_PREFIX = Regex("^chapter\\s*\\d", RegexOption.IGNORE_CASE)

/**
 * "Chapter 5: The Reckoning", or just "Chapter 5" when the chapter has no title of its own.
 * Some sources hand back a chapter "title" that's already just "Chapter 6" (no real name) —
 * prefixing our own "Chapter {index}: " onto that would read as "Chapter 9: Chapter 6", so
 * when the title already looks like a bare chapter number, it's shown as-is instead.
 */
fun chapterLabel(index: Int, title: String): String {
    val number = "Chapter ${index + 1}"
    return when {
        title.isBlank() -> number
        CHAPTER_PREFIX.containsMatchIn(title) -> title
        else -> "$number: $title"
    }
}
