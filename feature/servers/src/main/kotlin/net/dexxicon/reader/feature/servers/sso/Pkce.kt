package net.dexxicon.reader.feature.servers.sso

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE verifier/challenge + nonce for the WebView flow (AppAuth handles this itself). */
data class Pkce(
    val verifier: String,
    val challenge: String,
    val nonce: String,
) {
    companion object {
        fun generate(): Pkce {
            val random = SecureRandom()
            val verifier = randomUrlToken(random, 64)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(
                verifier = verifier,
                challenge = Base64.encodeToString(digest, B64_FLAGS),
                nonce = randomUrlToken(random, 16),
            )
        }

        private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

        private fun randomUrlToken(random: SecureRandom, bytes: Int): String {
            val buffer = ByteArray(bytes)
            random.nextBytes(buffer)
            return Base64.encodeToString(buffer, B64_FLAGS)
        }
    }
}
