package net.dexxicon.reader.shared.library

import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.model.BookSort
import net.dexxicon.reader.core.model.ContentFilter
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.model.Download
import net.dexxicon.reader.core.model.HomeSection
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.shared.home.ContinueItem
import net.dexxicon.reader.shared.home.HomeUiState
import net.dexxicon.reader.shared.home.OnDeckItem
import org.junit.Test

class HomeShelfBooksTest {
    private val servers = listOf(Server("server", "Library", "https://example.test", ServerType.BOOKORBIT))
    private val home = HomeUiState(
        continueReading = listOf(ContinueItem("server", "read", "Reading", "Author", null, ContentFormat.EPUB, 0.5f)),
        continueListening = listOf(ContinueItem("server", "listen", "Listening", null, null, ContentFormat.AUDIOBOOK, 0.2f)),
        onDeck = listOf(OnDeckItem("server", "deck", "On deck", null, null, ContentFormat.EPUB)),
        downloads = listOf(
            Download("server", "z", "Zulu", listOf("Writer"), format = ContentFormat.EPUB),
            Download("server", "a", "Alpha", listOf("Other"), format = ContentFormat.PDF),
        ),
        loading = false,
    )

    @Test
    fun `each section lists only its own books`() {
        fun ids(section: HomeSection) =
            homeShelfBooks(section, home, servers, LibraryUiState()).map { it.primary.bookId }
        assertThat(ids(HomeSection.CONTINUE_READING)).containsExactly("read")
        assertThat(ids(HomeSection.CONTINUE_LISTENING)).containsExactly("listen")
        assertThat(ids(HomeSection.ON_DECK)).containsExactly("deck")
        assertThat(ids(HomeSection.DOWNLOADED)).containsExactly("z", "a").inOrder()
    }

    @Test
    fun `shelf list supports sort search and format filters`() {
        fun books(state: LibraryUiState) = homeShelfBooks(HomeSection.DOWNLOADED, home, servers, state)
        assertThat(books(LibraryUiState(sort = BookSort.TITLE)).map { it.title }).containsExactly("Alpha", "Zulu").inOrder()
        assertThat(books(LibraryUiState(query = "writer")).map { it.primary.bookId }).containsExactly("z")
        assertThat(books(LibraryUiState(filter = ContentFilter.PDFS)).map { it.primary.bookId }).containsExactly("a")
        assertThat(books(LibraryUiState(query = "missing"))).isEmpty()
    }
}
