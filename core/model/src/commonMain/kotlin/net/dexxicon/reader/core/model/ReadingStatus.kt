package net.dexxicon.reader.core.model

/**
 * The per-user reading status both servers track (and sync to Kobo / KOReader / Hardcover).
 * BookOrbit and Grimmory use different vocabularies; this is the common set the app offers.
 */
enum class ReadingStatus(val label: String) {
    UNREAD("Unread"),
    WANT_TO_READ("Want to read"),
    READING("Reading"),
    ON_HOLD("On hold"),
    REREADING("Rereading"),
    READ("Read"),
    ABANDONED("Did not finish");

    companion object {
        /** Parse whatever a server sent (any case, `_`-separated or not). */
        fun fromServer(raw: String?): ReadingStatus? =
            when (raw?.lowercase()?.replace("_", "")) {
                "unread" -> UNREAD
                "wanttoread" -> WANT_TO_READ
                "reading", "partiallyread" -> READING
                "onhold", "paused" -> ON_HOLD
                "rereading" -> REREADING
                "read" -> READ
                "abandoned", "skimmed", "wontread" -> ABANDONED
                else -> null // "unset" / null / unrecognised
            }
    }
}
