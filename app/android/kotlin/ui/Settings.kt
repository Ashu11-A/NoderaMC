package dev.nodera.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nodera.app.NoderaCore
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The settings destinations, as one list so the root menu and the router cannot disagree. */
enum class SettingsPage(val title: String, val summary: String) {
    Root("Settings", ""),
    Appearance("Appearance", "Colours follow your wallpaper"),
    Network("Network", "Trackers and how data moves"),
    Storage("Storage", "Where world data is kept"),
    Battery("Battery", "Whether Android may stop this node"),
    Privacy("Privacy", "What, if anything, is reported"),
    About("About", "Version and licences"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(page: SettingsPage, onOpen: (SettingsPage) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    if (page != SettingsPage.Root) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                SettingsPage.Root ->
                    SettingsPage.entries.filter { it != SettingsPage.Root }.forEach { entry ->
                        ListItem(
                            headlineContent = { Text(entry.title) },
                            supportingContent = { Text(entry.summary) },
                            modifier = Modifier.clickableRow { onOpen(entry) },
                        )
                        HorizontalDivider()
                    }

                SettingsPage.Appearance -> AppearancePage()
                SettingsPage.Battery -> BatteryPage()
                SettingsPage.Privacy -> PrivacyPage()
                SettingsPage.About -> AboutPage()
                SettingsPage.Network -> VerbPage("resolved_services", "The endpoints this node will dial")
                SettingsPage.Storage -> VerbPage("settings", "Where world data is kept")
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.fillMaxWidth().clickable(onClick = onClick)

/**
 * Appearance, which on Android 12+ has nothing left to ask.
 *
 * The webview build offered five accent swatches and explained that a WebView cannot read the
 * wallpaper. That paragraph was true and is not any more, so it says the true thing now rather than
 * being deleted — the honest note is the point, not the picker.
 */
@Composable
private fun AppearancePage() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (DYNAMIC_COLOUR_AVAILABLE) "Following your system colours"
            else "Using Nodera's own colours",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (DYNAMIC_COLOUR_AVAILABLE)
                "Android derives this app's palette from your wallpaper, so there is nothing to choose here."
            else
                "This version of Android has no wallpaper palette to read, so Nodera uses its own colour.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatteryPage() {
    val scope = rememberCoroutineScope()
    var policy by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { policy = NoderaCore.obj("battery_policy") }

    val unrestricted = policy?.optBoolean("unrestricted") ?: false
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (unrestricted) "Android will leave this node running"
            else "Android may stop this node in the background",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "A node the system stops is a node other people cannot rely on. Nothing else on this " +
                "screen would ever reveal that, which is why it is here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!unrestricted) {
            Button(onClick = { scope.launch { NoderaCore.call("open_battery_settings") } }) {
                Text("Open battery settings")
            }
            OutlinedButton(onClick = { scope.launch { NoderaCore.call("open_battery_help") } }) {
                Text("Advice for this phone")
            }
        }
    }
}

/**
 * The telemetry question.
 *
 * Three rules are structural here and a change that breaks one is a bug. They are the same three
 * the webview carried, and Material 3 makes the first one easy to break: `AlertDialog` gives
 * `confirmButton` visual priority by convention, so the two answers are two `OutlinedButton`s in a
 * `Row` instead. A dialog engineered to make "yes" easier produces consent worth nothing.
 */
@Composable
private fun PrivacyPage() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { status = NoderaCore.obj("telemetry_status") }

    val granted = status?.optString("consent") == "granted"
    val pending = status?.optBoolean("pending") ?: false

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListItem(
            headlineContent = { Text("Share anonymous telemetry") },
            supportingContent = {
                Text(
                    "Counts and buckets only. It never includes anything that identifies you or " +
                        "your worlds, and you can change your mind at any time.",
                )
            },
            trailingContent = {
                Switch(
                    checked = granted,
                    onCheckedChange = { next ->
                        scope.launch {
                            status = NoderaCore.obj(
                                "set_telemetry_consent",
                                JSONObject().put("granted", next),
                            )
                        }
                    },
                )
            },
        )
        // An answer this app holds but the node has not taken. Not an error — it is kept and
        // re-offered until a worker accepts it — but it must not be rendered as though it were in
        // force. The exit test in `first-run-offline.test.mjs` exists for exactly this line.
        if (pending) {
            Text(
                "Your answer is saved here and will be applied to the node as soon as it is running.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun AboutPage() {
    var about by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { about = NoderaCore.obj("about") }
    Column {
        ListItem(
            headlineContent = { Text(about?.optString("version") ?: "—") },
            supportingContent = { Text("Version") },
        )
        ListItem(
            headlineContent = { Text(about?.optString("licence") ?: "—") },
            supportingContent = { Text("Licence") },
        )
        ListItem(
            headlineContent = { Text(about?.optString("config_dir") ?: "—") },
            supportingContent = { Text("Settings folder") },
        )
    }
}

/** A page that is still a raw read of one verb. Honest about being one rather than faking a form. */
@Composable
private fun VerbPage(verb: String, title: String) {
    var raw by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(verb) { raw = NoderaCore.call(verb) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            raw ?: "…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
