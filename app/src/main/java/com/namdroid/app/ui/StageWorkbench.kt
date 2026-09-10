package com.namdroid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.namdroid.app.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

private val StageBlack = Color(0xFF090C10)
private val StageSurface = Color(0xFF151B22)
private val StageLine = Color(0xFF34404D)
private val StageAccent = Color(0xFF8BE34F)

/** Presentation only: all sound, persistence and routing stay in PedalboardScreen. */
@Composable
fun StageWorkbench(
    rigName: String, blocks: List<PedalBlock>, selectedId: String, editing: Boolean,
    running: Boolean, scene: Int, bpm: Int, status: String, inputDb: Float, outputDb: Float,
    onSelect: (String) -> Unit, onCloseEditor: () -> Unit,
    onMove: (Int, Int) -> Unit, onToggleBlock: (String) -> Unit,
    onParameter: (ParameterSpec, Float) -> Unit, onDelete: () -> Unit,
    onScene: (Int) -> Unit, onSaveScene: () -> Unit, onTap: () -> Unit,
    onRigs: () -> Unit, onTone: () -> Unit, onAudio: () -> Unit, onSettings: () -> Unit,
    onAdd: () -> Unit, onTuner: () -> Unit, onLooper: () -> Unit,
    onPickNam: () -> Unit, onPickIr: () -> Unit, foreground: Boolean = true,
) {
    var live by rememberSaveable { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var confirmScene by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = blocks.firstOrNull { it.id == selectedId }
    BackHandler(foreground && (editing || live)) { if (editing) onCloseEditor() else live = false }
    Column(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(StageSurface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (editing) IconButton(onCloseEditor) { Icon(Icons.Default.ArrowBack, "Volver a la cadena") }
            TextButton(onRigs, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("NAMDROID / STAGE", color = StageAccent, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text(rigName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            FilledTonalButton({ live = !live; onCloseEditor() }, contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (live) StageAccent else StageLine, contentColor = if (live) Color.Black else Color.White)) { Text(if (live) "LIVE" else "RIG") }
            Button(onAudio, contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = if (running) StageAccent else StageLine, contentColor = if (running) Color.Black else Color.White)) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Text(if (running) " ON" else " START", fontSize = 12.sp)
            }
            Box {
                IconButton({ overflow = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }, modifier = Modifier.widthIn(min = 260.dp, max = 340.dp)) {
                    DropdownMenuItem(text = { MenuLabel("TONE3000", "Catálogo, descargas y cuenta") }, leadingIcon = { Icon(Icons.Default.Cloud, null) }, onClick = { overflow = false; onTone() })
                    DropdownMenuItem(text = { MenuLabel("Entrada y salida", "USB · Auto / Exclusive / Shared") }, leadingIcon = { Icon(Icons.Default.SettingsInputComponent, null) }, onClick = { overflow = false; onSettings() })
                    DropdownMenuItem(text = { MenuLabel("Rigs y setlist", "Guardar, ordenar y respaldar") }, leadingIcon = { Icon(Icons.Default.QueueMusic, null) }, onClick = { overflow = false; onRigs() })
                    DropdownMenuItem(text = { MenuLabel("Agregar efecto", "Pedales, amp, cab y utilidades") }, leadingIcon = { Icon(Icons.Default.AddCircleOutline, null) }, onClick = { overflow = false; onAdd() })
                    DropdownMenuItem(text = { MenuLabel("Afinador", "Afinación cromática") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null) }, onClick = { overflow = false; onTuner() })
                    DropdownMenuItem(text = { MenuLabel("Looper", "Grabación y reproducción") }, leadingIcon = { Icon(Icons.Default.Loop, null) }, onClick = { overflow = false; onLooper() })
                }
            }
        }
        if (editing && selected != null) {
            StageEditor(Modifier.weight(1f), selected, onParameter, { onToggleBlock(selected.id) }, { confirmDelete = true }, onTone, onPickNam, onPickIr,
                { delta -> val index = blocks.indexOfFirst { it.id == selected.id }; onMove(index, index + delta) })
        } else {
            StageChain(Modifier.weight(1f), blocks, selectedId, live, onSelect, onToggleBlock, onMove)
        }
        Row(Modifier.fillMaxWidth().background(StageSurface).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(4) { index ->
                Surface(onClick = { onScene(index) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(6.dp), color = if (scene == index) StageAccent.copy(alpha = .18f) else StageBlack, border = androidx.compose.foundation.BorderStroke(1.dp, if (scene == index) StageAccent else StageLine)) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Box(Modifier.size(6.dp).background(if (scene == index) StageAccent else StageLine, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("${('A'.code + index).toChar()}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (scene == index) StageAccent else Color.White)
                    }
                }
            }
            TextButton(onTap, contentPadding = PaddingValues(horizontal = 8.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("TAP", fontSize = 10.sp); Text("$bpm", fontWeight = FontWeight.Bold) } }
            IconButton({ tools = true }) { Icon(Icons.Default.Apps, "Herramientas: efectos, escenas, afinador y looper") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(23.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StageMeter("IN", inputDb); StageMeter("OUT", outputDb)
            Text(status, Modifier.weight(1f), color = Color(0xFFADB8C4), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (tools) AlertDialog(onDismissRequest = { tools = false }, title = { Text("Herramientas del rig") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf("Agregar efecto" to onAdd, "Guardar escena ${('A'.code + scene).toChar()}" to { confirmScene = true }, "Afinador" to onTuner, "Looper" to onLooper, "Rigs / setlist / respaldo" to onRigs, "Entrada y salida de audio" to onSettings).forEach { (label, action) ->
                TextButton({ tools = false; action() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label, Modifier.fillMaxWidth()) }
            }
        }
    }, confirmButton = { TextButton({ tools = false }) { Text("Cerrar") } })
    if (confirmScene) AlertDialog(onDismissRequest = { confirmScene = false }, title = { Text("Guardar escena ${('A'.code + scene).toChar()}") }, text = { Text("Se reemplazarán sus estados de bypass y parámetros por los actuales.") }, confirmButton = { Button({ onSaveScene(); confirmScene = false }) { Text("Guardar") } }, dismissButton = { TextButton({ confirmScene = false }) { Text("Cancelar") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Quitar ${selected?.type?.label.orEmpty()}") }, text = { Text("Se quitará del rig actual. El archivo NAM o IR guardado no se borra.") }, confirmButton = { Button({ onDelete(); confirmDelete = false }) { Text("Quitar") } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
}

@Composable private fun MenuLabel(title: String, subtitle: String) {
    Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Color(0xFF94A2AE), fontSize = 10.sp) }
}

@Composable private fun StageMeter(label: String, db: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 9.sp, color = Color.LightGray)
        Canvas(Modifier.padding(start = 4.dp).size(42.dp, 5.dp)) {
            drawRect(StageLine)
            drawRect(if (db > -3f) Color(0xFFFF635B) else StageAccent, size = Size(size.width * ((db + 60) / 60).coerceIn(0f, 1f), size.height))
        }
    }
}

@Composable private fun StageChain(modifier: Modifier, blocks: List<PedalBlock>, selectedId: String, live: Boolean, select: (String) -> Unit, toggle: (String) -> Unit, move: (Int, Int) -> Unit) {
    val bounds = remember { mutableMapOf<String, Rect>() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val latestBlocks by rememberUpdatedState(blocks.toList())
    val latestMove by rememberUpdatedState(move)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth.value / if (live) 150f else 114f).toInt().coerceIn(2, 6)
        val rows = (blocks.size + columns - 1) / columns
        val cellHeight = ((maxHeight - 26.dp) / rows.coerceAtLeast(1) - 8.dp).coerceIn(86.dp, if (live) 150.dp else 132.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            Text(if (live) "LIVE  /  Tocá para activar · Mantené para editar" else "SIGNAL PATH  /  Tocá para editar · Arrastrá para reordenar", color = Color(0xFFADB8C4), fontSize = 10.sp, modifier = Modifier.padding(vertical = 5.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                items(blocks, key = { it.id }) { block ->
                    val position = blocks.indexOfFirst { it.id == block.id }
                    val dragModifier = Modifier.onGloballyPositioned { bounds[block.id] = it.boundsInRoot() }
                        .zIndex(if (dragged == block.id) 2f else 0f)
                        .graphicsLayer { if (dragged == block.id) { translationX = delta.x; translationY = delta.y; scaleX = 1.05f; scaleY = 1.05f; alpha = .88f } }
                        .pointerInput(block.id, live) {
                            detectDragGesturesAfterLongPress(onDragStart = { local ->
                                if (live) select(block.id)
                                else if (block.type.engineId != null) { dragged = block.id; delta = Offset.Zero; finger = (bounds[block.id]?.topLeft ?: Offset.Zero) + local }
                            }, onDragCancel = { dragged = null; delta = Offset.Zero }, onDragEnd = {
                                val id = dragged
                                if (id != null) {
                                    val target = latestBlocks.firstOrNull { it.id != id && bounds[it.id]?.contains(finger) == true }
                                    if (target != null) latestMove(latestBlocks.indexOfFirst { it.id == id }, latestBlocks.indexOfFirst { it.id == target.id })
                                }
                                dragged = null; delta = Offset.Zero
                            }) { change, amount -> if (dragged == block.id) { change.consume(); delta += amount; finger += amount } }
                        }
                    StageTile(block, position, block.id == selectedId, live, dragModifier.height(cellHeight)) { if (live && block.type.engineId != null) toggle(block.id) else select(block.id) }
                }
            }
        }
    }
}

@Composable private fun StageTile(block: PedalBlock, position: Int, selected: Boolean, live: Boolean, modifier: Modifier, click: () -> Unit) {
    val tint = block.type.color
    Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(7.dp), color = StageSurface, border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tint else StageLine)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(tint.copy(alpha = if (block.enabled) .22f else .04f), StageSurface))).padding(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(position + 1), fontSize = 9.sp, color = Color.LightGray)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(6.dp).background(if (block.enabled) tint else StageLine, CircleShape))
                Text(" →", color = tint, fontSize = 11.sp)
            }
            GearFace(block, Modifier.weight(1f).fillMaxWidth(), large = false)
            Text(block.type.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (block.enabled) Color.White else Color(0xFF8E9AA6))
            Text(if (block.enabled) (block.assetName?.substringBeforeLast('.') ?: "ACTIVE") else "BYPASS", fontSize = 9.sp, color = if (block.enabled) tint else Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun GearFace(block: PedalBlock, modifier: Modifier, large: Boolean, change: ((ParameterSpec, Float) -> Unit)? = null) {
    val isPedal = block.type !in setOf(BlockType.INPUT, BlockType.OUTPUT, BlockType.AMP, BlockType.IR)
    val resource = when (block.type) {
        BlockType.AMP -> R.drawable.amp
        BlockType.IR -> R.drawable.cab
        BlockType.COMP -> R.drawable.comp
        BlockType.DRIVE -> R.drawable.drive
        BlockType.DELAY -> R.drawable.delay
        BlockType.REVERB -> R.drawable.reverb
        else -> R.drawable.pedal_shell
    }
    Box(modifier.padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(resource), null, Modifier.fillMaxSize(if (isPedal) .96f else .9f), contentScale = ContentScale.Fit,
            colorFilter = if (isPedal && resource == R.drawable.pedal_shell) ColorFilter.tint(block.type.color, BlendMode.Color) else null)
        if (block.type == BlockType.INPUT || block.type == BlockType.OUTPUT) {
            Canvas(Modifier.fillMaxSize(.62f)) { drawCircle(block.type.color.copy(alpha = .2f)); drawCircle(block.type.color, size.minDimension * .34f, style = Stroke(if (large) 8.dp.toPx() else 3.dp.toPx())) }
            Text(block.type.shortLabel, fontWeight = FontWeight.Black, color = block.type.color, fontSize = if (large) 18.sp else 10.sp)
            return@Box
        }
        val faceWidth = if (isPedal && large) .82f else if (isPedal) .66f else .72f
        Column(Modifier.fillMaxWidth(faceWidth).fillMaxHeight(if (isPedal) .88f else .62f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(block.type.label.uppercase(), color = Color.White, fontSize = if (large) 13.sp else 8.sp, fontWeight = FontWeight.Black, letterSpacing = if (large) 1.sp else .5.sp, maxLines = 1)
            Spacer(Modifier.height(if (large) 12.dp else 3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                block.type.parameters.take(2).forEach { spec ->
                    GearKnob(spec, block.parameters[spec.key] ?: spec.default, block.type.color, large, change?.let { action -> { value -> action(spec, value) } })
                }
            }
            Spacer(Modifier.weight(1f))
            if (block.type != BlockType.IR) {
                Canvas(Modifier.size(if (large) 13.dp else 7.dp)) { drawCircle(if (block.enabled) StageAccent else Color(0xFF303840)); if (block.enabled) drawCircle(Color.White.copy(alpha = .55f), size.minDimension * .2f) }
                Spacer(Modifier.height(if (large) 9.dp else 3.dp))
                Canvas(Modifier.size(if (large) 48.dp else 22.dp)) { val r = size.minDimension * .43f; drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF89939A), Color(0xFF242B30))), r); drawCircle(Color(0xFF1D2429), r * .7f, style = Stroke(if (large) 3.dp.toPx() else 1.dp.toPx())) }
            }
        }
    }
}

