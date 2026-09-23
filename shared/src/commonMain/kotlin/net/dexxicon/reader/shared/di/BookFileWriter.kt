package net.dexxicon.reader.shared.di

/**
 * issue #259 — the platform half of Book Detail's "save a copy" button: creating a new,
 * user-visible file and writing a book into it. Separate from "Make available offline", which
 * keeps its private in-app copy exactly as before; this is a copy for the user to use outside
 * the app. Android's is `AndroidBookFileWriter`: the device's Downloads folder by default (via
 * MediaStore), or a folder the user picked in Settings (Storage Access Framework). Neither needs
 * a runtime permission on any Android version the app supports. iOS supplies none yet, which
 * hides the button there.
 *
 * Everything else — naming the file, choosing where the bytes come from (an offline copy, or
 * the server), the HTTP download itself — is common code in [AppContainer.saveBookToDevice].
 */
interface BookFileWriter {
    /**
     * Creates [fileName] in [folderUri] (null = the Downloads folder), then calls [fill] with a
     * sink to write the book's bytes into. Returns where it went ("Downloads", or the folder's
     * name). The new file is removed again if [fill] throws, so a failed save never leaves a
     * truncated book behind.
     */
    suspend fun write(
        fileName: String,
        mimeType: String,
        folderUri: String?,
        fill: suspend (sink: (bytes: ByteArray, count: Int) -> Unit) -> Unit,
    ): String

    /** [write], with the bytes copied from a local file ([localPath]) — an offline copy. */
    suspend fun copy(localPath: String, fileName: String, mimeType: String, folderUri: String?): String
}

/** issue #259 — what was saved, and where ("Downloads", or a folder's name). */
data class SavedBook(val fileName: String, val location: String)

/** issue #259 — a picked folder the app can no longer write to (deleted, or access revoked). */
class SaveFolderUnavailableException(message: String) : Exception(message)
