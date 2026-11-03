package com.gsb.onyxremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gsb.onyxremote.RemoteViewModel
import com.gsb.onyxremote.model.CmdType
import com.gsb.onyxremote.model.Command
import com.gsb.onyxremote.model.Page
import com.gsb.onyxremote.model.Tile
import com.gsb.onyxremote.model.TileKind

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TileEditor(initial: Tile, isNew: Boolean, page: Page, vm: RemoteViewModel, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun edit(f: (Tile) -> Tile) { t = f(t); error = null }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).imePadding(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(BarBg).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isNew) "New tile" else "Edit tile", fontSize = 20.sp, modifier = Modifier.weight(1f))
                    if (!isNew) {
                        TextButton(onClick = { error = vm.duplicateTile(page.id, t); if (error == null) onDismiss() }) { Text("Duplicate") }
                        TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = FailRed)) { Text("Delete") }
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        error = vm.saveTile(page.id, t.copy(label = t.label.trimEnd()))
                        if (error == null) onDismiss()
                    }) { Text("Save") }
                }
                error?.let { Text(it, Modifier.fillMaxWidth().background(FailRed.copy(alpha = 0.15f)).padding(12.dp), color = FailRed) }

                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
                        Section("Tile") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TileKind.entries.forEach { k ->
                                    FilterChip(selected = t.kind == k, onClick = { edit { it.copy(kind = k) } }, label = { Text(k.label) })
                                }
                            }
                            OutlinedTextField(
                                t.label, { v -> edit { it.copy(label = v) } },
                                label = { Text("Label") }, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                                supportingText = { Text("Press Enter to put text on a second line") },
                            )
                            Text("Colour", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TilePalette.forEach { c ->
                                    val selected = (t.color and 0xFFFFFF) == (c and 0xFFFFFF)
                                    Box(
                                        Modifier.size(40.dp).background(Color(c), CircleShape)
                                            .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0xFF455A64), CircleShape)
                                            .clickable { edit { it.copy(color = c) } },
                                    )
                                }
                            }
                        }

                        Section("Position and size") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Stepper("Column", t.x + 1, 1..page.columns) { v -> edit { it.copy(x = v - 1) } }
                                Stepper("Row", t.y + 1, 1..page.rows) { v -> edit { it.copy(y = v - 1) } }
                                Stepper("Width", t.w, 1..page.columns) { v -> edit { it.copy(w = v) } }
                                Stepper("Height", t.h, 1..page.rows) { v -> edit { it.copy(h = v) } }
                            }
                            Text("On the panel you can also drag a tile to move it and drag its corner to resize.",
                                color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                        }

                        when (t.kind) {
                            TileKind.BUTTON -> ButtonSection(t, vm, ::edit)
                            TileKind.FADER -> Section("Fader") {
                                IntField("Cuelist number", t.faderCuelist, 1..99999, { v -> edit { it.copy(faderCuelist = v) } })
                                Text("Dragging sends SQL ${t.faderCuelist},<0-255>. Onyx can't report a cuelist's level, " +
                                    "so the fader shows the last level this tablet sent.", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                            }
                            TileKind.LABEL -> Section("Label") {
                                Text("A heading or note on the panel. It doesn't send anything.", color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this tile?") },
            confirmButton = {
                Button(onClick = { vm.deleteTile(page.id, t.id); confirmDelete = false; onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = FailRed)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ButtonSection(t: Tile, vm: RemoteViewModel, edit: ((Tile) -> Tile) -> Unit) {
    Section("When pressed") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask before firing")
                Text("For Release All and other big moves", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
            }
            Switch(t.confirm, { v -> edit { it.copy(confirm = v) } })
        }
        Text("Commands run top to bottom. A macro stops if Onyx goes offline partway.",
            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
        t.commands.forEachIndexed { i, cmd ->
            CommandRow(
                index = i,
                cmd = cmd,
                last = i == t.commands.lastIndex,
                onChange = { c -> edit { it.copy(commands = it.commands.toMutableList().also { l -> l[i] = c }) } },
                onMove = { d -> edit { it.copy(commands = it.commands.toMutableList().also { l -> l.add(i + d, l.removeAt(i)) }) } },
                onDelete = { edit { it.copy(commands = it.commands.filterIndexed { j, _ -> j != i }) } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                // A new step inherits the previous step's cuelist - macros usually work one cuelist.
                val prev = t.commands.lastOrNull()
                edit { it.copy(commands = it.commands + Command(cuelist = prev?.cuelist ?: 1)) }
            }) { Icon(Icons.Default.Add, null); Text("Add command") }
            if (t.commands.isNotEmpty()) {
                OutlinedButton(onClick = { vm.fire(t) }) { Text("Test now") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandRow(
    index: Int,
    cmd: Command,
    last: Boolean,
    onChange: (Command) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", Modifier.width(28.dp), fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary)
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.align(Alignment.CenterVertically)) {
                    OutlinedButton(onClick = { open = true }) {
                        Text(cmd.type.label)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(open, { open = false }) {
                        CmdType.entries.forEach { type ->
                            DropdownMenuItem(text = { Text(type.label) }, onClick = { onChange(cmd.copy(type = type)); open = false })
                        }
                    }
                }
                if (cmd.type.needsCuelist) IntField("Cuelist", cmd.cuelist, 1..99999, { onChange(cmd.copy(cuelist = it)) })
                if (cmd.type.needsCue) {
                    OutlinedTextField(
                        cmd.cue, { v -> onChange(cmd.copy(cue = v.filter { it.isDigit() || it == '.' }.take(10))) },
                        label = { Text("Cue") }, singleLine = true, modifier = Modifier.width(110.dp),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    )
                }
                if (cmd.type.needsLevel) IntField("Level %", cmd.level, 0..100, { onChange(cmd.copy(level = it)) })
                if (cmd.type == CmdType.WAIT) DecimalField("Seconds", cmd.seconds, 0.0..3600.0) { onChange(cmd.copy(seconds = it)) }
                if (cmd.type == CmdType.RAW) {
                    OutlinedTextField(cmd.raw, { onChange(cmd.copy(raw = it)) }, label = { Text("Telnet line") },
                        singleLine = true, modifier = Modifier.widthIn(min = 220.dp))
                }
                Text(cmd.describe(), Modifier.align(Alignment.CenterVertically), fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
            IconButton(onClick = { onMove(1) }, enabled = !last) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove", tint = FailRed) }
        }
    }
}
