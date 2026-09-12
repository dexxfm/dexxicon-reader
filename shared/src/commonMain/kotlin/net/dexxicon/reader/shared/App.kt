package net.dexxicon.reader.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.dexxicon.reader.core.designsystem.nav.FloatingPillNavBar
import net.dexxicon.reader.core.designsystem.nav.PillNavigationRail
import net.dexxicon.reader.core.datastore.AppPreferences
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.designsystem.theme.DexxiconTheme
import net.dexxicon.reader.core.model.AuthMode
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.shared.catalog.BookDetailScreen
import net.dexxicon.reader.shared.catalog.BooksScreen
import net.dexxicon.reader.shared.di.AppContainer
import net.dexxicon.reader.shared.home.HomeScreen
import net.dexxicon.reader.shared.library.LibraryScreen
import net.dexxicon.reader.shared.nav.TopLevelDestination
import net.dexxicon.reader.shared.settings.SettingsScreen
import net.dexxicon.reader.shared.servers.AddServerState
import net.dexxicon.reader.shared.servers.ServersState
import net.dexxicon.reader.shared.servers.SsoState
import net.dexxicon.reader.shared.servers.TestState
import net.dexxicon.reader.shared.sso.SsoWebViewScreen

// Phase 2: the real app shell — a servers list (with a "no servers yet" empty state, now
// reached from Settings — issue #133) behind a NavHost, an add-server form (native login,
// Slice 1 issue #62; SSO WebView, Slice 2 issue #70), and read-only catalog browsing (issue
// #78) — wired to the Phase 1/2 data layer via [AppContainer]. Renders identically on Android
// ([SharedPreviewActivity], debug-only) and iOS ([MainViewController]).
//
// Phase 3 (issue #99): actually reading a book hands off to [onOpenReader] — see
// [net.dexxicon.reader.shared.OnOpenReader]'s doc comment for why this is a plain callback
// the platform host supplies, not a screen this NavHost owns itself.
@Serializable private object ManageServersRoute
@Serializable private object AddServerRoute
@Serializable private data class EditServerRoute(val serverId: String)
@Serializable private object LibraryRoute
@Serializable private object HomeRoute
@Serializable private object SettingsRoute
@Serializable private data class BooksRoute(val serverId: String)
@Serializable private data class BookDetailRoute(val serverId: String, val bookId: String)

/** Same breakpoint as native's `DexxiconApp.kt` — Material's "medium" window-width class. */
private val RAIL_BREAKPOINT = 600.dp

/**
 * Phase 4 Stage D (issue #133) — maps [TopLevelDestination] onto this NavHost's actual routes.
 * Home lands on [HomeRoute] (Continue reading/listening, On Deck, Downloaded); Library lands
 * on [LibraryRoute] ([net.dexxicon.reader.shared.library.LibraryScreen] — the merged,
 * de-duplicated grid across every server, matching native's Library tab), replacing the
 * server-list-first [ManageServersRoute] Stage D's predecessor used here — server management
 * moved to Settings instead (see [SettingsScreen]'s doc comment); Settings is [SettingsRoute].
 */
private fun TopLevelDestination.toRoute(): Any = when (this) {
    TopLevelDestination.HOME -> HomeRoute
    TopLevelDestination.LIBRARY -> LibraryRoute
    TopLevelDestination.SETTINGS -> SettingsRoute
}

