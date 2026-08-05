package dev.nodera.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nodera.app.NoderaCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

enum class SettingsPage(val title: String, val summary: String, val icon: ImageVector) {
    Root("Settings", "", Icons.Default.Settings),
    Appearance("Appearance", "Theme and Material You colours", Icons.Default.Palette),
    Network("Network", "Connection policy and effective services", Icons.Default.Wifi),
    TrackerStores("Trackers & stores", "Direct trackers and trusted publishers", Icons.Default.Dns),
    Storage("Storage", "Archive location and disk budget", Icons.Default.Storage),
    Battery("Battery", "Background access and power rules", Icons.Default.BatterySaver),
    Peers("Peers", "Connections and this device's peer", Icons.Default.Hub),
    Privacy("Privacy", "Telemetry consent and collection", Icons.Default.PrivacyTip),
    Diagnostics("Diagnostics", "Worker link and applied configuration", Icons.Default.MonitorHeart),
    About("About", "Build, protocol, and project", Icons.Default.Info),
    Licenses("Open-source licenses", "Libraries included in this build", Icons.Default.Description),
}

private data class ReviewedStore(val url: String, val index: JSONObject)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    page: SettingsPage,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    node: NodeState,
    themePreference: String,
    onThemePreference: (String) -> Unit,
    offeredStoreUrl: String?,
    onStoreHandled: () -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (wide && page == SettingsPage.Root) "Settings" else page.title) },
                navigationIcon = {
                    if (!wide && page != SettingsPage.Root) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (wide) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                SettingsMenu(page, onOpen, Modifier.width(320.dp))
                VerticalDivider()
                Box(Modifier.weight(1f)) {
                    if (page == SettingsPage.Root) SettingsOverview(onOpen) else SettingsContent(
                        page, node, themePreference, onThemePreference, onOpen,
                        offeredStoreUrl, onStoreHandled,
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (page == SettingsPage.Root) SettingsMenu(page, onOpen, Modifier.fillMaxSize())
                else SettingsContent(
                    page, node, themePreference, onThemePreference, onOpen,
                    offeredStoreUrl, onStoreHandled,
                )
            }
        }
    }
}

