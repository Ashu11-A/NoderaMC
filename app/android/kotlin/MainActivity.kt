package dev.nodera.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.nodera.app.ui.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The Android front end. Native, not a webview.
 *
 * # What changed, and why it had to
 *
 * This used to be `TauriActivity`: the same React bundle as the desktop, with Material 3 imitated in
 * CSS and an accent colour the user picked from five swatches, because a WebView cannot read the
 * system wallpaper. Compose can. `dynamicDarkColorScheme(context)` is the whole reason this file is
 * Kotlin rather than TypeScript.
 *
 * # What is deliberately not here
 *
 * A Play button. There is no Java Minecraft client on Android and Bedrock speaks a different
 * protocol, so the launch lane is compiled out of the core entirely — a Compose screen cannot call
 * it by accident. What the phone *is* is a node that keeps other people's worlds alive, which is
 * what Home says.
 */
class MainActivity : ComponentActivity() {

    private var pendingStoreUrl by mutableStateOf<String?>(null)

    companion object {
        private const val PENDING_STORE_URL = "pending-store-url"
        private var live = WeakReference<MainActivity>(null)

        /** The visible Activity, for the helpers that need one (the SAF picker, Custom Tabs). */
        @JvmStatic
        fun live(): MainActivity? = live.get()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = WeakReference(this)
        enableEdgeToEdge()

        // Before anything draws: the node owns the data directory, and a screen that rendered first
        // would render defaults read from a path that did not exist yet.
        // Failure is logged and survivable: battery checks and worker startup stay unavailable, but
        // the app remains open and reports the node offline.
        runCatching { NoderaCore.start(this) }
            .onFailure { android.util.Log.e("NoderaMC", "could not start the node", it) }
        // Only an activity can start the folder picker for a result.
        NoderaStorage.attach(this)
        pendingStoreUrl = savedInstanceState?.getString(PENDING_STORE_URL)
        offerStoreFrom(intent)

        setContent {
            var themePreference by rememberSaveable { mutableStateOf("system") }
            LaunchedEffect(Unit) {
                themePreference = NoderaCore.obj("settings")
                    ?.optJSONObject("appearance")
                    ?.optString("theme", "system") ?: "system"
            }
            NoderaTheme(themePreference) {
                NoderaApp(
                    themePreference = themePreference,
                    onThemePreference = { themePreference = it },
                    pendingStoreUrl = pendingStoreUrl,
                    onStoreHandled = { pendingStoreUrl = null },
                )
            }
        }
    }

    /**
     * A `nodera://tracker-store?url=…` link.
     *
     * The link **records**; it never adds. A page on any website can send a user here, so the URL is
     * held and shown and waits for a person to say yes — the same shape Mihon uses, and for the same
     * reason: the decision is "I trust this publisher".
     *
     * `onNewIntent` matters as much as `onCreate`: with `launchMode="singleTask"` a link arriving
     * while the app is already open comes through here, and an app that only read `onCreate` would
     * drop every warm-start link silently.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerStoreFrom(intent)
    }

    private fun offerStoreFrom(intent: Intent?) {
        val url = intent?.data?.getQueryParameter("url") ?: return
        // A singleTask Activity retains its Intent across recreation. Consume recognized data now
        // so rotation cannot offer the same untrusted store a second time.
        intent.data = null
        pendingStoreUrl = url
        lifecycleScope.launch { NoderaCore.call("offer_tracker_store", JSONObject().put("url", url)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStoreUrl?.let { outState.putString(PENDING_STORE_URL, it) }
        super.onSaveInstanceState(outState)
    }

    /**
     * Route the system folder picker's answer back to [NoderaStorage].
     *
     * The picker is an activity result, and only an activity can receive one — which is why the
     * storage flow lives partly in Kotlin rather than entirely in Rust. Kept on the older API
     * deliberately: the handoff to the core is the `storage-pick.json` file, and changing the
     * transport and the interface in one step is how the `filesDir` trap was introduced the first
     * time.
     */
    @Deprecated("Activity result APIs; the storage handoff file is the contract, not this call.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == NoderaStorage.REQUEST_CODE) {
            NoderaStorage.onResult(this, resultCode, data)
        }
    }

    override fun onDestroy() {
        NoderaStorage.detach(this)
        super.onDestroy()
    }
}

