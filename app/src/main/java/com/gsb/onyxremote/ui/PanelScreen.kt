package com.gsb.onyxremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.text.KeyboardOptions
import com.gsb.onyxremote.RemoteViewModel
import com.gsb.onyxremote.RemoteViewModel.Screen
import com.gsb.onyxremote.model.MAX_GRID
import com.gsb.onyxremote.model.Page
import com.gsb.onyxremote.model.Rect
import com.gsb.onyxremote.model.Tile
import com.gsb.onyxremote.model.TileKind
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PanelScreen(vm: RemoteViewModel) {
    val cfg by vm.config.collectAsState()
    val ui by vm.ui.collectAsState()
    val link by vm.client.link.collectAsState()
    val problem by vm.client.problem.collectAsState()
    val status by vm.status.collectAsState()

    val pageIndex = ui.page.coerceIn(0, cfg.pages.lastIndex)
    val page = cfg.pages[pageIndex]

    var editTile by remember { mutableStateOf<Pair<Tile, Boolean>?>(null) } // tile, isNew
    var editPage by remember { mutableStateOf(false) }
    var confirmTile by remember { mutableStateOf<Tile?>(null) }
    var askPin by remember { mutableStateOf(false) }

    // Kiosk: Back never leaves the panel; in edit mode it just ends editing.
    BackHandler(enabled = true) { if (ui.editing) vm.setEditing(false) }

    Column(Modifier.fillMaxSize().background(PanelBg)) {
        Row(
            Modifier.fillMaxWidth().background(BarBg).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(cfg.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row(
                Modifier.weight(1f).padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cfg.pages.size > 1 || ui.editing) {
                    cfg.pages.forEachIndexed { i, p ->
                        FilterChip(
                            selected = i == pageIndex,
                            onClick = { vm.selectPage(i) },
                            label = { Text(p.name, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp)) },
                        )
                    }
                }
                if (ui.editing) {
                    TextButton(onClick = { vm.addPage() }) { Icon(Icons.Default.Add, null); Text("Page") }
                }
            }
            LinkPill(link, standby = cfg.idleDisconnectSec > 0 && problem == null)
            if (ui.editing) {
                TextButton(onClick = { editPage = true }) { Text("Page setup") }
                TextButton(onClick = { vm.show(Screen.SETTINGS) }) { Text("Settings") }
                Button(onClick = { vm.setEditing(false) }) { Text("Done") }
            } else {
                IconButton(onClick = { if (vm.needsPin()) askPin = true else vm.setEditing(true) }) {
                    Icon(if (vm.needsPin()) Icons.Default.Lock else Icons.Default.Settings, "Edit panel", tint = Color(0xFF78909C))
                }
            }
        }

        PageGrid(
            page = page,
            editing = ui.editing,
            vm = vm,
            onTileClick = { t ->
                when {
                    ui.editing -> editTile = t to false
                    t.kind != TileKind.BUTTON -> Unit
                    t.confirm -> confirmTile = t
                    else -> vm.fire(t)
                }
            },
            onAddAt = { x, y -> editTile = Tile(x = x, y = y) to true },
            modifier = Modifier.weight(1f),
        )

        StatusStrip(status, if (link == com.gsb.onyxremote.net.OnyxClient.Link.OFFLINE) problem else null, ui.editing)
    }

    editTile?.let { (t, isNew) ->
        TileEditor(initial = t, isNew = isNew, page = page, vm = vm, onDismiss = { editTile = null })
    }
    if (editPage) PageDialog(page, cfg.pages.size, vm, onDismiss = { editPage = false })
    confirmTile?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmTile = null },
            icon = { Icon(Icons.Default.Warning, null, tint = WarnAmber) },
            title = { Text("Fire “${t.label.replace('\n', ' ')}”?") },
            text = { Text(t.commands.joinToString("  ·  ") { it.describe() }, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                Button(
                    onClick = { vm.fire(t); confirmTile = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(t.color), contentColor = contentColorFor(Color(t.color))),
                ) { Text("Fire") }
            },
            dismissButton = { TextButton(onClick = { confirmTile = null }) { Text("Cancel") } },
        )
    }
    if (askPin) PinDialog(onDismiss = { askPin = false }, onSubmit = { vm.unlock(it).also { ok -> if (ok) askPin = false } })
}

