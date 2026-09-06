package net.dexxicon.reader.core.serverapi.kosync

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * KOReader sync protocol (`kosync`). Base URL is per-server (BookLore hosts it at
 * `{host}/koreader`, BookOrbit at `{host}/api/v1/koreader`). Auth is a pair of headers:
 * `x-auth-user` = username, `x-auth-key` = md5(password).
 */
interface KoSyncApi {

    @GET
    suspend fun authorize(
        @Url url: String,
        @Header("x-auth-user") user: String,
        @Header("x-auth-key") key: String,
    ): Response<Unit>

    @GET
    suspend fun getProgress(
        @Url url: String,
        @Header("x-auth-user") user: String,
        @Header("x-auth-key") key: String,
    ): Response<KoSyncProgressDto>

    @PUT
    suspend fun putProgress(
        @Url url: String,
        @Header("x-auth-user") user: String,
        @Header("x-auth-key") key: String,
        @Body body: KoSyncProgressUpdate,
    ): Response<Unit>
}

@Serializable
data class KoSyncProgressUpdate(
    val document: String,
    val progress: String,
    val percentage: Double,
    val device: String,
    val device_id: String,
)

@Serializable
data class KoSyncProgressDto(
    val document: String? = null,
    val progress: String? = null,
    val percentage: Double? = null,
    val device: String? = null,
    val device_id: String? = null,
    val timestamp: Long? = null,
)
