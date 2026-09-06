package net.dexxicon.reader.core.model

/** A configured remote library the app can talk to. Credentials are stored separately. */
data class Server(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val opdsPath: String = "/api/v1/opds",
    val authMode: AuthMode = AuthMode.BASIC,
    val type: ServerType = ServerType.GENERIC,
    val username: String = "",
    val koSyncPath: String? = null,
    val koboEndpoint: String? = null,
    val enabledProviders: Set<SyncProviderKind> = emptySet(),
)

enum class AuthMode { BASIC, NATIVE_JWT }

enum class ServerType { GENERIC, BOOKORBIT, GRIMMORY }

enum class SyncProviderKind { KO_SYNC, KOBO, NATIVE_ANNOTATIONS }
