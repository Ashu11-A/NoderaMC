package dev.nodera.app.ui

import dev.nodera.app.NoderaCore
import dev.nodera.app.NoderaEvents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import org.json.JSONArray
import org.json.JSONObject

/**
 * The node's picture, as the screens read it.
 *
 * Deliberately not a mirror of every field in `api::model::Dashboard`. A phone shows less, and a
 * class that restated forty fields would be forty chances to drift from the Rust that produces
 * them. What is here is what a screen renders.
 *
 * `known` is the load-bearing one. It is false until the worker has said something, and every
 * number below is meaningless until it is true — rendering `0` for "we have not heard yet" is the
 * bug this codebase keeps having, and the phone had it once already.
 */
data class NodeState(
    val known: Boolean = false,
    val stale: Boolean = false,
    val fault: String? = null,
    val nodeId: String = "",
    val address: String = "",
    val roles: List<String> = emptyList(),
    val paused: Boolean = false,
    val peers: Int = 0,
    val sharedBytes: Long = 0,
    val upPerSec: Long? = null,
    val downPerSec: Long? = null,
    val trackers: List<Tracker> = emptyList(),
    val worlds: List<WorldRow> = emptyList(),
    val peerRows: List<PeerRow> = emptyList(),
) {
    val reachableTrackers: Int get() = trackers.count { it.reachable }
}

data class Tracker(val host: String, val port: Int, val reachable: Boolean, val latencyMs: Int?)

data class WorldRow(
    val id: String,
    val name: String,
    val connected: Boolean,
    val administered: Boolean,
    val live: Boolean,
    val piecesHeld: Int,
    val pieceCount: Int,
    val heldBytes: Long,
    val players: Int?,
    val completeness: Int?,
    val seeders: Int,
    val regionsHeld: Int,
)

data class PeerRow(
    val id: String,
    val route: String,
    val path: String,
    val client: String,
    val upPerSec: Long,
    val downPerSec: Long,
    val totalUp: Long,
    val totalDown: Long,
)

private fun JSONObject.longOr(key: String, fallback: Long = 0) = optLong(key, fallback)

private fun JSONObject.nullableLong(key: String): Long? =
    if (isNull(key)) null else optLong(key)

private fun parseWorlds(array: JSONArray?): List<WorldRow> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { i ->
        val w = array.getJSONObject(i)
        val pieces = w.optInt("piece_count")
        val held = w.optInt("pieces_held")
        val total = w.longOr("total_bytes")
        WorldRow(
            id = w.optString("world_id"),
            name = w.optString("name"),
            connected = w.optBoolean("connected"),
            administered = w.optBoolean("administered"),
            live = !w.isNull("game_endpoint"),
            piecesHeld = held,
            pieceCount = pieces,
            // The same derivation `api.ts` uses, so the two front ends report one number: this
            // node's share of the world, not the world's size — which is identical on every peer
            // and therefore says nothing about this device.
            heldBytes = if (pieces > 0) total * held / pieces else 0,
            players = if (w.isNull("players")) null else w.optInt("players"),
            completeness = if (w.isNull("completeness_permille")) null
                else w.optInt("completeness_permille"),
            seeders = w.optInt("seeders"),
            regionsHeld = w.optInt("regions_held"),
        )
    }
}

private fun parsePeers(array: JSONArray?): List<PeerRow> {
    if (array == null) return emptyList()
    return (0 until array.length()).map { i ->
        val peer = array.getJSONObject(i)
        PeerRow(
            id = peer.optString("node_id"),
            route = peer.optString("route"),
            path = peer.optString("path", "unknown"),
            client = peer.optString("client"),
            upPerSec = peer.optLong("up_bytes_per_sec"),
            downPerSec = peer.optLong("down_bytes_per_sec"),
            totalUp = peer.optLong("total_up_bytes"),
            totalDown = peer.optLong("total_down_bytes"),
        )
    }
}

fun parseDashboard(json: JSONObject): NodeState {
    val link = json.optJSONObject("link") ?: JSONObject()
    val node = json.optJSONObject("node") ?: JSONObject()
    val traffic = json.optJSONObject("traffic") ?: JSONObject()
    val counts = json.optJSONObject("counts") ?: JSONObject()
    val discovery = json.optJSONObject("discovery") ?: JSONObject()
    val trackerArray = discovery.optJSONArray("trackers")

    val hasData = link.optBoolean("has_data")
    val status = link.optString("status")
    val rolesArray = node.optJSONArray("roles")

    return NodeState(
        known = hasData,
        // The same predicate as `isStale` in `api.ts`: a picture exists, but the link is not
        // carrying it any more. Both halves matter — without `has_data` a cold start would claim
        // its empty screen was stale.
        stale = hasData && (status == "offline" || status == "connecting"),
        fault = link.optString("last_error").ifEmpty { null }?.takeIf { status == "offline" },
        nodeId = node.optString("node_id"),
        address = node.optString("self_route"),
        roles = (0 until (rolesArray?.length() ?: 0)).map { rolesArray!!.getString(it) },
        paused = node.optBoolean("transfers_paused"),
        peers = counts.optInt("peers"),
        sharedBytes = counts.longOr("shared_bytes"),
        upPerSec = traffic.nullableLong("up_bytes_per_sec"),
        downPerSec = traffic.nullableLong("down_bytes_per_sec"),
        trackers = (0 until (trackerArray?.length() ?: 0)).map { i ->
            val t = trackerArray!!.getJSONObject(i)
            Tracker(
                host = t.optString("host"),
                port = t.optInt("port"),
                reachable = t.optBoolean("reachable"),
                latencyMs = if (t.isNull("latency_ms")) null else t.optInt("latency_ms"),
            )
        },
        worlds = parseWorlds(json.optJSONArray("worlds")),
        peerRows = parsePeers(json.optJSONArray("peers")),
    )
}

/**
 * The node, as a piece of Compose state.
 *
 * Seeded with one `dashboard` call so a screen opened before the first push renders immediately,
 * then fed by the pushed stream. Both halves are needed: the seed alone would go stale, the stream
 * alone would leave a cold start blank for up to a second.
 */
@Composable
fun rememberNodeState(): State<NodeState> = produceState(NodeState()) {
    NoderaCore.obj("dashboard")?.let { value = parseDashboard(it) }
    NoderaEvents.events.collect { (topic, json) ->
        if (topic == "dashboard") {
            runCatching { parseDashboard(JSONObject(json)) }.getOrNull()?.let { value = it }
        }
    }
}

/** Bytes, in the same words the desktop uses. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return if (unit == 0) "${value.toInt()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}

fun shortId(id: String, head: Int = 10, tail: Int = 6): String =
    if (id.length <= head + tail + 1) id else "${id.take(head)}…${id.takeLast(tail)}"
