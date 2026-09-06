package net.dexxicon.reader.core.model

/**
 * A configured remote library. Secrets (password, sync tokens) are never stored on this
 * object — they live in the encrypted credential store, keyed by [id].
 */
data class Server(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val type: ServerType = ServerType.GENERIC,
    val authMode: AuthMode = AuthMode.NATIVE,
    val username: String = "",
    val opdsPath: String = "/api/v1/opds",
    val koSyncPath: String? = null,
    /** Full base URL of the KOReader sync server (e.g. `https://host/koreader`). */
    val koSyncUrl: String? = null,
    val koSyncUsername: String? = null,
    val koboEndpoint: String? = null,
    val enabledProviders: Set<SyncProviderKind> = emptySet(),
    val createdAt: Long = 0L,
) {
    /** Base URL with any trailing slash removed. */
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    fun resolve(path: String): String =
        normalizedBaseUrl + "/" + path.trimStart('/')
}

/** How the app authenticates to a server. */
enum class AuthMode {
    /** HTTP Basic with dedicated OPDS credentials (generic OPDS servers). */
    BASIC,

    /** Username + password exchanged for a bearer token via the server's native API. */
    NATIVE,

    /** OpenID Connect: browser auth-code + PKCE, exchanged for a native session. */
    OIDC,
}

/** OIDC parameters discovered from a server, needed to run the browser flow. */
data class OidcConfig(
    /** BookOrbit only: the provider slug (`/api/v1/auth/oidc/{slug}/state`). */
    val providerSlug: String? = null,
    val issuerUri: String? = null,
    val authorizationEndpoint: String? = null,
    val clientId: String,
    val scopes: String,
    val providerName: String? = null,
)

enum class ServerType {
    GENERIC,
    BOOKORBIT,
    GRIMMORY;

    val supportsNativeApi: Boolean get() = this == BOOKORBIT || this == GRIMMORY
}

enum class SyncProviderKind { KO_SYNC, KOBO, NATIVE_ANNOTATIONS }

/** Plain-text credentials, held only transiently while talking to the credential store. */
data class ServerCredentials(
    val username: String,
    val password: String,
)

/** Result of probing a server before it is saved. */
sealed interface ServerProbeResult {
    data class Success(
        val detectedType: ServerType,
        val detectedAuthMode: AuthMode,
        val serverName: String?,
        val serverVersion: String?,
    ) : ServerProbeResult

    data class InvalidCredentials(val message: String) : ServerProbeResult
    data class Unreachable(val message: String) : ServerProbeResult
    data class NotAServer(val message: String) : ServerProbeResult
}
