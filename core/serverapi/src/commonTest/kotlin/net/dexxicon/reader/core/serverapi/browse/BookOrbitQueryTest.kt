package net.dexxicon.reader.core.serverapi.browse

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** issue #287 — the `BookQuery` body, encoded with the HTTP client's own JSON settings. */
class BookOrbitQueryTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Test
    fun aFormatFilterIsSentAsAGroupRule() {
        val body = json.encodeToString(
            BookOrbitQuery(
                pagination = BookOrbitPagination(page = 0, size = 40),
                filter = BookOrbitFilterGroup(
                    type = "group",
                    join = "AND",
                    rules = listOf(BookOrbitFilterRule("rule", "format", "includesAny", listOf("cbz", "cbr"))),
                ),
            ),
        )
        assertEquals(
            """{"pagination":{"page":0,"size":40},"filter":{"type":"group","join":"AND","rules":""" +
                """[{"type":"rule","field":"format","operator":"includesAny","value":["cbz","cbr"]}]}}""",
            body,
        )
    }

    @Test
    fun noFilterMeansNoFilterKey() {
        val body = json.encodeToString(BookOrbitQuery(pagination = BookOrbitPagination(page = 0, size = 40)))
        assertFalse("filter" in body)
    }
}
