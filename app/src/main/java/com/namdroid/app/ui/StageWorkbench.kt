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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    onDropDelete: (String) -> Unit, onUndoDelete: () -> Unit,
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
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selected = blocks.firstOrNull { it.id == selectedId }
    val deleteWithUndo: (String) -> Unit = { id ->
        onDropDelete(id)
        snackbarHost.currentSnackbarData?.dismiss()
        scope.launch {
            val result = snackbarHost.showSnackbar(
                message = "Efecto eliminado del rig",
                actionLabel = "DESHACER",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoDelete()
        }
    }

    if (editing && selected?.type == BlockType.GATE) {
        BackHandler(foreground) { onCloseEditor() }
        Box(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
            GateDroidEditor(
                modifier = Modifier.fillMaxSize(),
                block = selected,
                change = onParameter,
                toggle = { onToggleBlock(selected.id) },
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

    val standardDroidTypes = setOf(
        BlockType.COMPRESSOR,
        BlockType.DRIVE,
        BlockType.EQ,
        BlockType.CHORUS,
        BlockType.AUTO_WAH,
        BlockType.TREMOLO,
        BlockType.DETUNE,
    )
    if (editing && selected != null && selected.type in standardDroidTypes) {
        BackHandler(foreground) { onCloseEditor() }
        Box(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
            StandardDroidEditor(
                modifier = Modifier.fillMaxSize(),
                block = selected,
                change = onParameter,
                toggle = { onToggleBlock(selected.id) },
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

    if (editing && selected?.type == BlockType.IR) {
        BackHandler(foreground) { onCloseEditor() }
        Box(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
            CabDroidEditor(
                modifier = Modifier.fillMaxSize(),
                block = selected,
                change = onParameter,
                toggle = { onToggleBlock(selected.id) },
                pickIr = onPickIr,
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

    // AMP-DROID uses a fixed-ratio compositor so every interactive layer
    // stays registered to the approved amplifier artwork on every screen.
    if (editing && selected?.type == BlockType.AMP) {
        BackHandler(foreground) { onCloseEditor() }
        Box(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
            AmpDroidEditor(
                Modifier.fillMaxSize(),
                selected,
                onParameter,
                { onToggleBlock(selected.id) },
                onTone,
                onPickNam,
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

    // Full-screen pedal editors are a separate navigation surface. They must
    // never inherit the measurement constraints of the legacy split editor.
    if (editing && selected != null &&
        (selected.type == BlockType.DELAY || selected.type == BlockType.REVERB)
    ) {
        BackHandler(foreground) { onCloseEditor() }
        Box(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
            when (selected.type) {
                BlockType.DELAY -> DelayDroidEditor(
                    Modifier.fillMaxSize(),
                    selected,
                    onParameter,
                    { onToggleBlock(selected.id) },
                )
                BlockType.REVERB -> ReverbDroidEditor(
                    Modifier.fillMaxSize(),
                    selected,
                    onParameter,
                    { onToggleBlock(selected.id) },
                )
                else -> Unit
            }
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
    Box(Modifier.fillMaxSize().background(StageBlack).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
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
            StageChain(
                Modifier.weight(1f),
                blocks,
                selectedId,
                live,
                onSelect,
                onToggleBlock,
                onMove,
                deleteWithUndo,
            )
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
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
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

@Composable
private fun StageChain(
    modifier: Modifier,
    blocks: List<PedalBlock>,
    selectedId: String,
    live: Boolean,
    select: (String) -> Unit,
    toggle: (String) -> Unit,
    move: (Int, Int) -> Unit,
    delete: (String) -> Unit,
) {
    val bounds = remember { mutableMapOf<String, Rect>() }
    var chainBounds by remember { mutableStateOf(Rect.Zero) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var dragStartFinger by remember { mutableStateOf(Offset.Zero) }
    var deleteHover by remember { mutableStateOf(false) }
    var deleteArmed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val latestBlocks by rememberUpdatedState(blocks.toList())
    val latestMove by rememberUpdatedState(move)
    val latestDelete by rememberUpdatedState(delete)
    val latestDeleteArmed by rememberUpdatedState(deleteArmed)

    LaunchedEffect(deleteHover, dragged) {
        deleteArmed = false
        if (deleteHover && dragged != null) {
            delay(450)
            if (deleteHover && dragged != null) {
                deleteArmed = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    val zoneStrength by animateFloatAsState(
        targetValue = when {
            deleteArmed -> 1f
            deleteHover -> .72f
            dragged != null -> .34f
            else -> 0f
        },
        animationSpec = tween(160),
        label = "delete-zone-strength",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { chainBounds = it.boundsInRoot() },
    ) {
        val columns = (maxWidth.value / if (live) 150f else 114f).toInt().coerceIn(2, 6)
        val rows = (blocks.size + columns - 1) / columns
        val cellHeight = ((maxHeight - 26.dp) / rows.coerceAtLeast(1) - 8.dp)
            .coerceIn(86.dp, if (live) 150.dp else 132.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            Text(
                if (live) "LIVE  /  Tocá para activar · Mantené para editar"
                else "SIGNAL PATH  /  Tocá para editar · Arrastrá para reordenar",
                color = Color(0xFFADB8C4),
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 5.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(blocks, key = { it.id }) { block ->
                    val position = blocks.indexOfFirst { it.id == block.id }
                    val dragModifier = Modifier
                        .onGloballyPositioned { bounds[block.id] = it.boundsInRoot() }
                        .zIndex(if (dragged == block.id) 6f else 0f)
                        .graphicsLayer {
                            if (dragged == block.id) {
                                translationX = delta.x
                                translationY = delta.y
                                scaleX = if (deleteArmed) .92f else 1.05f
                                scaleY = if (deleteArmed) .92f else 1.05f
                                alpha = if (deleteArmed) .70f else .88f
                            }
                        }
                        .then(
                            if (dragged == block.id && deleteArmed) {
                                Modifier.border(2.dp, Color(0xFFFF3B30), RoundedCornerShape(9.dp))
                            } else Modifier
                        )
                        .pointerInput(block.id, live) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { local ->
                                    if (live) {
                                        select(block.id)
                                    } else if (block.type.engineId != null) {
                                        dragged = block.id
                                        delta = Offset.Zero
                                        finger = (bounds[block.id]?.topLeft ?: Offset.Zero) + local
                                        dragStartFinger = finger
                                        deleteHover = false
                                        deleteArmed = false
                                    }
                                },
                                onDragCancel = {
                                    dragged = null
                                    delta = Offset.Zero
                                    deleteHover = false
                                    deleteArmed = false
                                },
                                onDragEnd = {
                                    val id = dragged
                                    if (id != null && latestDeleteArmed) {
                                        latestDelete(id)
                                    } else if (id != null) {
                                        val target = latestBlocks.firstOrNull {
                                            it.id != id && bounds[it.id]?.contains(finger) == true
                                        }
                                        if (target != null) {
                                            latestMove(
                                                latestBlocks.indexOfFirst { it.id == id },
                                                latestBlocks.indexOfFirst { it.id == target.id },
                                            )
                                        }
                                    }
                                    dragged = null
                                    delta = Offset.Zero
                                    deleteHover = false
                                    deleteArmed = false
                                },
                            ) { change, amount ->
                                if (dragged == block.id) {
                                    change.consume()
                                    delta += amount
                                    finger += amount
                                    val intentionalTravel =
                                        finger.x - dragStartFinger.x >= 44.dp.toPx()
                                    val deepEdge =
                                        finger.x >= chainBounds.right - chainBounds.width * .09f
                                    deleteHover = intentionalTravel && deepEdge
                                }
                            }
                        }
                    StageTile(
                        block,
                        position,
                        block.id == selectedId,
                        live,
                        dragModifier.height(cellHeight),
                    ) {
                        if (live && block.type.engineId != null) toggle(block.id)
                        else select(block.id)
                    }
                }
            }
        }

        if (dragged != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(maxWidth * .16f)
                    .zIndex(5f)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xFFFF2418).copy(alpha = zoneStrength * .82f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    Modifier.padding(end = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Arrastrar hasta aquí para eliminar",
                        tint = Color.White,
                        modifier = Modifier.size(if (deleteArmed) 52.dp else 38.dp),
                    )
                    Text(
                        if (deleteArmed) "SOLTÁ PARA BORRAR"
                        else if (deleteHover) "MANTENÉ"
                        else "ELIMINAR",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
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
            val selectedDrop = (block.parameters["drop"] ?: 2f).roundToInt().coerceIn(-2, 8)
            BoxWithConstraints(Modifier.fillMaxHeight(.88f).aspectRatio(2f / 3f)) {
                Canvas(Modifier.fillMaxSize()) {
                    val positions = listOf(
                        Offset(size.width * .22f, size.height * .43f),
                        Offset(size.width * .17f, size.height * .35f),
                        Offset(size.width * .17f, size.height * .26f),
                        Offset(size.width * .23f, size.height * .18f),
                        Offset(size.width * .34f, size.height * .12f),
                        Offset(size.width * .47f, size.height * .10f),
                        Offset(size.width * .60f, size.height * .12f),
                        Offset(size.width * .71f, size.height * .18f),
                        Offset(size.width * .78f, size.height * .26f),
                        Offset(size.width * .78f, size.height * .35f),
                        Offset(size.width * .73f, size.height * .43f),
                    )
                    val radius = size.minDimension * if (large) .027f else .023f
                    positions.forEachIndexed { index, center ->
                        val position = index - 2
                        drawCircle(Color.Black.copy(alpha = .78f), radius * 1.45f, center)
                        drawCircle(if (position == selectedDrop && block.enabled) Color(0xFF42F5FF) else Color(0xFF263640), radius, center)
                        if (position == selectedDrop && block.enabled) {
                            drawCircle(Color.White.copy(alpha = .8f), radius * .35f, center)
                        }
                    }
                }
                MasterKnob(
                    fraction = (selectedDrop + 2f) / 10f,
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
private fun GateDroidEditor(
    modifier: Modifier,
    block: PedalBlock,
    change: (ParameterSpec, Float) -> Unit,
    toggle: () -> Unit,
) {
    val specs = remember(block.type) { block.type.parameters.associateBy { it.key } }
    val controls = remember(specs) {
        listOf(
            "threshold" to "THRESHOLD",
            "release" to "RELEASE",
            "attack" to "ATTACK",
            "hold" to "HOLD",
            "range" to "REDUCTION",
            "hysteresis" to "HYSTERESIS",
        ).mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val centers = remember { listOf(.13f, .278f, .426f, .574f, .722f, .87f) }
    val pulse = rememberInfiniteTransition(label = "gate-led").animateFloat(
        initialValue = .32f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(680, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "gate-led-alpha",
    ).value
    val switchDepth by animateFloatAsState(
        if (block.enabled) 3f else 0f,
        spring(stiffness = Spring.StiffnessMedium),
        label = "gate-switch-depth",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.pedal_editor_background),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pedalWidth = minOf(maxWidth, maxHeight * (16f / 9f))
            val pedalHeight = pedalWidth * (9f / 16f)
            val controlWidth = pedalWidth * .128f
            val controlHeight = pedalHeight * .36f

            Box(Modifier.size(pedalWidth, pedalHeight)) {
                Image(
                    painterResource(R.drawable.gate_droid_base),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )

                controls.forEachIndexed { index, (key, label, spec) ->
                    GateFrontKnob(
                        spec = spec,
                        label = label,
                        value = block.parameters[key] ?: spec.default,
                        change = change,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = pedalWidth * centers[index] - controlWidth / 2f,
                                y = pedalHeight * .115f,
                            )
                            .size(controlWidth, controlHeight),
                    )
                }

                Canvas(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = pedalWidth * .846f, y = pedalHeight * .555f)
                        .size(12.dp),
                ) {
                    drawCircle(Color.White.copy(alpha = if (block.enabled) pulse else .14f))
                    if (block.enabled) {
                        drawCircle(Color.White, radius = size.minDimension * .20f)
                    }
                }

                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = pedalWidth * .815f, y = pedalHeight * .605f)
                        .size(pedalWidth * .090f)
                        .clickable(onClick = toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(R.drawable.delay_footswitch_base),
                        null,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    Image(
                        painterResource(R.drawable.delay_footswitch_cap),
                        "Activar o desactivar Gate",
                        Modifier.fillMaxSize().graphicsLayer {
                            translationY = switchDepth
                            scaleX = if (block.enabled) .97f else 1f
                            scaleY = if (block.enabled) .97f else 1f
                        },
                        contentScale = ContentScale.Fit,
                    )
                }

                DroidWordmark(
                    "GATE-DROID",
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = pedalWidth * .30f, y = pedalHeight * .735f)
                        .size(pedalWidth * .40f, pedalHeight * .105f),
                    25.sp,
                )
            }
        }
    }
}

@Composable
private fun GateFrontKnob(
    spec: ParameterSpec,
    label: String,
    value: Float,
    change: (ParameterSpec, Float) -> Unit,
    modifier: Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val fraction = ((value - spec.range.start) /
        (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)

    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val knobSize = minOf(maxWidth * .62f, maxHeight * .52f)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${"%.1f".format(value)} ${spec.unit}",
                color = Color.White,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            MasterKnob(
                fraction,
                Color(0xFFD7DEE3),
                Modifier
                    .size(knobSize)
                    .pointerInput(spec.key) {
                        detectVerticalDragGestures { event, amount ->
                            event.consume()
                            change(
                                spec,
                                (currentValue - amount / 210.dp.toPx() *
                                    (spec.range.endInclusive - spec.range.start))
                                    .coerceIn(spec.range),
                            )
                        }
                    },
                2.6f,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                label,
                color = Color(0xFFE6E9EC),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AmpDroidEditor(
    modifier: Modifier,
    block: PedalBlock,
    change: (ParameterSpec, Float) -> Unit,
    toggle: () -> Unit,
    tone: () -> Unit,
    pickNam: () -> Unit,
) {
    val specs = remember(block.type) { block.type.parameters.associateBy { it.key } }
    val controls = remember(specs) {
        listOf(
            "input" to "DRIVE",
            "bass" to "BASS",
            "mid" to "MID",
            "midfreq" to "MID FREQ",
            "midq" to "MID Q",
            "treble" to "TREBLE",
            "presence" to "PRESENCE",
            "resonance" to "RESONANCE",
            "lowcut" to "LOW CUT",
            "highcut" to "HIGH CUT",
            "output" to "OUTPUT",
        ).mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val centers = remember {
        listOf(.1066f, .1862f, .2658f, .3459f, .4255f, .5051f, .5852f, .6648f, .7444f, .8240f, .9036f)
    }
    val pulse = rememberInfiniteTransition(label = "amp-led").animateFloat(
        initialValue = .38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(620, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "amp-led-alpha",
    ).value
    val switchDepth by animateFloatAsState(
        if (block.enabled) 3f else 0f,
        spring(stiffness = Spring.StiffnessMedium),
        label = "amp-switch-depth",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.pedal_editor_background),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val ampRatio = 1981f / 793f
            val ampWidth = minOf(maxWidth, maxHeight * ampRatio)
            val ampHeight = ampWidth / ampRatio
            val controlWidth = ampWidth * .078f
            val controlHeight = ampHeight * .280f

            Box(Modifier.size(ampWidth, ampHeight)) {
                Image(
                    painterResource(R.drawable.amp_droid_base),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )

                controls.forEachIndexed { index, (key, label, spec) ->
                    AmpFrontKnob(
                        spec = spec,
                        label = label,
                        value = block.parameters[key] ?: spec.default,
                        change = change,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(
                                x = ampWidth * centers[index] - controlWidth / 2f,
                                y = ampHeight * .135f,
                            )
                            .size(controlWidth, controlHeight),
                    )
                }

                Surface(
                    onClick = pickNam,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = ampWidth * .340f, y = ampHeight * .466f)
                        .size(ampWidth * .332f, ampHeight * .105f),
                    color = Color(0xDD05090C),
                    shape = RoundedCornerShape(5.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27DCE8)),
                ) {
                    Column(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            block.assetName?.takeIf { it.isNotBlank() } ?: "SIN MODELO CARGADO",
                            color = Color(0xFF7FF4F8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "TOCAR PARA CARGAR LOCAL",
                            color = Color.White.copy(alpha = .58f),
                            fontSize = 6.sp,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    onClick = tone,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = ampWidth * .718f, y = ampHeight * .448f)
                        .size(ampWidth * .207f, ampHeight * .145f),
                    color = Color.Black,
                    shape = RoundedCornerShape(5.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27DCE8)),
                ) {
                    Image(
                        painterResource(R.drawable.tone3000_official),
                        "Abrir TONE3000",
                        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
                        contentScale = ContentScale.Fit,
                    )
                }

                Canvas(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = ampWidth * .102f, y = ampHeight * .442f)
                        .size(11.dp),
                ) {
                    drawCircle(Color(0xFF27E9F2).copy(alpha = if (block.enabled) pulse else .14f))
                    if (block.enabled) {
                        drawCircle(Color.White.copy(alpha = .75f), radius = size.minDimension * .18f)
                    }
                }

                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = ampWidth * .085f, y = ampHeight * .492f)
                        .size(ampWidth * .040f)
                        .clickable(onClick = toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(R.drawable.delay_footswitch_cap),
                        "Activar o desactivar amplificador",
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

@Composable
private fun AmpFrontKnob(
    spec: ParameterSpec,
    label: String,
    value: Float,
    change: (ParameterSpec, Float) -> Unit,
    modifier: Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val fraction = ((value - spec.range.start) /
        (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)

    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val knobSize = minOf(maxWidth * .72f, maxHeight * .46f)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${"%.1f".format(value)} ${spec.unit}",
                modifier = Modifier.offset(y = 5.dp),
                color = Color(0xFF48E8EE),
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            MasterKnob(
                fraction,
                Color(0xFF27DCE8),
                Modifier
                    .size(knobSize)
                    .pointerInput(spec.key) {
                        detectVerticalDragGestures { event, amount ->
                            event.consume()
                            change(
                                spec,
                                (currentValue - amount / 210.dp.toPx() *
                                    (spec.range.endInclusive - spec.range.start))
                                    .coerceIn(spec.range),
                            )
                        }
                    },
                2.4f,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                modifier = Modifier.offset(y = (-5).dp),
                color = Color.White,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        Box(modifier) {
            Image(
                painterResource(R.drawable.pedal_editor_background),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                DelayModeButton(
                    "VOLVER AL PEDAL",
                    { advanced = false },
                    Modifier.align(Alignment.End),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(170.dp),
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(block.type.parameters, key = { it.key }) { spec ->
                        StageParameter(
                            block.id,
                            spec,
                            block.parameters[spec.key] ?: spec.default,
                            block.type.color,
                        ) { change(spec, it) }
                    }
                }
            }
        }
        return
    }

    val specs = remember(block.type) { block.type.parameters.associateBy { it.key } }
    val primaryKnobs = remember(specs) {
        listOf(
            "time" to "TIME",
            "feedback" to "FEEDBACK",
            "mix" to "MIX",
            "cutoff" to "TONE",
            "modulation" to "MOD",
        ).mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val secondaryKnobs = remember(specs) {
        listOf(
            "character" to "CHARACTER",
            "resonance" to "RESONANCE",
            "level" to "LEVEL",
        ).mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val layers = remember(specs) {
        listOf("quarter" to "1/4", "sixteenth" to "1/16", "triplet" to "TRIPLET")
            .mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val pulse = rememberInfiniteTransition(label = "delay-led").animateFloat(
        initialValue = .38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(540, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
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
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pedalWidth = minOf(maxWidth, maxHeight * (16f / 9f))
            val pedalHeight = pedalWidth * (9f / 16f)
            Box(
                Modifier.size(pedalWidth, pedalHeight),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(R.drawable.delay_droid_base),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                DelayModeButton(
                    "CONTROLES",
                    { advanced = true },
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            end = pedalWidth * .022f,
                            top = pedalHeight * .022f,
                        )
                        .zIndex(3f),
                )
                Column(
                    Modifier.fillMaxSize().padding(
                        start = pedalWidth * .065f,
                        end = pedalWidth * .065f,
                        top = pedalHeight * .055f,
                        bottom = pedalHeight * .055f,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(30.dp))
                    Row(
                        Modifier.weight(.38f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        primaryKnobs.forEach { (key, label, spec) ->
                            DelayFrontKnob(
                                spec,
                                label,
                                block.parameters[key] ?: spec.default,
                                change,
                                Modifier.weight(1f),
                            )
                        }
                    }
                    Row(
                        Modifier.weight(.38f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        secondaryKnobs.forEach { (key, label, spec) ->
                            DelayFrontKnob(
                                spec,
                                label,
                                block.parameters[key] ?: spec.default,
                                change,
                                Modifier.weight(1f),
                            )
                        }
                        layers.forEach { (key, label, spec) ->
                            val active = (block.parameters[key] ?: spec.default) >= 50f
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                DelayLayerButton(label, active) {
                                    change(spec, if (active) 0f else 100f)
                                }
                            }
                        }
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Canvas(Modifier.size(12.dp)) {
                                drawCircle(
                                    Color(0xFF27E9F2).copy(
                                        alpha = if (block.enabled) pulse else .16f,
                                    ),
                                )
                                if (block.enabled) {
                                    drawCircle(
                                        Color.White.copy(alpha = .7f),
                                        radius = size.minDimension * .18f,
                                    )
                                }
                            }
                            Box(
                                Modifier.size(54.dp).clickable(onClick = toggle),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painterResource(R.drawable.delay_footswitch_base),
                                    null,
                                    Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
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
                    Spacer(Modifier.weight(.18f))
                }
                DroidWordmark(
                    "DELAY-DROID",
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = pedalHeight * .335f)
                        .width(pedalWidth * .36f)
                        .height(pedalHeight * .105f),
                    27.sp,
                )
            }
        }
    }
}

@Composable
private fun DelayModeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xCC10181A))
            .border(1.dp, Color(0xFF27E9F2), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color(0xFF42F5C5),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .5.sp,
        )
    }
}

@Composable
private fun DelayFrontKnob(
    spec: ParameterSpec,
    label: String,
    value: Float,
    change: (ParameterSpec, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val fraction = ((value - spec.range.start) /
        (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MasterKnob(
            fraction,
            Color(0xFF27E9F2),
            Modifier.size(44.dp).pointerInput(spec.key) {
                detectVerticalDragGestures { event, amount ->
                    event.consume()
                    change(
                        spec,
                        (currentValue - amount / 210.dp.toPx() *
                            (spec.range.endInclusive - spec.range.start))
                            .coerceIn(spec.range),
                    )
                }
            },
            3f,
        )
        Text(
            label,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "%.1f".format(value),
            color = Color(0xFF27E9F2),
            fontSize = 7.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun DelayLayerButton(label: String, active: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (active) .94f else 1f,
        spring(stiffness = Spring.StiffnessHigh),
        label = "layer-button",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .size(82.dp, 50.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (active) 1f else .78f
                }
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.delay_layer_button),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            label,
            color = if (active) Color(0xFF27E9F2) else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReverbDroidEditor(
    modifier: Modifier,
    block: PedalBlock,
    change: (ParameterSpec, Float) -> Unit,
    toggle: () -> Unit,
) {
    var advanced by rememberSaveable(block.id) { mutableStateOf(false) }
    val reverbTint = Color(0xFFFF64C8)

    if (advanced) {
        Box(modifier) {
            Image(
                painterResource(R.drawable.pedal_editor_background),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                DelayModeButton(
                    "VOLVER AL PEDAL",
                    { advanced = false },
                    Modifier.align(Alignment.End),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(170.dp),
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(block.type.parameters, key = { it.key }) { spec ->
                        StageParameter(
                            block.id,
                            spec,
                            block.parameters[spec.key] ?: spec.default,
                            reverbTint,
                        ) { change(spec, it) }
                    }
                }
            }
        }
        return
    }

    val specs = remember(block.type) { block.type.parameters.associateBy { it.key } }
    val knobs = remember(specs) {
        listOf(
            "decay" to "DECAY",
            "tone" to "TONE",
            "mix" to "MIX",
        ).mapNotNull { (key, label) -> specs[key]?.let { Triple(key, label, it) } }
    }
    val modeSpec = specs["mode"]
    val pulse = rememberInfiniteTransition(label = "reverb-led").animateFloat(
        initialValue = .38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(680, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "reverb-led-alpha",
    ).value
    val switchDepth by animateFloatAsState(
        if (block.enabled) 3f else 0f,
        spring(stiffness = Spring.StiffnessMedium),
        label = "reverb-switch-depth",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.pedal_editor_background),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pedalWidth = minOf(maxWidth, maxHeight * (16f / 9f))
            val pedalHeight = pedalWidth * (9f / 16f)
            Box(
                Modifier.size(pedalWidth, pedalHeight),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(R.drawable.reverb_droid_base),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                DelayModeButton(
                    "CONTROLES",
                    { advanced = true },
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            end = pedalWidth * .022f,
                            top = pedalHeight * .022f,
                        )
                        .zIndex(3f),
                )
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = pedalHeight * .205f)
                        .fillMaxWidth(.62f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    knobs.forEach { (key, label, spec) ->
                        ReverbFrontKnob(
                            spec,
                            label,
                            block.parameters[key] ?: spec.default,
                            change,
                            reverbTint,
                        )
                    }
                }
                modeSpec?.let { spec ->
                    ReverbModeSelector(
                        spec = spec,
                        value = block.parameters[spec.key] ?: spec.default,
                        change = change,
                        tint = reverbTint,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = pedalWidth * .075f)
                            .offset(y = pedalHeight * .195f)
                            .width(pedalWidth * .58f)
                            .height(pedalHeight * .23f),
                    )
                }
                DroidWordmark(
                    "REVERB-DROID",
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = pedalHeight * .335f)
                        .width(pedalWidth * .43f)
                        .height(pedalHeight * .12f),
                    27.sp,
                )
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = pedalWidth * .105f)
                        .offset(y = pedalHeight * .17f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        drawCircle(
                            reverbTint.copy(
                                alpha = if (block.enabled) pulse else .16f,
                            ),
                        )
                        if (block.enabled) {
                            drawCircle(
                                Color.White.copy(alpha = .72f),
                                radius = size.minDimension * .18f,
                            )
                        }
                    }
                    Box(
                        Modifier.size(66.dp).clickable(onClick = toggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(R.drawable.delay_footswitch_base),
                            null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        Image(
                            painterResource(R.drawable.delay_footswitch_cap),
                            "Activar o desactivar Reverb",
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
private fun ReverbFrontKnob(
    spec: ParameterSpec,
    label: String,
    value: Float,
    change: (ParameterSpec, Float) -> Unit,
    tint: Color,
) {
    val currentValue by rememberUpdatedState(value)
    val fraction = ((value - spec.range.start) /
        (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)
    Column(
        Modifier.width(116.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MasterKnob(
            fraction,
            tint,
            Modifier.size(66.dp).pointerInput(spec.key) {
                detectVerticalDragGestures { event, amount ->
                    event.consume()
                    change(
                        spec,
                        (currentValue - amount / 210.dp.toPx() *
                            (spec.range.endInclusive - spec.range.start))
                            .coerceIn(spec.range),
                    )
                }
            },
            4f,
        )
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "%.1f".format(value),
            color = tint,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}


@Composable
private fun ReverbModeSelector(
    spec: ParameterSpec,
    value: Float,
    change: (ParameterSpec, Float) -> Unit,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val modes = listOf("ROOM", "HALL", "PLATE", "SHIMMER", "AMBIENT")
    val currentValue by rememberUpdatedState(value)
    val selected = value.roundToInt().coerceIn(0, modes.lastIndex)

    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = .18f))
            .border(
                1.dp,
                Color.White.copy(alpha = .10f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MasterKnob(
            selected / 4f,
            tint,
            Modifier
                .size(66.dp)
                .pointerInput(spec.key) {
                    var accumulated = 0f
                    detectVerticalDragGestures(
                        onDragStart = { accumulated = 0f },
                    ) { event, amount ->
                        event.consume()
                        accumulated -= amount
                        val stepSize = 28.dp.toPx()
                        val steps = (accumulated / stepSize).toInt()
                        if (steps != 0) {
                            val next = (currentValue.roundToInt() + steps)
                                .coerceIn(0, modes.lastIndex)
                            change(spec, next.toFloat())
                            accumulated -= steps * stepSize
                        }
                    }
                },
            4f,
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "TYPE",
                    color = Color.White.copy(alpha = .72f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    modes[selected],
                    color = tint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                modes.forEachIndexed { index, label ->
                    val active = index == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(27.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (active) tint.copy(alpha = .20f)
                                else Color.Black.copy(alpha = .24f),
                            )
                            .border(
                                1.dp,
                                if (active) tint
                                else Color.White.copy(alpha = .16f),
                                RoundedCornerShape(5.dp),
                            )
                            .clickable { change(spec, index.toFloat()) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (active) tint else Color.White.copy(alpha = .78f),
                            fontSize = if (label.length > 6) 6.sp else 7.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
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
