package net.dexxicon.reader.core.model

/** Every content type the app knows how to route. `priority` picks a default acquisition. */
enum class ContentFormat(val priority: Int, val streamable: Boolean) {
    EPUB(0, true),
    PDF(1, true),
    COMIC(2, true),
    AUDIOBOOK(3, true),
    FB2(4, false),
    MOBI(5, false),
    AZW3(6, false),
    UNKNOWN(99, false);

    val needsConversion: Boolean get() = this == FB2 || this == MOBI || this == AZW3

    companion object {
        private val byMediaType: Map<String, ContentFormat> = buildMap {
            put("application/epub+zip", EPUB)
            put("application/pdf", PDF)
            put("application/vnd.comicbook+zip", COMIC)
            put("application/vnd.comicbook-rar", COMIC)
            put("application/x-cbz", COMIC)
            put("application/x-cbr", COMIC)
            // application/x-cb7 (7z) deliberately NOT mapped to COMIC — issue #110: dropped
            // support rather than adding real 7z-extraction to back it, so it falls through
            // to UNKNOWN like any other archive format nothing here can actually open.
            put("application/audiobook+zip", AUDIOBOOK)
            put("application/audiobook+json", AUDIOBOOK)
            put("audio/mpeg", AUDIOBOOK)
            put("audio/mp4", AUDIOBOOK)
            put("audio/x-m4b", AUDIOBOOK)
            put("audio/x-m4a", AUDIOBOOK)
            put("audio/aac", AUDIOBOOK)
            put("audio/ogg", AUDIOBOOK)
            put("audio/flac", AUDIOBOOK)
            put("application/x-fictionbook+xml", FB2)
            put("application/fb2+zip", FB2)
            put("application/x-mobipocket-ebook", MOBI)
            put("application/vnd.amazon.ebook", AZW3)
            put("application/vnd.amazon.mobi8-ebook", AZW3)
        }

        fun fromMediaType(mediaType: String?): ContentFormat {
            if (mediaType == null) return UNKNOWN
            val normalized = mediaType.substringBefore(';').trim().lowercase()
            return byMediaType[normalized] ?: UNKNOWN
        }
    }
}
