package com.gsb.onyxremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gsb.onyxremote.net.OnyxClient.Link

val PanelBg = Color(0xFF0E0F12)
val BarBg = Color(0xFF15171C)
val OkGreen = Color(0xFF66BB6A)
val FailRed = Color(0xFFEF5350)
val WarnAmber = Color(0xFFFFCA28)

val TilePalette = listOf(
    0xFF2E7D32, 0xFF43A047, 0xFF00838F, 0xFF1565C0, 0xFF283593, 0xFF6A1B9A,
    0xFFAD1457, 0xFFC62828, 0xFFEF6C00, 0xFFF9A825, 0xFF455A64, 0xFF212121,
    0xFF4FC3F7, 0xFFBA68C8, 0xFFFFFFFF,
)

@Composable
fun OnyxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4FC3F7),
            onPrimary = Color(0xFF00222E),
            secondary = Color(0xFF90A4AE),
            background = PanelBg,
            surface = Color(0xFF17191E),
            surfaceVariant = Color(0xFF22252C),
            error = FailRed,
        ),
        content = content,
    )
}

fun contentColorFor(bg: Color): Color = if (bg.luminance() > 0.45f) Color.Black else Color.White

@Composable
fun LinkPill(link: Link, standby: Boolean, modifier: Modifier = Modifier) {
    val (color, text) = when (link) {
        Link.CONNECTED -> OkGreen to "Onyx connected"
        Link.CONNECTING -> WarnAmber to "Connecting…"
        Link.OFFLINE -> if (standby) Color(0xFF90A4AE) to "Onyx standby" else FailRed to "Onyx offline"
    }
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, Modifier.padding(start = 8.dp), color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Integer field that keeps whatever the user typed, but only reports values within [range]. */
@Composable
fun IntField(
    label: String,
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier.width(130.dp),
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() }.take(6)
            text.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        isError = parsed == null || parsed !in range,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun DecimalField(label: String, value: Double, range: ClosedFloatingPointRange<Double>, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(com.gsb.onyxremote.model.formatSeconds(value)) }
    val parsed = text.toDoubleOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() || it == '.' }.take(7)
            text.toDoubleOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        isError = parsed == null || parsed !in range,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(130.dp),
    )
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onValue(value - 1) }, enabled = value > range.first) { Text("−", fontSize = 20.sp) }
            Text(value.toString(), Modifier.width(36.dp), fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FilledTonalIconButton(onClick = { onValue(value + 1) }, enabled = value < range.last) { Text("+", fontSize = 20.sp) }
        }
    }
}
