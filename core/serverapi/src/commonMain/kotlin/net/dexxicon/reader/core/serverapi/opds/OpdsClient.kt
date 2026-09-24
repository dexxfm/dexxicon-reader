package net.dexxicon.reader.core.serverapi.opds

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * issue #291 — fetches OPDS catalogs. Every request asks for OPDS 2.0 first
 * (`application/opds+json`) with OPDS 1.2 (Atom) as the fallback, then parses whichever the
 * server actually sent. Many servers ignore `Accept` and only ever send one, so the choice is
 * made from the response (its content type, else its first character) rather than trusted to
 * negotiation.
 *
 * Credentials come from [client]'s auth plugin for a saved server (HTTP Basic, matched by
 * host); [authorization] overrides that for a catalog that isn't saved yet (Add server's test).
 */
class OpdsClient(private val client: HttpClient) {

    suspend fun feed(url: String, authorization: String? = null): OpdsFeed {
        val (body, contentType) = fetch(url, authorization)
        return when (kindOf(body, contentType)) {
            Kind.JSON -> Opds2Parser.parseFeed(body, url)
            Kind.XML -> Opds1Parser.parseFeed(body, url)
            Kind.OTHER -> throw OpdsFormatException("That address didn't return an OPDS catalog")
        }
    }

    /**
     * A publication's full entry, from its [OpdsPublication.detailUrl]: an OPDS 2 publication
     * document, or an OPDS 1 feed/entry whose first publication is the one (Gutenberg's
     * `/ebooks/<n>.opds`).
     */
    suspend fun publication(url: String): OpdsPublication? {
        val (body, contentType) = fetch(url, null)
        return when (kindOf(body, contentType)) {
            Kind.JSON -> {
                // Some catalogs answer a detail link with a one-publication feed instead.
                Opds2Parser.parsePublication(body, url)
                    ?: runCatching { Opds2Parser.parseFeed(body, url).publications.firstOrNull() }.getOrNull()
            }
            Kind.XML -> {
                val wrapped = if (body.contains("<feed")) body else wrapEntry(body)
                mergeEditions(Opds1Parser.parseFeed(wrapped, url).publications)
            }
            Kind.OTHER -> null
        }
    }

    /** A search URL for [query]: OPDS 2 templates expand directly; an OpenSearch description
     *  (OPDS 1) is fetched for its template first. Null if the catalog can't be searched. */
    suspend fun searchUrl(search: OpdsSearch, query: String): String? = when (search) {
        is OpdsSearch.Template -> search.expand(query)
        is OpdsSearch.Description -> {
            val (body, _) = fetch(search.url, null)
            Opds1Parser.parseOpenSearchTemplate(body, search.url)?.let { OpdsSearch.Template(it).expand(query) }
        }
    }

    private suspend fun fetch(url: String, authorization: String?): Pair<String, String?> {
        val response = client.get(url) {
            header(HttpHeaders.Accept, ACCEPT)
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }
        return response.bodyAsText() to response.contentType()?.toString()
    }

    private enum class Kind { JSON, XML, OTHER }

    private fun kindOf(body: String, contentType: String?): Kind {
        val type = contentType.orEmpty().lowercase()
        val first = body.firstOrNull { !it.isWhitespace() && it != '﻿' }
        return when {
            "json" in type || first == '{' -> Kind.JSON
            ("atom" in type || "xml" in type) && "html" !in type -> Kind.XML
            first == '<' && !body.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true) &&
                !body.trimStart().startsWith("<html", ignoreCase = true) -> Kind.XML
            else -> Kind.OTHER
        }
    }

    /**
     * A book's own OPDS 1 feed can list it more than once, one entry per edition (Gutenberg:
     * with and without images). The richest entry wins, with the other editions' downloads
     * added after its own, so every file stays on offer.
     */
    private fun mergeEditions(entries: List<OpdsPublication>): OpdsPublication? {
        val main = entries.maxByOrNull { it.acquisitions.size } ?: return null
        val editions = entries.filter { it.title == main.title && it !== main }
        return main.copy(acquisitions = (main.acquisitions + editions.flatMap { it.acquisitions }).distinctBy { it.href })
    }

    private fun wrapEntry(entryXml: String): String =
        "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>entry</title>" +
            entryXml.substringAfter("?>") + "</feed>"

    private companion object {
        const val ACCEPT = "application/opds+json, application/atom+xml;profile=opds-catalog;q=0.9, " +
            "application/atom+xml;q=0.8, application/json;q=0.5, */*;q=0.1"
    }
}
