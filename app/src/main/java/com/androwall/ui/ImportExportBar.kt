package com.androwall.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androwall.PhoenixButton
import com.androwall.data.AppDao
import com.androwall.data.FilterMode
import com.androwall.data.FilterRule
import com.androwall.data.RulesIO
import com.androwall.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AndroWall/IO"

/**
 * A compact row that offers Import / Export buttons for a rule scope.
 *
 * - [scope] must be either `"global"` or `"app:<packageName>"`.
 * - [rulesProvider] should return the current live list of rules for export.
 * - [filterMode] is exported into the JSON header when non-null (global scope only).
 * - [onChanged] is invoked whenever an import modified the DB (caller can mark
 *   pending changes / restart VPN).
 *
 * Import mode is decided via a confirmation dialog:
 *   - **Merge**: keeps existing rules, inserts new ones (skipping duplicates by
 *     pattern+matchType+action).
 *   - **Replace**: deletes every existing rule in this scope, then inserts the
 *     imported ones.
 *
 * Success/error feedback is written to logcat (TAG = `AndroWall/IO`) instead of
 * an on-screen toast, per the app's UI direction.
 */
@Composable
fun ImportExportBar(
    scope: String,
    rulesProvider: () -> List<FilterRule>,
    filterMode: FilterMode?,
    dao: AppDao,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val currentScope = rememberUpdatedState(scope)
    val coroutineScope = rememberCoroutineScope()

    // Pending parsed import awaiting user's merge/replace choice
    var pendingImport by remember { mutableStateOf<RulesIO.RulesImport?>(null) }
    // Set to a non-null message when an invalid/informative situation occurs
    var infoMessage   by remember { mutableStateOf<String?>(null) }

    // ── File pickers ────────────────────────────────────────────────────────
    val createFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RulesIO.MIME_TYPE)
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val rules = rulesProvider()
        if (rules.isEmpty()) {
            Log.i(TAG, "Export skipped: no rules in scope ${currentScope.value}")
            infoMessage = "No rules to export"
            return@rememberLauncherForActivityResult
        }
        val scopeValue = currentScope.value
        val json = RulesIO.serialize(rules, scopeValue, filterMode)
        coroutineScope.launch {
            val ok = writeUri(context, uri, json)
            if (ok) {
                Log.i(TAG, "Exported ${rules.size} rule(s) from $scopeValue -> $uri")
            } else {
                Log.e(TAG, "Export failed: could not write to $uri")
                infoMessage = "Failed to write file"
            }
        }
    }

    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { readUri(context, uri) }
                val parsed = RulesIO.parse(json)
                if (parsed.rules.isEmpty()) {
                    Log.w(TAG, "Import: file $uri contained no rules")
                    infoMessage = "File contains no rules"
                } else {
                    pendingImport = parsed
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Import rejected: ${e.message}")
                infoMessage = e.message ?: "Invalid file"
            } catch (e: Exception) {
                Log.e(TAG, "Import read failed: ${e.message}", e)
                infoMessage = "Read failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    // ── Merge/Replace dialog ────────────────────────────────────────────────
    pendingImport?.let { import ->
        val count = import.rules.size
        val c = LocalAppColors.current
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            containerColor   = c.surface,
            tonalElevation   = 0.dp,
            shape            = PhoenixShapeLarge,
            title = { Text("Import $count rule${plural(count)}", fontWeight = FontWeight.Bold, color = PhoenixFlame) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Importing into ${if (currentScope.value == "global") "Global" else "this app"} scope.",
                        fontSize = 12.sp, color = c.textSecondary
                    )
                    if (import.filterMode != null) {
                        Text(
                            "File filter mode: ${import.filterMode.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = PhoenixFlameDim
                        )
                    }
                    Text(
                        "Choose how to import:",
                        fontSize = 12.sp, color = c.textSecondary
                    )
                    Text("• Merge — add new, keep existing", fontSize = 11.sp, color = c.textSecondary.copy(0.85f))
                    Text("• Replace — delete current, then add", fontSize = 11.sp, color = c.textSecondary.copy(0.85f))
                }
            },
            confirmButton = {
                PhoenixButton("Merge", c.green, onClick = {
                    val imp = pendingImport ?: return@PhoenixButton
                    pendingImport = null
                    coroutineScope.launch {
                        val n = applyImport(dao, currentScope.value, imp, replace = false)
                        Log.i(TAG, "Imported (merge) $n rule(s) into ${currentScope.value}")
                        onChanged()
                    }
                })
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoenixButton("Replace", c.red, onClick = {
                        val imp = pendingImport ?: return@PhoenixButton
                        pendingImport = null
                        coroutineScope.launch {
                            val n = applyImport(dao, currentScope.value, imp, replace = true)
                            Log.i(TAG, "Imported (replace) $n rule(s) into ${currentScope.value}")
                            onChanged()
                        }
                    })
                    PhoenixButton("Cancel", c.textSecondary, onClick = { pendingImport = null })
                }
            }
        )
    }

    // ── UI ──────────────────────────────────────────────────────────────────
    val c = LocalAppColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(c.card, PhoenixShapeSmall)
            .border(0.5.dp, c.borderMid, PhoenixShapeSmall)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.UnfoldMore, null,
                modifier = Modifier.size(14.dp),
                tint = PhoenixFlame.copy(0.7f)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Backup & Restore", fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp, color = c.textPrimary)
                Text(
                    infoMessage ?: "Export rules to a file, or import from one",
                    fontSize = 10.sp,
                    color = if (infoMessage != null) c.red else c.textSecondary.copy(0.7f)
                )
            }
            Spacer(Modifier.width(8.dp))
            ImportExportButton(
                label = "Export",
                icon  = Icons.Default.Upload,
                color = PhoenixFlame,
                onClick = {
                    infoMessage = null
                    val suggestion = suggestedFileName(currentScope.value)
                    createFileLauncher.launch(suggestion)
                }
            )
            Spacer(Modifier.width(6.dp))
            ImportExportButton(
                label = "Import",
                icon  = Icons.Default.Download,
                color = c.green,
                onClick = {
                    infoMessage = null
                    openFileLauncher.launch(arrayOf(RulesIO.MIME_TYPE, "application/octet-stream"))
                }
            )
        }
    }
}

