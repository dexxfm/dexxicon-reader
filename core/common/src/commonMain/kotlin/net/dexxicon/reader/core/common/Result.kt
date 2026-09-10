package net.dexxicon.reader.core.common

/** Lightweight result type for repository/network calls. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: DexxiconError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.getOrElse(fallback: (DexxiconError) -> T): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> fallback(error)
}

sealed class DexxiconError(open val message: String?) {
    data class Network(override val message: String?) : DexxiconError(message)
    data class Unauthorized(override val message: String?) : DexxiconError(message)
    data class NotFound(override val message: String?) : DexxiconError(message)
    data class Parse(override val message: String?) : DexxiconError(message)
    data class Unsupported(override val message: String?) : DexxiconError(message)
    data class Unknown(override val message: String?, val cause: Throwable? = null) : DexxiconError(message)
}
