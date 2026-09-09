package net.dexxicon.reader.crash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Shown once at launch when a crash was captured on the previous run. Everything the report
 * contains is visible here; nothing is sent unless the user taps **Send report** and then
 * sends the email their mail app opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportSheet(
    report: File,
    onSend: (note: String) -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    val text = remember(report) { runCatching { report.readText() }.getOrDefault("") }

    ModalBottomSheet(onDismissRequest = onKeep) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Dexxicon Reader closed unexpectedly", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sending this report helps fix the bug. It contains the error and your " +
                    "device model — no account details. You can edit it in your email app " +
                    "before sending.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("What were you doing? (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }

            Button(onClick = { onSend(note) }, modifier = Modifier.fillMaxWidth()) {
                Text("Send report")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onKeep) { Text("Not now") }
                TextButton(onClick = onDiscard) { Text("Delete") }
            }
        }
    }
}
