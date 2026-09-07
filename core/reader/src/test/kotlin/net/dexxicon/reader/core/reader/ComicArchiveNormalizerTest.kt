package net.dexxicon.reader.core.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComicArchiveNormalizerTest {

    @Test
    fun `detects cbr and rar by extension`() {
        assertThat(comicSourceLooksLikeRar("https://x/comic.cbr", null)).isTrue()
        assertThat(comicSourceLooksLikeRar("https://x/comic.CBR", null)).isTrue()
        assertThat(comicSourceLooksLikeRar("https://x/comic.rar?token=abc", null)).isTrue()
    }

    @Test
    fun `detects rar by media type`() {
        assertThat(comicSourceLooksLikeRar("https://x/files/5/serve", "application/vnd.comicbook-rar")).isTrue()
        assertThat(comicSourceLooksLikeRar("https://x/files/5/serve", "application/x-cbr")).isTrue()
    }

    @Test
    fun `zip-based comics are not RAR`() {
        assertThat(comicSourceLooksLikeRar("https://x/comic.cbz", null)).isFalse()
        assertThat(comicSourceLooksLikeRar("https://x/files/5/serve", "application/vnd.comicbook+zip")).isFalse()
        assertThat(comicSourceLooksLikeRar("https://x/files/5/serve", "COMIC")).isFalse()
        assertThat(comicSourceLooksLikeRar(null, null)).isFalse()
    }
}
