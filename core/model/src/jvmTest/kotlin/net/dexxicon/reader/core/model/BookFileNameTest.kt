package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookFileNameTest {

    @Test
    fun `title and author with the file's own extension`() {
        assertThat(bookFileName("Dune", "Frank Herbert", "epub", ContentFormat.EPUB)).isEqualTo("Dune - Frank Herbert.epub")
        assertThat(bookFileName("Dune", null, "EPUB", ContentFormat.EPUB)).isEqualTo("Dune.epub")
    }

    @Test
    fun `illegal characters and separators are replaced, whitespace collapsed`() {
        assertThat(bookFileName("What If?: Serious / Absurd <Answers>", "R. Munroe", "pdf", ContentFormat.PDF))
            .isEqualTo("What If Serious Absurd Answers - R. Munroe.pdf")
        assertThat(bookFileName("...", null, "epub", ContentFormat.EPUB)).isEqualTo("Book.epub")
    }

    @Test
    fun `missing or odd extensions fall back to the format's usual one`() {
        assertThat(bookFileName("X", null, null, ContentFormat.COMIC)).isEqualTo("X.cbz")
        assertThat(bookFileName("X", null, "tar.gz", ContentFormat.PDF)).isEqualTo("X.pdf")
        assertThat(bookFileName("X", null, ".azw3", ContentFormat.AZW3)).isEqualTo("X.azw3")
    }

    @Test
    fun `very long names are capped`() {
        val name = bookFileName("A".repeat(400), null, "epub", ContentFormat.EPUB)
        assertThat(name.length).isAtMost(125)
        assertThat(name).endsWith(".epub")
    }

    @Test
    fun `mime types follow the extension`() {
        assertThat(bookMimeType("Dune.epub")).isEqualTo("application/epub+zip")
        assertThat(bookMimeType("X.CBZ")).isEqualTo("application/vnd.comicbook+zip")
        assertThat(bookMimeType("X")).isEqualTo("application/octet-stream")
    }
}
