package com.armadasx3.diagnostics

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Export session diagnostics": one row in Settings > App, next to Backup.
 *
 * Tapping it lists what the zip will contain before anything is written; the user can untick the
 * optional files, describe the scenario, and then picks where the zip goes through the Storage
 * Access Framework. Nothing is uploaded. When the previous run died mid-game the row says so and
 * that session is the one offered first.
 *
 * [row] draws the row in the host screen's own style (AppTab's BackupActionRow), so this file
 * owns the behaviour and the host keeps its look.
 */
@Composable
fun DiagnosticsExportRow(
    row: @Composable (labelKey: String, descKey: String, status: String, busy: Boolean, onClick: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf<List<Pair<SessionDiagnostics.Target, SessionJournal.Record>>>(emptyList()) }
    var dialogOpen by remember { mutableStateOf(false) }
    var pendingPlan by remember { mutableStateOf<SessionDiagnostics.Plan?>(null) }
    var pendingSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingNotes by remember { mutableStateOf(UserNotes()) }

    suspend fun refresh() {
        targets = withContext(Dispatchers.IO) { SessionDiagnostics.availableTargets() }
    }
    LaunchedEffect(Unit) { refresh() }

    val interrupted = targets.firstOrNull { it.first == SessionDiagnostics.Target.INTERRUPTED }?.second
    val shownStatus = status.ifEmpty {
        interrupted?.let { I18n.get("diag.interruptedFound").replace("%s", describe(it)) } ?: ""
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val plan = pendingPlan
        if (uri == null || plan == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        status = I18n.get("diag.working")
        val appContext = context.applicationContext
        val selection = pendingSelection
        val notes = pendingNotes
        // Not the composition's scope: leaving the screen must not orphan a half-written zip.
        SessionDiagnostics.exportScope.launch {
            val r = runCatching { SessionDiagnostics.exportTo(appContext, uri, plan, selection, notes) }
            withContext(Dispatchers.Main) {
                busy = false
                status = r.fold(
                    onSuccess = { I18n.get("diag.exported").replace("%s", it.files.size.toString()) },
                    onFailure = { I18n.get("diag.failed").replace("%s", it.message ?: it.javaClass.simpleName) },
                )
                Toast.makeText(appContext, status, Toast.LENGTH_LONG).show()
            }
        }
    }

    row("diag.export", "diag.export.desc", shownStatus, busy) {
        if (!busy) {
            scope.launch {
                refresh()
                dialogOpen = true
            }
        }
    }

    if (dialogOpen) {
        ExportDialog(
            targets = targets,
            onDismiss = { dialogOpen = false },
            onDismissInterrupted = {
                scope.launch {
                    withContext(Dispatchers.IO) { SessionDiagnostics.dismissInterrupted() }
                    status = ""
                    refresh()
                }
            },
            onExport = { plan, selection, notes ->
                dialogOpen = false
                busy = true
                pendingPlan = plan
                pendingSelection = selection
                pendingNotes = notes
                saver.launch(SessionDiagnostics.suggestedName(plan))
            },
        )
    }
}

@Composable
private fun ExportDialog(
    targets: List<Pair<SessionDiagnostics.Target, SessionJournal.Record>>,
    onDismiss: () -> Unit,
    onDismissInterrupted: () -> Unit,
    onExport: (SessionDiagnostics.Plan, Set<String>, UserNotes) -> Unit,
) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(targets.firstOrNull()) }
    var plan by remember { mutableStateOf<SessionDiagnostics.Plan?>(null) }
    val deselected = remember { mutableStateListOf<String>() }
    var scenario by remember { mutableStateOf("") }
    var observations by remember { mutableStateOf("") }

    LaunchedEffect(chosen) {
        val target = chosen?.first ?: SessionDiagnostics.Target.NONE
        plan = withContext(Dispatchers.IO) { SessionDiagnostics.plan(context, target, chosen?.second) }
        deselected.clear()
        plan?.entries?.filter { it.optional && !it.selectedByDefault }?.forEach { deselected += it.zipPath }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str("diag.dialog.title")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (targets.isEmpty()) {
                    Text(str("diag.noSession"), style = MaterialTheme.typography.bodyMedium)
                }
                targets.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = chosen == t, onClick = { chosen = t })
                        Text(
                            I18n.get("diag.target.${t.first.name.lowercase()}").replace("%s", describe(t.second)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Text(str("diag.contents"), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, onCheckedChange = null, enabled = false)
                    Text("report.json — " + str("diag.always"), style = MaterialTheme.typography.bodySmall)
                }
                plan?.entries?.forEach { e ->
                    val on = !e.optional || e.zipPath !in deselected
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = on,
                            enabled = e.optional,
                            onCheckedChange = { checked -> if (checked) deselected.remove(e.zipPath) else deselected.add(e.zipPath) },
                        )
                        Text("${e.zipPath} (${formatBytes(e.source.size)})", style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = scenario,
                    onValueChange = { scenario = it.take(500) },
                    label = { Text(str("diag.scenario")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = observations,
                    onValueChange = { observations = it.take(2000) },
                    label = { Text(str("diag.observations")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Text(str("diag.privacy"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (chosen?.first == SessionDiagnostics.Target.INTERRUPTED) {
                    TextButton(onClick = {
                        onDismissInterrupted()
                        onDismiss()
                    }) { Text(str("diag.dismissInterrupted")) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = plan != null,
                onClick = {
                    val p = plan ?: return@TextButton
                    val selection = p.entries.map { it.zipPath }.filter { it !in deselected }.toSet()
                    onExport(p, selection, UserNotes(scenario, observations))
                },
            ) { Text(str("diag.choose")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str("action.cancel")) } },
    )
}

private fun describe(r: SessionJournal.Record): String {
    val game = r.state.game?.let { g -> listOfNotNull(g.title, g.serial).joinToString(" · ") }?.ifBlank { null } ?: "—"
    return "$game, ${r.state.startedAtUtc.take(16).replace('T', ' ')} UTC"
}

private fun formatBytes(n: Long): String = when {
    n >= 1L shl 20 -> "%.1f MB".format(n / (1024.0 * 1024.0))
    n >= 1L shl 10 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}
