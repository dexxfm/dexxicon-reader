package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentFormatTest {

    @Test
    fun `maps common media types`() {
        assertThat(ContentFormat.fromMediaType("application/epub+zip")).isEqualTo(ContentFormat.EPUB)
        assertThat(ContentFormat.fromMediaType("application/pdf")).isEqualTo(ContentFormat.PDF)
        assertThat(ContentFormat.fromMediaType("application/vnd.comicbook+zip")).isEqualTo(ContentFormat.COMIC)
        assertThat(ContentFormat.fromMediaType("application/vnd.comicbook-rar")).isEqualTo(ContentFormat.COMIC)
        assertThat(ContentFormat.fromMediaType("audio/x-m4b")).isEqualTo(ContentFormat.AUDIOBOOK)
        assertThat(ContentFormat.fromMediaType("application/x-mobipocket-ebook")).isEqualTo(ContentFormat.MOBI)
    }

    @Test
    fun `ignores parameters and case`() {
        assertThat(ContentFormat.fromMediaType("APPLICATION/EPUB+ZIP; charset=utf-8"))
            .isEqualTo(ContentFormat.EPUB)
    }

    @Test
    fun `unknown or null media type is UNKNOWN`() {
        assertThat(ContentFormat.fromMediaType(null)).isEqualTo(ContentFormat.UNKNOWN)
        assertThat(ContentFormat.fromMediaType("application/octet-stream")).isEqualTo(ContentFormat.UNKNOWN)
    }

    @Test
    fun `cb7 comics are deliberately UNKNOWN, not COMIC — issue 110`() {
        assertThat(ContentFormat.fromMediaType("application/x-cb7")).isEqualTo(ContentFormat.UNKNOWN)
    }

    @Test
    fun `needsConversion for the formats without a native reader`() {
        assertThat(ContentFormat.FB2.needsConversion).isTrue()
        assertThat(ContentFormat.MOBI.needsConversion).isTrue()
        assertThat(ContentFormat.AZW3.needsConversion).isTrue()
        assertThat(ContentFormat.EPUB.needsConversion).isFalse()
        assertThat(ContentFormat.AUDIOBOOK.needsConversion).isFalse()
    }

    @Test
    fun `primary acquisition is the lowest-priority format`() {
        val detail = BookDetail(
            summary = BookSummary(id = "1", serverId = "s", title = "T"),
            acquisitions = listOf(
                Acquisition("u1", "application/pdf", ContentFormat.PDF, AcquisitionRelation.ACQUIRE),
                Acquisition("u2", "application/epub+zip", ContentFormat.EPUB, AcquisitionRelation.ACQUIRE),
            ),
        )
        assertThat(detail.primaryAcquisition?.format).isEqualTo(ContentFormat.EPUB)
    }
}