@Composable
private fun SettingsMenu(active: SettingsPage, onOpen: (SettingsPage) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(SettingsPage.entries.filter { it != SettingsPage.Root && it != SettingsPage.Licenses }) { entry ->
            ListItem(
                leadingContent = { Icon(entry.icon, null) },
                headlineContent = { Text(entry.title) },
                supportingContent = { Text(entry.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                colors = ListItemDefaults.colors(
                    containerColor = if (active == entry) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth().clickable { onOpen(entry) },
            )
        }
    }
}

@Composable
private fun SettingsOverview(onOpen: (SettingsPage) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Tune this node", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Changes are saved through the node core. Pages show enforcement status wherever the worker reports it, " +
                    "and label controls this Android bridge cannot apply.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(SettingsPage.Diagnostics) }) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.MonitorHeart, null) },
                    headlineContent = { Text("Check node health") },
                    supportingContent = { Text("Worker link, configuration delivery, and errors") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                )
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(SettingsPage.Network) }) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Wifi, null) },
                    headlineContent = { Text("Review data use") },
                    supportingContent = { Text("Protect mobile data and inspect every service this node dials") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(
    page: SettingsPage,
    node: NodeState,
    themePreference: String,
    onThemePreference: (String) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    offeredStoreUrl: String?,
    onStoreHandled: () -> Unit,
) {
    when (page) {
        SettingsPage.Appearance -> AppearancePage(themePreference, onThemePreference)
        SettingsPage.Network -> NetworkPage(onOpen)
        SettingsPage.TrackerStores -> TrackerStoresPage(offeredStoreUrl, onStoreHandled)
        SettingsPage.Storage -> StoragePage()
        SettingsPage.Battery -> BatteryPage()
        SettingsPage.Peers -> PeersPage(node)
        SettingsPage.Privacy -> PrivacyPage(node)
        SettingsPage.Diagnostics -> DiagnosticsPage(node)
        SettingsPage.About -> AboutPage(onOpen)
        SettingsPage.Licenses -> LicensesPage()
        SettingsPage.Root -> Unit
    }
}

@Composable
private fun AppearancePage(themePreference: String, onThemePreference: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var statusVersion by remember { mutableIntStateOf(0) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (DYNAMIC_COLOUR_AVAILABLE) "Colours from your wallpaper" else "Nodera colour palette",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (DYNAMIC_COLOUR_AVAILABLE) "Android 12+ supplies this palette. Theme choice controls brightness."
                        else "This Android version uses Nodera's branded Material 3 palette.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionTitle("Theme") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                    FilterChip(
                        selected = themePreference == value,
                        onClick = {
                            scope.launch {
                                val error = NoderaCore.errorOf(
                                    NoderaCore.call("set_theme", JSONObject().put("theme", value)),
                                )
                                if (error == null) {
                                    onThemePreference(value)
                                    statusVersion += 1
                                }
                                message = error ?: "Theme saved"
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        item { SettingStatusNote("appearance.theme", statusVersion) }
        message?.let { item { MessageCard(if (it == "Theme saved") "Saved" else "Could not save", it, it != "Theme saved") } }
    }
}

@Composable
private fun NetworkPage(onOpen: (SettingsPage) -> Unit) {
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<JSONObject?>(null) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var policy by rememberSaveable { mutableStateOf("wifi_only") }
    var maxConnections by rememberSaveable { mutableStateOf("200") }
    var hydrated by rememberSaveable { mutableStateOf(false) }
    var appliedPolicy by rememberSaveable { mutableStateOf("wifi_only") }
    var appliedMaxConnections by rememberSaveable { mutableStateOf("200") }
    var applying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var statusVersion by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        document = NoderaCore.obj("settings")
        state = NoderaCore.obj("network_state")
        if (!hydrated) {
            document?.optJSONObject("network")?.let { network ->
                policy = network.optString("transfer_network", "wifi_only")
                maxConnections = network.optLong("max_connections", 200).toString()
                appliedPolicy = policy
                appliedMaxConnections = maxConnections
                hydrated = true
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    val parsedMaxConnections = maxConnections.toLongOrNull()
    val maxConnectionsValid = parsedMaxConnections != null && parsedMaxConnections in 0..UInt.MAX_VALUE.toLong()
    val networkChanged = hydrated && (policy != appliedPolicy || maxConnections != appliedMaxConnections)

    suspend fun applyNetwork() {
        applying = true
        val error = NoderaCore.errorOf(
            NoderaCore.call(
                "set_network_policy",
                JSONObject()
                    .put("transfer_network", policy)
                    .put("max_connections", maxConnections.toLong()),
            ),
        )
        applying = false
        if (error == null) {
            appliedPolicy = policy
            appliedMaxConnections = maxConnections
            statusVersion += 1
            message = null
        } else {
            message = error
        }
    }

    LaunchedEffect(policy, maxConnections) {
        if (!hydrated || !networkChanged || !maxConnectionsValid) return@LaunchedEffect
        delay(800)
        applyNetwork()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val error = state?.optString("error").orEmpty()
            val transport = state?.optString("transport").orEmpty()
            val known = state != null && error.isEmpty() && transport !in setOf("", "unknown", "offline")
            val headline = when {
                state == null -> "Reading connection"
                error.isNotEmpty() -> "Connection status unavailable"
                transport == "unknown" || transport.isEmpty() -> "Connection type unknown"
                transport == "offline" -> "No active network"
                else -> transport.replace('_', ' ').replaceFirstChar(Char::uppercase)
            }
            val detail = when {
                state == null -> "Waiting for Android"
                error.isNotEmpty() -> "Android could not classify this connection"
                transport == "unknown" || transport.isEmpty() -> "Metering status is not known"
                transport == "offline" -> "Metering does not apply while offline"
                state?.optBoolean("metered") == true -> "Metered connection"
                else -> "Unmetered connection"
            } + if (state?.optBoolean("vpn") == true) " · VPN" else ""
            ElevatedCard(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Wifi, null) },
                    headlineContent = { Text(headline) },
                    supportingContent = { Text(detail) },
                    trailingContent = {
                        when {
                            state == null -> StatusPill("Checking")
                            error.isNotEmpty() -> StatusPill("Unavailable", false)
                            transport == "offline" -> StatusPill("Offline", false)
                            !known -> StatusPill("Unknown")
                            state?.optBoolean("metered") == true -> StatusPill("Metered")
                            else -> StatusPill("Unmetered", true)
                        }
                    },
                )
            }
        }
        state?.optString("error")?.takeIf { it.isNotEmpty() }?.let { error ->
            item { MessageCard("Android could not read this connection", error, true) }
        }
        item { SectionTitle("Transfer network", "Choose when this node may move world data") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "wifi_only" to "Wi-Fi or Ethernet",
                    "unmetered_only" to "Any unmetered connection",
                    "any" to "Any connection",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = policy == value,
                        onClick = { policy = value },
                        label = { Text(label) },
                        leadingIcon = { if (policy == value) Icon(Icons.Default.Check, null) },
                    )
                }
            }
        }
        item { SettingStatusNote("network.transfer_network", statusVersion) }
        item { SectionTitle("Connection capacity", "Maximum simultaneous peer connections") }
        item {
            OutlinedTextField(
                value = maxConnections,
                onValueChange = { maxConnections = it.filter(Char::isDigit).take(10) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Maximum connections") },
                supportingText = {
                    Text(if (maxConnections == "0") "Unlimited" else "0 means unlimited")
                },
                isError = !maxConnectionsValid,
                singleLine = true,
            )
        }
        item { SettingStatusNote("network.max_connections", statusVersion) }
        if (networkChanged || applying || message != null) item {
            AutoApplyBar(
                changed = networkChanged,
                applying = applying,
                error = message,
                onUndo = {
                    policy = appliedPolicy
                    maxConnections = appliedMaxConnections
                    message = null
                },
                onRetry = { scope.launch { applyNetwork() } },
            )
        }
        item { SectionTitle("Tracker sources", "Manage direct addresses and subscribed stores together") }
        item {
            OutlinedButton(onClick = { onOpen(SettingsPage.TrackerStores) }) {
                Icon(Icons.Default.Dns, null)
                Spacer(Modifier.width(8.dp))
                Text("Manage trackers & stores")
            }
        }
    }
}

