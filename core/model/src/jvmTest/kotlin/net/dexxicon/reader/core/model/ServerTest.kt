package net.dexxicon.reader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerTest {

    private fun server(type: ServerType, koSyncUrl: String? = null) = Server(
        id = "s1",
        displayName = "S",
        baseUrl = "https://books.example.com/",
        type = type,
        koSyncUrl = koSyncUrl,
    )

    @Test
    fun `normalizedBaseUrl trims trailing slash`() {
        assertThat(server(ServerType.BOOKORBIT).normalizedBaseUrl)
            .isEqualTo("https://books.example.com")
    }

    @Test
    fun `assumed kosync url is api v1 koreader for BookOrbit`() {
        assertThat(server(ServerType.BOOKORBIT).assumedKoSyncUrl)
            .isEqualTo("https://books.example.com/api/v1/koreader")
    }

    @Test
    fun `assumed kosync url is api koreader for Grimmory and generic`() {
        assertThat(server(ServerType.GRIMMORY).assumedKoSyncUrl)
            .isEqualTo("https://books.example.com/api/koreader")
        assertThat(server(ServerType.GENERIC).assumedKoSyncUrl)
            .isEqualTo("https://books.example.com/api/koreader")
    }

    @Test
    fun `effective kosync url prefers a custom override`() {
        assertThat(server(ServerType.GENERIC, koSyncUrl = "https://sync.example.com/kosync/").effectiveKoSyncUrl)
            .isEqualTo("https://sync.example.com/kosync")
    }

    @Test
    fun `effective kosync url falls back to assumed when the override is blank`() {
        assertThat(server(ServerType.BOOKORBIT, koSyncUrl = "  ").effectiveKoSyncUrl)
            .isEqualTo("https://books.example.com/api/v1/koreader")
    }

    @Test
    fun `resolve joins a path onto the base with exactly one slash`() {
        val s = server(ServerType.BOOKORBIT)
        assertThat(s.resolve("/api/v1/books")).isEqualTo("https://books.example.com/api/v1/books")
        assertThat(s.resolve("api/v1/books")).isEqualTo("https://books.example.com/api/v1/books")
    }

    @Test
    fun `supportsNativeApi only for BookOrbit and Grimmory`() {
        assertThat(ServerType.BOOKORBIT.supportsNativeApi).isTrue()
        assertThat(ServerType.GRIMMORY.supportsNativeApi).isTrue()
        assertThat(ServerType.GENERIC.supportsNativeApi).isFalse()
    }
}
