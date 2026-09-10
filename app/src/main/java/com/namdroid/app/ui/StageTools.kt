package com.namdroid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.namdroid.app.audio.NamEngine
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

@Composable private fun StageToolWindow(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), color = Carbon) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedButton(close) { Text("Volver al rig") }
                }
                content()
            }
        }
    }
}

@Composable fun StageLooper(engine: NamEngine, running: Boolean, close: () -> Unit) {
    var state by remember { mutableIntStateOf(engine.getLooperState()) }
    var position by remember { mutableFloatStateOf(0f) }
    var clear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { state = engine.getLooperState(); position = engine.getLooperProgress(); delay(80) } }
    StageToolWindow("LOOPER / 60 s", close) {
        Text(if (!running) "Iniciá el audio desde la pedalera" else when (state) { 1 -> "GRABANDO"; 2 -> "REPRODUCIENDO"; 3 -> "OVERDUB"; else -> "DETENIDO" }, color = if (state == 1) Color(0xFFFF635B) else SignalGreen, modifier = Modifier.padding(vertical = 12.dp))
        LinearProgressIndicator(progress = { position.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = SignalGreen)
        LazyVerticalGrid(GridCells.Adaptive(125.dp), Modifier.weight(1f).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(listOf(1 to "REC", 2 to "PLAY", 3 to "OVERDUB", 0 to "STOP", 4 to "BORRAR")) { (command, label) ->
                FilledTonalButton({ if (command == 4) clear = true else engine.looperCommand(command) }, Modifier.height(78.dp), enabled = running || command == 4, colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (state == command && command != 0) SignalGreen.copy(alpha = .25f) else PanelRaised)) { Text(label, fontWeight = FontWeight.Bold) }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("¿Borrar el loop?") }, text = { Text("Esta grabación no se podrá recuperar.") }, confirmButton = { Button({ engine.looperCommand(4); clear = false }) { Text("Borrar") } }, dismissButton = { TextButton({ clear = false }) { Text("Cancelar") } })
}

@Composable fun StageTuner(engine: NamEngine, running: Boolean, close: () -> Unit) {
    var hz by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) { engine.setTunerEnabled(true); onDispose { engine.setTunerEnabled(false) } }
    LaunchedEffect(Unit) { while (true) { hz = engine.getDetectedFrequency(); delay(80) } }
    val valid = running && hz > 0f && hz.isFinite()
    val midi = if (valid) (69 + 12 * log2(hz / 440f)).roundToInt() else 0
    val cents = if (valid) (1200 * log2(hz / (440 * Math.pow(2.0, (midi - 69) / 12.0)).toFloat())).roundToInt() else 0
    val notes = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    StageToolWindow("AFINADOR / A = 440 Hz", close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (valid) notes[(midi % 12 + 12) % 12] + (midi / 12 - 1) else "—", fontSize = 64.sp, fontWeight = FontWeight.Black, color = if (valid && abs(cents) <= 5) SignalGreen else Color.White)
            Text(if (!running) "Iniciá el audio para afinar" else if (!valid) "Tocá una nota" else "%.1f Hz · %+d cents".format(hz, cents), color = MutedText)
            Canvas(Modifier.fillMaxWidth(.8f).height(64.dp)) {
                repeat(11) { tick -> val x = size.width * tick / 10; drawLine(if (tick == 5) SignalGreen else Color.Gray, Offset(x, size.height * .3f), Offset(x, size.height * .7f), if (tick == 5) 3.dp.toPx() else 1.dp.toPx()) }
                if (valid) { val x = size.width * ((cents + 50f) / 100f).coerceIn(0f, 1f); drawCircle(if (abs(cents) <= 5) SignalGreen else WarmOrange, 6.dp.toPx(), Offset(x, size.height / 2)) }
            }
            Text("−50                         CENTRADO                         +50", color = MutedText, fontSize = 11.sp)
        }
    }
}

@Composable fun StageAddBlock(existing: Set<BlockType>, add: (BlockType) -> Unit, close: () -> Unit) {
    val available = BlockType.entries.filter { it.engineId != null && it !in existing }
    StageToolWindow("BIBLIOTECA DE EFECTOS", close) {
        if (available.isEmpty()) Text("Todos los tipos de efecto ya están en este rig.", Modifier.padding(16.dp))
        LazyVerticalGrid(GridCells.Adaptive(200.dp), Modifier.weight(1f).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(available) { type ->
                OutlinedButton({ add(type) }, Modifier.heightIn(min = 76.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(type.label, color = type.color, fontWeight = FontWeight.Bold)
                        Text("${type.category} · Agregar", color = MutedText)
                    }
                }
            }
        }
    }
}
