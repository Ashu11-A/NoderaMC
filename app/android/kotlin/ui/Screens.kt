package dev.nodera.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nodera.app.NoderaCore
import dev.nodera.app.NoderaEvents
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun HomeScreen(state: NodeState, onFixTrackers: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pausing by remember { mutableStateOf(false) }
    var pauseReason by remember { mutableStateOf("") }

    LaunchedEffect(state.paused) {
        pauseReason = if (state.paused) {
            NoderaCore.obj("pause_reason")?.optString("reason")?.ifEmpty { "" } ?: ""
        } else ""
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(280.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.stale) item(span = { GridItemSpan(maxLineSpan) }) { StaleNotice() }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ElevatedCard(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (state.paused) Icons.Default.PauseCircle else Icons.Default.CloudDone,
                        null,
                        Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (state.paused) "Sharing is paused" else "Keeping the network available",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (state.paused && pauseReason.isNotEmpty()) {
                        Text(
                            pauseReason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        if (!state.known) "Waiting for this node to report"
                        else "${state.peers} connected peers · ${formatBytes(state.sharedBytes)} held for others",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Button(
                        onClick = {
                            pausing = true
                            scope.launch {
                                when {
                                    !state.paused -> NoderaCore.call("toggle_pause")
                                    pauseReason.contains("network") || pauseReason.contains("connection") -> {
                                        val net = NoderaCore.obj("settings")?.optJSONObject("network")
                                        NoderaCore.call(
                                            "set_network_policy",
                                            org.json.JSONObject()
                                                .put("transfer_network", "any")
                                                .put("max_connections", net?.optLong("max_connections", 200) ?: 200),
                                        )
                                    }
                                    else -> NoderaCore.call("toggle_pause")
                                }
                                pausing = false
                            }
                        },
                        enabled = !pausing,
                    ) {
                        Icon(if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.paused) "Resume sharing" else "Pause sharing")
                    }
                    if (state.paused && pauseReason != "paused by you") {
                        Text(
                            "Resume may not take effect while the pause reason is active. Check Network settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        item { MetricCard("Peers", if (state.known) "${state.peers}" else "—", Icons.Default.Group) }
        item {
            MetricCard(
                "Trackers",
                if (state.trackers.isEmpty()) "—" else "${state.reachableTrackers}/${state.trackers.size}",
                Icons.Default.TravelExplore,
            )
        }
        item { MetricCard("Upload", rate(state.upPerSec), Icons.Default.Upload) }
        item { MetricCard("Download", rate(state.downPerSec), Icons.Default.Download) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = { Icon(Icons.Default.Fingerprint, null) },
                    headlineContent = { Text(state.nodeId.ifEmpty { "Not reported" }.let { shortId(it, 14, 6) }) },
                    supportingContent = { Text("Node identity") },
                )
                HorizontalDivider()
                ListItem(
                    leadingContent = { Icon(Icons.Default.Router, null) },
                    headlineContent = { Text(state.address.ifEmpty { "Not reported" }) },
                    supportingContent = { Text(state.roles.joinToString(" · ").ifEmpty { "No roles reported" }) },
                )
            }
        }
        if (state.known && state.reachableTrackers == 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        leadingContent = {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        },
                        headlineContent = {
                            Text(if (state.trackers.isEmpty()) "No trackers configured" else "No tracker is answering")
                        },
                        supportingContent = { Text("New peers cannot find this node until one responds") },
                        trailingContent = { TextButton(onClick = onFixTrackers) { Text("Review") } },
                    )
                }
            }
        }
    }
}

private fun rate(bytes: Long?): String = bytes?.let { "${formatBytes(it)}/s" } ?: "—"

@Composable
private fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.large,
            ) { Icon(icon, null, Modifier.padding(10.dp)) }
            Column {
                Text(value, style = MaterialTheme.typography.headlineSmall)
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldsScreen(state: NodeState) {
    var selected by remember { mutableStateOf<WorldRow?>(null) }
    if (state.worlds.isEmpty()) {
        EmptyState(
            Icons.Default.Public,
            if (state.known) "No worlds yet" else "Waiting for this node",
            if (state.known) "Worlds this node hosts or helps preserve appear here" else "",
        )
        return
    }
    val groups = listOf(
        "You are here" to state.worlds.filter { it.connected },
        "Worlds you run" to state.worlds.filter { !it.connected && it.administered },
        "Worlds you help preserve" to state.worlds.filter { !it.connected && !it.administered },
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.stale) item(span = { GridItemSpan(maxLineSpan) }) { StaleNotice() }
        groups.forEach { (title, worlds) ->
            if (worlds.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(title) }
                gridItems(worlds, key = { it.id }) { world -> WorldCard(world) { selected = world } }
            }
        }
    }
    selected?.let { world ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            WorldDetails(world)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WorldCard(world: WorldRow, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(world.name.ifEmpty { shortId(world.id) }, style = MaterialTheme.typography.titleMedium)
                    Text(shortId(world.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(if (world.connected) "Here" else if (world.live) "Live" else "Stored", true)
            }
            world.completeness?.let {
                LinearProgressIndicator(progress = { it / 1000f }, Modifier.fillMaxWidth())
            }
            Text(
                if (world.pieceCount == 0) "No content pieces held yet"
                else "${formatBytes(world.heldBytes)} · ${world.piecesHeld}/${world.pieceCount} pieces",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorldDetails(world: WorldRow) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(world.name.ifEmpty { "World details" }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        KeyValue("World ID", world.id)
        KeyValue("Players", world.players?.toString() ?: "Not reported")
        KeyValue("Seeders", world.seeders.toString())
        KeyValue("Regions handled here", world.regionsHeld.toString())
        KeyValue("Content held here", formatBytes(world.heldBytes))
    }
}

@Composable
fun ActivityScreen() {
    val lines = remember { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var eventsOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        NoderaCore.call("worker_logs").let { raw ->
            runCatching { org.json.JSONArray(raw) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) lines.add(array.getString(i))
            }
        }
        NoderaEvents.events.collect { (topic, json) ->
            if (topic != "event") return@collect
            val event = runCatching { JSONObject(json) }.getOrNull() ?: return@collect
            lines.add("EVENT  ${event.optString("event", "Node event")}")
            if (lines.size > 500) lines.removeRange(0, lines.size - 500)
        }
    }
    val filtered = lines.filter {
        (!eventsOnly || it.startsWith("EVENT")) && it.contains(query, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, "Clear search")
                }
            },
            label = { Text("Search activity") },
            singleLine = true,
        )
        FilterChip(
            selected = eventsOnly,
            onClick = { eventsOnly = !eventsOnly },
            label = { Text("Node events only") },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (filtered.isEmpty()) {
            EmptyState(Icons.Default.Timeline, "Nothing to show", "Node events and worker activity appear here")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered.asReversed()) { line ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                if (line.startsWith("EVENT")) Icons.Default.Bolt else Icons.Default.Terminal,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                line.removePrefix("EVENT  "),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = if (line.startsWith("EVENT")) null else FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StaleNotice() {
    MessageCard("Last known state", "Worker link is offline. Values below may be out of date.", error = true)
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
