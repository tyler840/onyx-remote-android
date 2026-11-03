package com.gsb.onyxremote.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsb.onyxremote.BuildConfig
import com.gsb.onyxremote.RemoteViewModel
import com.gsb.onyxremote.RemoteViewModel.Screen
import com.gsb.onyxremote.model.AppConfig
import com.gsb.onyxremote.model.Orientation

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: RemoteViewModel) {
    val saved by vm.config.collectAsState()
    val status by vm.status.collectAsState()
    val link by vm.client.link.collectAsState()
    val problem by vm.client.problem.collectAsState()
    var d by remember { mutableStateOf(saved) }
    val dirty = d.copy(pages = saved.pages) != saved
    var askDiscard by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Pages may change underneath (fader levels), so only the settings fields come from the draft.
    fun commit() = vm.updateConfig { cur -> d.copy(pages = cur.pages) }
    fun leave() = if (dirty) askDiscard = true else vm.show(Screen.PANEL)

    BackHandler { leave() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(vm.exportJson().toByteArray()) }
        }.isSuccess
        vm.setStatus(if (ok) "Layout exported" else "Export failed", ok)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImport = runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        }.getOrElse { vm.setStatus("Couldn't read that file", false); null }
    }

    Column(Modifier.fillMaxSize().background(PanelBg)) {
        Row(Modifier.fillMaxWidth().background(BarBg).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", fontSize = 20.sp, modifier = Modifier.weight(1f))
            LinkPill(link, standby = saved.idleDisconnectSec > 0 && problem == null, modifier = Modifier.padding(end = 12.dp))
            Button(onClick = { commit() }, enabled = dirty) { Text("Save") }
        }
        status?.let {
            Text(it.text, Modifier.fillMaxWidth().background(BarBg).padding(horizontal = 16.dp, vertical = 6.dp),
                color = when (it.ok) { true -> OkGreen; false -> FailRed; null -> MaterialTheme.colorScheme.secondary }, fontSize = 14.sp)
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
                Section("Onyx connection") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(d.host, { d = d.copy(host = it.trim()) }, label = { Text("Onyx PC address") },
                            singleLine = true, placeholder = { Text("192.168.1.50") }, modifier = Modifier.width(260.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        IntField("Telnet port", d.port, 1..65535, { d = d.copy(port = it) })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField("Heartbeat (s)", d.heartbeatSec, 0..3600, { d = d.copy(heartbeatSec = it) }, Modifier.width(150.dp))
                        OutlinedTextField(d.probeCommand, { d = d.copy(probeCommand = it) }, label = { Text("Heartbeat command") },
                            singleLine = true, modifier = Modifier.width(220.dp))
                        IntField("Disconnect when idle (s)", d.idleDisconnectSec, 0..3600, { d = d.copy(idleDisconnectSec = it) }, Modifier.width(220.dp))
                    }
                    Hint("Heartbeat: while connected, sends the heartbeat command this often to prove Onyx is alive (0 = off). " +
                        "The default, ${AppConfig.DEFAULT_PROBE}, asks for a cuelist that doesn't exist, so it changes nothing.")
                    Hint("Disconnect when idle: 0 keeps a telnet session open all the time (fastest, live status). " +
                        "A number closes it after that many quiet seconds and reconnects on the next press.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { if (dirty) commit(); vm.testConnection() }) { Text("Test connection") }
                        OutlinedButton(onClick = { if (dirty) commit(); vm.show(Screen.CONSOLE) }) { Text("Telnet console") }
                    }
                }

                Section("Panel") {
                    OutlinedTextField(d.title, { d = d.copy(title = it) }, label = { Text("Panel title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SwitchRow("Keep screen on", d.keepScreenOn) { d = d.copy(keepScreenOn = it) }
                    SwitchRow("Hide Android status and navigation bars", d.fullscreen) { d = d.copy(fullscreen = it) }
                    Text("Orientation", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Orientation.entries.forEach { o ->
                            FilterChip(selected = d.orientation == o, onClick = { d = d.copy(orientation = o) }, label = { Text(o.label) })
                        }
                    }
                    OutlinedTextField(
                        d.pin, { v -> d = d.copy(pin = v.filter { it.isDigit() }.take(12)) },
                        label = { Text("Edit PIN") }, singleLine = true, modifier = Modifier.width(220.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        supportingText = { Text(if (d.pin.isEmpty()) "No PIN - anyone can edit" else "Needed to enter edit mode") },
                    )
                }

                Section("Shared layout") {
                    Hint("Point every tablet at one layout file on an http server and they all pick up changes. " +
                        "Only the title and pages are taken; each tablet keeps its own connection, PIN and display settings.")
                    OutlinedTextField(d.syncUrl, { d = d.copy(syncUrl = it.trim()) }, label = { Text("Layout URL") },
                        placeholder = { Text("http://192.168.1.10/onyx-remote.json") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    SwitchRow("Sync when the app starts", d.syncOnStart) { d = d.copy(syncOnStart = it) }
                    OutlinedButton(enabled = d.syncUrl.isNotBlank(), onClick = { if (dirty) commit(); vm.syncFromUrl(d.syncUrl) }) { Text("Sync now") }
                }

                Section("Backup and transfer") {
                    Hint("Export writes everything (connection, PIN, display and pages) to a .json file you can copy to another tablet.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { exportLauncher.launch("onyx-remote-layout.json") }) { Text("Export to file…") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                            Text("Import from file…")
                        }
                    }
                }

                Section("Tablet") {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open Android settings") }
                    Hint("Onyx Remote ${BuildConfig.VERSION_NAME}")
                }
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Save changes?") },
            confirmButton = { Button(onClick = { commit(); askDiscard = false; vm.show(Screen.PANEL) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { askDiscard = false; vm.show(Screen.PANEL) }) { Text("Discard") } },
        )
    }
    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import layout") },
            text = { Text("Layout only keeps this tablet's connection, PIN and display settings. Everything replaces them too.") },
            confirmButton = {
                Button(onClick = {
                    val err = vm.importJson(text, layoutOnly = true)
                    vm.setStatus(err?.let { "Import failed: $it" } ?: "Layout imported", err == null)
                    pendingImport = null
                }) { Text("Layout only") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val err = vm.importJson(text, layoutOnly = false)
                    vm.setStatus(err?.let { "Import failed: $it" } ?: "Everything imported", err == null)
                    if (err == null) d = vm.config.value
                    pendingImport = null
                }) { Text("Everything") }
            },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
}
