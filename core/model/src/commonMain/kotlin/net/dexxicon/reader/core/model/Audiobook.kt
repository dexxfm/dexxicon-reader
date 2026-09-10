package net.dexxicon.reader.core.model

/** Everything the audiobook player needs to start playing one book. */
data class Audiobook(
    val serverId: String,
    val bookId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val streamUrl: String,
    val durationMs: Long,
    val chapters: List<Chapter> = emptyList(),
) {
    val key: String get() = "$serverId::$bookId"

    fun chapterAt(positionMs: Long): Chapter? =
        chapters.lastOrNull { it.startMs <= positionMs } ?: chapters.firstOrNull()

    fun chapterIndexAt(positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
}

data class Chapter(
    val title: String,
    val startMs: Long,
)
