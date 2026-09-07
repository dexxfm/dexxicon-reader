package net.dexxicon.reader.core.data.catalog

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.model.BookPage
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.BookSummary
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import org.junit.Test

class AggregateMergeTest {

    private fun server(id: String, name: String) =
        Server(id = id, displayName = name, baseUrl = "https://$id", type = ServerType.BOOKORBIT)

    private fun book(id: String, title: String, author: String, format: ContentFormat, cover: String? = null) =
        BookSummary(id = id, serverId = "", title = title, authors = listOf(author), coverUrl = cover, format = format)

    private fun page(vararg books: BookSummary, hasMore: Boolean = false) =
        BookPage(books = books.toList(), page = 0, hasMore = hasMore)

    @Test
    fun `normalizeKeyPart strips case punctuation and whitespace`() {
        assertThat(normalizeKeyPart("  The  Hobbit!! ")).isEqualTo("the hobbit")
        assertThat(normalizeKeyPart("Dune (Special Ed.)")).isEqualTo("dune special ed")
    }

    @Test
    fun `aggregateKey differs by format but not by punctuation`() {
        val a = aggregateKey("Dune", "Frank Herbert", ContentFormat.EPUB)
        val b = aggregateKey("dune!", "frank  herbert", ContentFormat.EPUB)
        val c = aggregateKey("Dune", "Frank Herbert", ContentFormat.AUDIOBOOK)
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `same title-author-format on two servers merges into one book with two copies`() {
        val bookOrbit = server("bo", "BookOrbit")
        val grimmory = server("gr", "Grimmory")
        val merged = mergeAggregated(
            listOf(
                bookOrbit to page(book("1", "Dune", "Frank Herbert", ContentFormat.EPUB, cover = null)),
                grimmory to page(book("42", "dune", "frank herbert", ContentFormat.EPUB, cover = "c.jpg")),
            ),
            BookSort.RECENT,
        )
        assertThat(merged.books).hasSize(1)
        val entry = merged.books.single()
        assertThat(entry.copies.map { it.serverId }).containsExactly("bo", "gr")
        assertThat(entry.copies.map { it.serverName }).containsExactly("BookOrbit", "Grimmory")
        assertThat(entry.coverUrl).isEqualTo("c.jpg") // first non-null wins
    }

    @Test
    fun `a BookLore "Title - Author" suffix matches the plain title on another server`() {
        val bo = server("bo", "BookOrbit")
        val gr = server("gr", "Grimmory")
        val merged = mergeAggregated(
            listOf(
                bo to page(book("1", "Gantz Omnibus Volume 7", "Hiroya Oku", ContentFormat.COMIC)),
                gr to page(book("9", "Gantz Omnibus Volume 7 - Hiroya Oku", "Hiroya Oku", ContentFormat.COMIC)),
            ),
            BookSort.RECENT,
        )
        assertThat(merged.books).hasSize(1)
        assertThat(merged.books.single().copies).hasSize(2)
        assertThat(merged.books.single().title).isEqualTo("Gantz Omnibus Volume 7") // shorter wins
    }

    @Test
    fun `different formats of one work stay separate`() {
        val s = server("bo", "BookOrbit")
        val merged = mergeAggregated(
            listOf(
                s to page(
                    book("1", "Dune", "Frank Herbert", ContentFormat.EPUB),
                    book("2", "Dune", "Frank Herbert", ContentFormat.AUDIOBOOK),
                ),
            ),
            BookSort.RECENT,
        )
        assertThat(merged.books).hasSize(2)
    }

    @Test
    fun `hasMore is true when any source has more`() {
        val a = server("a", "A")
        val b = server("b", "B")
        val merged = mergeAggregated(
            listOf(
                a to page(book("1", "X", "Y", ContentFormat.EPUB), hasMore = false),
                b to page(book("2", "Z", "W", ContentFormat.EPUB), hasMore = true),
            ),
            BookSort.TITLE,
        )
        assertThat(merged.hasMore).isTrue()
        assertThat(merged.books.map { it.title }).containsExactly("X", "Z").inOrder()
    }
}
