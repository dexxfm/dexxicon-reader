package net.dexxicon.reader.core.data.auth

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Outcome
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.serverapi.auth.NativeAuthApi
import net.dexxicon.reader.core.serverapi.auth.NativeAuthClient
import net.dexxicon.reader.core.serverapi.auth.NativeSession
import org.junit.Test

/**
 * Issue #334 — against a fake Grimmory with its real refresh rules: every refresh token is
 * single-use (revoked once redeemed) and unusable until two minutes after it was issued.
 */
@OptIn(ExperimentalTime::class)
class TokenManagerTest {

    private val server = Server(
        id = "g",
        displayName = "Waffull",
        baseUrl = "https://grimmory.test",
        type = ServerType.GRIMMORY,
        authMode = AuthMode.OIDC,
    )

    /** The test's clock: real time plus whatever the test has fast-forwarded. */
    private var skipped = 0L
    private fun now() = Clock.System.now().toEpochMilliseconds() + skipped

    private val grimmory = FakeGrimmory(::now)
    private val secrets = FakeSecrets()
    private val tokens = TokenManager(NativeAuthClient(NativeAuthApi(grimmory.client)), secrets, ::now)

    private suspend fun signIn(): NativeSession {
        val session = grimmory.issue()
        tokens.seedSession(server.id, session)
        return session
    }

    @Test
    fun aRefreshTooSoonAfterSignInKeepsTheSessionInsteadOfAskingToSignInAgain() = runTest {
        val first = signIn()
        skipped += 30_000 // the reporter's second sign-in: a 401 thirty-four seconds later

        val outcome = tokens.forceRefresh(server, rejectedToken = first.accessToken)

        assertThat(outcome).isEqualTo(Outcome.Success(first.accessToken))
        assertThat(grimmory.refreshCalls).isEqualTo(0) // a refresh token inside its not-before isn't sent
        assertThat(tokens.needsSignIn.value).isEmpty()
    }

    @Test
    fun aSecond401RightAfterARefreshReusesTheNewTokenInsteadOfRefreshingAgain() = runTest {
        val first = signIn()
        skipped += 3 * 60_000

        val renewed = tokens.forceRefresh(server, rejectedToken = first.accessToken)
        assertThat(renewed).isInstanceOf(Outcome.Success::class.java)
        // Nine seconds later another request that was sent with the old token 401s too.
        skipped += 9_000
        val again = tokens.forceRefresh(server, rejectedToken = first.accessToken)

        assertThat(again).isEqualTo(renewed)
        assertThat(grimmory.refreshCalls).isEqualTo(1)
        assertThat(tokens.needsSignIn.value).isEmpty()
    }

    @Test
    fun aRotatedTokenThatCantBePersistedStillWorksForTheNextRefresh() = runTest {
        val first = signIn()
        secrets.failWrites = true // e.g. the iOS Keychain refusing while the phone is locked
        skipped += 3 * 60_000

        val second = tokens.forceRefresh(server, rejectedToken = first.accessToken) as Outcome.Success
        skipped += 3 * 60_000
        val third = tokens.forceRefresh(server, rejectedToken = second.value)

        assertThat(third).isInstanceOf(Outcome.Success::class.java)
        assertThat(grimmory.refreshCalls).isEqualTo(2)
        assertThat(grimmory.rejectedRefreshes).isEqualTo(0) // the revoked first token is never replayed
        assertThat(tokens.needsSignIn.value).isEmpty()
    }

    @Test
    fun aRefreshTokenStillTooNewAtColdStartIsATemporaryFailureNotASignInPrompt() = runTest {
        // A relaunch a few seconds after a refresh: nothing in memory, the stored token too new.
        secrets.stored[server.id] = grimmory.issue().refreshToken!!

        val outcome = tokens.bearerToken(server)

        assertThat((outcome as Outcome.Failure).error).isInstanceOf(DexxiconError.Network::class.java)
        assertThat(grimmory.refreshCalls).isEqualTo(0)
        assertThat(tokens.needsSignIn.value).isEmpty()
    }