@Composable
fun App(container: AppContainer, onOpenReader: OnOpenReader) {
    // Phase 4 Stage E1 (issue #136) — Settings' theme chips need this to actually do
    // something; a control that doesn't visibly change anything is worse than no control.
    val theme by container.appPreferences.preferences.collectAsState(initial = AppPreferences())
    val darkTheme = when (theme.theme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    DexxiconTheme(darkTheme = darkTheme) {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentDestination: NavDestination? = backStackEntry?.destination
        val currentTopLevel = TopLevelDestination.entries.firstOrNull { destination ->
            currentDestination.isOnTopLevel(destination)
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= RAIL_BREAKPOINT
            val showRail = wide && currentTopLevel != null
            val showBottomBar = !wide && currentTopLevel != null

            Scaffold(
                bottomBar = {
                    // navigationBarsPadding() here is load-bearing — see the equivalent
                    // comment in native's DexxiconApp.kt (issue #115 / PR #120 feedback):
                    // without it the pill sits flush against the bottom edge, under the
                    // system's gesture swipe indicator instead of clear of it.
                    if (showBottomBar) {
                        FloatingPillNavBar(
                            destinations = TopLevelDestination.entries,
                            current = currentTopLevel,
                            icon = { it.icon },
                            label = { it.label },
                            onSelect = { nav.switchTopLevel(it) },
                            modifier = Modifier.navigationBarsPadding(),
                        )
                    }
                },
            ) { innerPadding ->
                Row(Modifier.fillMaxSize().padding(innerPadding)) {
                    if (showRail) {
                        PillNavigationRail(
                            destinations = TopLevelDestination.entries,
                            current = currentTopLevel,
                            icon = { it.icon },
                            label = { it.label },
                            onSelect = { nav.switchTopLevel(it) },
                        )
                    }
                    NavHost(
                        navController = nav,
                        startDestination = HomeRoute,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                container = container,
                                onOpenBook = { serverId, bookId -> nav.navigate(BookDetailRoute(serverId, bookId)) },
                                onOpenReader = onOpenReader,
                            )
                        }
                        composable<LibraryRoute> {
                            LibraryScreen(
                                container = container,
                                onOpenBook = { book -> nav.navigate(BookDetailRoute(book.primary.serverId, book.primary.bookId)) },
                                onOpenReader = onOpenReader,
                            )
                        }
                        composable<ManageServersRoute> {
                            ServersScreen(
                                container = container,
                                onBack = { nav.popBackStack() },
                                onAddServer = { nav.navigate(AddServerRoute) },
                                onEditServer = { serverId -> nav.navigate(EditServerRoute(serverId)) },
                                onOpenServer = { serverId -> nav.navigate(BooksRoute(serverId)) },
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(
                                container = container,
                                onManageServers = { nav.navigate(ManageServersRoute) },
                            )
                        }
                        composable<AddServerRoute> {
                            AddServerScreen(
                                container = container,
                                editingId = null,
                                onBack = { nav.popBackStack() },
                                onSaved = { nav.popBackStack() },
                            )
                        }
                        composable<EditServerRoute> { entry ->
                            val route = entry.toRoute<EditServerRoute>()
                            AddServerScreen(
                                container = container,
                                editingId = route.serverId,
                                onBack = { nav.popBackStack() },
                                onSaved = { nav.popBackStack() },
                            )
                        }
                        composable<BooksRoute> { entry ->
                            val route = entry.toRoute<BooksRoute>()
                            BooksScreen(
                                container = container,
                                serverId = route.serverId,
                                onBack = { nav.popBackStack() },
                                onOpenBook = { bookId -> nav.navigate(BookDetailRoute(route.serverId, bookId)) },
                            )
                        }
                        composable<BookDetailRoute> { entry ->
                            val route = entry.toRoute<BookDetailRoute>()
                            BookDetailScreen(
                                container = container,
                                serverId = route.serverId,
                                bookId = route.bookId,
                                onBack = { nav.popBackStack() },
                                onOpenReader = onOpenReader,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun NavHostController.switchTopLevel(destination: TopLevelDestination) {
    navigate(destination.toRoute()) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavDestination?.isOnTopLevel(destination: TopLevelDestination): Boolean {
    val route = destination.toRoute()
    return this?.hierarchy?.any { it.hasRoute(route::class) } == true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (serverId: String) -> Unit,
    onOpenServer: (serverId: String) -> Unit,
) {
    val servers by container.serverRepository.servers.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val serversState = remember { ServersState(container.serverRepository, scope) }
    var pendingDelete by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        val list = servers
        when {
            list == null -> Unit // first emission still pending
            list.isEmpty() -> EmptyServersState(Modifier.fillMaxSize().padding(padding), onAddServer)
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                if (list.size > 1) {
                    Text(
                        "Press and hold the handle to reorder. This order sets which library's " +
                            "books come first when browsing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                ReorderableServers(
                    servers = list,
                    accounts = serversState.accounts,
                    onOpen = { onOpenServer(it.id) },
                    // NATIVE opens the field-editing form (#90); OIDC opens the reauth-only
                    // screen instead (#94) — a BASIC server (generic OPDS) gets neither,
                    // :shared has no add-flow for those.
                    editLabel = { server ->
                        when (server.authMode) {
                            AuthMode.NATIVE -> "Edit"
                            AuthMode.OIDC -> "Sign in again"
                            AuthMode.BASIC -> null
                        }
                    },
                    onEdit = { onEditServer(it.id) },
                    onRemove = { pendingDelete = it },
                    onReorder = serversState::reorder,
                )
                OutlinedButton(
                    onClick = onAddServer,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add server")
                }
            }
        }
    }

    // A confirmation dialog, not a swipe gesture — issue #72 deliberately keeps this to a
    // tap + confirm so a stray touch (or an unfamiliar swipe direction on a round-cornered
    // list) can never silently drop a configured server.
    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${server.displayName}?") },
            text = { Text("This only removes it from Dexxicon — nothing changes on the server itself.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.serverRepository.delete(server.id) }
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyServersState(modifier: Modifier, onAddServer: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("No servers yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add your BookOrbit or Grimmory library to start browsing and reading.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAddServer) { Text("Add a server") }
    }
}

/**
 * The Servers list with long-press drag-to-reorder (Phase 4 Stage E1, issue #136) — ported
 * from native's `feature/settings/SettingsScreen.kt`. The order shown here is the display
 * priority used everywhere servers are listed or their books merged. Previously
 * deliberately skipped in favor of plain up/down buttons (issue #92 — see [ServersState]'s
 * doc comment for why); with Stage D moving this screen out from under the Library tab, this
 * is the natural point to close that UX gap instead of leaving it permanent.
 */
@Composable
private fun ReorderableServers(
    servers: List<Server>,
    accounts: Map<String, String>,
    onOpen: (Server) -> Unit,
    editLabel: (Server) -> String?,
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
                    editLabel = editLabel(server),
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
    editLabel: String?,
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
                Text(server.baseUrl, style = MaterialTheme.typography.bodySmall)
                // issue #92 — "me" endpoint reads back who the app is actually signed in
                // as; absent until that call resolves (or for a server type it doesn't
                // apply to).
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
                    if (editLabel != null) {
                        DropdownMenuItem(
                            text = { Text(editLabel) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServerScreen(
    container: AppContainer,
    editingId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(editingId) {
        AddServerState(
            container.serverProber,
            container.serverRepository,
            container.oidcAuthenticator,
            scope,
            editingId = editingId,
        )
    }

    // While the browser step is in progress, the SSO WebView replaces the form entirely —
    // once completeSso() moves ssoState past Ready (into Exchanging, on the way to Idle+saved
    // or Error), this falls through to the form below, which is fine: the exchange itself
    // needs no WebView, just a network round-trip.
    val sso = state.ssoState
    if (sso is SsoState.Ready) {
        SsoWebViewScreen(
            handshake = sso.handshake,
            pkce = sso.pkce,
            onCode = { code -> state.completeSso(sso.handshake, code, onSaved) },
            onError = state::onSsoError,
            onCancel = state::onSsoCancelled,
        )
        return
    }

    // While an OIDC edit target is loading, its auth mode isn't known yet — wait rather than
    // flash the native/password form for what's about to turn out to be a reauth screen
    // (issue #94).
    if (state.isEditing && state.editingAuthMode == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Edit server") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        }
        return
    }

    // Reauth-only for an existing OIDC server (issue #94) — no native/password fields apply,
    // just re-run the SSO handshake against the same server id. See AddServerState's doc
    // comment for why this is manual-only, not the native app's auto-detected-expiry flow.
    if (state.isEditing && state.editingAuthMode == AuthMode.OIDC) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign in again") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${state.displayName} uses single sign-on. Sign in again to refresh its session.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = state::discoverSso, enabled = state.baseUrl.isNotBlank()) {
                    Text("Sign in with SSO")
                }
                when (sso) {
                    SsoState.Discovering -> CircularProgressIndicator(Modifier.padding(8.dp))
                    is SsoState.Error -> Text(sso.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit server" else "Add server") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = state::onBaseUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("books.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // SSO sign-in only applies to adding a new server — an existing OIDC server uses
            // the reauth-only branch above instead (issue #94).
            if (!state.isEditing) {
                TextButton(onClick = state::discoverSso, enabled = state.baseUrl.isNotBlank()) {
                    Text("Sign in with SSO instead")
                }
                when (sso) {
                    SsoState.Discovering -> CircularProgressIndicator(Modifier.padding(8.dp))
                    is SsoState.Error -> Text(sso.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = state.username,
                onValueChange = state::onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = state::onPasswordChange,
                label = { Text(if (state.isEditing) "Password (leave blank to keep current)" else "Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = state::onDisplayNameChange,
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val testState = state.testState
            when (testState) {
                TestState.Idle -> Unit
                TestState.Testing -> CircularProgressIndicator(Modifier.padding(8.dp))
                is TestState.Success -> Text(
                    testState.detail,
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestState.Failure -> Text(
                    testState.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(onClick = state::test, enabled = state.canTest) { Text("Test connection") }
            Button(onClick = { state.save(onSaved) }, enabled = state.canSave) {
                Text(if (state.saving) "Saving…" else "Save")
            }
        }
    }
}
