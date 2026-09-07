package net.dexxicon.reader.core.data.catalog

import net.dexxicon.reader.core.model.ContentFormat

/** Lower-case, strip everything but letters/digits, collapse runs to single spaces. */
internal fun normalizeKeyPart(value: String?): String =
    value.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * The identity two book copies must share to be merged into one Browse entry:
 * normalized title + normalized first author + content format.
 *
 * BookLore/Grimmory append `" - <author>"` to titles; that suffix is stripped so a book
 * titled "Gantz Vol. 7 - Hiroya Oku" on one server matches "Gantz Vol. 7" on another.
 */
internal fun aggregateKey(title: String, author: String?, format: ContentFormat): String {
    val normalizedAuthor = normalizeKeyPart(author)
    var normalizedTitle = normalizeKeyPart(title)
    if (normalizedAuthor.isNotEmpty() && normalizedTitle.endsWith(" $normalizedAuthor")) {
        normalizedTitle = normalizedTitle.removeSuffix(" $normalizedAuthor").trim()
    }
    return "$normalizedTitle|$normalizedAuthor|${format.name}"
}