@Composable private fun GearKnob(spec: ParameterSpec, value: Float, tint: Color, large: Boolean, change: ((Float) -> Unit)?) {
    val currentValue by rememberUpdatedState(value); val currentChange by rememberUpdatedState(change)
    val fraction = ((value - spec.range.start) / (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (large) 70.dp else 34.dp)) {
        Canvas(Modifier.size(if (large) 54.dp else 25.dp).then(if (change == null) Modifier else Modifier.pointerInput(spec.key) {
            detectVerticalDragGestures { event, amount -> event.consume(); currentChange?.invoke((currentValue - amount / 180.dp.toPx() * (spec.range.endInclusive - spec.range.start)).coerceIn(spec.range)) }
        })) {
            val r = size.minDimension * .38f; val angle = (135 + fraction * 270) * Math.PI / 180
            drawArc(Color.Black.copy(alpha = .55f), 135f, 270f, false, style = Stroke(if (large) 4.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF5F6B74), Color(0xFF11161A))), r)
            drawLine(tint, center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (r * .48f), center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (r * .84f), if (large) 3.dp.toPx() else 1.5.dp.toPx(), StrokeCap.Round)
        }
        Text(spec.label.uppercase(), color = Color.White, fontSize = if (large) 9.sp else 6.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (large) Text("${"%.1f".format(value)} ${spec.unit}", color = tint, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable private fun StageEditor(modifier: Modifier, block: PedalBlock, change: (ParameterSpec, Float) -> Unit, toggle: () -> Unit, delete: () -> Unit, tone: () -> Unit, pickNam: () -> Unit, pickIr: () -> Unit, move: (Int) -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.type.label, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = block.type.color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (block.type.engineId != null) TextButton(toggle) { Text(if (block.enabled) "ACTIVO" else "BYPASS") }
            if (block.type == BlockType.AMP) { TextButton(tone) { Text("TONE3000") }; TextButton(pickNam) { Text("LOCAL") } }
            if (block.type == BlockType.IR) TextButton(pickIr) { Text("CARGAR IR") }
            if (block.type.engineId != null) {
                IconButton({ move(-1) }) { Icon(Icons.Default.KeyboardArrowLeft, "Mover antes") }
                IconButton({ move(1) }) { Icon(Icons.Default.KeyboardArrowRight, "Mover después") }
                IconButton(delete) { Icon(Icons.Default.DeleteOutline, "Quitar efecto") }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.weight(.36f).fillMaxHeight(),
                color = StageSurface,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, StageLine),
            ) {
                GearFace(block, Modifier.fillMaxSize().padding(8.dp), large = true, change = change)
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(170.dp), modifier = Modifier.weight(.64f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(block.type.parameters, key = { it.key }) { spec -> StageParameter(block.id, spec, block.parameters[spec.key] ?: spec.default, block.type.color) { change(spec, it) } }
            }
        }
    }
}