@Composable
private fun ServiceList(title: String, entries: JSONArray?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text("${entries?.length() ?: 0} configured") },
            )
            if (entries == null || entries.length() == 0) {
                Text("None", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else for (i in 0 until entries.length()) {
                val endpoint = entries.optJSONObject(i) ?: continue
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.Default.Dns, null) },
                    headlineContent = { Text(endpoint.optString("endpoint")) },
                    supportingContent = { Text(endpoint.optString("store").ifEmpty { "Added directly" }) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerStoresPage(offeredStoreUrl: String?, onStoreHandled: () -> Unit) {
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<JSONObject?>(null) }
    var directTrackers by remember { mutableStateOf<List<String>>(emptyList()) }
    var directTracker by rememberSaveable { mutableStateOf("") }
    var services by remember { mutableStateOf<JSONObject?>(null) }
    var stores by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var url by rememberSaveable { mutableStateOf(offeredStoreUrl.orEmpty()) }
    var preview by remember { mutableStateOf<ReviewedStore?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<JSONObject?>(null) }
    var removeDirect by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val settingsRaw = NoderaCore.call("settings")
        val servicesRaw = NoderaCore.call("resolved_services")
        val storesRaw = NoderaCore.call("tracker_stores")
        val error = listOf(settingsRaw, servicesRaw, storesRaw)
            .firstNotNullOfOrNull(NoderaCore::errorOf)
        val nextDocument = runCatching { JSONObject(settingsRaw) }.getOrNull()
        val nextServices = runCatching { JSONObject(servicesRaw) }.getOrNull()
        if (error != null || nextDocument == null || nextServices == null) {
            loadError = error ?: "The node returned tracker settings in an unreadable form."
        } else {
            document = nextDocument
            directTrackers = jsonStrings(
                nextDocument.optJSONObject("network")?.optJSONArray("default_trackers"),
            ).distinct()
            services = nextServices
            stores = parseArray(storesRaw)
            loadError = null
        }
        loading = false
    }

    suspend fun saveDirectTrackers(next: List<String>, success: String) {
        val raw = NoderaCore.call(
            "set_direct_trackers",
            JSONObject().put("trackers", JSONArray(next.distinct())),
        )
        val error = NoderaCore.errorOf(raw)
        message = error ?: success
        messageIsError = error != null
        if (error == null) load()
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!busy) load()
            delay(30_000)
        }
    }
    LaunchedEffect(offeredStoreUrl) {
        if (offeredStoreUrl != null) {
            url = offeredStoreUrl
            preview = null
            message = null
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MessageCard(
                "Tracker sources are not authorities",
                "Direct addresses and store suggestions only help discovery. Peers still verify identities and world data.",
            )
        }
        if (loading) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        loadError?.let { error ->
            item { MessageCard("Could not load tracker sources", error, true) }
            item { OutlinedButton(onClick = { scope.launch { load() } }) { Text("Retry") } }
        }
        item { SectionTitle("Direct trackers", "Addresses this node always tries") }
        item {
            OutlinedTextField(
                value = directTracker,
                onValueChange = { directTracker = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tracker host:port or tcp://host:port") },
                leadingIcon = { Icon(Icons.Default.Dns, null) },
                singleLine = true,
            )
        }
        item {
            Button(
                enabled = !busy && document != null && directTracker.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        val endpoint = directTracker.trim()
                        saveDirectTrackers((directTrackers + endpoint).distinct(), "Tracker added")
                        if (!messageIsError) directTracker = ""
                        busy = false
                    }
                },
            ) { Text("Add direct tracker") }
        }
        if (!loading && loadError == null && directTrackers.isEmpty()) item {
            MessageCard("No direct trackers", "Subscribed stores can still contribute tracker addresses.")
        }
        items(directTrackers, key = { it }) { endpoint ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Dns, null) },
                    headlineContent = { Text(endpoint) },
                    supportingContent = { Text("Added directly") },
                    trailingContent = {
                        IconButton(enabled = !busy, onClick = { removeDirect = endpoint }) {
                            Icon(Icons.Default.DeleteOutline, "Remove direct tracker")
                        }
                    },
                )
            }
        }
        item { SectionTitle("Effective trackers", "Direct and store-provided addresses now in use") }
        if (!loading) item { ServiceList("Trackers", services?.optJSONArray("trackers")) }
        item { SectionTitle("Effective rendezvous relays", "Store-provided relay addresses now in use") }
        if (!loading) item { ServiceList("Rendezvous relays", services?.optJSONArray("rendezvous")) }
        item { SectionTitle("Tracker stores", "HTTPS lists maintained by publishers you choose") }
        item {
            OutlinedTextField(
                value = url,
                onValueChange = { next ->
                    if (next != url) {
                        preview = null
                        message = null
                        if (offeredStoreUrl != null) onStoreHandled()
                    }
                    url = next
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HTTPS store URL") },
                leadingIcon = { Icon(Icons.Default.Link, null) },
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val requestedUrl = url.trim()
                            val raw = NoderaCore.call("preview_tracker_store", JSONObject().put("url", requestedUrl))
                            message = NoderaCore.errorOf(raw)
                            messageIsError = message != null
                            preview = if (message == null && url.trim() == requestedUrl) {
                                runCatching { ReviewedStore(requestedUrl, JSONObject(raw)) }.getOrNull()
                            } else null
                            busy = false
                        }
                    },
                    enabled = !busy && url.isNotBlank(),
                ) { Text("Preview") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val error = NoderaCore.errorOf(NoderaCore.call("refresh_tracker_stores"))
                            message = error ?: "Stores refreshed"
                            messageIsError = error != null
                            load()
                            busy = false
                        }
                    },
                    enabled = !busy && stores.isNotEmpty(),
                ) { Text("Refresh all") }
            }
        }
        if (offeredStoreUrl != null) item {
            TextButton(onClick = {
                url = ""
                preview = null
                onStoreHandled()
            }) { Text("Dismiss offered store") }
        }
        message?.let { item { MessageCard("Trackers & stores", it, messageIsError) } }
        item { SectionTitle("Subscribed stores", "${stores.size} trusted publisher${if (stores.size == 1) "" else "s"}") }
        if (!loading && loadError == null && stores.isEmpty()) item { MessageCard("No stores", "Add an HTTPS index above. No source is trusted silently.") }
        items(stores, key = { it.optString("url") }) { store ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        leadingContent = { Icon(if (store.optBoolean("built_in")) Icons.Default.Verified else Icons.Default.Store, null) },
                        headlineContent = { Text(store.optString("name", "Unnamed store")) },
                        supportingContent = {
                            Text("${store.optJSONArray("services")?.length() ?: 0} services · ${store.optString("description")}")
                        },
                        trailingContent = {
                            IconButton(enabled = !busy, onClick = { remove = store }) {
                                Icon(Icons.Default.DeleteOutline, "Remove ${store.optString("name", "store")}")
                            }
                        },
                    )
                    if (store.optString("last_error").isNotEmpty()) {
                        Text(
                            store.optString("last_error"),
                            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        store.optString("url"),
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    preview?.let { reviewed ->
        val index = reviewed.index
        AlertDialog(
            onDismissRequest = {
                preview = null
                if (offeredStoreUrl != null) onStoreHandled()
            },
            icon = { Icon(Icons.Default.Store, null) },
            title = { Text(index.optString("name", "Store preview")) },
            text = {
                Text(
                    storePreviewText(index, reviewed.url),
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val raw = NoderaCore.call("add_tracker_store", JSONObject().put("url", reviewed.url))
                        val error = NoderaCore.errorOf(raw)
                        message = error ?: "Store added"
                        messageIsError = error != null
                        preview = null
                        if (error == null && offeredStoreUrl != null) onStoreHandled()
                        load()
                        busy = false
                    }
                }) { Text("Add store") }
            },
            dismissButton = {
                TextButton(onClick = {
                    preview = null
                    if (offeredStoreUrl != null) onStoreHandled()
                }) { Text("Cancel") }
            },
        )
    }
    remove?.let { store ->
        val impact = storeRemovalImpact(store, stores, directTrackers)
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Remove ${store.optString("name")}?" ) },
            text = {
                Text(impact, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        val raw = NoderaCore.call("remove_tracker_store", JSONObject().put("url", store.optString("url")))
                        val error = NoderaCore.errorOf(raw)
                        message = error ?: "Store removed"
                        messageIsError = error != null
                        remove = null
                        load()
                        busy = false
                    }
                }, enabled = !busy) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } },
        )
    }
    removeDirect?.let { endpoint ->
        AlertDialog(
            onDismissRequest = { removeDirect = null },
            title = { Text("Remove tracker?") },
            text = { Text("$endpoint will stop being used unless a subscribed store also provides it.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        saveDirectTrackers(directTrackers.filterNot { it == endpoint }, "Tracker removed")
                        removeDirect = null
                        busy = false
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeDirect = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StoragePage() {
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<JSONObject?>(null) }
    var storageInfo by remember { mutableStateOf<JSONObject?>(null) }
    var selectedDir by rememberSaveable { mutableStateOf("") }
    var pickedChoice by remember { mutableStateOf<StorageChoice?>(null) }
    var locationBusy by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var budgetBytes by rememberSaveable { mutableLongStateOf(0L) }
    var budgetGb by rememberSaveable { mutableFloatStateOf(0f) }
    var budgetEdited by rememberSaveable { mutableStateOf(false) }
    var sweepSeconds by rememberSaveable { mutableStateOf("0") }
    var policyHydrated by rememberSaveable { mutableStateOf(false) }
    var appliedBudgetGb by rememberSaveable { mutableFloatStateOf(0f) }
    var appliedSweepSeconds by rememberSaveable { mutableStateOf("0") }
    var applying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var statusVersion by remember { mutableIntStateOf(0) }

    suspend fun reloadStorageInfo() {
        storageInfo = NoderaCore.obj("storage_info")
        storageInfo?.let { if (selectedDir.isEmpty()) selectedDir = it.optString("current") }
    }

    suspend fun chooseFolder() {
        message = null
        val openError = NoderaCore.errorOf(NoderaCore.call("pick_storage_folder"))
        if (openError != null) {
            message = openError
            return
        }
        picking = true
    }

    fun acceptPickedFolder(result: JSONObject) {
        picking = false
        val reason = result.optString("error")
        val path = result.optString("path")
        when {
            reason.isNotEmpty() -> message = reason
            result.optBoolean("writable") && path.isNotEmpty() -> {
                pickedChoice = StorageChoice(
                    path,
                    result.optString("label", "Chosen folder"),
                    "Selected with Android's folder picker and verified with a test write.",
                    null,
                    true,
                )
                selectedDir = path
            }
        }
    }

    LaunchedEffect(picking) {
        while (picking) {
            val result = NoderaCore.obj("picked_folder")
            if (result != null && !result.optBoolean("pending")) {
                acceptPickedFolder(result)
                break
            }
            delay(500)
        }
    }

    LaunchedEffect(Unit) {
        document = NoderaCore.obj("settings")
        reloadStorageInfo()
        val recovered = NoderaCore.obj("picked_folder")
        if (recovered?.optBoolean("writable") == true &&
            recovered.optString("path") == selectedDir &&
            storageChoices(storageInfo).none { it.path == selectedDir }
        ) {
            acceptPickedFolder(recovered)
        }
        if (!policyHydrated) {
            document?.optJSONObject("storage")?.let { storage ->
                budgetBytes = storage.optLong("replication_budget_bytes")
                budgetGb = budgetBytes / 1_073_741_824f
                budgetEdited = false
                sweepSeconds = storage.optLong("replication_sweep_seconds").toString()
                appliedBudgetGb = budgetGb
                appliedSweepSeconds = sweepSeconds
                policyHydrated = true
            }
        }
    }
    val choices = (storageChoices(storageInfo) + listOfNotNull(pickedChoice))
        .distinctBy(StorageChoice::path)
    val storageChanged = policyHydrated && (budgetGb != appliedBudgetGb || sweepSeconds != appliedSweepSeconds)

    suspend fun applyStorage() {
        applying = true
        val bytes = if (budgetEdited) budgetGb.roundToInt().toLong() * 1_073_741_824L else budgetBytes
        val error = NoderaCore.errorOf(
            NoderaCore.call(
                "set_storage_policy",
                JSONObject()
                    .put("budget_bytes", bytes)
                    .put("sweep_seconds", sweepSeconds.toLongOrNull() ?: 0),
            ),
        )
        applying = false
        if (error == null) {
            appliedBudgetGb = budgetGb
            appliedSweepSeconds = sweepSeconds
            statusVersion += 1
            message = null
        } else {
            message = error
        }
    }

    LaunchedEffect(budgetGb, sweepSeconds) {
        if (!policyHydrated || !storageChanged) return@LaunchedEffect
        delay(800)
        applyStorage()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionTitle("World storage", "Choose a location the worker has verified it can write") }
        item {
            StorageLocationChooser(
                choices = choices,
                selectedPath = selectedDir,
                loading = storageInfo == null,
                enabled = !locationBusy && !picking,
                onSelected = { selectedDir = it },
                onBrowse = { scope.launch { chooseFolder() } },
            )
        }
        item {
            Button(
                enabled = !locationBusy && !picking && selectedDir.isNotEmpty() && selectedDir != storageInfo?.optString("current"),
                onClick = {
                    scope.launch {
                        locationBusy = true
                        val raw = NoderaCore.call("set_storage_dir", JSONObject().put("worlds_dir", selectedDir))
                        val error = NoderaCore.errorOf(raw)
                        message = error ?: "Storage location saved"
                        if (error == null) {
                            document = NoderaCore.obj("settings")
                            reloadStorageInfo()
                            statusVersion += 1
                        }
                        locationBusy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (picking) "Waiting for folder…" else if (locationBusy) "Saving…" else "Use selected location") }
        }
        item { SettingStatusNote("storage.peer_worlds_dir", statusVersion) }
        item { SectionTitle("Archive budget", "Space available for preserving other people's worlds") }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (budgetGb < .5f) "Core default" else "${budgetGb.roundToInt()} GB", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = budgetGb,
                        onValueChange = {
                            budgetGb = it
                            budgetEdited = true
                        },
                        valueRange = 0f..64f,
                        steps = 63,
                    )
                    Text("0 uses worker default", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SettingStatusNote("storage.replication_budget_bytes", statusVersion) }
        item {
            OutlinedTextField(
                value = sweepSeconds,
                onValueChange = { sweepSeconds = it.filter(Char::isDigit).take(12) },
                label = { Text("Replication sweep interval (seconds)") },
                supportingText = { Text("0 uses worker default; worker enforces its 30-second minimum") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item { SettingStatusNote("storage.replication_sweep_seconds", statusVersion) }
        if (storageChanged || applying || message != null) item {
            AutoApplyBar(
                changed = storageChanged,
                applying = applying,
                error = message,
                onUndo = {
                    budgetGb = appliedBudgetGb
                    budgetEdited = appliedBudgetGb != budgetBytes / 1_073_741_824f
                    sweepSeconds = appliedSweepSeconds
                    message = null
                },
                onRetry = { scope.launch { applyStorage() } },
            )
        }
    }
}

@Composable
private fun BatteryPage() {
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<JSONObject?>(null) }
    var policy by remember { mutableStateOf<JSONObject?>(null) }
    var onlyCharging by rememberSaveable { mutableStateOf(false) }
    var floorEnabled by rememberSaveable { mutableStateOf(false) }
    var threshold by rememberSaveable { mutableFloatStateOf(20f) }
    var duringGame by rememberSaveable { mutableStateOf(false) }
    var hydrated by rememberSaveable { mutableStateOf(false) }
    var appliedCharging by rememberSaveable { mutableStateOf(false) }
    var appliedFloor by rememberSaveable { mutableStateOf(false) }
    var appliedThreshold by rememberSaveable { mutableFloatStateOf(20f) }
    var appliedDuringGame by rememberSaveable { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var statusVersion by remember { mutableIntStateOf(0) }
    var policyBusy by remember { mutableStateOf(false) }

    suspend fun reloadPolicy() {
        policyBusy = true
        policy = NoderaCore.obj("battery_policy")
        policyBusy = false
    }

    LaunchedEffect(Unit) {
        document = NoderaCore.obj("settings")
        reloadPolicy()
        if (!hydrated) {
            document?.optJSONObject("behavior")?.let {
                onlyCharging = it.optBoolean("only_when_charging")
                floorEnabled = it.optBoolean("battery_control")
                threshold = it.optInt("battery_threshold_percent", 20).toFloat()
                duringGame = it.optBoolean("power_rules_during_game")
                appliedCharging = onlyCharging
                appliedFloor = floorEnabled
                appliedThreshold = threshold
                appliedDuringGame = duringGame
                hydrated = true
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            reloadPolicy()
        }
    }
    val batteryChanged = hydrated && (
        onlyCharging != appliedCharging ||
            floorEnabled != appliedFloor ||
            threshold != appliedThreshold ||
            duringGame != appliedDuringGame
    )

    suspend fun applyBattery() {
        applying = true
        val error = NoderaCore.errorOf(
            NoderaCore.call(
                "set_power_rules",
                JSONObject()
                    .put("only_charging", onlyCharging)
                    .put("battery_control", floorEnabled)
                    .put("threshold", threshold.roundToInt())
                    .put("during_game", duringGame),
            ),
        )
        applying = false
        if (error == null) {
            appliedCharging = onlyCharging
            appliedFloor = floorEnabled
            appliedThreshold = threshold
            appliedDuringGame = duringGame
            statusVersion += 1
            message = null
        } else {
            message = error
        }
    }

    LaunchedEffect(onlyCharging, floorEnabled, threshold, duringGame) {
        if (!hydrated || !batteryChanged) return@LaunchedEffect
        delay(800)
        applyBattery()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val error = policy?.optString("error").orEmpty()
            val supported = policy?.optBoolean("supported") == true
            BatteryOptimizationCard(
                restricted = policy?.takeIf { supported && error.isEmpty() }?.optBoolean("restricted"),
                manufacturer = policy?.optString("manufacturer").orEmpty(),
                error = error,
                busy = policyBusy,
                onOpenSettings = {
                    scope.launch {
                        NoderaCore.errorOf(NoderaCore.call("open_battery_settings"))?.let { message = it }
                    }
                },
                onRefresh = { scope.launch { reloadPolicy() } },
                onHelp = {
                    scope.launch {
                        NoderaCore.errorOf(NoderaCore.call("open_battery_help"))?.let { message = it }
                    }
                },
            )
        }
        item { SectionTitle("Transfer rules") }
        item { SwitchRow("Only while charging", "Pause transfers when running on battery", onlyCharging) { onlyCharging = it } }
        item { SwitchRow("Battery floor", "Pause below a chosen charge level", floorEnabled) { floorEnabled = it } }
        if (floorEnabled) item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pause below ${threshold.roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                    Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 5f..80f, steps = 14)
                }
            }
        }
        if (floorEnabled || onlyCharging) item {
            SwitchRow(
                "Apply while a game is open",
                "May pause sharing during active play",
                duringGame,
            ) { duringGame = it }
        }
        item { SettingStatusNote("behavior.battery_control", statusVersion) }
        if (batteryChanged || applying || message != null) item {
            AutoApplyBar(
                changed = batteryChanged,
                applying = applying,
                error = message,
                onUndo = {
                    onlyCharging = appliedCharging
                    floorEnabled = appliedFloor
                    threshold = appliedThreshold
                    duringGame = appliedDuringGame
                    message = null
                },
                onRetry = { scope.launch { applyBattery() } },
            )
        }
    }
}

/**
 * Replaces every Save button. Controls mutate local state immediately; this bar watches for
 * divergence from the last confirmed values and shows "Undo" during a brief debounce window before
 * the change reaches the worker. An accidental tap costs one press of Undo, not a round trip.
 */
@Composable
private fun AutoApplyBar(
    changed: Boolean,
    applying: Boolean,
    error: String?,
    onUndo: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    when {
        error != null -> Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        applying -> Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Applying…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        changed -> Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Applying in a moment", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                TextButton(onClick = onUndo) { Text("Undo") }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onChecked,
        ),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeersPage(node: NodeState) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<PeerRow?>(null) }
    var mobile by remember { mutableStateOf<JSONObject?>(null) }
    var testing by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<JSONObject?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var peerGeneration by remember { mutableIntStateOf(0) }
    val dashboardAvailable = node.known && !node.stale && node.fault == null

    LaunchedEffect(dashboardAvailable, node.nodeId, node.trackers) {
        peerGeneration += 1
        mobile = null
        statusError = null
        test = null
        testError = null
        testing = false
        if (!dashboardAvailable) return@LaunchedEffect
        val raw = NoderaCore.call("peer_status")
        statusError = NoderaCore.errorOf(raw)
        mobile = if (statusError == null) runCatching { JSONObject(raw) }.getOrNull() else null
    }
    val peerStatusAvailable = mobile != null
    val list: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.PhoneAndroid, null) },
                        headlineContent = {
                            Text(
                                shortId(
                                    mobile?.optString("node_id").orEmpty()
                                        .ifEmpty { if (dashboardAvailable) node.nodeId else "Not reported" },
                                ),
                            )
                        },
                        supportingContent = {
                            Text(
                                when {
                                    peerStatusAvailable -> "This device · ${mobile!!.optLong("known_peers")} peers known"
                                    statusError != null -> "This device · peer status unavailable"
                                    else -> "This device · checking peer status"
                                },
                            )
                        },
                        trailingContent = {
                            when {
                                peerStatusAvailable -> {
                                    val announced = mobile!!.optBoolean("announced")
                                    StatusPill(if (announced) "Announced" else "Not announced", announced)
                                }
                                statusError != null -> StatusPill("Unavailable", false)
                                else -> StatusPill("Checking")
                            }
                        },
                    )
                }
            }
            item {
                Button(onClick = {
                    scope.launch {
                        val generation = peerGeneration
                        testing = true
                        val raw = NoderaCore.call("peer_self_test")
                        if (generation == peerGeneration) {
                            testError = NoderaCore.errorOf(raw)
                            test = if (testError == null) runCatching { JSONObject(raw) }.getOrNull() else null
                            testing = false
                        }
                    }
                }, enabled = dashboardAvailable && !testing) {
                    Icon(Icons.Default.Science, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (testing) "Testing…" else "Run network self-test")
                }
            }
            test?.let { result ->
                item {
                    MessageCard(
                        if (result.optBoolean("passed")) "Self-test passed" else "Self-test failed",
                        if (result.optBoolean("passed")) "Tracker returned this device in its peer directory."
                        else result.optString("error", "No diagnostic was returned."),
                        !result.optBoolean("passed"),
                    )
                }
            }
            statusError?.let { item { MessageCard("Peer status unavailable", it, true) } }
            testError?.let { item { MessageCard("Peer self-test failed", it, true) } }
            item {
                SectionTitle(
                    "Active connections",
                    if (dashboardAvailable) "${node.peerRows.size} peers exchanging data"
                    else "Connection state unavailable",
                )
            }
            if (node.peerRows.isEmpty()) item {
                MessageCard(
                    if (dashboardAvailable) "No active connections" else "Connections unavailable",
                    if (dashboardAvailable) "This node is online but no peer is exchanging data right now."
                    else "Waiting for a current dashboard from the node.",
                )
            }
            items(node.peerRows, key = { it.id }) { peer ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { selected = peer }) {
                    ListItem(
                        leadingContent = { Icon(if (peer.path == "direct") Icons.Default.Lan else Icons.Default.SyncAlt, null) },
                        headlineContent = { Text(shortId(peer.id)) },
                        supportingContent = { Text("${peer.path.replaceFirstChar(Char::uppercase)} · ${peer.client.ifEmpty { peer.route }}") },
                        trailingContent = { Text("↑ ${formatBytes(peer.upPerSec)}/s") },
                    )
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 720.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(.55f)) { list() }
                VerticalDivider()
                Box(Modifier.weight(.45f)) {
                    selected?.let { PeerDetails(it) }
                        ?: EmptyState(Icons.Default.Hub, "Select a peer", "Connection details appear here")
                }
            }
        } else {
            list()
            selected?.let { peer ->
                ModalBottomSheet(onDismissRequest = { selected = null }) {
                    PeerDetails(peer)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PeerDetails(peer: PeerRow) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { Text("Connection details", style = MaterialTheme.typography.headlineSmall) }
        item { KeyValue("Node", peer.id) }
        item { KeyValue("Route", peer.route) }
        item { KeyValue("Path", peer.path.replaceFirstChar(Char::uppercase)) }
        item { KeyValue("Client", peer.client) }
        item { KeyValue("Current upload", "${formatBytes(peer.upPerSec)}/s") }
        item { KeyValue("Current download", "${formatBytes(peer.downPerSec)}/s") }
        item { KeyValue("Uploaded this connection", formatBytes(peer.totalUp)) }
        item { KeyValue("Downloaded this connection", formatBytes(peer.totalDown)) }
    }
}

@Composable
private fun PrivacyPage(node: NodeState) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(node.stale, node.nodeId) {
        while (true) {
            val next = NoderaCore.obj("telemetry_status")
            status = next
            if (next?.optBoolean("supported") == true && !next.optBoolean("pending")) break
            delay(1_000)
        }
    }
    val supported = status?.optBoolean("supported") == true
    val pending = status?.optBoolean("pending") ?: false
    val localAnswer = status?.let { if (it.isNull("answer")) null else it.optBoolean("answer") }
    val granted = when {
        pending -> false
        supported -> status?.optString("consent") == "granted"
        localAnswer != null -> localAnswer
        else -> false
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val enabled = !busy && status != null && !pending
            ElevatedCard(
                Modifier.fillMaxWidth().toggleable(
                    value = granted,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { next ->
                        scope.launch {
                            busy = true
                            status = NoderaCore.obj("set_telemetry_consent", JSONObject().put("granted", next))
                            busy = false
                        }
                    },
                ),
            ) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.PrivacyTip, null) },
                    headlineContent = { Text("Share anonymous telemetry") },
                    supportingContent = {
                        Text(
                            if (status != null && !supported) "Current worker does not report telemetry support."
                            else "Fixed counts and buckets only. No names, world content, addresses, or free text.",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = granted,
                            enabled = enabled,
                            onCheckedChange = null,
                        )
                    },
                )
            }
        }
        if (pending) item {
            MessageCard(
                "Saved locally",
                "Your ${if (localAnswer == true) "opt-in" else "opt-out"} answer will reach the node as soon as its worker is available.",
            )
        }
        if (pending && localAnswer != null) item {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = NoderaCore.obj(
                            "set_telemetry_consent",
                            JSONObject().put("granted", !localAnswer),
                        )
                        busy = false
                    }
                },
            ) {
                Text(if (localAnswer) "Withdraw pending opt-in" else "Allow instead")
            }
        }
        if (status != null && !supported) item {
            MessageCard(
                "Telemetry unavailable",
                "This worker did not confirm telemetry support. No telemetry is sent, and any saved answer remains local.",
            )
        }
        item {
            MessageCard(
                "Collection never controls the network",
                "Telemetry is opt-in and unavailable telemetry cannot change peer, tracker, storage, or simulation behavior.",
            )
        }
    }
}

