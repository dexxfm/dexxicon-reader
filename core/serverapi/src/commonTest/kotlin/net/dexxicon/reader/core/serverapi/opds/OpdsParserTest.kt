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
        // issue #295 — shown as Gutenberg's lists show it, not in catalogue order.
        assertEquals(listOf("Jane Austen"), book.authors)
        assertEquals("1998-06-01T00:00:00+00:00", book.published)
        assertTrue("Text" !in book.categories, "a DCMI type isn't a subject")
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

    // ---- Facets: sort and filter (issue #293) -----------------------------------------------

    @Test
    fun opds2FacetsParseWithTheirActiveChoiceAndCounts() {
        val body = """{"metadata":{"title":"Search"},"links":[],"facets":[
            {"metadata":{"title":"Availability"},"links":[
              {"title":"Everything","href":"/opds/search?query=x","type":"application/opds+json","properties":{"active":true}},
              {"title":"Open Access","href":"/opds/search?query=x&mode=open_access","type":"application/opds+json"}]},
            {"metadata":{"title":"Language"},"links":[
              {"title":"English","href":"/opds/search?query=x&language=en","properties":{"numberOfItems":3729841}}]}]}"""
        val facets = Opds2Parser.parseFeed(body, "https://openlibrary.org/opds/search?query=x").facets
        assertEquals(listOf("Availability", "Language"), facets.map { it.title })
        assertEquals("Everything", facets[0].facets.single { it.active }.title)
        assertEquals("https://openlibrary.org/opds/search?query=x&mode=open_access", facets[0].facets[1].href)
        assertEquals(3729841, facets[1].facets[0].count)
    }

    @Test
    fun opds1FacetLinksGroupByFacetGroup() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog"
              xmlns:thr="http://purl.org/syndication/thread/1.0"><title>Books</title>
            <link rel="http://opds-spec.org/facet" href="/opds/books?sort=title" title="Title" opds:facetGroup="Sort by"/>
            <link rel="http://opds-spec.org/facet" href="/opds/books?sort=new" title="Newest" opds:facetGroup="Sort by" opds:activeFacet="true"/>
            <link rel="http://opds-spec.org/facet" href="/opds/books?lang=fr" title="French" opds:facetGroup="Language" thr:count="12"/>
            </feed>"""
        val facets = Opds1Parser.parseFeed(body, "https://calibre.example/opds/books").facets
        assertEquals(listOf("Sort by", "Language"), facets.map { it.title })
        assertEquals("Newest", facets[0].facets.single { it.active }.title)
        assertEquals("https://calibre.example/opds/books?lang=fr", facets[1].facets.single().href)
        assertEquals(12, facets[1].facets.single().count)
    }

    @Test
    fun gutenbergListsOfferTheirSortOrders() {
        val url = "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&start_index=26"
        val sort = Opds1Parser.parseFeed(OpdsFixtures.GUTENBERG_POPULAR, url).facets.single()
        assertEquals("Sort order", sort.title)
        assertEquals(listOf("Popular", "Newest", "Title"), sort.facets.map { it.title })
        assertEquals("Popular", sort.facets.single { it.active }.title)
        // A new order starts from the first page.
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?sort_order=title", sort.facets.last().href)
    }

    @Test
    fun gutenbergSearchKeepsItsQueryWhenSorted() {
        val sort = Opds1Parser.parseFeed(OpdsFixtures.GUTENBERG_POPULAR, "https://www.gutenberg.org/ebooks/search.opds/?query=moby").facets.single()
        assertTrue(sort.facets.none { it.active })
        assertEquals("https://www.gutenberg.org/ebooks/search.opds/?query=moby&sort_order=release_date", sort.facets[1].href)
    }

    @Test
    fun theGutenbergRootIsNotSortable() {
        assertTrue(Opds1Parser.parseFeed(OpdsFixtures.GUTENBERG_ROOT, "https://www.gutenberg.org/ebooks.opds/").facets.isEmpty())
    }

    // ---- Descriptions (issue #295) -------------------------------------------------------

    private fun gutenbergEntry(content: String) = """<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title><entry>
        <title>Pride and Prejudice</title><id>urn:gutenberg:1342:2</id>
        <author><name>Austen, Jane</name></author>
        <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" href="/ebooks/1342.epub3.images"/>
        <content type="xhtml">
        <div xmlns="http://www.w3.org/1999/xhtml">$content</div>
        </content></entry></feed>"""

    private val gutenbergRecord = """<p>This edition had all images removed.</p>
        <p>
        Title:
        Pride and Prejudice
        </p>
        <p>
        Note:
        Wikipedia page about this book: https:<a href="//en.wikipedia.org/wiki/Pride_and_Prejudice">//en.wikipedia.org/wiki/Pride_and_Prejudice</a>
        </p>
        <p>
        Summary:
        "Pride and Prejudice" by Jane Austen is a novel published in 1813. It follows
        Elizabeth Bennet. (This is an automatically generated summary.)
        </p>
        <p>
        Reading Level:
        Reading ease score: 69.2 (8th &amp; 9th grade).
        </p>
        <p>Author: Austen, Jane, 1775-1817</p>
        <p>EBook No.: 1342</p>
        <p>Subject: England -- Fiction</p><p>Subject: Love stories</p>
        <p>Rights: Public domain in the USA.</p>"""

    @Test
    fun aGutenbergRecordShowsJustItsSummary() {
        val book = Opds1Parser.parseFeed(gutenbergEntry(gutenbergRecord), "https://www.gutenberg.org/ebooks/1342.opds").publications.single()
        assertEquals(
            "\"Pride and Prejudice\" by Jane Austen is a novel published in 1813. It follows Elizabeth Bennet. " +
                "(This is an automatically generated summary.)",
            book.summary,
        )
    }

    @Test
    fun aGutenbergRecordWithoutASummaryDropsWhatTheDetailsAlreadyShow() {
        val record = gutenbergRecord.substringBefore("<p>\n        Summary:") +
            gutenbergRecord.substringAfter("summary.)\n        </p>")
        val book = Opds1Parser.parseFeed(gutenbergEntry(record), "https://www.gutenberg.org/ebooks/1342.opds").publications.single()
        assertEquals(
            "This edition had all images removed.\n\n" +
                "Note: Wikipedia page about this book: https://en.wikipedia.org/wiki/Pride_and_Prejudice\n\n" +
                "Reading Level: Reading ease score: 69.2 (8th & 9th grade).",
            book.summary,
        )
    }

    @Test
    fun xhtmlAndHtmlDescriptionsFlowIntoParagraphs() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title>
            <entry><title>A</title><id>a</id><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/a.epub"/>
              <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>One line,
                hard-wrapped.</p><p>Two<br/>Three</p></div></content></entry>
            <entry><title>B</title><id>b</id><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/b.epub"/>
              <summary type="html">&lt;p&gt;Fish &amp;amp; chips&amp;hellip;&lt;/p&gt;&lt;p&gt;&lt;i&gt;Second&lt;/i&gt;&lt;/p&gt;</summary></entry>
            <entry><title>C</title><id>c</id><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/c.epub"/>
              <summary>First paragraph
            wrapped.

            Second.</summary></entry>
            </feed>"""
        val books = Opds1Parser.parseFeed(body, "https://calibre.example/opds").publications
        assertEquals(
            listOf("One line, hard-wrapped.\n\nTwo\n\nThree", "Fish & chips…\n\nSecond", "First paragraph wrapped.\n\nSecond."),
            books.map { it.summary },
        )
    }

    @Test
    fun anOpds2HtmlDescriptionIsPlainText() {
        val body = """{"metadata":{"title":"Feed"},"publications":[{"metadata":{"title":"A",
            "description":"<p>Line one.</p>\n<p>Line &amp; two.</p>"},"links":[]}]}"""
        assertEquals("Line one.\n\nLine & two.", Opds2Parser.parseFeed(body, "https://x.example/").publications.single().summary)
    }

    @Test
    fun aGutenbergListEntryWithoutAnAuthorDoesNotShowItsDownloadsAsOne() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Popular</title>
            <entry><title>Chambers's Twentieth Century Dictionary</title><id>urn:gutenberg:37683</id>
              <content type="text">52555 downloads</content>
              <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/37683.opds"/></entry>
            </feed>"""
        val book = Opds1Parser.parseFeed(body, "https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads").publications.single()
        assertEquals(emptyList(), book.authors)
    }

    @Test
    fun catalogueNamesReadFirstNameFirst() {
        assertEquals("Jane Austen", OpdsText.uninvertName("Austen, Jane"))
        assertEquals("J. R. R. Tolkien", OpdsText.uninvertName("Tolkien, J. R. R. (John Ronald Reuel)"))
        assertEquals("Martin Luther King, Jr.", OpdsText.uninvertName("King, Martin Luther, Jr."))
        assertEquals("Various", OpdsText.uninvertName("Various"))
        assertEquals("Great Britain. Parliament", OpdsText.uninvertName("Great Britain. Parliament"))
    }
}
