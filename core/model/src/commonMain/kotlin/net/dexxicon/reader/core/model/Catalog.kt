package net.dexxicon.reader.core.model

/** A parsed OPDS feed reduced to what the UI needs. */
data class CatalogFeed(
    val title: String,
    val feedUrl: String,
    val navigation: List<CatalogLink> = emptyList(),
    val entries: List<CatalogEntry> = emptyList(),
    val facets: List<CatalogFacetGroup> = emptyList(),
    val searchUrlTemplate: String? = null,
    val nextPageUrl: String? = null,
    val prevPageUrl: String? = null,
)

data class CatalogLink(
    val title: String,
    val href: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
)

data class CatalogFacetGroup(
    val name: String,
    val facets: List<CatalogFacet>,
)

data class CatalogFacet(
    val title: String,
    val href: String,
    val active: Boolean,
    val count: Int? = null,
)

/** A publication in a feed, with the acquisition links we can act on. */
data class CatalogEntry(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val summary: String? = null,
    val coverUrl: String? = null,
    val thumbnailUrl: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val series: String? = null,
    val acquisitions: List<Acquisition> = emptyList(),
    val pageStream: PageStreamInfo? = null,
) {
    val primaryAcquisition: Acquisition?
        get() = acquisitions.minByOrNull { it.format.priority }
}

data class Acquisition(
    val href: String,
    val mediaType: String,
    val format: ContentFormat,
    val relation: AcquisitionRelation,
    val sizeBytes: Long? = null,
)

enum class AcquisitionRelation { ACQUIRE, OPEN_ACCESS, BORROW, SAMPLE, SUBSCRIBE }

/** OPDS Page Streaming Extension data attached to a comic entry. */
data class PageStreamInfo(
    val hrefTemplate: String,
    val count: Int,
    val mediaType: String,
    val lastRead: Int? = null,
)
