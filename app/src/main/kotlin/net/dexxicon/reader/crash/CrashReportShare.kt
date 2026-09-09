package net.dexxicon.reader.crash

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.dexxicon.reader.core.common.crash.CrashReporter
import java.io.File

/**
 * Opens the user's email app with a crash report attached and a body they can edit. Nothing
 * leaves the device unless the user then hits Send in their mail client.
 */
fun shareCrashReport(context: Context, report: File, note: String) {
    val body = buildString {
        appendLine("(You can add or remove anything before sending.)")
        appendLine()
        if (note.isNotBlank()) {
            appendLine("What I was doing:")
            appendLine(note.trim())
            appendLine()
        }
        appendLine("The full report is attached, and also below:")
        appendLine()
        append(report.readText())
    }

    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", report)
    }.getOrNull()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "message/rfc822"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReporter.CONTACT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Dexxicon Reader — crash report")
        putExtra(Intent.EXTRA_TEXT, body)
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
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
