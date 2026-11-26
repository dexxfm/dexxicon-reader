package net.dexxicon.reader.core.data.font

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookOrbitFontLibraryTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = Server("one", "Library", "https://example.test", ServerType.BOOKORBIT)
    private val face = """{"id":1,"familyName":"Example","format":"ttf","weight":400,"style":"normal","fileSize":4}"""

    private fun client(failSecond: Boolean = false) = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        when {
            failSecond && path.endsWith("/2/file") -> respond("error", HttpStatusCode.InternalServerError)
            path.endsWith("/file") -> respond("font")
            else -> respond("[$face]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }) {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Test
    fun `both scopes remain distinct and enablement survives restart`() = runBlocking {
        val dir = temp.newFolder()
        val client = client()
        val library = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        val available = library.discover(server)
        assertThat(available.map { it.scope }).containsExactly(FontScope.SERVER, FontScope.USER)
        available.forEach { library.download(server, it) }
        val original = library.families.value
        assertThat(original).hasSize(2)
        assertThat(original.map { it.id }.distinct()).hasSize(2)
        assertThat(original.all { java.io.File(library.filePath(it, it.faces.single())).readText() == "font" }).isTrue()
        library.setEnabled(original.first().id, false)

        val reloaded = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        reloaded.load()
        assertThat(reloaded.families.value.first().enabled).isFalse()
        reloaded.remove(original.first().id)
        assertThat(reloaded.families.value).hasSize(1)
        assertThat(java.io.File(dir, original.first().id).exists()).isFalse()
        client.close()
    }

    @Test
    fun `failed face leaves no partially downloaded family`() = runBlocking {
        val dir = temp.newFolder()
        val client = client(failSecond = true)
        val library = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        val first = BookOrbitFont(1, "Example", "ttf", 400, "normal", fileSize = 4)
        val family = AvailableFontFamily(server.id, server.displayName, FontScope.SERVER, "Example", listOf(first, first.copy(id = 2)))
        val failure = runCatching { library.download(server, family) }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(library.families.value).isEmpty()
        assertThat(dir.listFiles()?.toList()).isEmpty()
        client.close()
    }

    @Test
    fun `rejects truncated font files without publishing them`() = runBlocking {
        val dir = temp.newFolder()
        val client = client()
        val library = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        val face = BookOrbitFont(1, "Example", "ttf", 400, "normal", fileSize = 5)
        val family = AvailableFontFamily(server.id, server.displayName, FontScope.USER, "Example", listOf(face))
        val failure = runCatching { library.download(server, family) }.exceptionOrNull()
        assertThat(failure?.message).contains("size differs")
        assertThat(library.families.value).isEmpty()
        client.close()
    }

    @Test
    fun `missing font files can be removed and are not usable`() = runBlocking {
        val dir = temp.newFolder()
        val client = client()
        val library = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        library.download(server, library.discover(server).first())
        val family = library.families.value.single()
        java.io.File(library.filePath(family, family.faces.single())).delete()
        val reloaded = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        reloaded.load()
        assertThat(reloaded.missingFamilies.value).containsExactly(family.id)
        assertThat(runCatching { reloaded.setEnabled(family.id, true) }.isFailure).isTrue()
        reloaded.remove(family.id)
        assertThat(reloaded.families.value).isEmpty()
        client.close()
    }

    @Test
    fun `corrupt saved weight ranges are rejected`() = runBlocking {
        val dir = temp.newFolder()
        val client = client()
        val library = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        library.download(server, library.discover(server).first())
        val index = java.io.File(dir, "families.json")
        index.writeText(index.readText().replace("\"weightMin\":null,\"weightMax\":null", "\"weightMin\":900,\"weightMax\":100"))
        val reloaded = BookOrbitFontLibrary(client, dir.path, Dispatchers.IO)
        assertThat(runCatching { reloaded.load() }.exceptionOrNull()?.message).contains("metadata is damaged")
        client.close()
    }
}