    @Test
    fun aRevokedRefreshTokenStillAsksForSignIn() = runTest {
        val first = signIn()
        grimmory.revokeAll()
        skipped += 3 * 60_000

        val outcome = tokens.forceRefresh(server, rejectedToken = first.accessToken)

        assertThat((outcome as Outcome.Failure).error).isInstanceOf(DexxiconError.Unauthorized::class.java)
        assertThat(tokens.needsSignIn.value).containsExactly(server.id)
    }

    @Test
    fun anUnreachableServerIsntRetriedByEveryQueuedRequest() = runTest {
        // Issue #337 — a cold start with the server down: nothing in memory, a usable stored token.
        secrets.stored[server.id] = grimmory.issue().refreshToken!!
        skipped += 3 * 60_000
        grimmory.down = true

        val first = tokens.bearerToken(server)
        val queued = List(5) { tokens.bearerToken(server) }

        assertThat((first as Outcome.Failure).error).isInstanceOf(DexxiconError.Network::class.java)
        assertThat(queued.map { (it as Outcome.Failure).error }.all { it is DexxiconError.Network }).isTrue()
        assertThat(grimmory.refreshCalls).isEqualTo(1)
        assertThat(tokens.needsSignIn.value).isEmpty()

        // Back online after the short backoff: the next request reaches the server again.
        grimmory.down = false
        skipped += 11_000
        assertThat(tokens.bearerToken(server)).isInstanceOf(Outcome.Success::class.java)
        assertThat(grimmory.refreshCalls).isEqualTo(2)
    }

    @Test
    fun notBeforeIsReadFromTheJwtAndIgnoredOnOpaqueTokens() {
        assertThat(notBeforeMillis(jwt(nbfSeconds = 1_700_000_000))).isEqualTo(1_700_000_000_000)
        assertThat(notBeforeMillis("opaque-refresh-token")).isNull()
        assertThat(notBeforeMillis(jwt(nbfSeconds = null))).isNull()
    }
}

private class FakeSecrets : SessionSecrets {
    val stored = mutableMapOf<String, String>()
    var failWrites = false
    override suspend fun refreshToken(serverId: String) = stored[serverId]
    override suspend fun saveRefreshToken(serverId: String, token: String) {
        if (failWrites) error("Keychain error -25308: User interaction is not allowed.")
        stored[serverId] = token
    }
    override suspend fun password(serverId: String): String? = null
}

/** `POST /api/v1/auth/refresh` with Grimmory 3.5.0's rules (AuthenticationService/JwtUtils). */
private class FakeGrimmory(private val now: () -> Long) {
    private val live = mutableMapOf<String, Long>() // refresh token -> not-before (ms)
    private var serial = 0
    var refreshCalls = 0
    var rejectedRefreshes = 0
    var down = false

    fun issue(): NativeSession {
        serial++
        val notBefore = now() + 2 * 60_000
        val refresh = jwt(nbfSeconds = notBefore / 1000, id = serial)
        live[refresh] = notBefore
        return NativeSession("access-$serial", refresh, now() + 7_200_000 - 30_000)
    }

    fun revokeAll() = live.clear()

    val client = HttpClient(
        MockEngine { request ->
            refreshCalls++
            if (down) throw kotlinx.io.IOException("Could not connect to the server.")
            val token = Json.parseToJsonElement((request.body as TextContent).text)
                .jsonObject.getValue("refreshToken").jsonPrimitive.content
            val notBefore = live[token]
            // Nimbus allows 60 s of clock skew on nbf.
            if (notBefore == null || now() < notBefore - 60_000) {
                rejectedRefreshes++
                return@MockEngine respond("""{"message":"Invalid or expired refresh token"}""", HttpStatusCode.Unauthorized, json)
            }
            live.remove(token)
            val next = issue()
            respond(
                """{"accessToken":"${next.accessToken}","refreshToken":"${next.refreshToken}","expires":7200}""",
                HttpStatusCode.OK,
                json,
            )
        },
    ) {
        expectSuccess = true
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
}

@OptIn(ExperimentalEncodingApi::class)
private fun jwt(nbfSeconds: Long?, id: Int = 0): String {
    val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val claims = buildString {
        append("""{"jti":"$id","sub":"waffull"""")
        if (nbfSeconds != null) append(""","nbf":$nbfSeconds""")
        append("}")
    }
    return b64.encode("""{"alg":"HS256"}""".encodeToByteArray()) + "." +
        b64.encode(claims.encodeToByteArray()) + ".sig"
}