@Composable
private fun StatusStrip(status: RemoteViewModel.Status?, problem: String?, editing: Boolean) {
    val (text, color) = when {
        problem != null -> problem to FailRed
        status != null -> status.text to when (status.ok) { true -> OkGreen; false -> FailRed; null -> Color(0xFFB0BEC5) }
        editing -> "Tap a tile to edit it · drag to move · drag the corner to resize · tap + to add" to Color(0xFFB0BEC5)
        else -> "Ready" to Color(0xFF78909C)
    }
    Text(
        text,
        Modifier.fillMaxWidth().background(BarBg).padding(horizontal = 14.dp, vertical = 8.dp),
        color = color, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PageGrid(
    page: Page,
    editing: Boolean,
    vm: RemoteViewModel,
    onTileClick: (Tile) -> Unit,
    onAddAt: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().padding(6.dp)) {
        val cols = page.columns.coerceIn(1, MAX_GRID)
        val rows = page.rows.coerceIn(1, MAX_GRID)
        val cw = maxWidth / cols
        val ch = maxHeight / rows
        val density = LocalDensity.current
        val cwPx = with(density) { cw.toPx() }
        val chPx = with(density) { ch.toPx() }

        if (editing) {
            val taken = Array(rows) { BooleanArray(cols) }
            page.tiles.forEach { t ->
                for (y in t.y until minOf(t.y + t.h, rows)) for (x in t.x until minOf(t.x + t.w, cols)) taken[y][x] = true
            }
            for (y in 0 until rows) for (x in 0 until cols) if (!taken[y][x]) {
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier.offset(cw * x, ch * y).size(cw, ch).padding(4.dp).clip(shape)
                        .border(1.dp, Color(0xFF37474F), shape)
                        .clickable { onAddAt(x, y) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, "Add tile", tint = Color(0xFF546E7A)) }
            }
        }

        page.tiles.forEach { tile ->
            if (tile.x >= cols || tile.y >= rows) return@forEach
            key(tile.id) {
                val t = tile.copy(w = minOf(tile.w, cols - tile.x), h = minOf(tile.h, rows - tile.y))
                val current by rememberUpdatedState(t)
                var drag by remember { mutableStateOf(Offset.Zero) }
                var grow by remember { mutableStateOf(Offset.Zero) }
                val active = drag != Offset.Zero || grow != Offset.Zero

                val moveMod = if (!editing) Modifier else Modifier.pointerInput(page.id, cwPx, chPx) {
                    detectDragGestures(
                        onDragEnd = {
                            val c = current
                            val r = Rect(c.x + (drag.x / cwPx).roundToInt(), c.y + (drag.y / chPx).roundToInt(), c.w, c.h)
                            drag = Offset.Zero
                            vm.moveTile(page.id, c.id, r)
                        },
                        onDragCancel = { drag = Offset.Zero },
                    ) { change, amount -> change.consume(); drag += amount }
                }
                val widthPx = cwPx * t.w + grow.x
                val heightPx = chPx * t.h + grow.y
                Box(
                    Modifier
                        .zIndex(if (active) 1f else 0f)
                        .offset { IntOffset((cwPx * t.x + drag.x).roundToInt(), (chPx * t.y + drag.y).roundToInt()) }
                        .size(
                            with(density) { widthPx.coerceAtLeast(cwPx).toDp() },
                            with(density) { heightPx.coerceAtLeast(chPx).toDp() },
                        )
                        .graphicsLayer { alpha = if (active) 0.85f else 1f }
                        .then(moveMod)
                        .padding(4.dp),
                ) {
                    TileView(t, editing, vm, onClick = { onTileClick(tile) })
                    if (editing) {
                        ResizeHandle(
                            Modifier.align(Alignment.BottomEnd).pointerInput(page.id, cwPx, chPx) {
                                detectDragGestures(
                                    onDragEnd = {
                                        val c = current
                                        val r = Rect(
                                            c.x, c.y,
                                            (c.w + (grow.x / cwPx).roundToInt()).coerceAtLeast(1),
                                            (c.h + (grow.y / chPx).roundToInt()).coerceAtLeast(1),
                                        )
                                        grow = Offset.Zero
                                        vm.moveTile(page.id, c.id, r)
                                    },
                                    onDragCancel = { grow = Offset.Zero },
                                ) { change, amount -> change.consume(); grow += amount }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier) {
    Canvas(modifier.size(34.dp).padding(6.dp)) {
        val c = Color.White.copy(alpha = 0.8f)
        val w = size.width
        for (i in 1..3) {
            val o = w * i / 3f
            drawLine(c, Offset(w - o, w), Offset(w, w - o), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun TileView(tile: Tile, editing: Boolean, vm: RemoteViewModel, onClick: () -> Unit) {
    when (tile.kind) {
        TileKind.BUTTON -> ButtonTile(tile, editing, vm, onClick)
        TileKind.FADER -> FaderTile(tile, editing, vm, onClick)
        TileKind.LABEL -> LabelTile(tile, editing, onClick)
    }
}

@Composable
private fun ButtonTile(tile: Tile, editing: Boolean, vm: RemoteViewModel, onClick: () -> Unit) {
    val flashes by vm.flashes.collectAsState()
    val running by vm.running.collectAsState()
    val flash = flashes[tile.id]
    var showFlash by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(flash) {
        if (flash != null && System.currentTimeMillis() - flash.at < 1500) {
            showFlash = flash.ok
            delay(900)
            showFlash = null
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = Color(tile.color)
    val fg = contentColorFor(base)
    val shape = RoundedCornerShape(12.dp)
    val border = when (showFlash) {
        true -> OkGreen
        false -> FailRed
        null -> Color.White.copy(alpha = 0.10f)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { val s = if (pressed) 0.96f else 1f; scaleX = s; scaleY = s }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.10f), lerp(base, Color.Black, 0.22f))))
            .background(if (pressed) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .border(if (showFlash != null) 5.dp else 1.dp, border, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val fontSize = (min(maxWidth, maxHeight).value * 0.17f).coerceIn(13f, 34f).sp
        Text(
            tile.label,
            Modifier.padding(10.dp),
            color = fg, fontSize = fontSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            lineHeight = fontSize * 1.1f, maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
        if (tile.confirm) {
            Icon(Icons.Default.Warning, "Asks first", Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp), tint = fg.copy(alpha = 0.7f))
        }
        if (tile.id in running) {
            CircularProgressIndicator(Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp), color = fg, strokeWidth = 2.dp)
        }
        if (editing) {
            Text(
                tile.commands.joinToString(" · ") { it.describe() }.ifEmpty { "no commands" },
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FaderTile(tile: Tile, editing: Boolean, vm: RemoteViewModel, onClick: () -> Unit) {
    var level by remember(tile.id, tile.faderLevel) { mutableFloatStateOf(tile.faderLevel.toFloat()) }
    val accent = Color(tile.color)
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        Modifier.fillMaxSize().clip(shape).background(Color(0xFF1B1E24)).border(1.dp, accent.copy(alpha = 0.5f), shape)
            .then(if (editing) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        val vertical = maxHeight >= maxWidth
        val trackPx = with(LocalDensity.current) { (if (vertical) maxHeight else maxWidth).toPx() }
        // Relative drag: the level moves with the finger but never jumps to where it lands,
        // so a stray touch can't snap the lights.
        val dragMod = if (editing) Modifier else Modifier.pointerInput(tile.id, vertical, trackPx) {
            detectDragGestures(onDragEnd = { vm.commitFader(tile.id, level.roundToInt()) }) { change, amount ->
                change.consume()
                val d = if (vertical) -amount.y else amount.x
                level = (level + d / trackPx * 100f).coerceIn(0f, 100f)
                vm.setFader(tile.id, level.roundToInt())
            }
        }
        Box(Modifier.fillMaxSize().then(dragMod)) {
            val frac = level / 100f
            val fill = Brush.verticalGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.55f)))
            if (vertical) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(frac).background(fill))
            } else {
                Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(frac).background(fill))
            }
            Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tile.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.weight(1f))
                Text("${level.roundToInt()}%", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (editing) Text("SQL ${tile.faderCuelist}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun LabelTile(tile: Tile, editing: Boolean, onClick: () -> Unit) {
    val accent = Color(tile.color)
    BoxWithConstraints(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.16f))
            .then(if (editing) Modifier.border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val fontSize = (min(maxWidth, maxHeight).value * 0.22f).coerceIn(13f, 30f).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(tile.label, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 3)
            Box(Modifier.padding(top = 4.dp).width(40.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN to edit") },
        text = {
            OutlinedTextField(
                pin, { pin = it.take(12); wrong = false },
                singleLine = true, isError = wrong,
                supportingText = { if (wrong) Text("Wrong PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { Button(onClick = { if (!onSubmit(pin)) { wrong = true; pin = "" } }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PageDialog(page: Page, pageCount: Int, vm: RemoteViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(page.name) }
    var cols by remember { mutableStateOf(page.columns) }
    var rows by remember { mutableStateOf(page.rows) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clipped = page.tiles.count { it.x + it.w > cols || it.y + it.h > rows }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Page name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stepper("Columns", cols, 1..MAX_GRID) { cols = it }
                    Stepper("Rows", rows, 1..MAX_GRID) { rows = it }
                }
                if (clipped > 0) Text("$clipped tile(s) would fall off the grid - move or shrink them first.", color = FailRed)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.movePage(page.id, -1) }) { Text("◀ Move left") }
                    TextButton(onClick = { vm.movePage(page.id, 1) }) { Text("Move right ▶") }
                }
                if (pageCount > 1) {
                    TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = FailRed)) {
                        Text("Delete this page")
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = clipped == 0 && name.isNotBlank(), onClick = {
                vm.updatePage(page.id) { it.copy(name = name.trim(), columns = cols, rows = rows) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${page.name}”?") },
            text = { Text("Its ${page.tiles.size} tile(s) go with it.") },
            confirmButton = {
                Button(onClick = { vm.deletePage(page.id); confirmDelete = false; onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = FailRed)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
