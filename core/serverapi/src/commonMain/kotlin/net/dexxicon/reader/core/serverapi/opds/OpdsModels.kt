package net.dexxicon.reader.core.serverapi.opds

import io.ktor.http.encodeURLParameter

/**
 * issue #291 — OPDS catalogs, as one model whichever generation the server speaks. [OpdsClient]
 * asks for OPDS 2.0 (JSON) first and falls back to OPDS 1.2 (Atom XML) when that's what comes
 * back; both parse into these types, with every link already resolved to an absolute URL.
 */
enum class OpdsVersion(val label: String) {
    OPDS_2("OPDS 2.0"),
    OPDS_1("OPDS 1.2"),
}

data class OpdsFeed(
    val version: OpdsVersion,
    /** The URL this feed was fetched from. */
    val url: String,
    val title: String,
    /** Links to other feeds — a catalog's sections, or a navigation feed's entries. */
    val navigation: List<OpdsLink> = emptyList(),
    val publications: List<OpdsPublication> = emptyList(),
    /** OPDS 2 groups (e.g. Open Library's "Trending Books"): a titled run of navigation and/or
     *  publications, often with a link to the full list. Empty for OPDS 1. */
    val groups: List<OpdsGroup> = emptyList(),
    /** The next page of this feed, when it's paged. */
    val nextUrl: String? = null,
    val search: OpdsSearch? = null,
    /** issue #293 — this feed's sort/filter choices (OPDS 1 facet links, OPDS 2 `facets`). */
    val facets: List<OpdsFacetGroup> = emptyList(),
)

/** issue #293 — an OPDS facet group, e.g. "Language", and its choices. */
data class OpdsFacetGroup(val title: String, val facets: List<OpdsFacet>)

data class OpdsFacet(val title: String, val href: String, val active: Boolean = false, val count: Int? = null)

data class OpdsLink(val title: String, val href: String, val type: String? = null)

data class OpdsGroup(
    val title: String,
    /** The group's own full feed, when it has one (its `self` link). */
    val href: String?,
    val navigation: List<OpdsLink> = emptyList(),
    val publications: List<OpdsPublication> = emptyList(),
)

/** How to search a catalog. */
sealed interface OpdsSearch {
    /** A URI template: OPDS 2's `{?query}`, or OpenSearch's `{searchTerms}`. */
    data class Template(val template: String) : OpdsSearch {
        fun expand(query: String): String {
            val q = query.encodeURLParameter()
            return template
                .replace(Regex("""\{\?query[^}]*\}"""), "?query=$q")
                .replace(Regex("""\{&query[^}]*\}"""), "&query=$q")
                .replace("{searchTerms}", q)
                // Optional OpenSearch parameters we don't fill ({startPage?}, {count?}, …).
                // Every brace escaped: Android's ICU regex rejects a bare `}` the JVM allows.
                .replace(Regex("""\{[^}]*\?\}"""), "")
        }
    }

    /** An OpenSearch description document (OPDS 1) — its template is fetched on first use. */
    data class Description(val url: String) : OpdsSearch
}

data class OpdsPublication(
    /** The publication's own identifier (a URN, ISBN or URL), else its detail URL or title. */
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val summary: String? = null,
    val language: String? = null,
    val published: String? = null,
    val publisher: String? = null,
    val coverUrl: String? = null,
    val thumbnailUrl: String? = null,
    val acquisitions: List<OpdsAcquisition> = emptyList(),
    val series: String? = null,
    val seriesPosition: Double? = null,
    val categories: List<String> = emptyList(),
    /** Where the complete entry lives, when this one is a summary without acquisitions (a
     *  Gutenberg list entry, an OPDS 1.2 partial entry) or has its own document (OPDS 2 `self`). */
    val detailUrl: String? = null,
)

data class OpdsAcquisition(
    val href: String,
    val type: String?,
    val rel: String,
    val title: String? = null,
    val length: Long? = null,
) {
    /** Free to download as-is: a plain or `open-access` acquisition, not a loan or purchase. */
    val isOpenAccess: Boolean get() = rel == ACQUISITION || rel == "$ACQUISITION/open-access"

    companion object {
        const val ACQUISITION = "http://opds-spec.org/acquisition"
    }
}

/** The response wasn't an OPDS feed (HTML, an error page, some other JSON). */
class OpdsFormatException(message: String) : Exception(message)
