package net.dexxicon.reader.shared.di

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * issue #259 — Android's [BookFileWriter]. No runtime permission is involved either way on
 * Android 10+ (the app's minSdk):
 *  - **Downloads** (the default): a new MediaStore `Downloads` entry. Apps may add files there
 *    freely; it's marked pending while being written, so other apps never see a half-written
 *    book.
 *  - **A picked folder**: `DocumentsContract.createDocument` in the tree the user chose with the
 *    system folder picker, which granted the app lasting access to it (`DexxiconApp` persists
 *    that grant). If the folder was deleted or the grant revoked since, this throws
 *    [SaveFolderUnavailableException] so the user can be asked to pick it again.
 * Either way a name clash gets a " (1)"-style suffix from the system rather than overwriting.
 */
class AndroidBookFileWriter(private val context: Context) : BookFileWriter {

    override suspend fun write(
        fileName: String,
        mimeType: String,
        folderUri: String?,
        fill: suspend (sink: (bytes: ByteArray, count: Int) -> Unit) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (folderUri == null) writeToDownloads(fileName, mimeType, fill) else writeToFolder(Uri.parse(folderUri), fileName, mimeType, fill)
    }

    override suspend fun copy(localPath: String, fileName: String, mimeType: String, folderUri: String?): String =
        write(fileName, mimeType, folderUri) { sink ->
            File(localPath).inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink(buffer, read)
                }
            }
        }

    private suspend fun writeToDownloads(
        fileName: String,
        mimeType: String,
        fill: suspend (sink: (ByteArray, Int) -> Unit) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the file in Downloads")
        fillOrDelete(uri, fill) { resolver.delete(uri, null, null) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return "Downloads"
    }

    private suspend fun writeToFolder(
        tree: Uri,
        fileName: String,
        mimeType: String,
        fill: suspend (sink: (ByteArray, Int) -> Unit) -> Unit,
    ): String {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = try {
            DocumentsContract.createDocument(resolver, parent, mimeType, fileName)
        } catch (e: SecurityException) {
            null
        } catch (e: FileNotFoundException) {
            null
        } ?: throw SaveFolderUnavailableException(
            "Can't save to that folder any more — it may have been moved or deleted. Choose it again in Settings › Downloads.",
        )
        fillOrDelete(doc, fill) { DocumentsContract.deleteDocument(resolver, doc) }
        return "the chosen folder"
    }

    /** Writes into [uri]; on any failure runs [cleanup] (so no truncated book is left) and rethrows. */
    private suspend fun fillOrDelete(
        uri: Uri,
        fill: suspend (sink: (ByteArray, Int) -> Unit) -> Unit,
        cleanup: () -> Unit,
    ) {
        try {
            val out = context.contentResolver.openOutputStream(uri, "w") ?: error("Couldn't open the new file for writing")
            out.use { stream -> fill { bytes, count -> stream.write(bytes, 0, count) } }
        } catch (e: Throwable) {
            runCatching(cleanup)
            throw e
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
