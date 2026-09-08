package net.dexxicon.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.designsystem.component.FormatLegend
import net.dexxicon.reader.core.model.BookViewMode
import net.dexxicon.reader.core.model.Server
import kotlin.math.roundToInt

private const val GB = 1024L * 1024 * 1024

/** Storage-limit presets. `null` = no limit. */
private val DOWNLOAD_LIMIT_OPTIONS: List<Pair<String, Long?>> = listOf(
    "1 GB" to 1 * GB,
    "5 GB" to 5 * GB,
    "10 GB" to 10 * GB,
    "20 GB" to 20 * GB,
    "50 GB" to 50 * GB,
    "None" to null,
)

private fun formatGigabytes(bytes: Long): String {
    val gb = bytes / GB.toDouble()
    return when {
        bytes < GB / 10 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        gb < 10 -> "%.1f GB".format(gb)
        else -> "%.0f GB".format(gb)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    versionName: String,
    onAddServer: () -> Unit = {},
    onEditServer: (String) -> Unit = {},
    onOpenServerCatalog: (id: String, name: String) -> Unit = { _, _ -> },
    viewModel: SettingsViewModel = hiltViewModel(),
    koSyncViewModel: KoSyncSettingsViewModel = hiltViewModel(),
    serverListViewModel: ServerListViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val koSyncRows by koSyncViewModel.rows.collectAsStateWithLifecycle()
    val refreshing by koSyncViewModel.refreshing.collectAsStateWithLifecycle()
    val servers by serverListViewModel.servers.collectAsStateWithLifecycle()
    val serverAccounts by serverListViewModel.accounts.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = koSyncViewModel::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp)
                .padding(20.dp),
        ) {
            SectionTitle("Servers")
            if (servers.isEmpty()) {
                Text(
                    "Add your BookOrbit or Grimmory instance to start browsing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (servers.size > 1) {
                Text(
                    "Press and hold the handle to reorder. This order sets which library's " +
                        "books come first when browsing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            ReorderableServers(
                servers = servers,
                accounts = serverAccounts,
                onOpen = { onOpenServerCatalog(it.id, it.displayName) },
                onEdit = { onEditServer(it.id) },
                onRemove = { serverListViewModel.remove(it.id) },
                onReorder = serverListViewModel::reorder,
            )
            OutlinedButton(
                onClick = onAddServer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Add server")
            }

            Spacer()
            SectionTitle("Appearance")
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = prefs.theme == theme,
                        onClick = { viewModel.setTheme(theme) },
                        label = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            LayoutSpacer(Modifier.height(16.dp))
            Text("Book layout", style = MaterialTheme.typography.bodyMedium)
            Text(
                "The default for Browse and a server's catalog. Each screen keeps its own " +
                    "grid/list toggle; changing this here resets them all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.bookViewDefault == mode,
                        onClick = { viewModel.setBookViewDefault(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer()
            SectionTitle("Downloads")
            SettingRow(
                title = "Download over Wi-Fi only",
                subtitle = "Queued downloads wait for an unmetered connection",
            ) {
                Switch(
                    checked = prefs.downloadsWifiOnly,
                    onCheckedChange = viewModel::setDownloadsWifiOnly,
                )
            }

            val usedBytes by viewModel.downloadUsedBytes.collectAsStateWithLifecycle()
            LayoutSpacer(Modifier.height(16.dp))
            Text("Storage limit", style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(formatGigabytes(usedBytes)).append(" used")
                    prefs.downloadLimitBytes?.let { append(" of ").append(formatGigabytes(it)) }
                    append(". A download that would go over is skipped.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DOWNLOAD_LIMIT_OPTIONS.forEach { (label, bytes) ->
                    FilterChip(
                        selected = prefs.downloadLimitBytes == bytes,
                        onClick = { viewModel.setDownloadLimit(bytes) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer()
            SectionTitle("Reading sync")
            Text(
                "Reading & listening position syncs with each server. BookOrbit and Grimmory " +
                    "sync through their own library API (same as the web reader); other OPDS " +
                    "servers use a KOReader sync account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (koSyncRows.isEmpty()) {
                Text(
                    "Add a server first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            koSyncRows.forEach { row ->
                if (row.usesNative) {
                    NativeSyncRow(row)
                } else {
                    KoSyncServerCard(
                        row = row,
                        onSave = { url, user, pass -> koSyncViewModel.save(row.serverId, url, user, pass) },
                        onVerify = { koSyncViewModel.verify(row.serverId) },
                    )
                }
                LayoutSpacer(Modifier.height(10.dp))
            }

            Spacer()
            SectionTitle("Format badges")
            Text(
                "The coloured tag on a cover's bottom-right corner shows its file type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            FormatLegend()

            Spacer()
            SectionTitle("Feedback")
            val context = androidx.compose.ui.platform.LocalContext.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { sendProblemReport(context, versionName) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Report a problem", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Opens an email with your device and app details filled in. Nothing " +
                            "is sent until you send it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer()
            SectionTitle("About")
            SettingRow(title = "Dexxicon Reader", subtitle = "Version $versionName") {}
            SettingRow(
                title = "Source code",
                subtitle = "github.com/dexxfm/dexxicon-reader",
            ) {}
        }
        }
    }
}

/** Opens the user's email app with device/app context prefilled — no crash required. */
private fun sendProblemReport(context: android.content.Context, versionName: String) {
    val body = buildString {
        appendLine("Describe the problem here:")
        appendLine()
        appendLine()
        appendLine("---")
        appendLine(net.dexxicon.reader.core.common.crash.CrashReporter.deviceBlock(context))
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("mailto:")
        putExtra(
            android.content.Intent.EXTRA_EMAIL,
            arrayOf(net.dexxicon.reader.core.common.crash.CrashReporter.CONTACT_EMAIL),
        )
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Dexxicon Reader $versionName — problem report")
        putExtra(android.content.Intent.EXTRA_TEXT, body)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun NativeSyncRow(row: SyncServerRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Syncs with the library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.lastSyncedAt?.let { at ->
                Text(
                    "Last synced ${relativeTime(at)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KoSyncServerCard(
    row: SyncServerRow,
    onSave: (customUrl: String, user: String, pass: String) -> Unit,
    onVerify: () -> Unit,
) {
    var user by remember(row.serverId) { mutableStateOf(row.koSyncUsername) }
    var pass by remember(row.serverId) { mutableStateOf("") }
    var customUrl by remember(row.serverId) { mutableStateOf(row.customUrl) }
    var useCustom by remember(row.serverId) { mutableStateOf(row.customUrl.isNotBlank()) }
    var expanded by remember(row.serverId) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                    val status = when {
                        row.verifying -> "Checking…"
                        row.verified == true -> "(koreader) · connected"
                        row.verified == false -> "(koreader) · sign-in failed"
                        row.configured -> "(koreader) · configured"
                        else -> "Not set up"
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.verified == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    row.lastSyncedAt?.let { at ->
                        Text(
                            "Last synced ${relativeTime(at)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (row.verifying) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Close" else "Edit")
                }
            }
            if (expanded) {
                Text(
                    "Sync endpoint",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useCustom,
                        onClick = { useCustom = false },
                        label = { Text("Assumed") },
                    )
                    FilterChip(
                        selected = useCustom,
                        onClick = { useCustom = true },
                        label = { Text("Custom") },
                    )
                }
                if (!useCustom) {
                    Text(
                        row.assumedUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Custom sync URL") },
                        placeholder = { Text(row.assumedUrl) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Sync username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(if (row.configured) "Sync password (leave blank to keep)" else "Sync password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onVerify, enabled = row.configured) { Text("Verify") }
                    TextButton(onClick = {
                        onSave(if (useCustom) customUrl else "", user, pass)
                        expanded = false
                    }) { Text("Save") }
                }
            }
        }
    }
}

private fun relativeTime(atMillis: Long): String {
    val now = System.currentTimeMillis()
    if (atMillis <= 0L || atMillis > now) return "just now"
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        atMillis,
        now,
        android.text.format.DateUtils.MINUTE_IN_MILLIS,
        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString().replaceFirstChar { it.lowercase() }
}

/**
 * The Servers list with long-press drag-to-reorder. The order shown here is the display
 * priority used everywhere servers are listed or their books merged.
 */
@Composable
private fun ReorderableServers(
    servers: List<Server>,
    accounts: Map<String, String>,
    onOpen: (Server) -> Unit,
    onEdit: (Server) -> Unit,
    onRemove: (Server) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    // A local copy so the drag reflows instantly; re-synced from upstream when not dragging.
    var order by remember(servers) { mutableStateOf(servers) }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    val canReorder = servers.size > 1

    Column(Modifier.fillMaxWidth()) {
        order.forEachIndexed { index, server ->
            val dragging = dragIndex == index
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { rowHeights[server.id] = it.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .offset { IntOffset(0, if (dragging) dragDelta.roundToInt() else 0) }
                    .then(if (dragging) Modifier.shadow(6.dp) else Modifier)
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surface,
                    ),
            ) {
                ServerRow(
                    server = server,
                    account = accounts[server.id],
                    onOpen = { onOpen(server) },
                    onEdit = { onEdit(server) },
                    onRemove = { onRemove(server) },
                    dragHandle = if (!canReorder) null else { modifier ->
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = modifier.pointerInput(order.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragIndex = index; dragDelta = 0f },
                                    onDragEnd = {
                                        if (dragIndex != null) onReorder(order.map { it.id })
                                        dragIndex = null
                                        dragDelta = 0f
                                    },
                                    onDragCancel = {
                                        order = servers
                                        dragIndex = null
                                        dragDelta = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val cur = dragIndex ?: return@detectDragGesturesAfterLongPress
                                        dragDelta += amount.y
                                        val h = rowHeights[order[cur].id] ?: return@detectDragGesturesAfterLongPress
                                        if (dragDelta > h / 2 && cur < order.lastIndex) {
                                            order = order.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                            dragIndex = cur + 1
                                            dragDelta -= h
                                        } else if (dragDelta < -h / 2 && cur > 0) {
                                            order = order.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                            dragIndex = cur - 1
                                            dragDelta += h
                                        }
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: Server,
    account: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    dragHandle: (@Composable (Modifier) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(server.displayName) },
        supportingContent = {
            Column {
                Text(server.normalizedBaseUrl, style = MaterialTheme.typography.bodySmall)
                account?.let {
                    Text(
                        "Signed in as $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        leadingContent = {
            if (dragHandle != null) dragHandle(Modifier) else Icon(Icons.Filled.Dns, contentDescription = null)
        },
        trailingContent = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Spacer() {
    HorizontalDivider(Modifier.padding(vertical = 20.dp))
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}
