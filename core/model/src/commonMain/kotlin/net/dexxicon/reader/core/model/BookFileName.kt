package net.dexxicon.reader.core.model

/**
 * issue #259 — the file name a book is saved to the device under: `Title - Author.epub`.
 * Characters that are illegal on common filesystems (and path separators) become spaces,
 * runs of whitespace collapse, and the base name is capped so the full name stays well
 * under filesystem limits. An unknown extension falls back to [format]'s usual one.
 */
fun bookFileName(title: String, author: String?, extension: String?, format: ContentFormat): String {
    val base = listOfNotNull(title, author?.takeIf { it.isNotBlank() })
        .joinToString(" - ")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .take(MAX_BASE_NAME)
        .trim()
        .ifEmpty { "Book" }
    val ext = extension?.trim()?.lowercase()?.trimStart('.')?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
        ?: defaultExtension(format)
    return "$base.$ext"
}

/** issue #259 — the MIME type a saved book is registered under, from its file extension. */
fun bookMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "epub", "kepub" -> "application/epub+zip"
    "pdf" -> "application/pdf"
    "cbz" -> "application/vnd.comicbook+zip"
    "cbr" -> "application/vnd.comicbook-rar"
    "mobi", "prc" -> "application/x-mobipocket-ebook"
    "azw3", "azw" -> "application/vnd.amazon.ebook"
    "fb2" -> "application/x-fictionbook+xml"
    "m4b", "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    else -> "application/octet-stream"
}

private fun defaultExtension(format: ContentFormat): String = when (format) {
    ContentFormat.EPUB -> "epub"
    ContentFormat.PDF -> "pdf"
    ContentFormat.COMIC -> "cbz"
    ContentFormat.MOBI -> "mobi"
    ContentFormat.AZW3 -> "azw3"
    ContentFormat.FB2 -> "fb2"
    ContentFormat.AUDIOBOOK -> "m4b"
    else -> "bin"
}

private const val MAX_BASE_NAME = 120
