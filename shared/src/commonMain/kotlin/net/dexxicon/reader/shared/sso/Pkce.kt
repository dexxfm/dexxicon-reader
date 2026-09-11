package net.dexxicon.reader.shared.sso

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.random.CryptoRand

/**
 * PKCE verifier/challenge + nonce for the SSO WebView flow. commonMain port of the native
 * `feature/servers` `Pkce` (issue #70) — that one used `java.security.SecureRandom`/
 * `MessageDigest`/`android.util.Base64`, all JVM-only. Here:
 *  - random bytes come from `org.kotlincrypto.random`'s `CryptoRand` (multiplatform secure
 *    RNG — `java.security.SecureRandom` on Android, `SecRandomCopyBytes` on iOS under the
 *    hood, neither of which this code has to know about);
 *  - the SHA-256 digest comes from `org.kotlincrypto.hash.sha2` (pure Kotlin, no cinterop —
 *    the "prefer a mature library" call [AppContainer][net.dexxicon.reader.shared.di.AppContainer]
 *    already made for `ConnectivityMonitor`, and a real one here: hand-rolling this via
 *    CommonCrypto/Security cinterop is exactly the kind of iOS-only code this port keeps
 *    getting wrong without being able to compile it locally);
 *  - base64url (unpadded, per RFC 7636) comes from the Kotlin stdlib's own `Base64.UrlSafe`,
 *    itself multiplatform — no platform Base64 needed at all.
 */
@OptIn(ExperimentalEncodingApi::class)
data class Pkce(
    val verifier: String,
    val challenge: String,
    val nonce: String,
) {
    companion object {
        private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        fun generate(): Pkce {
            val verifier = randomUrlToken(64)
            val digest = SHA256().let { it.update(verifier.encodeToByteArray()); it.digest() }
            return Pkce(
                verifier = verifier,
                challenge = base64Url.encode(digest),
                nonce = randomUrlToken(16),
            )
        }

        private fun randomUrlToken(bytes: Int): String =
            base64Url.encode(CryptoRand.Default.nextBytes(ByteArray(bytes)))
    }
}