@Composable
private fun ImportExportButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val c = LocalAppColors.current
    Box(
        Modifier
            .background(color.copy(0.1f), PhoenixShapeSmall)
            .border(0.5.dp, color.copy(0.55f), PhoenixShapeSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = color)
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun plural(n: Int): String = if (n != 1) "s" else ""

private fun suggestedFileName(scope: String): String {
    val safe = if (scope == "global") "global" else scope.removePrefix("app:").replace(Regex("[^A-Za-z0-9._-]"), "_")
    val stamp = System.currentTimeMillis()
    return "androwall-${safe}-rules-$stamp.json"
}

/**
 * Writes the rules JSON to the SAF-provided [uri]. Returns true on success.
 */
private suspend fun writeUri(context: Context, uri: Uri, content: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

/**
 * Reads the full text from the SAF-provided [uri].
 */
private fun readUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.use { input ->
        input.bufferedReader(Charsets.UTF_8).readText()
    } ?: throw IllegalArgumentException("Could not open file for reading")
}

/**
 * Persists the parsed import. Returns the number of rules actually inserted.
 *
 * - When [replace] is true, the existing rules in this scope are deleted first.
 * - When [replace] is false (merge), rules whose (pattern, matchType, action)
 *   triple already exists in the current scope are skipped.
 */
private suspend fun applyImport(
    dao: AppDao,
    scope: String,
    import: RulesIO.RulesImport,
    replace: Boolean
): Int {
    val packageName: String? = if (scope == "global") null else scope.removePrefix("app:")

    return withContext(Dispatchers.IO) {
        if (replace) {
            if (packageName == null) dao.deleteAllGlobalRules()
            else dao.deleteAllRulesForApp(packageName)
        }

        // For merge mode, fetch existing (pattern, matchType, action) keys to dedupe
        val existingKeys: Set<Triple<String, String, String>> =
            if (replace) emptySet()
            else {
                val current = if (packageName == null) dao.getGlobalRules().first()
                else dao.getAppRules(packageName).first()
                current.map { Triple(it.pattern, it.matchType.name, it.action.name) }.toSet()
            }

        var inserted = 0
        import.rules.forEach { rule ->
            val key = Triple(rule.pattern, rule.matchType.name, rule.action.name)
            if (key in existingKeys) return@forEach
            dao.insertRule(rule.copy(packageName = packageName, id = 0))
            inserted++
        }
        inserted
    }
}
