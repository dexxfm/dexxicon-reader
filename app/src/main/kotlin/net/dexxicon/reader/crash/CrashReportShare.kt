package net.dexxicon.reader.crash

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.dexxicon.reader.core.common.crash.CrashReporter
import java.io.File

/**
 * Opens the user's email app with the crash report inline and a log archive attached, and a
 * body they can edit. Nothing leaves the device unless the user then hits Send in their mail
 * client. [logsZip] is the full-logs bundle from `DiagnosticsArchive`; when it's null (build
 * failed) the plain-text report is attached on its own instead.
 */
fun shareCrashReport(context: Context, report: File, note: String, logsZip: File?) {
    val body = buildString {
        appendLine("(You can add or remove anything before sending.)")
        appendLine()
        if (note.isNotBlank()) {
            appendLine("What I was doing:")
            appendLine(note.trim())
            appendLine()
        }
        appendLine("A zip of the app's logs is attached. The crash itself is also below:")
        appendLine()
        append(report.readText())
    }

    val attachment = (logsZip ?: report).let { file ->
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReporter.CONTACT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Dexxicon Reader — crash report")
        putExtra(Intent.EXTRA_TEXT, body)
        if (attachment != null) {
            putExtra(Intent.EXTRA_STREAM, attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Send crash report")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * issue #262 — Settings › Report a problem's own send path: no crash triggered this, so
 * there's no stack trace or "what I was doing" note to include, just the device summary and
 * whatever the diagnostics zip captured. Nothing leaves the device unless the user then hits
 * Send in their mail client.
 */
fun shareProblemReport(context: Context, logsZip: File?) {
    val body = buildString {
        appendLine("(Describe what happened, then hit Send — a zip of the app's logs is attached.)")
        appendLine()
        appendLine(CrashReporter.deviceBlock(context))
    }

    val attachment = logsZip?.let { file ->
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReporter.CONTACT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Dexxicon Reader — feedback")
        putExtra(Intent.EXTRA_TEXT, body)
        if (attachment != null) {
            putExtra(Intent.EXTRA_STREAM, attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Send feedback")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