private enum class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("Home", Icons.Default.Home),
    Worlds("Worlds", Icons.Default.Public),
    Activity("Activity", Icons.Default.Timeline),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * The shell.
 *
 * A navigation **bar** on a phone and a **rail** on anything wider. The width test is explicit
 * rather than delegated to `NavigationSuiteScaffold`, for two reasons: that component is an
 * experimental API in a Compose version this toolchain cannot compile against, and the distinction
 * matters here for a reason worth being able to read — an Android tablet is wide and still has no
 * game client to launch, so "wide" means "more room for the same four destinations", never "show the
 * desktop's screens".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoderaApp(
    themePreference: String,
    onThemePreference: (String) -> Unit,
    pendingStoreUrl: String?,
    onStoreHandled: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    val node by rememberNodeState()
    var onboardingFinished by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var onboardingLoadFailed by rememberSaveable { mutableStateOf(false) }
    var onboardingLoadAttempt by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(onboardingLoadAttempt) {
        val setup = NoderaCore.obj("setup_state")
        onboardingLoadFailed = setup == null
        onboardingFinished = setup?.optBoolean("completed")
    }
    LaunchedEffect(pendingStoreUrl) {
        if (pendingStoreUrl != null) {
            destination = Destination.Settings
            settingsPage = SettingsPage.TrackerStores
        }
    }

    if (onboardingFinished == false) {
        OnboardingScreen { onboardingFinished = true }
        return
    }
    if (onboardingFinished == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (onboardingLoadFailed) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Could not read setup state")
                    Text("The node is not ready yet. Retry without skipping setup.")
                    Button(onClick = { onboardingLoadAttempt += 1 }) { Text("Retry") }
                }
            } else {
                CircularProgressIndicator()
            }
        }
        return
    }

    BackHandler(enabled = destination != Destination.Home) {
        when {
            destination == Destination.Settings && settingsPage == SettingsPage.Licenses ->
                settingsPage = SettingsPage.About
            destination == Destination.Settings && settingsPage != SettingsPage.Root ->
                settingsPage = SettingsPage.Root
            else -> destination = Destination.Home
        }
    }

    val open: (Destination) -> Unit = { entry ->
        destination = entry
        if (entry == Destination.Settings) settingsPage = SettingsPage.Root
    }

    if (destination == Destination.Settings) {
        SettingsShell(
            page = settingsPage,
            onOpen = { settingsPage = it },
            onBack = {
                when (settingsPage) {
                    SettingsPage.Root -> open(Destination.Home)
                    SettingsPage.Licenses -> settingsPage = SettingsPage.About
                    else -> settingsPage = SettingsPage.Root
                }
            },
            destination = destination,
            onDestination = open,
            node = node,
            themePreference = themePreference,
            onThemePreference = onThemePreference,
            offeredStoreUrl = pendingStoreUrl,
            onStoreHandled = onStoreHandled,
        )
        return
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (destination == Destination.Home) "Nodera" else destination.label) },
                actions = {
                    StatusPill(
                        if (!node.known) "Connecting" else if (node.stale || node.fault != null) "Offline" else "Online",
                        if (!node.known) null else !(node.stale || node.fault != null),
                    )
                },
            )
        },
        bottomBar = { if (!wide) BottomBar(destination, open) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) SideRail(destination, open)
            Box(Modifier.weight(1f)) {
                when (destination) {
                    Destination.Home ->
                        HomeScreen(node) {
                            destination = Destination.Settings
                            settingsPage = SettingsPage.Network
                        }
                    Destination.Worlds -> WorldsScreen(node)
                    Destination.Activity -> ActivityScreen()
                    Destination.Settings -> Unit
                }
            }
        }
    }
}

@Composable
private fun BottomBar(active: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar {
        Destination.entries.forEach { entry ->
            NavigationBarItem(
                selected = active == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) },
            )
        }
    }
}

@Composable
private fun SideRail(active: Destination, onSelect: (Destination) -> Unit) {
    NavigationRail {
        Destination.entries.forEach { entry ->
            NavigationRailItem(
                selected = active == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) },
            )
        }
    }
}

/** Settings keeps the same navigation beside it, so leaving it is one tap rather than a back gesture. */
@Composable
private fun SettingsShell(
    page: SettingsPage,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    destination: Destination,
    onDestination: (Destination) -> Unit,
    node: NodeState,
    themePreference: String,
    onThemePreference: (String) -> Unit,
    offeredStoreUrl: String?,
    onStoreHandled: () -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Scaffold(bottomBar = { if (!wide) BottomBar(destination, onDestination) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) SideRail(destination, onDestination)
            Box(Modifier.weight(1f)) {
                SettingsScreen(
                    page = page,
                    onOpen = onOpen,
                    onBack = onBack,
                    node = node,
                    themePreference = themePreference,
                    onThemePreference = onThemePreference,
                    offeredStoreUrl = offeredStoreUrl,
                    onStoreHandled = onStoreHandled,
                )
            }
        }
    }
}
