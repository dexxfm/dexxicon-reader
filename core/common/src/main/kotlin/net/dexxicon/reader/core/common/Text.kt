package net.dexxicon.reader.core.common

private val TAG_REGEX = Regex("<[^>]+>")
private val WHITESPACE_REGEX = Regex("[ \\t]*\\n[ \\t]*\\n\\s*")

/**
 * Best-effort conversion of a snippet of HTML (book descriptions from BookOrbit / some
 * BookLore metadata providers) into readable plain text. Not a real parser — just enough
 * to strip tags, turn `<br>`/`<p>` into line breaks and decode the common entities.
 */
fun String.htmlToPlainText(): String {
    if (isBlank()) return ""
    return this
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p>"), "\n\n")
        .replace(Regex("(?i)</div>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "• ")
        .replace(Regex("(?i)</li>"), "\n")
        .replace(TAG_REGEX, "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&rsquo;", "’")
        .replace("&lsquo;", "‘")
        .replace("&rdquo;", "”")
        .replace("&ldquo;", "“")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
        .replace(WHITESPACE_REGEX, "\n\n")
        .trim()
}