@Composable
private fun DiagnosticsPage(node: NodeState) {
    var config by remember { mutableStateOf<JSONObject?>(null) }
    var statuses by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var network by remember { mutableStateOf<JSONObject?>(null) }
    var fault by remember { mutableStateOf("") }
    var logCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        config = NoderaCore.obj("config_status")
        statuses = parseArray(NoderaCore.call("setting_status"))
        network = NoderaCore.obj("network_state")
        fault = decodeJsonString(NoderaCore.call("settings_fault"))
        logCount = runCatching { JSONArray(NoderaCore.call("worker_logs")).length() }.getOrDefault(0)
    }
    val applied = config?.optJSONArray("applied")?.length() ?: 0
    val restart = config?.optJSONArray("restart_required")?.length() ?: 0
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fault.isNotEmpty()) item { MessageCard("Settings file problem", fault, true) }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                KeyValue("Worker link", if (!node.known) "Connecting" else if (node.stale) "Offline" else "Live")
                HorizontalDivider()
                KeyValue("Configuration delivery", if (config?.optBoolean("pushed") == true) "Worker answered" else "Waiting for worker")
                HorizontalDivider()
                KeyValue("Settings in force", "$applied live · $restart need restart")
                HorizontalDivider()
                KeyValue("Network reading", network?.optString("transport")?.replace('_', ' ') ?: "Unknown")
                HorizontalDivider()
                KeyValue("Buffered worker log", "$logCount lines")
            }
        }
        config?.optString("error")?.takeIf { it.isNotEmpty() }?.let { item { MessageCard("Configuration push", it, true) } }
        item { SectionTitle("Setting enforcement", "Reported by the core and currently connected worker") }
        items(statuses) { status ->
            val state = status.optString("state")
            OutlinedCard(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(status.optString("key")) },
                    supportingContent = { Text(status.optString("reason").ifEmpty { "Confirmed by worker" }) },
                    trailingContent = { StatusPill(state.replace('_', ' '), state == "live") },
                )
            }
        }
    }
}

