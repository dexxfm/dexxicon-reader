package net.dexxicon.reader.core.data

/**
 * Fire-and-forget seed of a server's "continue reading" / "continue listening" rows into
 * local reading progress, right after that server is added or signed into. Split out from
 * [ReadingProgressRepository] (its only implementation, staying Android-only) so
 * [net.dexxicon.reader.core.data.auth.OidcAuthenticator] (commonMain) doesn't need that
 * repository's full Android-specific dependency graph — `KoSyncRepository`'s `Context`/
 * `Settings.Secure`/raw `OkHttpClient` usage — just to trigger this one thing after sign-in.
 */
interface ProgressSeeder {
    fun seedFromServerAsync(serverId: String)
}
