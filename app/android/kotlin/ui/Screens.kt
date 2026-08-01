package dev.nodera.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nodera.app.NoderaCore
import dev.nodera.app.NoderaEvents
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Home: is this node doing its job, and can anyone find it.
 *
 * The phone is not a game client — there is no Java Minecraft on Android — so it has no Play
 * button. What it *is* is a node that keeps other people's worlds alive while it sits in a pocket,
 * and that is what the hero says. A Play button here would be a button that cannot work.
 */
@Composable
fun HomeScreen(state: NodeState, onFixTrackers: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pausing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.stale) StaleNotice()

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (state.paused) "Sharing is paused" else "Keeping these worlds alive",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    // `known` gates every figure below it. "We have not heard from the worker" and
                    // "the worker says zero" are different answers and must not read the same.
                    if (!state.known) "Waiting for this node to report"
                    else "${state.peers} peers · ${formatBytes(state.sharedBytes)} served",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        pausing = true
                        scope.launch {
                            NoderaCore.call("toggle_pause")
                            pausing = false
                        }
                    },
                    enabled = !pausing,
                ) {
                    Icon(
                        if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.paused) "Resume sharing" else "Pause sharing")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("Peers", if (state.known) "${state.peers}" else "—", Modifier.weight(1f))
            Metric(
                "Trackers",
                if (state.trackers.isEmpty()) "—" else "${state.reachableTrackers}/${state.trackers.size}",
                Modifier.weight(1f),
            )
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text(if (state.nodeId.isEmpty()) "—" else shortId(state.nodeId, 14, 6)) },
                    supportingContent = { Text("Node") },
                )
                ListItem(
                    headlineContent = { Text(state.address.ifEmpty { "—" }) },
                    supportingContent = { Text("Address") },
                )
                ListItem(
                    headlineContent = { Text(state.roles.joinToString(", ").ifEmpty { "—" }) },
                    supportingContent = { Text("Roles") },
                )
            }
        }

        // Not a takeover. A node with peers and traffic but no reachable tracker is not idle — it
        // simply cannot be found by anyone new. Gated on `known`, because "the worker has not told
        // us anything yet" is not "there are no trackers", and a cold start that said so once sent
        // users to add a tracker they already had.
        if (state.known && state.reachableTrackers == 0) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    },
                    headlineContent = {
                        Text(if (state.trackers.isEmpty()) "No trackers configured" else "No tracker is answering")
                    },
                    supportingContent = { Text("New players cannot find this node until one responds") },
                    trailingContent = { TextButton(onClick = onFixTrackers) { Text("Fix") } },
                )
            }
        }
    }
}

/**
 * Worlds: the same three groups the desktop uses, in the same order and for the same reason —
 * where you are, what you run, what you carry for other people.
 *
 * Each row's supporting line is *this device's* contribution rather than the world's size, which is
 * identical on every peer and therefore tells the owner of this phone nothing.
 */
@Composable
fun WorldsScreen(state: NodeState) {
    if (state.worlds.isEmpty()) {
        Empty(
            if (state.known) "No worlds yet" else "Waiting for this node to report",
            if (state.known) "Worlds you play in, run, or help share appear here" else "",
        )
        return
    }

    val playing = state.worlds.filter { it.connected }
    val mine = state.worlds.filter { !it.connected && it.administered }
    val helping = state.worlds.filter { !it.connected && !it.administered }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        if (state.stale) item { StaleNotice() }
        group("You are playing in", playing)
        group("Worlds you run", mine)
        group("Worlds you help share", helping)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.group(title: String, worlds: List<WorldRow>) {
    if (worlds.isEmpty()) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
    items(worlds, key = { it.id }) { world ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(world.name.ifEmpty { shortId(world.id) }) },
                supportingContent = {
                    Text(
                        if (world.pieceCount == 0) "no pieces here yet"
                        else "${formatBytes(world.heldBytes)} held · ${world.piecesHeld}/${world.pieceCount} pieces",
                    )
                },
                trailingContent = {
                    val label = when {
                        world.connected -> "here"
                        world.live -> "live"
                        else -> "idle"
                    }
                    AssistChip(onClick = {}, label = { Text(label) })
                },
            )
        }
    }
}

/** Activity: what the node has been doing, in its own words. */
@Composable
fun ActivityScreen() {
    val lines = remember { mutableStateListOf<String>() }

    // Two sources on purpose. The log is what the worker wrote; the event stream is what it
    // announced. A screen with only the first misses the moment something happened.
    LaunchedEffect(Unit) {
        NoderaCore.call("worker_logs").let { raw ->
            runCatching { org.json.JSONArray(raw) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) lines.add(array.getString(i))
            }
        }
        NoderaEvents.events.collect { (topic, json) ->
            if (topic != "event") return@collect
            val event = runCatching { JSONObject(json) }.getOrNull() ?: return@collect
            lines.add("• ${event.optString("event")}")
            if (lines.size > 200) lines.removeRange(0, lines.size - 200)
        }
    }

    if (lines.isEmpty()) {
        Empty("Nothing yet", "The node writes here as it works")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        reverseLayout = true,
    ) {
        items(lines.asReversed()) { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------------------------ fragments */

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "These figures are the last known picture, not the current one."
 *
 * The same claim the desktop makes, in the same words, for the same reason: a node showing stale
 * numbers without saying so reads as live but idle when the worker has actually stopped.
 */
@Composable
fun StaleNotice() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Showing the last known picture — the worker link is offline.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
