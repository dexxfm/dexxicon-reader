package net.dexxicon.reader.core.common.crash

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local, opt-in crash capture. On an uncaught exception it writes a plain-text report to
 * [dir] and then lets the process die normally. Nothing is ever sent automatically — on the
 * next launch the app offers the user the choice to email a report they can read in full
 * first ([CrashReport], `sendCrashReport`).
 *
 * There is no SDK and no background upload; this is a `Thread.UncaughtExceptionHandler` and
 * a folder of text files.
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** `cacheDir/crash-reports` — cache so the OS can reclaim it, and so FileProvider can share it. */
    val dir: File by lazy { File(context.cacheDir, "crash-reports").apply { mkdirs() } }

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Call once, early in `Application.onCreate`. Idempotent. */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Pending reports, newest first. */
    fun pending(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun read(file: File): String = runCatching { file.readText() }.getOrDefault("")

    fun discard(file: File) {
        runCatching { file.delete() }
    }

    fun discardAll() {
        pending().forEach(::discard)
    }

    private fun write(thread: Thread, throwable: Throwable) {
        prune()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, "crash-$stamp$EXT").writeText(compose(thread, throwable))
    }

    /** Keep only the few most recent so the folder can't grow without bound. */
    private fun prune() {
        pending().drop(MAX_REPORTS - 1).forEach(::discard)
    }

    private fun compose(thread: Thread, throwable: Throwable): String {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Dexxicon Reader — crash report")
            appendLine("=".repeat(30))
            appendLine(deviceBlock(context))
            appendLine("Thread: ${thread.name}")
            appendLine("When: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
            appendLine()
            appendLine(stack.trim())
        }
    }

    companion object {
        private const val EXT = ".txt"
        private const val MAX_REPORTS = 5

        /** Where crash reports and problem reports are emailed. Change to a dedicated inbox. */
        const val CONTACT_EMAIL = "dexxfm@outlook.com"

        /** App + device facts, no account data. Shared by the crash prompt and Settings feedback. */
        fun deviceBlock(context: Context): String {
            val pkg = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            // minSdk 29: longVersionCode is available directly.
            val version = pkg?.let { "${it.versionName} (${it.longVersionCode})" } ?: "unknown"
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)
            return buildString {
                appendLine("App: $version  (${context.packageName})")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("Locale: ${Locale.getDefault()}")
                append("Heap: ${usedMb} MB used / ${maxMb} MB max")
            }
        }
    }
}