@Composable
private fun AboutPage(onOpen: (SettingsPage) -> Unit) {
    var about by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { about = NoderaCore.obj("about") }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Hub, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(about?.optString("name", "NoderaMC") ?: "NoderaMC", style = MaterialTheme.typography.headlineSmall)
                    Text("Version ${about?.optString("version", "—") ?: "—"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                KeyValue("Project license", about?.optString("licence") ?: "—")
                HorizontalDivider()
                KeyValue("Control protocol", about?.optInt("protocol_version")?.toString() ?: "—")
                HorizontalDivider()
                KeyValue("Settings folder", about?.optString("config_dir") ?: "—")
            }
        }
        item {
            OutlinedButton(onClick = { onOpen(SettingsPage.Licenses) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Description, null)
                Spacer(Modifier.width(8.dp))
                Text("Open-source licenses")
            }
        }
        item {
            TextButton(onClick = {
                about?.optString("repository")?.let { url ->
                    scope.launch { NoderaCore.call("open_external", JSONObject().put("url", url)) }
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Project repository")
            }
        }
    }
}

@Composable
private fun LicensesPage() {
    var query by rememberSaveable { mutableStateOf("") }
    var packages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(Unit) {
        packages = NoderaCore.obj("about")?.optJSONArray("packages")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } ?: emptyList()
    }
    val shown = packages.filter { it.optString("name").contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search ${packages.size} packages") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { "${it.optString("ecosystem")}:${it.optString("name")}" }) { pkg ->
                ListItem(
                    headlineContent = { Text(pkg.optString("name")) },
                    supportingContent = {
                        Text("${pkg.optString("ecosystem").replaceFirstChar(Char::uppercase)} · ${pkg.optString("version")}")
                    },
                    trailingContent = { Text(pkg.optString("licence").ifEmpty { "Unknown" }) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SettingStatusNote(key: String, refresh: Int = 0) {
    var status by remember(key) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(key, refresh) {
        suspend fun reload() {
            status = parseArray(NoderaCore.call("setting_status"))
                .firstOrNull { it.optString("key") == key }
        }
        if (refresh == 0) {
            reload()
        } else {
            // After auto-apply, mark_pending has cleared the prior verdict. Reading immediately
            // would flash "Not enforced" for the ~1 s between the local save and the worker's
            // reply. Wait for the worker to confirm (or a 10 s timeout) before re-reading.
            for (i in 0 until 40) {
                delay(250)
                if (NoderaCore.obj("config_status")?.optBoolean("pushed") == true) break
            }
            reload()
        }
    }
    // Silence is success: a live setting needs no badge. Showing a card for every working control
    // trains people to ignore cards, which is exactly the moment the one card that matters — a real
    // problem — blends in. The desktop shell made the same call (Settings.tsx `badgeFor`).
    status?.let { value ->
        val state = value.optString("state")
        if (state == "live") return@let
        val label = when (state) {
            "restart_required" -> "Restart required"
            "unsupported_by_worker" -> "Worker unsupported"
            "unenforced" -> "Not enforced"
            else -> "Status unknown"
        }
        val explanation = value.optString("reason").ifEmpty { "No explanation was reported." }
        OutlinedCard(Modifier.fillMaxWidth()) {
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Rule, null) },
                headlineContent = { Text(label) },
                supportingContent = { Text(explanation) },
                trailingContent = { StatusPill(label, if (state == "restart_required") null else false) },
            )
        }
    }
}

