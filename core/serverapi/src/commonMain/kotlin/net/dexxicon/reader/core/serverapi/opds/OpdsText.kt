package net.dexxicon.reader.core.serverapi.opds

/**
 * issue #295 — readable descriptions out of OPDS markup. Catalogs send XHTML, escaped HTML or
 * hard-wrapped plain text. All of it comes out as paragraphs (joined by a blank line) with the
 * whitespace inside each paragraph collapsed, so source line breaks don't leak into the UI.
 */
internal object OpdsText {
    /** Marks a block boundary (`<p>`, `<br>`, …) inside text collected from markup. */
    const val BREAK = ' '

    val BLOCK_ELEMENTS = setOf(
        "p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
        "tr", "blockquote", "pre", "dd", "dt", "section", "hr",
    )

    private val WHITESPACE = Regex("""\s+""")
    private val HTML_BLOCK = Regex("""(?i)<\s*/?\s*(${BLOCK_ELEMENTS.joinToString("|")})\b[^>]*>""")
    private val HTML_TAG = Regex("""<[^>]*>""")
    private val LOOKS_LIKE_HTML = Regex("""(?i)<\s*(p|br|div|ul|ol|li|b|i|em|strong|a)\b[^>]*>""")
    private val BLANK_LINE = Regex("""\n\s*\n""")
    private val ENTITY = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);""")

    /** Text with [BREAK] block markers (see [XmlTree]) → paragraphs, whitespace collapsed. */
    fun paragraphs(marked: String): List<String> =
        marked.split(BREAK).map { it.replace(WHITESPACE, " ").trim() }.filter { it.isNotEmpty() }

    /** A description in a string: HTML if it looks like HTML, else plain text. */
    fun paragraphsOf(raw: String): List<String> =
        if (LOOKS_LIKE_HTML.containsMatchIn(raw)) htmlParagraphs(raw) else plainParagraphs(raw)

    /** Escaped HTML (Atom `type="html"`, or HTML in an OPDS 2 `description`). */
    fun htmlParagraphs(html: String): List<String> =
        paragraphs(decodeEntities(html.replace(HTML_BLOCK, BREAK.toString()).replace(HTML_TAG, "")))

    /** Plain text: blank lines separate paragraphs; single line breaks are just wrapping. */
    fun plainParagraphs(text: String): List<String> =
        paragraphs(text.replace("\r\n", "\n").replace(BLANK_LINE, BREAK.toString()))

    fun join(paragraphs: List<String>): String? = paragraphs.joinToString("\n\n").takeIf { it.isNotBlank() }

    private fun decodeEntities(s: String): String = s.replace(ENTITY) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? = when {
        cp in 0..0xFFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> (cp - 0x10000).let {
            charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString()
        }
        else -> null
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "copy" to "©",
    )

    /**
     * "Austen, Jane" → "Jane Austen", as Gutenberg's lists already show it. Its detail entries
     * give the catalogue form. Keeps a Jr./Sr./roman-numeral suffix and drops a parenthesised
     * full form ("Tolkien, J. R. R. (John Ronald Reuel)"). Names that aren't "Last, First"
     * (corporate authors, "Various") come back unchanged.
     */
    fun uninvertName(name: String): String {
        val parts = name.split(", ")
        val suffix = parts.getOrNull(2)?.takeIf { SUFFIX.matches(it) }
        if (parts.size != 2 && !(parts.size == 3 && suffix != null)) return name
        val given = parts[1].replace(PARENTHESISED, "").trim()
        if (given.isEmpty() || parts[0].isBlank()) return name
        return "$given ${parts[0].trim()}" + (suffix?.let { ", $it" } ?: "")
    }

    private val SUFFIX = Regex("""(Jr|Sr)\.?|[IVX]+""")
    private val PARENTHESISED = Regex("""\s*\([^)]*\)""")
}
