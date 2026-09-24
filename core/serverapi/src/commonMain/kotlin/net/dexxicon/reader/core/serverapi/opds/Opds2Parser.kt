package net.dexxicon.reader.core.serverapi.opds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * issue #291 — OPDS 2.0 (`application/opds+json`), read as loose JSON rather than strict
 * classes: the spec lets most fields take several shapes (an author is a string, an object or
 * a list of either; a title may be a language map; `rel` a string or an array), and catalogs
 * use all of them.
 */
internal object Opds2Parser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseFeed(body: String, url: String): OpdsFeed {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw OpdsFormatException("Not an OPDS 2 feed")
        if ("metadata" !in root && "links" !in root) throw OpdsFormatException("Not an OPDS 2 feed")
        val links = root.array("links")
        return OpdsFeed(
            version = OpdsVersion.OPDS_2,
            url = url,
            title = root.obj("metadata")?.let { title(it["title"]) } ?: "Catalog",
            navigation = navigation(root.array("navigation"), url),
            publications = root.array("publications").mapNotNull { (it as? JsonObject)?.let { p -> publication(p, url) } },
            groups = root.array("groups").mapNotNull { (it as? JsonObject)?.let { g -> group(g, url) } },
            nextUrl = links.firstWithRel("next")?.href(url),
            search = links.firstWithRel("search")?.let { link ->
                val href = link.href(url) ?: return@let null
                if (link.string("type")?.contains("opensearchdescription") == true) {
                    OpdsSearch.Description(href)
                } else {
                    OpdsSearch.Template(href)
                }
            },
            facets = root.array("facets").mapNotNull { (it as? JsonObject)?.let { g -> facetGroup(g, url) } },
        )
    }

    /** issue #293 — `{"metadata": {"title": …}, "links": [{title, href, properties: {active, numberOfItems}}]}`. */
    private fun facetGroup(g: JsonObject, base: String): OpdsFacetGroup? {
        val title = g.obj("metadata")?.let { title(it["title"]) } ?: return null
        val facets = g.array("links").mapNotNull { item ->
            val link = item as? JsonObject ?: return@mapNotNull null
            val href = link.href(base) ?: return@mapNotNull null
            val props = link.obj("properties")
            OpdsFacet(
                title = link.string("title")?.trim() ?: return@mapNotNull null,
                href = href,
                active = (props?.get("active") as? JsonPrimitive)?.booleanOrNull == true,
                count = props?.long("numberOfItems")?.toInt(),
            )
        }
        return OpdsFacetGroup(title, facets).takeIf { facets.isNotEmpty() }
    }

    /** A single publication document (`application/opds-publication+json`), e.g. a detail page. */
    fun parsePublication(body: String, url: String): OpdsPublication? =
        (json.parseToJsonElement(body) as? JsonObject)?.let { publication(it, url) }

    private fun group(g: JsonObject, base: String): OpdsGroup? {
        val title = g.obj("metadata")?.let { title(it["title"]) } ?: return null
        return OpdsGroup(
            title = title,
            href = g.array("links").firstWithRel("self")?.href(base),
            navigation = navigation(g.array("navigation"), base),
            publications = g.array("publications").mapNotNull { (it as? JsonObject)?.let { p -> publication(p, base) } },
        )
    }

    private fun navigation(items: List<JsonElement>, base: String): List<OpdsLink> = items.mapNotNull { item ->
        val link = item as? JsonObject ?: return@mapNotNull null
        val href = link.href(base) ?: return@mapNotNull null
        OpdsLink(title = link.string("title") ?: href, href = href, type = link.string("type"))
    }

    private fun publication(p: JsonObject, base: String): OpdsPublication? {
        val metadata = p.obj("metadata") ?: return null
        val title = title(metadata["title"]) ?: return null
        val links = p.array("links")
        val images = p.array("images").mapNotNull { it as? JsonObject }
        val detail = links.firstWithRel("self")?.href(base)
        val series = (metadata.obj("belongsTo")?.get("series"))?.let { s ->
            (s as? JsonArray)?.firstOrNull() ?: s
        }
        return OpdsPublication(
            id = metadata.string("identifier") ?: detail ?: title,
            title = title,
            authors = contributors(metadata["author"]),
            // issue #295 — the spec allows HTML here.
            summary = metadata.string("description")?.let { OpdsText.join(OpdsText.paragraphsOf(it)) },
            language = metadata["language"].let { (it as? JsonArray)?.firstOrNull() ?: it }?.let { (it as? JsonPrimitive)?.contentOrNull },
            published = metadata.string("published"),
            publisher = contributors(metadata["publisher"]).firstOrNull(),
            coverUrl = (images.firstOrNull { it.rels().contains("cover") } ?: images.maxByOrNull { it.long("width") ?: 0 })?.href(base),
            thumbnailUrl = (images.minByOrNull { it.long("width") ?: Long.MAX_VALUE } ?: images.firstOrNull())?.href(base),
            acquisitions = links.mapNotNull { it as? JsonObject }.flatMap { link ->
                val href = link.href(base) ?: return@flatMap emptyList()
                link.rels().filter { it.startsWith(OpdsAcquisition.ACQUISITION) }.map { rel ->
                    OpdsAcquisition(href, link.string("type"), rel, link.string("title"), link.obj("properties")?.long("size"))
                }
            },
            series = (series as? JsonObject)?.string("name") ?: (series as? JsonPrimitive)?.contentOrNull,
            seriesPosition = (series as? JsonObject)?.get("position")?.let { (it as? JsonPrimitive)?.doubleOrNull },
            categories = contributors(metadata["subject"]),
            detailUrl = detail,
        )
    }

    /** A string, a `{name}` object, or a list of either → names. */
    private fun contributors(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonArray -> e.flatMap { contributors(it) }
        is JsonObject -> listOfNotNull(title(e["name"]))
        is JsonPrimitive -> listOfNotNull(e.contentOrNull?.takeIf { it.isNotBlank() })
    }

    /** A plain string, or a language map (`{"en": "…", "fr": "…"}`) → its English/first value. */
    private fun title(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        is JsonObject -> (e["en"] ?: e.values.firstOrNull())?.let { (it as? JsonPrimitive)?.contentOrNull }
        else -> null
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.rels(): List<String> = when (val r = this["rel"]) {
        is JsonArray -> r.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(r.contentOrNull)
        else -> emptyList()
    }

    /** An absolute URL. A templated link keeps its template (search), a plain one is resolved. */
    private fun JsonObject.href(base: String): String? {
        val raw = string("href") ?: return null
        return if ((this["templated"] as? JsonPrimitive)?.booleanOrNull == true) {
            resolveUrl(base, raw.substringBefore('{')) + raw.substring(raw.indexOf('{').takeIf { it >= 0 } ?: raw.length)
        } else {
            resolveUrl(base, raw)
        }
    }

    private fun List<JsonElement>.firstWithRel(rel: String): JsonObject? =
        mapNotNull { it as? JsonObject }.firstOrNull { rel in it.rels() }
}