@Composable private fun StageParameter(blockId: String, spec: ParameterSpec, value: Float, tint: Color, change: (Float) -> Unit) {
    var exact by remember(blockId, spec.key) { mutableStateOf(false) }
    var text by remember(blockId, spec.key) { mutableStateOf("") }
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(change)
    val fraction = ((value - spec.range.start) / (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)
    Column(Modifier.background(StageSurface, RoundedCornerShape(9.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(64.dp).pointerInput(blockId, spec.key) {
            detectVerticalDragGestures { event, amount -> event.consume(); currentChange((currentValue - amount / 240.dp.toPx() * (spec.range.endInclusive - spec.range.start)).coerceIn(spec.range)) }
        }) {
            val r = size.minDimension * .38f
            drawArc(StageLine, 135f, 270f, false, Offset(3f, 3f), Size(size.width - 6f, size.height - 6f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawArc(tint, 135f, fraction * 270f, false, Offset(3f, 3f), Size(size.width - 6f, size.height - 6f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF424E5A), Color(0xFF141B22))), r)
            val angle = (135 + fraction * 270) * Math.PI / 180
            drawLine(Color.White, center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (r * .55f), center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (r * .85f), 3.dp.toPx(), StrokeCap.Round)
        }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(spec.label.uppercase(), fontSize = 11.sp, color = Color.LightGray)
                TextButton({ text = "%.3f".format(java.util.Locale.US, value); exact = true }, contentPadding = PaddingValues(0.dp)) { Text("${"%.1f".format(value)} ${spec.unit}", color = tint, fontWeight = FontWeight.Bold) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(value, { change(it) }, valueRange = spec.range, modifier = Modifier.weight(1f).semantics { contentDescription = spec.label })
            IconButton({ change(spec.default) }) { Icon(Icons.Default.Refresh, "Restablecer ${spec.label}", Modifier.size(18.dp)) }
        }
    }
    if (exact) {
        val parsed = text.replace(',', '.').toFloatOrNull()
        val valid = parsed != null && parsed.isFinite() && parsed in spec.range
        AlertDialog(onDismissRequest = { exact = false }, title = { Text(spec.label) }, text = {
            OutlinedTextField(text, { text = it }, label = { Text("${spec.range.start} … ${spec.range.endInclusive} ${spec.unit}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, isError = !valid)
        }, confirmButton = { Button({ parsed?.let(change); exact = false }, enabled = valid) { Text("Aplicar") } }, dismissButton = { TextButton({ exact = false }) { Text("Cancelar") } })
    }
}
