package com.namdroid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
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

    // Full-screen pedal editors are a separate navigation surface. They must
    // never inherit the measurement constraints of the legacy split editor.
    if (editing && selected?.type == BlockType.DELAY) {
        BackHandler(foreground) { onCloseEditor() }
        Box(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
            DelayDroidEditor(
                Modifier.fillMaxSize(),
                selected,
                onParameter,
                { onToggleBlock(selected.id) },
            )
            IconButton(
                onCloseEditor,
                Modifier.align(Alignment.TopStart).zIndex(5f)
                    .background(Color.Black.copy(alpha = .42f), CircleShape),
            ) {
                Icon(Icons.Default.ArrowBack, "Volver a la cadena", tint = Color.White)
            }
        }
        return
    }

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
    val endpoint = block.type == BlockType.INPUT || block.type == BlockType.OUTPUT
    Surface(onClick = click, modifier = modifier, shape = RoundedCornerShape(9.dp), color = if (endpoint) StageSurface else Color.Transparent, border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tint else if (endpoint) StageLine else Color.Transparent)) {
        if (endpoint) {
            Column(Modifier.background(Brush.verticalGradient(listOf(tint.copy(alpha = .18f), StageSurface))).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%02d".format(position + 1), Modifier.fillMaxWidth(), fontSize = 9.sp, color = Color.LightGray)
                GearFace(block, Modifier.weight(1f).fillMaxWidth(), large = false)
                Text(block.type.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(if (block.enabled) "ACTIVE" else "BYPASS", fontSize = 8.sp, color = if (block.enabled) tint else Color.Gray)
            }
        } else {
            Box(Modifier.fillMaxSize().padding(3.dp), contentAlignment = Alignment.Center) {
                GearFace(block, Modifier.fillMaxSize(), large = false)
                Surface(Modifier.align(Alignment.TopStart), shape = CircleShape, color = Color(0xCC11161A)) {
                    Text("%02d".format(position + 1), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 8.sp, color = Color.White)
                }
                if (!block.enabled) {
                    Surface(Modifier.align(Alignment.BottomCenter), shape = RoundedCornerShape(4.dp), color = Color(0xDD11161A)) {
                        Text("BYPASS", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.LightGray)
                    }
                }
            }
        }
    }
}

