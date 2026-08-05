package dev.nodera.app.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.nodera.app.NoderaCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val SETUP_STEPS = 4

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var telemetry by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var storageInfo by remember { mutableStateOf<JSONObject?>(null) }
    var selectedDir by rememberSaveable { mutableStateOf("") }
    var pickedChoice by remember { mutableStateOf<StorageChoice?>(null) }
    var battery by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reloadStorage() {
        val info = NoderaCore.obj("storage_info")
        if (info == null) {
            error = "Could not read writable storage locations from this device."
            return
        }
        storageInfo = info
        if (selectedDir.isEmpty()) selectedDir = info.optString("current")
    }

    suspend fun reloadBattery() {
        battery = NoderaCore.obj("battery_policy")
    }

    suspend fun openBatteryPage(verb: String) {
        NoderaCore.errorOf(NoderaCore.call(verb))?.let { error = it }
    }

    suspend fun chooseFolder() {
        error = null
        val openError = NoderaCore.errorOf(NoderaCore.call("pick_storage_folder"))
        if (openError != null) {
            error = openError
            return
        }
        picking = true
    }

    fun acceptPickedFolder(result: JSONObject) {
        picking = false
        val reason = result.optString("error")
        val path = result.optString("path")
        when {
            reason.isNotEmpty() -> error = reason
            result.optBoolean("writable") && path.isNotEmpty() -> {
                val choice = StorageChoice(
                    path,
                    result.optString("label", "Chosen folder"),
                    "Selected with Android's folder picker and verified with a test write.",
                    null,
                    true,
                )
                pickedChoice = choice
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
        reloadStorage()
        reloadBattery()
        val recovered = NoderaCore.obj("picked_folder")
        if (recovered?.optBoolean("writable") == true &&
            recovered.optString("path") == selectedDir &&
            storageChoices(storageInfo).none { it.path == selectedDir }
        ) {
            acceptPickedFolder(recovered)
        }
    }
    LaunchedEffect(step) {
        while (step == 2) {
            reloadBattery()
            delay(2_000)
        }
    }

    val choices = (storageChoices(storageInfo) + listOfNotNull(pickedChoice))
        .distinctBy(StorageChoice::path)
    val restricted = battery?.takeIf { it.optString("error").isEmpty() && it.optBoolean("supported") }
        ?.optBoolean("restricted")

    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Box(Modifier.widthIn(max = 680.dp)) {
                    when (step) {
                        0 -> SetupHero(
                            step, SETUP_STEPS, Icons.Default.CloudDone,
                            "Keep worlds available",
                            "This phone becomes a real Nodera peer. It can preserve and serve world data even when Minecraft is closed elsewhere.",
                        )
                        1 -> SetupHero(
                            step, SETUP_STEPS, Icons.Default.Storage,
                            "Choose where worlds live",
                            "Pick a writable location now. Nodera verifies every offered folder instead of accepting a path that the worker cannot use.",
                        )
                        2 -> SetupHero(
                            step, SETUP_STEPS, Icons.Default.BatterySaver,
                            "Stay online in the background",
                            "Android may stop peer transfers after this screen closes. Review the system policy and choose whether to exempt Nodera.",
                        )
                        else -> SetupHero(
                            step, SETUP_STEPS, Icons.Default.PrivacyTip,
                            "Choose what leaves this device",
                            "Telemetry is optional, fixed-schema, and never includes names, addresses, world content, or free text.",
                        )
                    }
                }
            }

            if (step == 0) {
                item { Box(Modifier.widthIn(max = 680.dp)) { MessageCard("No mobile Minecraft required", "The phone contributes storage and connectivity. It does not claim to run the Java game client.") } }
                item { Box(Modifier.widthIn(max = 680.dp)) { MessageCard("You remain in control", "Storage, battery use, trackers, and telemetry stay editable in Settings after setup.") } }
            }

            if (step == 1) item {
                Box(Modifier.widthIn(max = 680.dp)) {
                    StorageLocationChooser(
                        choices = choices,
                        selectedPath = selectedDir,
                        loading = storageInfo == null,
                        enabled = !busy && !picking,
                        onSelected = { selectedDir = it },
                        onBrowse = { scope.launch { chooseFolder() } },
                    )
                }
            }

            if (step == 2) item {
                Box(Modifier.widthIn(max = 680.dp)) {
                    BatteryOptimizationCard(
                        restricted = restricted,
                        manufacturer = battery?.optString("manufacturer").orEmpty(),
                        error = battery?.optString("error").orEmpty(),
                        busy = busy,
                        onOpenSettings = { scope.launch { openBatteryPage("open_battery_settings") } },
                        onRefresh = { scope.launch { reloadBattery() } },
                        onHelp = { scope.launch { openBatteryPage("open_battery_help") } },
                    )
                }
            }
            if (step == 2 && restricted == true) item {
                Box(Modifier.widthIn(max = 680.dp)) {
                    MessageCard("You can continue", "Battery exemption is recommended for a dependable peer, but setup never changes this system setting without you.")
                }
            }

            if (step == 3) item {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConsentChoice("Keep telemetry off", "No diagnostics leave this node.", telemetry == false) { telemetry = false }
                    ConsentChoice("Share anonymous telemetry", "Send fixed counters and coarse buckets to help improve Nodera.", telemetry == true) { telemetry = true }
                }
            }

            error?.let { reason ->
                item { Box(Modifier.widthIn(max = 680.dp)) { MessageCard("Setup needs attention", reason, true) } }
            }

            item {
                Row(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (step > 0) {
                        OutlinedButton(onClick = { error = null; step-- }, enabled = !busy && !picking) { Text("Back") }
                    }
                    Button(
                        onClick = {
                            error = null
                            if (step < SETUP_STEPS - 1) {
                                step++
                            } else scope.launch {
                                busy = true
                                telemetry?.let {
                                    NoderaCore.call("set_telemetry_consent", JSONObject().put("granted", it))
                                }
                                val result = NoderaCore.call("complete_setup", JSONObject().put("worlds_dir", selectedDir))
                                error = NoderaCore.errorOf(result)
                                busy = false
                                if (error == null) onFinished()
                            }
                        },
                        enabled = !busy && !picking && when (step) {
                            1 -> selectedDir.isNotEmpty()
                            3 -> telemetry != null
                            else -> true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy || picking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text(if (step < SETUP_STEPS - 1) "Continue" else "Start Nodera")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentChoice(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        ListItem(
            leadingContent = { RadioButton(selected, onClick = null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(body) },
        )
    }
}
