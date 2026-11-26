package net.dexxicon.reader.core.data.font

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

@Serializable
enum class FontScope(val endpoint: String) {
    SERVER("server-fonts"),
    USER("fonts"),
}

@Serializable
data class BookOrbitFont(
    val id: Long,
    val familyName: String,
    val format: String,
    val weight: Int,
    val style: String,
    val weightMin: Int? = null,
    val weightMax: Int? = null,
    val fileSize: Long,
)

data class AvailableFontFamily(
    val serverId: String,
    val serverName: String,
    val scope: FontScope,
    val name: String,
    val faces: List<BookOrbitFont>,
)

@Serializable
data class DownloadedFontFace(
    val fileName: String,
    val format: String,
    val weight: Int,
    val style: String,
    val weightMin: Int?,
    val weightMax: Int?,
)

@Serializable
data class DownloadedFontFamily(
    val id: String,
    val serverId: String,
    val serverName: String,
    val scope: FontScope,
    val name: String,
    val enabled: Boolean,
    val faces: List<DownloadedFontFace>,
)

/**
 * Downloads and metadata share one private directory. Only a complete family is published in
 * the index; a failed transfer never replaces or exposes an existing family.
 */
class BookOrbitFontLibrary(
    private val client: HttpClient,
    directory: String,
    private val io: CoroutineDispatcher,
) {
    private val fs = FileSystem.SYSTEM
    private val root = directory.toPath()
    private val index = root / "families.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _families = MutableStateFlow<List<DownloadedFontFamily>>(emptyList())
    val families: StateFlow<List<DownloadedFontFamily>> = _families.asStateFlow()
    private val _missingFamilies = MutableStateFlow<Set<String>>(emptySet())
    val missingFamilies: StateFlow<Set<String>> = _missingFamilies.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(io) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked() {
        if (loaded) return
        fs.createDirectories(root)
        val saved = if (fs.exists(index)) {
            json.decodeFromString<List<DownloadedFontFamily>>(fs.read(index) { readUtf8() })
        } else {
            emptyList()
        }
        require(saved.all { family ->
            isSafeId(family.id) && family.faces.isNotEmpty() && family.faces.all { face ->
                isSafeFileName(face.fileName) && face.format in FORMATS &&
                    face.style in setOf("normal", "italic") && face.weight in 1..1000 &&
                    validWeightRange(face.weightMin, face.weightMax)
            }
        }) { "The downloaded font library metadata is damaged." }
        _missingFamilies.value = saved.filter { family ->
            family.faces.any { face -> !fs.exists(root / family.id / face.fileName) }
        }.map { it.id }.toSet()
        _families.value = saved
        loaded = true
    }

    suspend fun discover(server: Server): List<AvailableFontFamily> {
        require(server.type == ServerType.BOOKORBIT) { "Fonts require a BookOrbit server." }
        return FontScope.entries.flatMap { scope ->
            val faces: List<BookOrbitFont> = client.get(server.resolve("/api/v1/${scope.endpoint}")).body()
            faces.groupBy { it.familyName }.map { (name, familyFaces) ->
                AvailableFontFamily(server.id, server.displayName, scope, name, familyFaces)
            }
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun download(server: Server, family: AvailableFontFamily) = withContext(io) {
        require(server.type == ServerType.BOOKORBIT && server.id == family.serverId)
        require(family.name.isNotBlank() && family.faces.isNotEmpty())
        mutex.withLock {
            loadLocked()
            require(_families.value.none {
                it.serverId == family.serverId && it.scope == family.scope && it.name == family.name
            }) { "This font family is already downloaded." }
            val id = generateId()
            val staging = root / "$id.pending"
            val target = root / id
            fs.createDirectories(staging)
            var committed = false
            try {
                val faces = family.faces.mapIndexed { index, face ->
                    validate(face, family.name)
                    val name = "$id-$index.${face.format}"
                    val path = staging / name
                    client.prepareGet(server.resolve("/api/v1/${family.scope.endpoint}/${face.id}/file"))
                        .execute { response ->
                            require(response.contentLength()?.let { it in 1..MAX_FONT_BYTES } != false) {
                                "Font file is too large or empty."
                            }
                            val channel = response.bodyAsChannel()
                            var total = 0L
                            fs.sink(path).buffer().use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (!channel.isClosedForRead) {
                                    val read = channel.readAvailable(buffer, 0, buffer.size)
                                    if (read <= 0) continue
                                    total += read
                                    require(total <= MAX_FONT_BYTES) { "Font file is too large." }
                                    sink.write(buffer, 0, read)
                                }
                            }
                            require(total > 0) { "Font file is empty." }
                            require(total == face.fileSize) { "Font file size differs from the server's metadata." }
                        }
                    DownloadedFontFace(name, face.format, face.weight, face.style, face.weightMin, face.weightMax)
                }
                fs.atomicMove(staging, target)
                val saved = DownloadedFontFamily(
                    id, family.serverId, family.serverName, family.scope, family.name, true, faces,
                )
                saveLocked(_families.value + saved)
                committed = true
            } finally {
                if (!committed) {
                    if (fs.exists(staging)) fs.deleteRecursively(staging)
                    if (fs.exists(target)) fs.deleteRecursively(target)
                }
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(io) {
        mutex.withLock {
            loadLocked()
            require(_families.value.any { it.id == id }) { "Font family not found." }
            require(!enabled || id !in _missingFamilies.value) { "Remove and download this family again; font files are missing." }
            saveLocked(_families.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    suspend fun remove(id: String) = withContext(io) {
        mutex.withLock {
            loadLocked()
            require(isSafeId(id) && _families.value.any { it.id == id }) { "Font family not found." }
            saveLocked(_families.value.filterNot { it.id == id })
            _missingFamilies.value -= id
            if (fs.exists(root / id)) fs.deleteRecursively(root / id)
        }
    }

    fun filePath(family: DownloadedFontFamily, face: DownloadedFontFace): String {
        require(isSafeId(family.id) && isSafeFileName(face.fileName) && face in family.faces)
        return (root / family.id / face.fileName).toString()
    }

    private fun saveLocked(families: List<DownloadedFontFamily>) {
        val temporary = root / "families.json.pending"
        fs.write(temporary) { writeUtf8(json.encodeToString(families)) }
        fs.atomicMove(temporary, index)
        _families.value = families
    }

    private fun generateId(): String {
        while (true) {
            val id = Random.nextLong().toULong().toString(16) + Random.nextLong().toULong().toString(16)
            if (!fs.exists(root / id) && !fs.exists(root / "$id.pending")) return id
        }
    }

    private fun validate(face: BookOrbitFont, familyName: String) {
        require(face.id > 0 && face.familyName == familyName)
        require(face.format in FORMATS && face.style in setOf("normal", "italic"))
        require(face.weight in 1..1000 && face.fileSize in 1..MAX_FONT_BYTES)
        require(validWeightRange(face.weightMin, face.weightMax))
    }

    private fun validWeightRange(min: Int?, max: Int?): Boolean =
        (min == null && max == null) || (min != null && max != null && min in 1..1000 && max in min..1000)

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 32 && id.all { it in '0'..'9' || it in 'a'..'f' }

    private fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' } &&
            name != "." && name != ".."

    private companion object {
        const val MAX_FONT_BYTES = 50L * 1024 * 1024
        val FORMATS = setOf("ttf", "otf", "woff", "woff2")
    }
}
