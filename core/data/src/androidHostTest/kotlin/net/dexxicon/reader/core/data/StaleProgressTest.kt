package net.dexxicon.reader.core.data

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.data.catalog.BookOrbitCatalogSource
import net.dexxicon.reader.core.data.catalog.GrimmoryCatalogSource
import net.dexxicon.reader.core.database.entity.ReadingProgressEntity
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.browse.BookOrbitBrowseApi
import net.dexxicon.reader.core.serverapi.browse.GrimmoryBrowseApi
import org.junit.Test

/**
 * Issue #251 — the same book twice in Continue reading, the second copy's tap doing nothing:
 * a progress row for a book id that no longer exists server-side (deleted, or re-imported
 * under a new id) was never cleaned up.
 */
class StaleProgressTest {

    private fun row(bookId: String, percent: Double? = 0.4, dirty: Boolean = false, serverId: String = "s1") =
        ReadingProgressEntity(
            key = "$serverId::$bookId",
            serverId = serverId,
            bookId = bookId,
            percent = percent,
            locator = null,
            updatedAt = 0L,
            dirty = dirty,
        )

    @Test
    fun `only in-progress rows the server didn't list, with nothing unsynced or downloaded, are checked`() {
        val rows = listOf(
            row("listed"), // the server's continue list has it: fine
            row("stale"), // not listed: check it
            row("dirty", dirty = true), // unconfirmed local change: never touched
            row("downloaded"), // readable offline: never touched
            row("finished", percent = 1.0), // not on a Continue shelf anyway
            row("unstarted", percent = 0.0),
            row("stale", serverId = "s2"), // another server's row
        )
        val candidates = pruneCandidates(rows, "s1", seen = setOf("listed"), downloadedKeys = setOf("s1::downloaded"))
        assertThat(candidates.map { it.bookId }).containsExactly("stale")
    }

    @Test
    fun `checks are capped per pass`() {
        val rows = (1..25).map { row("b$it") }
        assertThat(pruneCandidates(rows, "s1", emptySet(), emptySet())).hasSize(MAX_PRUNE_CHECKS)
    }

    // --- a real 404 must be distinguishable from "couldn't reach the server" ---------------

    private fun client(status: HttpStatusCode): HttpClient = HttpClient(
        MockEngine { respond("""{"message":"x"}""", status, headersOf(HttpHeaders.ContentType, "application/json")) },
    ) {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private val bookOrbit = Server("s1", "Orbit", "https://orbit.test", type = ServerType.BOOKORBIT)
    private val grimmory = Server("s2", "Grim", "https://grim.test", type = ServerType.GRIMMORY)

    @Test
    fun `a 404 on book detail is NotFound on both servers, other errors are not`() = runBlocking<Unit> {
        val gone = BookOrbitCatalogSource(BookOrbitBrowseApi(client(HttpStatusCode.NotFound))).detail(bookOrbit, "9")
        assertThat((gone as Outcome.Failure).error).isInstanceOf(DexxiconError.NotFound::class.java)

        val goneG = GrimmoryCatalogSource(GrimmoryBrowseApi(client(HttpStatusCode.NotFound))).detail(grimmory, "9")
        assertThat((goneG as Outcome.Failure).error).isInstanceOf(DexxiconError.NotFound::class.java)

        val down = BookOrbitCatalogSource(BookOrbitBrowseApi(client(HttpStatusCode.BadGateway))).detail(bookOrbit, "9")
        assertThat((down as Outcome.Failure).error).isInstanceOf(DexxiconError.Network::class.java)
    }
}
