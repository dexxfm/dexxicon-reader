package net.dexxicon.reader.core.serverapi.opds

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * issue #291 — OPDS 1.2 (Atom) feeds: the fallback when a catalog doesn't speak OPDS 2.
 * Entries with acquisition links are publications. Entries without are navigation, except
 * two summary forms that point at the full entry elsewhere:
 *  - an OPDS 1.2 partial entry: a cover plus an `alternate` link of `type=entry`;
 *  - Project Gutenberg's list entries: only a `subsection` link to `/ebooks/<n>.opds` (a
 *    one-entry feed with the downloads), with the author in `<content>`.
 */
internal object Opds1Parser {
    private const val IMAGE = "http://opds-spec.org/image"
    private const val THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    private val GUTENBERG_BOOK = Regex("""^https?://(www\.|m\.)?gutenberg\.org/ebooks/(\d+)\.opds$""")

    fun parseFeed(body: String, url: String): OpdsFeed {
        val feed = XmlTree.parse(body)
        if (feed.name != "feed") throw OpdsFormatException("Not an OPDS 1 feed")
        val links = feed.all("link")
        val entries = feed.all("entry").map { entry(it, url) }
        return OpdsFeed(
            version = OpdsVersion.OPDS_1,
            url = url,
            title = feed.child("title")?.deepText?.trim()?.takeIf { it.isNotBlank() } ?: "Catalog",
            navigation = entries.mapNotNull { it as? Entry.Navigation }.map { it.link },
            publications = entries.mapNotNull { (it as? Entry.Publication)?.publication },
            nextUrl = links.firstOrNull { it.attr("rel") == "next" }?.attr("href")?.let { resolveUrl(url, it) },
            search = links.firstOrNull { it.attr("rel") == "search" }?.let { link ->
                val href = link.attr("href") ?: return@let null
                if (link.attr("type")?.contains("opensearchdescription") == true) {
                    OpdsSearch.Description(resolveUrl(url, href))
                } else {
                    OpdsSearch.Template(resolveTemplate(url, href))
                }
            },
        )
    }

    /** An OpenSearch description's Atom (OPDS) template, else its first one. */
    fun parseOpenSearchTemplate(body: String, url: String): String? {
        val urls = XmlTree.parse(body).all("Url")
        val chosen = urls.firstOrNull { it.attr("type")?.contains("atom+xml") == true } ?: urls.firstOrNull()
        return chosen?.attr("template")?.let { resolveTemplate(url, it) }
    }

    private sealed interface Entry {
        data class Navigation(val link: OpdsLink) : Entry
        data class Publication(val publication: OpdsPublication) : Entry
        data object Skip : Entry
    }

    private fun entry(e: XmlTree.Node, base: String): Entry {
        val title = e.child("title")?.deepText?.trim()?.takeIf { it.isNotBlank() } ?: return Entry.Skip
        val links = e.all("link")
        val acquisitions = links.mapNotNull { l ->
            val rel = l.attr("rel") ?: return@mapNotNull null
            if (!rel.startsWith(OpdsAcquisition.ACQUISITION)) return@mapNotNull null
            val href = l.attr("href") ?: return@mapNotNull null
            OpdsAcquisition(resolveUrl(base, href), l.attr("type"), rel, l.attr("title"), l.attr("length")?.toLongOrNull())
        }
        fun image(rel: String) = links.firstOrNull { it.attr("rel") == rel }?.attr("href")
            ?.takeUnless { it.startsWith("data:") }?.let { resolveUrl(base, it) }
        val cover = image(IMAGE) ?: image("http://opds-spec.org/cover")
        val thumbnail = image(THUMBNAIL) ?: image("http://opds-spec.org/thumbnail")
        val feedLink = links.firstOrNull { it.attr("type")?.contains("atom+xml") == true && it.attr("rel") != "self" }
        val entryLink = links.firstOrNull { it.attr("rel") == "alternate" && it.attr("type")?.contains("type=entry") == true }
        val gutenberg = feedLink?.attr("href")?.let { resolveUrl(base, it) }?.let { GUTENBERG_BOOK.find(it) }

        val isPublication = acquisitions.isNotEmpty() || (entryLink != null && (cover ?: thumbnail) != null) || gutenberg != null
        if (!isPublication) {
            val href = feedLink?.attr("href") ?: return Entry.Skip
            return Entry.Navigation(OpdsLink(title, resolveUrl(base, href), feedLink.attr("type")))
        }

        val authors = e.all("author").mapNotNull { it.child("name")?.deepText?.trim()?.takeIf { n -> n.isNotBlank() } }
        val content = (e.child("summary") ?: e.child("content"))?.deepText?.trim()?.takeIf { it.isNotBlank() }
        val gutenbergId = gutenberg?.groupValues?.get(2)
        return Entry.Publication(
            OpdsPublication(
                id = e.child("id")?.deepText?.trim()?.takeIf { it.isNotBlank() } ?: title,
                title = title,
                // Gutenberg list entries carry the author as their only content.
                authors = authors.ifEmpty { listOfNotNull(content?.takeIf { gutenberg != null }) },
                summary = content?.takeIf { gutenberg == null },
                language = e.child("language")?.deepText?.trim(),
                published = (e.child("issued") ?: e.child("published"))?.deepText?.trim(),
                publisher = e.child("publisher")?.deepText?.trim(),
                coverUrl = cover ?: gutenbergId?.let { "https://www.gutenberg.org/cache/epub/$it/pg$it.cover.medium.jpg" },
                thumbnailUrl = thumbnail ?: cover ?: gutenbergId?.let { "https://www.gutenberg.org/cache/epub/$it/pg$it.cover.small.jpg" },
                acquisitions = acquisitions,
                categories = e.all("category").mapNotNull { it.attr("label") ?: it.attr("term") },
                detailUrl = (entryLink ?: feedLink.takeIf { gutenberg != null })?.attr("href")?.let { resolveUrl(base, it) },
            ),
        )
    }
}