private fun parseArray(raw: String): List<JSONObject> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}.getOrDefault(emptyList())

private fun jsonStrings(array: JSONArray?): List<String> = if (array == null) emptyList() else
    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }

private fun storePreviewText(index: JSONObject, url: String): String {
    val services = index.optJSONArray("services")
    val rows = if (services == null) emptyList() else (0 until services.length()).mapNotNull { i ->
        val service = services.optJSONObject(i) ?: return@mapNotNull null
        val identity = listOfNotNull(
            service.optString("operator").takeIf(String::isNotEmpty)?.let { "run by $it" },
            service.optString("region").takeIf(String::isNotEmpty),
            service.optString("node_id").takeIf(String::isNotEmpty)?.let { "identified" },
        ).joinToString(" · ")
        val heading = "${service.optString("kind").replaceFirstChar(Char::uppercase)}: " +
            service.optString("name", "Unnamed service") + if (identity.isEmpty()) "" else " ($identity)"
        heading + jsonStrings(service.optJSONArray("endpoints")).joinToString(
            separator = "\n  ",
            prefix = "\n  ",
        )
    }
    return buildString {
        append(index.optString("description"))
        append("\n\n")
        append(if (rows.isEmpty()) "No usable services" else rows.joinToString("\n\n"))
        append("\n\nFrom: ")
        append(url)
        append("\n\nAdd this publisher?")
    }
}

