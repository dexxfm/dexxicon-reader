package net.dexxicon.reader.core.common.crash

import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundles everything useful for diagnosing a report into a single `.zip`: this process's
 * logcat, every saved crash report, and a device/app summary. Attached to the email the
 * user chooses to send from the crash prompt or Settings › Report a problem — nothing is
 * collected until they ask for it, and it never leaves the device on its own.
 */
@Singleton
class DiagnosticsArchive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashReporter: CrashReporter,
) {
    /** `cacheDir/diagnostics` — cache so the OS can reclaim it, and so FileProvider can share it. */
    private val dir: File by lazy { File(context.cacheDir, "diagnostics").apply { mkdirs() } }

    /**
     * Writes a fresh archive and returns it, or null if it could not be built. Does blocking
     * file + process I/O — call off the main thread.
     */
    fun build(): File? = runCatching {
        prune()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zip = File(dir, "dexxicon-logs-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.entry("device.txt", summary().toByteArray())
            out.entry("logcat.txt", captureLogcat().toByteArray())
            crashReporter.pending().forEach { report ->
                out.entry("crashes/${report.name}", report.readBytes())
            }
        }
        zip
    }.getOrNull()

    private fun summary(): String = buildString {
        appendLine("Dexxicon Reader — diagnostics")
        appendLine("=".repeat(30))
        appendLine("Captured: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
        appendLine()
        appendLine(CrashReporter.deviceBlock(context))
    }

    /** This app's own log buffer. `--pid` keeps it to our process; apps can always read that. */
    private fun captureLogcat(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.ifBlank { "(logcat returned nothing)" }
    }.getOrElse { "logcat unavailable: ${it.message}" }

    /** Keep only the few most recent archives so the cache can't grow without bound. */
    private fun prune() {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_ARCHIVES - 1)
            ?.forEach { it.delete() }
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val MAX_ARCHIVES = 3
    }
}