/**
 * An absolute URL for [href] relative to [base] (RFC 3986's common cases). `data:` URIs pass
 * through untouched. Hand-rolled because Ktor's `URLBuilder.takeFrom` keeps the base's query
 * string when resolving a root-relative path (`/ebooks/1.opds` from `…/search.opds/?sort=x`
 * came out as `/ebooks/1.opds?sort=x`).
 */
internal fun resolveUrl(base: String, href: String): String {
    val h = href.trim()
    if (h.startsWith("data:") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(h)) return h
    val scheme = base.substringBefore("://", "https")
    val afterScheme = base.substringAfter("://", base)
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val path = afterScheme.removePrefix(authority).substringBefore('?').substringBefore('#').ifEmpty { "/" }
    val origin = "$scheme://$authority"
    return when {
        h.startsWith("//") -> "$scheme:$h"
        h.startsWith("/") -> origin + h
        h.startsWith("?") -> origin + path + h
        h.startsWith("#") || h.isEmpty() -> base.substringBefore('#') + h
        else -> origin + normalizeDots(path.substringBeforeLast('/') + "/" + h)
    }
}

/** Removes `.` and `..` path segments (keeping any query/fragment on the last one). */
private fun normalizeDots(pathAndRest: String): String {
    val path = pathAndRest.substringBefore('?').substringBefore('#')
    val rest = pathAndRest.removePrefix(path)
    val out = ArrayDeque<String>()
    path.split('/').forEach { seg ->
        when (seg) {
            "." -> Unit
            ".." -> if (out.size > 1) out.removeLast()
            else -> out.addLast(seg)
        }
    }
    val joined = out.joinToString("/")
    return (if (joined.startsWith("/")) joined else "/$joined") + rest
}

/** Like [resolveUrl], but keeps a URI template's `{…}` placeholders intact. */
internal fun resolveTemplate(base: String, template: String): String {
    val brace = template.indexOf('{')
    if (brace < 0) return resolveUrl(base, template)
    val head = template.substring(0, brace)
    // A placeholder in the query string: resolve the part before it (which may be relative).
    val cut = head.lastIndexOf('?').takeIf { it >= 0 } ?: head.length
    return resolveUrl(base, head.substring(0, cut)) + head.substring(cut) + template.substring(brace)
}

/** A just-big-enough element tree over xmlutil's streaming reader — the only XML parser that
 *  runs on both Android and iOS here. Namespaces are ignored: OPDS 1 names don't collide. */
internal object XmlTree {
    class Node(val name: String, private val attributes: Map<String, String>) {
        val children = mutableListOf<Node>()
        internal val text = StringBuilder()
        /** All text inside this element, children included, in document order. */
        val deepText: String get() = text.toString()
        fun attr(name: String) = attributes[name]
        fun child(name: String) = children.firstOrNull { it.name == name }
        fun all(name: String) = children.filter { it.name == name }
    }

    fun parse(xml: String): Node {
        val reader = xmlStreaming.newReader(xml)
        val stack = ArrayDeque<Node>()
        var root: Node? = null
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    val attrs = (0 until reader.attributeCount).associate {
                        reader.getAttributeLocalName(it) to reader.getAttributeValue(it)
                    }
                    val node = Node(reader.localName, attrs)
                    stack.lastOrNull()?.children?.add(node) ?: run { root = node }
                    stack.addLast(node)
                }
                EventType.END_ELEMENT -> stack.removeLastOrNull()
                EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF, EventType.IGNORABLE_WHITESPACE -> {
                    val t = reader.text
                    stack.forEach { it.text.append(t) }
                }
                else -> Unit
            }
        }
        return root ?: throw OpdsFormatException("Empty XML document")
    }
}
