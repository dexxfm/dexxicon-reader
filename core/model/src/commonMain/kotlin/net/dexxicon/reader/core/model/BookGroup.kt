package net.dexxicon.reader.core.model

/**
 * The kinds of named book groupings a server exposes that the Library can browse into
 * (issues #253, #254, #256). Each server family maps its own concepts onto these:
 *
 * | kind         | BookOrbit          | Grimmory / BookLore |
 * |--------------|--------------------|---------------------|
 * | [LIBRARY]    | libraries          | libraries           |
 * | [COLLECTION] | collections        | shelves             |
 * | [SMART]      | smart scopes       | magic shelves       |
 * | [SERIES]     | series (by id)     | series (by name)    |
 */
enum class BookGroupKind { LIBRARY, COLLECTION, SMART, SERIES }

/**
 * One named grouping of books on one server — a library, a collection, a smart scope or a
 * series. [id] is whatever that server addresses it by: a numeric id everywhere except
 * Grimmory's series, which it keys by name.
 */
data class BookGroup(
    val serverId: String,
    val kind: BookGroupKind,
    val id: String,
    val name: String,
    val bookCount: Int? = null,
    /** A representative cover (e.g. a series' first volume), when the server offers one. */
    val coverUrl: String? = null,
    /** Server display name, for telling same-named groups on different servers apart. */
    val serverName: String? = null,
    /** Series only: the authors across its volumes. */
    val authors: List<String> = emptyList(),
) {
    /** Stable identity across renames — used as a list key and for pinning (issue #254). */
    val key: String get() = "$serverId:${kind.name}:$id"
}

/**
 * One series as the Library's Series tab shows it (issue #256): every server's same-named
 * series merged into one row, the way [AggregatedBook] merges a book's copies.
 */
data class SeriesEntry(
    val name: String,
    val groups: List<BookGroup>,
    val authors: List<String> = emptyList(),
    val coverUrl: String? = null,
) {
    /** Total across servers — an overcount only when two servers hold the same volume. */
    val bookCount: Int? get() = groups.mapNotNull { it.bookCount }.takeIf { it.isNotEmpty() }?.sum()
}

/** One server's page of [BookGroup]s — the Series tab pages through series (issue #256). */
data class BookGroupPage(
    val groups: List<BookGroup>,
    val hasMore: Boolean,
)

data class SeriesPage(
    val series: List<SeriesEntry>,
    val hasMore: Boolean,
)

/** Series names compare case- and whitespace-insensitively when merging across servers. */
fun seriesKey(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")
