package dev.nodera.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun SectionTitle(title: String, summary: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (summary.isNotEmpty()) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatusPill(label: String, good: Boolean? = null) {
    val icon = when (good) {
        true -> Icons.Default.CheckCircle
        false -> Icons.Default.Error
        null -> Icons.Default.Info
    }
    val container = when (good) {
        true -> MaterialTheme.colorScheme.secondaryContainer
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (good) {
        true -> MaterialTheme.colorScheme.onSecondaryContainer
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.semantics { contentDescription = "Status: $label" },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun MessageCard(title: String, body: String, error: Boolean = false) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(if (error) Icons.Default.Error else Icons.Default.Info, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(value.ifEmpty { "Not reported" }) },
        supportingContent = { Text(label) },
        modifier = modifier,
    )
}

data class StorageChoice(
    val path: String,
    val label: String,
    val detail: String,
    val freeBytes: Long?,
    val writable: Boolean,
)

fun storageChoices(info: JSONObject?): List<StorageChoice> {
    val options = info?.optJSONArray("options") ?: return emptyList()
    return (0 until options.length()).mapNotNull { index ->
        val option = options.optJSONObject(index) ?: return@mapNotNull null
        StorageChoice(
            path = option.optString("path"),
            label = option.optString("label", "Storage"),
            detail = option.optString("detail"),
            freeBytes = if (option.isNull("free_bytes")) null else option.optLong("free_bytes"),
            writable = option.optBoolean("writable"),
        )
    }
}

@Composable
fun SetupHero(step: Int, total: Int, icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SETUP ${step + 1} OF $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("${((step + 1) * 100) / total}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(progress = { (step + 1f) / total }, Modifier.fillMaxWidth())
        ElevatedCard(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                    Icon(icon, null, Modifier.padding(16.dp).size(34.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun StorageLocationChooser(
    choices: List<StorageChoice>,
    selectedPath: String,
    loading: Boolean,
    enabled: Boolean = true,
    onSelected: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        choices.forEach { choice ->
            val selected = choice.path == selectedPath
            OutlinedCard(
                Modifier.fillMaxWidth().selectable(
                    selected = selected,
                    enabled = enabled && choice.writable,
                    role = Role.RadioButton,
                    onClick = { onSelected(choice.path) },
                ),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                ListItem(
                    leadingContent = { RadioButton(selected, onClick = null, enabled = enabled && choice.writable) },
                    headlineContent = { Text(choice.label) },
                    supportingContent = {
                        Column {
                            Text(choice.detail)
                            Text(
                                choice.path,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            choice.freeBytes?.let { Text("${formatBytes(it)} free", style = MaterialTheme.typography.labelMedium) }
                        }
                    },
                    trailingContent = {
                        StatusPill(if (!choice.writable) "Unavailable" else if (selected) "Selected" else "Available", if (!choice.writable) false else if (selected) true else null)
                    },
                )
            }
        }
        OutlinedButton(onClick = onBrowse, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Choose another folder")
        }
    }
}

@Composable
fun BatteryOptimizationCard(
    restricted: Boolean?,
    manufacturer: String,
    error: String,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onHelp: (() -> Unit)? = null,
) {
    val headline = when {
        error.isNotEmpty() -> "Battery status unavailable"
        restricted == true -> "Android may stop this node"
        restricted == false -> "Background access is unrestricted"
        else -> "Checking background access"
    }
    val detail = when {
        error.isNotEmpty() -> error
        restricted == true -> "Disable battery optimisation so world transfers can continue when this screen is closed."
        restricted == false -> "This device allows Nodera to remain available in the background."
        else -> "Reading Android's policy for this app."
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryAlert, null, Modifier.size(32.dp), tint = if (restricted == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.titleMedium)
                    if (manufacturer.isNotEmpty()) Text(manufacturer, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(if (restricted == false) "Ready" else if (restricted == true) "Restricted" else "Unknown", if (restricted == false) true else if (restricted == true) false else null)
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSettings, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (restricted == true) "Allow background" else "Battery settings")
                }
                OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Check again") }
            }
            onHelp?.let { help ->
                TextButton(onClick = help, enabled = !busy) { Text("Device-specific help") }
            }
        }
    }
}
