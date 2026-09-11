package net.dexxicon.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.core.model.SyncProviderKind

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val baseUrl: String,
    val type: String,
    val authMode: String,
    val username: String,
    val opdsPath: String,
    val koSyncPath: String?,
    val koSyncUrl: String? = null,
    val koSyncUsername: String? = null,
    val koboEndpoint: String?,
    val enabledProviders: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
) {
    fun toDomain(): Server = Server(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        type = runCatching { ServerType.valueOf(type) }.getOrDefault(ServerType.GENERIC),
        authMode = runCatching { AuthMode.valueOf(authMode) }.getOrDefault(AuthMode.NATIVE),
        username = username,
        opdsPath = opdsPath,
        koSyncPath = koSyncPath,
        koSyncUrl = koSyncUrl,
        koSyncUsername = koSyncUsername,
        koboEndpoint = koboEndpoint,
        enabledProviders = enabledProviders.split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { SyncProviderKind.valueOf(it) }.getOrNull() }
            .toSet(),
        createdAt = createdAt,
        sortOrder = sortOrder,
    )

    companion object {
        fun fromDomain(server: Server): ServerEntity = ServerEntity(
            id = server.id,
            displayName = server.displayName,
            baseUrl = server.baseUrl,
            type = server.type.name,
            authMode = server.authMode.name,
            username = server.username,
            opdsPath = server.opdsPath,
            koSyncPath = server.koSyncPath,
            koSyncUrl = server.koSyncUrl,
            koSyncUsername = server.koSyncUsername,
            koboEndpoint = server.koboEndpoint,
            enabledProviders = server.enabledProviders.joinToString(",") { it.name },
            createdAt = server.createdAt,
            sortOrder = server.sortOrder,
        )
    }
}
