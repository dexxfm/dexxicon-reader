package net.dexxicon.reader.core.model

/**
 * A highlight (optionally with a note) in one book. [locatorJson] is a Readium `Locator`
 * serialised to JSON — the position within the publication.
 */
data class Highlight(
    val id: String,
    val serverId: String,
    val bookId: String,
    val locatorJson: String,
    val progression: Double,
    val text: String,
    val note: String? = null,
    val color: HighlightColor = HighlightColor.YELLOW,
    val chapterTitle: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Server annotation id once synced. */
    val remoteId: String? = null,
    /** Local edits not yet pushed. */
    val dirty: Boolean = true,
)

enum class HighlightColor(val argb: Int, val serverHex: String) {
    YELLOW(0xFFFACC15.toInt(), "#FACC15"),
    GREEN(0xFF34D399.toInt(), "#34D399"),
    BLUE(0xFF60A5FA.toInt(), "#60A5FA"),
    PINK(0xFFF472B6.toInt(), "#F472B6"),
    PURPLE(0xFFA78BFA.toInt(), "#A78BFA");

    companion object {
        fun fromHex(hex: String?): HighlightColor =
            entries.firstOrNull { it.serverHex.equals(hex, ignoreCase = true) } ?: YELLOW
    }
}
