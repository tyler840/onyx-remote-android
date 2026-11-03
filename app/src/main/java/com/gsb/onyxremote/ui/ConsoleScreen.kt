package com.gsb.onyxremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsb.onyxremote.RemoteViewModel
import com.gsb.onyxremote.RemoteViewModel.Screen
import com.gsb.onyxremote.net.OnyxClient.LogLine.Kind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Raw telnet: type a line, see Onyx's reply. For checking commands before putting them on a button. */
@Composable
fun ConsoleScreen(vm: RemoteViewModel) {
    val log by vm.client.log.collectAsState()
    val link by vm.client.link.collectAsState()
    val problem by vm.client.problem.collectAsState()
    val cfg by vm.config.collectAsState()
    var line by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    BackHandler { vm.show(Screen.SETTINGS) }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }

    fun send() {
        vm.sendRaw(line)
        line = ""
    }

    Column(Modifier.fillMaxSize().background(PanelBg)) {
        Row(Modifier.fillMaxWidth().background(BarBg).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.show(Screen.SETTINGS) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Telnet console · ${cfg.host}:${cfg.port}", fontSize = 20.sp, modifier = Modifier.weight(1f))
            LinkPill(link, standby = cfg.idleDisconnectSec > 0 && problem == null)
            TextButton(onClick = { vm.client.clearLog() }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            items(log) { l ->
                val (prefix, color) = when (l.kind) {
                    Kind.TX -> "→ " to Color(0xFF4FC3F7)
                    Kind.RX -> "← " to Color(0xFFE0E0E0)
                    Kind.INFO -> "· " to Color(0xFF90A4AE)
                    Kind.ERROR -> "! " to FailRed
                }
                Text("${time.format(Date(l.time))}  $prefix${l.text}", color = color, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(BarBg).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                line, { line = it }, Modifier.weight(1f),
                singleLine = true, placeholder = { Text("e.g. GQL 1, GTQ 1,5, help") },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            OutlinedButton(onClick = { vm.sendRaw("help") }) { Text("help") }
            Button(onClick = { send() }, enabled = line.isNotBlank()) { Text("Send") }
        }
    }
}
