package net.dexxicon.reader.core.serverapi.opds

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** issue #291 — OPDS 2.0 and the OPDS 1.2 fallback, against real (trimmed) catalogs. */
class OpdsParserTest {

    // ---- OPDS 1.2: Project Gutenberg -------------------------------------------------------

    @Test
    fun gutenbergRootIsANavigationFeedWithOpenSearch() {
        val feed = Opds1Parser.parseFeed(OpdsFixtures.GUTENBERG_ROOT, "https://www.gutenberg.org/ebooks.opds/")
        assertEquals(OpdsVersion.OPDS_1, feed.version)
        assertTrue(feed.publications.isEmpty())
        assertTrue(feed.navigation.any { it.href == "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads" })
        assertEquals(OpdsSearch.Description("https://www.gutenberg.org/catalog/osd-books.xml"), feed.search)
    }

    @Test
    fun gutenbergListEntriesAreBookSummariesPointingAtTheirOwnFeed() {
        val feed = Opds1Parser.parseFeed(OpdsFixtures.GUTENBERG_POPULAR, "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads")
        val book = feed.publications.first()
        assertTrue(book.acquisitions.isEmpty())
        assertTrue(book.detailUrl!!.matches(Regex("""https://www\.gutenberg\.org/ebooks/\d+\.opds""")))
        assertTrue(book.authors.isNotEmpty(), "author comes from <content>")
        assertTrue(book.coverUrl!!.startsWith("https://www.gutenberg.org/cache/epub/"))
        assertNotNull(feed.nextUrl)
    }

    @Test
    fun gutenbergBookFeedHasOpenAccessEpubs() {
        // Two entries: the edition without images, then the one with.
        val book = Opds1Parser.parseFeed(OpdsFixtures.GUTENBERG_BOOK, "https://www.gutenberg.org/ebooks/1342.opds").publications
            .maxBy { it.acquisitions.size }
        assertEquals("Pride and Prejudice", book.title)
        assertEquals(listOf("Austen, Jane"), book.authors)
        val epub = book.acquisitions.first { it.href.endsWith("1342.epub3.images") }
        assertEquals("application/epub+zip", epub.type)
        assertTrue(epub.isOpenAccess)
        assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", book.coverUrl)
    }

    @Test
    fun gutenbergOpenSearchTemplateExpands() {
        val template = Opds1Parser.parseOpenSearchTemplate(OpdsFixtures.GUTENBERG_OSD, "https://www.gutenberg.org/catalog/osd-books.xml")
        assertEquals("http://m.gutenberg.org/ebooks/search.opds/?query=moby%20dick", OpdsSearch.Template(template!!).expand("moby dick"))
    }

    // ---- OPDS 2.0: Open Library -------------------------------------------------------------

    @Test
    fun openLibraryRootHasGroupsNavigationAndATemplatedSearch() {
        val feed = Opds2Parser.parseFeed(OpdsFixtures.OPEN_LIBRARY_ROOT, "https://openlibrary.org/opds/")
        assertEquals(OpdsVersion.OPDS_2, feed.version)
        assertEquals("Open Library", feed.title)
        assertEquals(2, feed.navigation.size)
        assertEquals("Trending Books", feed.groups.first().title)
        assertEquals("https://openlibrary.org/opds/?page=2", feed.nextUrl)
        val search = feed.search as OpdsSearch.Template
        assertEquals("https://openlibrary.org/opds/search?query=the%20hobbit", search.expand("the hobbit"))
    }

    @Test
    fun openLibraryPublicationsCarryCoversAndOpenAccessEpubs() {
        val feed = Opds2Parser.parseFeed(OpdsFixtures.OPEN_LIBRARY_ROOT, "https://openlibrary.org/opds/")
        val all = feed.groups.flatMap { it.publications }
        val first = all.first()
        assertTrue(first.coverUrl!!.startsWith("https://covers.openlibrary.org/"))
        assertTrue(first.authors.isNotEmpty())
        assertTrue(first.detailUrl!!.startsWith("https://openlibrary.org/opds/books/"))
        val open = all.first { p -> p.acquisitions.any { it.isOpenAccess } }
        assertEquals("application/epub+zip", open.acquisitions.first { it.isOpenAccess }.type)
    }

    // ---- The client: 2.0 first, 1.2 fallback ------------------------------------------------

    private fun client(body: String, contentType: String, seenAccept: MutableList<String> = mutableListOf()) =
        OpdsClient(
            HttpClient(
                MockEngine { request ->
                    seenAccept += request.headers[HttpHeaders.Accept].orEmpty()
                    respond(body, headers = headersOf(HttpHeaders.ContentType, contentType))
                },
            ),
        )

    @Test
    fun asksForOpds2FirstAndParsesIt() = runTest {
        val accept = mutableListOf<String>()
        val feed = client(OpdsFixtures.OPEN_LIBRARY_ROOT, "application/opds+json", accept).feed("https://openlibrary.org/opds/")
        assertEquals(OpdsVersion.OPDS_2, feed.version)
        assertTrue(accept.single().startsWith("application/opds+json"))
    }

    @Test
    fun fallsBackToOpds1WhenTheServerSendsAtom() = runTest {
        val feed = client(OpdsFixtures.GUTENBERG_ROOT, "application/atom+xml; charset=UTF-8").feed("https://www.gutenberg.org/ebooks.opds/")
        assertEquals(OpdsVersion.OPDS_1, feed.version)
    }

    @Test
    fun anHtmlPageIsNotACatalog() = runTest {
        assertFailsWith<OpdsFormatException> {
            client("<!DOCTYPE html><html><body>Hi</body></html>", "text/html").feed("https://example.com/")
        }
    }

    @Test
    fun aGutenbergDetailLinkResolvesToTheBookWithItsDownloads() = runTest {
        val book = client(OpdsFixtures.GUTENBERG_BOOK, "application/atom+xml").publication("https://www.gutenberg.org/ebooks/1342.opds")
        // Both editions' files, the with-images edition's first.
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", book!!.acquisitions.first().href)
        assertTrue(book.acquisitions.any { it.href.endsWith("1342.epub.noimages") })
    }

    @Test
    fun relativeLinksResolveLikeABrowser() {
        val base = "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads"
        assertEquals("https://www.gutenberg.org/ebooks/2701.opds", resolveUrl(base, "/ebooks/2701.opds"))
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?start_index=26", resolveUrl(base, "?start_index=26"))
        assertEquals("https://cdn.example.org/a.jpg", resolveUrl(base, "//cdn.example.org/a.jpg"))
        assertEquals("https://example.com/opds/books/1", resolveUrl("https://example.com/opds/catalog", "books/1"))
        assertEquals("https://example.com/b", resolveUrl("https://example.com/opds/x/", "../../b"))
        assertEquals("http://other.org/x", resolveUrl(base, "http://other.org/x"))
    }
}