@Composable private fun GearFace(block: PedalBlock, modifier: Modifier, large: Boolean, change: ((ParameterSpec, Float) -> Unit)? = null) {
    val isPedal = block.type !in setOf(BlockType.INPUT, BlockType.OUTPUT, BlockType.AMP, BlockType.IR)
    val expressionPedal = block.type == BlockType.WAH || block.type == BlockType.PITCH
    val detunePedal = block.type == BlockType.DETUNE
    val resource = when (block.type) {
        BlockType.AMP -> R.drawable.amp
        BlockType.IR -> R.drawable.cab
        BlockType.COMPRESSOR -> R.drawable.pedal_comp
        BlockType.GATE -> R.drawable.pedal_gate
        BlockType.DRIVE -> R.drawable.pedal_drive
        BlockType.EQ -> R.drawable.pedal_eq
        BlockType.CHORUS -> R.drawable.pedal_chorus
        BlockType.DELAY -> R.drawable.pedal_delay
        BlockType.REVERB -> R.drawable.pedal_reverb
        BlockType.WAH -> R.drawable.pedal_wah
        BlockType.AUTO_WAH -> R.drawable.pedal_auto_wah
        BlockType.TREMOLO -> R.drawable.pedal_tremolo
        BlockType.PITCH -> R.drawable.pedal_pitch
        BlockType.DETUNE -> R.drawable.pedal_detune
        else -> R.drawable.pedal_gate
    }
    Box(modifier.padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
        if (block.type !in setOf(BlockType.INPUT, BlockType.OUTPUT)) {
            Image(
                painterResource(resource),
                null,
                if (isPedal) Modifier.fillMaxHeight(.88f).aspectRatio(2f / 3f) else Modifier.fillMaxSize(.9f),
                contentScale = ContentScale.Fit
            )
        }
        if (expressionPedal) {
            val expressionValue = if (block.type == BlockType.WAH) {
                (block.parameters["position"] ?: 45f) / 100f
            } else {
                ((block.parameters["semitones"] ?: 0f) + 12f) / 24f
            }.coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxHeight(.88f).aspectRatio(2f / 3f).graphicsLayer {
                    rotationX = (0.5f - expressionValue) * 16f
                    cameraDistance = 12f * density
                },
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.expression_treadle), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Text(block.type.shortLabel, color = Color.White, fontWeight = FontWeight.Black,
                    fontSize = if (large) 15.sp else 8.sp)
            }
        }
        if (block.type == BlockType.INPUT || block.type == BlockType.OUTPUT) {
            Canvas(Modifier.fillMaxSize(.62f)) { drawCircle(block.type.color.copy(alpha = .2f)); drawCircle(block.type.color, size.minDimension * .34f, style = Stroke(if (large) 8.dp.toPx() else 3.dp.toPx())) }
            Text(block.type.shortLabel, fontWeight = FontWeight.Black, color = block.type.color, fontSize = if (large) 18.sp else 10.sp)
            return@Box
        }
        if (detunePedal) {
            val selectedDrop = (block.parameters["drop"] ?: 2f).roundToInt().coerceIn(0, 8)
            BoxWithConstraints(Modifier.fillMaxHeight(.88f).aspectRatio(2f / 3f)) {
                Canvas(Modifier.fillMaxSize()) {
                    val positions = listOf(
                        Offset(size.width * .27f, size.height * .43f),
                        Offset(size.width * .21f, size.height * .34f),
                        Offset(size.width * .22f, size.height * .24f),
                        Offset(size.width * .32f, size.height * .15f),
                        Offset(size.width * .50f, size.height * .11f),
                        Offset(size.width * .68f, size.height * .15f),
                        Offset(size.width * .78f, size.height * .24f),
                        Offset(size.width * .79f, size.height * .34f),
                        Offset(size.width * .73f, size.height * .43f),
                    )
                    val radius = size.minDimension * if (large) .027f else .023f
                    positions.forEachIndexed { index, center ->
                        drawCircle(Color.Black.copy(alpha = .78f), radius * 1.45f, center)
                        drawCircle(if (index == selectedDrop && block.enabled) Color(0xFF42F5FF) else Color(0xFF263640), radius, center)
                        if (index == selectedDrop && block.enabled) {
                            drawCircle(Color.White.copy(alpha = .8f), radius * .35f, center)
                        }
                    }
                }
                MasterKnob(
                    fraction = selectedDrop / 8f,
                    tint = Color(0xFF42F5FF),
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .20f)
                        .size(if (large) 58.dp else 29.dp),
                    strokeDp = if (large) 3f else 1.5f,
                )
                Canvas(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .75f).size(if (large) 28.dp else 14.dp)) {
                    val radius = size.minDimension * .43f
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF89939A), Color(0xFF242B30))), radius)
                    drawCircle(Color(0xFF1D2429), radius * .7f, style = Stroke(if (large) 1.5.dp.toPx() else 1.dp.toPx()))
                }
            }
        }
        if (isPedal && !expressionPedal && !detunePedal) {
            // The overlay uses the same 2:3 coordinate space as every pedal asset.
            // Controls therefore remain anchored to the chassis when its rendered size changes.
            BoxWithConstraints(Modifier.fillMaxHeight(.88f).aspectRatio(2f / 3f)) {
                Row(
                    Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .16f).fillMaxWidth(.72f),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    block.type.parameters.take(2).forEach { spec ->
                        GearKnob(spec, block.parameters[spec.key] ?: spec.default, block.type.color, large, change?.let { action -> { value -> action(spec, value) } })
                    }
                }
                Canvas(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .68f).size(if (large) 8.dp else 5.dp)) {
                    drawCircle(if (block.enabled) StageAccent else Color(0xFF303840))
                    if (block.enabled) drawCircle(Color.White.copy(alpha = .55f), size.minDimension * .2f)
                }
                Canvas(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * .76f).size(if (large) 28.dp else 14.dp)) {
                    val r = size.minDimension * .43f
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF89939A), Color(0xFF242B30))), r)
                    drawCircle(Color(0xFF1D2429), r * .7f, style = Stroke(if (large) 1.5.dp.toPx() else 1.dp.toPx()))
                }
            }
        } else if (!detunePedal) {
            Column(Modifier.fillMaxWidth(.72f).fillMaxHeight(.62f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(block.type.label.uppercase(), color = Color.White, fontSize = if (large) 13.sp else 8.sp, fontWeight = FontWeight.Black, letterSpacing = if (large) 1.sp else .5.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun GearKnob(spec: ParameterSpec, value: Float, tint: Color, large: Boolean, change: ((Float) -> Unit)?) {
    val currentValue by rememberUpdatedState(value); val currentChange by rememberUpdatedState(change)
    val fraction = ((value - spec.range.start) / (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (large) 32.dp else 20.dp)) {
        MasterKnob(fraction, tint, Modifier.size(if (large) 24.dp else 14.dp).then(if (change == null) Modifier else Modifier.pointerInput(spec.key) {
            detectVerticalDragGestures { event, amount -> event.consume(); currentChange?.invoke((currentValue - amount / 180.dp.toPx() * (spec.range.endInclusive - spec.range.start)).coerceIn(spec.range)) }
        }), if (large) 2f else 1f)
    }
}

@Composable private fun MasterKnob(fraction: Float, tint: Color, modifier: Modifier, strokeDp: Float) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Color.Black.copy(alpha = .55f), 135f, 270f, false,
                style = Stroke(strokeDp.dp.toPx(), cap = StrokeCap.Round))
            drawArc(tint, 135f, fraction.coerceIn(0f, 1f) * 270f, false,
                style = Stroke(strokeDp.dp.toPx(), cap = StrokeCap.Round))
        }
        Image(
            painter = painterResource(R.drawable.knob_master),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(.86f).graphicsLayer {
                rotationZ = -135f + fraction.coerceIn(0f, 1f) * 270f
            },
            contentScale = ContentScale.Fit,
        )
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
        // Stable fallback: the full-screen Delay-Droid compositor remains
        // isolated below, but is not entered until its device-only crash is
        // reproduced with diagnostics. Delay keeps all DSP and parameters.
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

@Composable
private fun DelayDroidEditor(
    modifier: Modifier,
    block: PedalBlock,
    change: (ParameterSpec, Float) -> Unit,
    toggle: () -> Unit,
) {
    var advanced by rememberSaveable(block.id) { mutableStateOf(false) }
    if (advanced) {
        Column(modifier) {
            TextButton({ advanced = false }, modifier = Modifier.align(Alignment.End)) { Text("VOLVER AL PEDAL") }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(block.type.parameters, key = { it.key }) { spec ->
                    StageParameter(block.id, spec, block.parameters[spec.key] ?: spec.default, block.type.color) { change(spec, it) }
                }
            }
        }
        return
    }

    val specs = remember(block.type) { block.type.parameters.associateBy { it.key } }
    val frontKnobs = remember(specs) {
        listOf(
            "time" to "TIME",
            "feedback" to "FEEDBACK",
            "mix" to "MIX",
            "cutoff" to "TONE",
            "modulation" to "MOD",
        ).mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val layers = remember(specs) {
        listOf("quarter" to "1/4", "sixteenth" to "1/16", "triplet" to "TRIPLET")
            .mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val pulse = rememberInfiniteTransition(label = "delay-led").animateFloat(
        initialValue = .38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(540, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "delay-led-alpha",
    ).value
    val switchDepth by animateFloatAsState(
        if (block.enabled) 3f else 0f,
        spring(stiffness = Spring.StiffnessMedium),
        label = "switch-depth",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.pedal_editor_background),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Image(
            painterResource(R.drawable.delay_droid_base),
            null,
            Modifier.fillMaxSize().padding(4.dp),
            contentScale = ContentScale.Fit,
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NAMdroid", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton({ advanced = true }) { Text("CONTROLES", color = Color.White, fontSize = 10.sp) }
            }
            Row(
                Modifier.weight(.48f).fillMaxWidth(.82f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                frontKnobs.forEach { (key, label, spec) ->
                    DelayFrontKnob(spec, label, block.parameters[key] ?: spec.default, change)
                }
            }
            Row(
                Modifier.weight(.23f).fillMaxWidth(.42f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                layers.forEach { (key, label, spec) ->
                    val active = (block.parameters[key] ?: spec.default) >= 50f
                    DelayLayerButton(label, active) { change(spec, if (active) 0f else 100f) }
                }
            }
            Row(
                Modifier.weight(.29f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    "DELAY-DROID",
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.weight(.55f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(13.dp)) {
                        drawCircle(Color(0xFF27E9F2).copy(alpha = if (block.enabled) pulse else .16f))
                        if (block.enabled) drawCircle(Color.White.copy(alpha = .7f), radius = size.minDimension * .18f)
                    }
                    Box(
                        Modifier.size(66.dp).clickable(onClick = toggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(painterResource(R.drawable.delay_footswitch_base), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        Image(
                            painterResource(R.drawable.delay_footswitch_cap),
                            "Activar o desactivar Delay",
                            Modifier.fillMaxSize().graphicsLayer {
                                translationY = switchDepth
                                scaleX = if (block.enabled) .97f else 1f
                                scaleY = if (block.enabled) .97f else 1f
                            },
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DelayFrontKnob(spec: ParameterSpec, label: String, value: Float, change: (ParameterSpec, Float) -> Unit) {
    val currentValue by rememberUpdatedState(value)
    val fraction = ((value - spec.range.start) / (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)
    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MasterKnob(
            fraction,
            Color(0xFF27E9F2),
            Modifier.size(48.dp).pointerInput(spec.key) {
                detectVerticalDragGestures { event, amount ->
                    event.consume()
                    change(spec, (currentValue - amount / 210.dp.toPx() * (spec.range.endInclusive - spec.range.start)).coerceIn(spec.range))
                }
            },
            3f,
        )
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("%.1f".format(value), color = Color(0xFF27E9F2), fontSize = 8.sp)
    }
}

@Composable
private fun DelayLayerButton(label: String, active: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (active) .94f else 1f, spring(stiffness = Spring.StiffnessHigh), label = "layer-button")
    Box(
        Modifier.size(74.dp, 48.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (active) 1f else .78f
        }.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.delay_layer_button), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Text(label, color = if (active) Color(0xFF27E9F2) else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
        MasterKnob(fraction, tint, Modifier.size(64.dp).pointerInput(blockId, spec.key) {
            detectVerticalDragGestures { event, amount -> event.consume(); currentChange((currentValue - amount / 240.dp.toPx() * (spec.range.endInclusive - spec.range.start)).coerceIn(spec.range)) }
        }, 4f)
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