private fun storeRemovalImpact(
    selected: JSONObject,
    stores: List<JSONObject>,
    directTrackers: List<String>,
): String {
    val selectedPairs = storeEndpointPairs(selected)
    val remaining = stores
        .filterNot { it.optString("url") == selected.optString("url") }
        .flatMap(::storeEndpointPairs)
        .toMutableSet()
    remaining += directTrackers.map { "tracker" to it }
    val lost = selectedPairs.filterNot(remaining::contains).distinct()
    val noTrackerRemains = remaining.none { it.first == "tracker" }
    return buildString {
        if (lost.isEmpty()) {
            append("Every endpoint from this store is also provided elsewhere.")
        } else {
            append("These endpoints are unique to this store and will stop being used:\n")
            append(lost.joinToString("\n") { (kind, endpoint) -> "${kind.replaceFirstChar(Char::uppercase)}: $endpoint" })
        }
        if (noTrackerRemains) {
            append("\n\nWarning: this leaves the node with no tracker.")
        }
    }
}

private fun storeEndpointPairs(store: JSONObject): List<Pair<String, String>> {
    val services = store.optJSONArray("services") ?: return emptyList()
    return (0 until services.length()).flatMap { i ->
        val service = services.optJSONObject(i) ?: return@flatMap emptyList()
        val kind = service.optString("kind")
        jsonStrings(service.optJSONArray("endpoints")).map { kind to it }
    }
}

private fun decodeJsonString(raw: String): String = runCatching {
    JSONTokener(raw).nextValue() as? String ?: ""
}.getOrDefault("")
