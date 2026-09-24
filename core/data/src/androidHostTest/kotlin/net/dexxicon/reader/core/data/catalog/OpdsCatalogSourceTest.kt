package net.dexxicon.reader.core.data.catalog

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.BookGroupKind
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.opds.OpdsClient
import org.junit.Test

/** issue #291 — an OPDS 1 catalog without per-book detail links (Calibre-style). */
class OpdsCatalogSourceTest {

    private val root = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Test Catalog</title>
        <entry><title>All books</title><id>urn:all</id>
          <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=acquisition" href="/opds/books"/></entry></feed>"""

    private val books = """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>All books</title>
        <entry><title>The Private Book</title><id>urn:test:book:1</id><author><name>Test Author</name></author>
          <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/files/1.epub"/>
          <link rel="http://opds-spec.org/acquisition/borrow" type="application/epub+zip" href="/loan/1"/></entry></feed>"""

    private fun source() = OpdsCatalogSource(
        OpdsClient(
            HttpClient(
                MockEngine { request ->
                    val body = when (request.url.encodedPath) {
                        "/opds" -> root
                        "/opds/books" -> books
                        else -> return@MockEngine respond("", HttpStatusCode.NotFound)
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"))
                },
            ),
        ),
    )

    private val server = Server(
        id = "s",
        displayName = "Test Catalog",
        baseUrl = "https://catalog.example/opds",
        type = ServerType.GENERIC,
        authMode = AuthMode.BASIC,
    )

    @Test
    fun `sections become libraries`() = runBlocking<Unit> {
        val groups = (source().groups(server, BookGroupKind.LIBRARY) as Outcome.Success).value
        assertThat(groups.map { it.name to it.id }).containsExactly("All books" to "https://catalog.example/opds/books")
    }

    @Test
    fun `a book listed by one instance opens in another`() = runBlocking<Unit> {
        // The Library lists through the shared container's instance; the Android reader resolves
        // the same id through its own graph's — and ids must survive a restart too.
        val listed = (source().books(server, "https://catalog.example/opds/books", null, BookSort.RECENT, 0, 40) as Outcome.Success)
            .value.books.single()
        val detail = (source().detail(server, listed.id) as Outcome.Success).value
        assertThat(detail.summary.title).isEqualTo("The Private Book")
        assertThat(detail.summary.format).isEqualTo(ContentFormat.EPUB)
        // Only the plain acquisition — the borrow link isn't offered.
        assertThat(detail.acquisitions.map { it.href }).containsExactly("https://catalog.example/files/1.epub")
    }

    @Test
    fun `entry ids round-trip whatever characters they hold`() {
        val id = OpdsCatalogSource.entryBookId("https://c.example/opds/books?page=2#x", "urn:a:b/c?d")
        assertThat(OpdsCatalogSource.decodeEntryBookId(id))
            .isEqualTo("https://c.example/opds/books?page=2#x" to "urn:a:b/c?d")
    }
}
