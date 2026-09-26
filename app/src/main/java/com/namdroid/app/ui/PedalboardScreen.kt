package com.namdroid.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.namdroid.app.audio.NamEngine
import com.namdroid.app.audio.AudioDeviceManager
import com.namdroid.app.audio.AudioDeviceOption
import com.namdroid.app.audio.MidiController
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PedalboardScreen(
    engine: NamEngine,
    loadedModelName: String?,
    loadedModelPath: String?,
    previewRevision: Int = 0,
    foreground: Boolean = true,
    running: Boolean,
    status: String,
    onToggleAudio: () -> Unit,
    onPickModel: () -> Unit,
    onBrowseTone3000: () -> Unit,
    onOpenStudio: () -> Unit,
    onLoadModelPath: (String) -> String,
    onAudioRouteChanged: (Int, Int, Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { RigStore(context.applicationContext) }
    var rigs by remember { mutableStateOf(store.loadAll()) }
    var activeRigIndex by remember { mutableIntStateOf(rigs.indexOfFirst { it.id == store.activeRigId() }.coerceAtLeast(0)) }
    val blocks = remember { mutableStateListOf<PedalBlock>().apply { addAll(rigs[activeRigIndex].blocks) } }
    var rigName by remember { mutableStateOf(rigs[activeRigIndex].name) }
    var bpm by remember { mutableIntStateOf(rigs[activeRigIndex].bpm) }
    var selectedId by remember { mutableStateOf(blocks.firstOrNull { it.type == BlockType.AMP }?.id ?: blocks.first().id) }
    var activeScene by remember { mutableIntStateOf(0) }
    var showLibrary by remember { mutableStateOf(false) }
    var showAddBlock by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showTuner by remember { mutableStateOf(false) }
    var showLooper by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBlockEditor by remember { mutableStateOf(false) }
    var lastDeletedBlock by remember { mutableStateOf<Pair<Int, PedalBlock>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var persistRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(notice) { if (notice != null) { delay(6000); notice = null } }
    var lastTap by remember { mutableLongStateOf(0L) }
    var inputMeter by remember { mutableFloatStateOf(-90f) }
    var outputMeter by remember { mutableFloatStateOf(-90f) }
    val audioDevices = remember { AudioDeviceManager(context.applicationContext) }
    var inputDeviceId by remember { mutableIntStateOf(audioDevices.savedInputId()) }
    var outputDeviceId by remember { mutableIntStateOf(audioDevices.savedOutputId()) }
    var sharingMode by remember { mutableIntStateOf(audioDevices.savedSharingMode()) }
    var inputChannelMode by remember { mutableIntStateOf(audioDevices.savedInputChannelMode()) }
    var audioDeviceRevision by remember { mutableIntStateOf(0) }
    val inputDeviceLabel = remember(inputDeviceId, audioDeviceRevision) { audioDevices.labelForInput(inputDeviceId) }
    val outputDeviceLabel = remember(outputDeviceId, audioDeviceRevision) { audioDevices.labelForOutput(outputDeviceId) }

    fun currentRig() = RigPreset(rigs[activeRigIndex].id, rigName, bpm, blocks.toList(), rigs[activeRigIndex].scenes)
    fun persist() { rigs = rigs.toMutableList().also { it[activeRigIndex] = currentRig() }; store.saveAll(rigs); store.selectRig(rigs[activeRigIndex].id) }
    fun schedulePersist() { persistRevision++ }
    fun syncEngine() {
        blocks.firstOrNull { it.type == BlockType.INPUT }?.parameters?.get("level")?.let(engine::setInputGainDb)
        blocks.firstOrNull { it.type == BlockType.OUTPUT }?.parameters?.get("level")?.let(engine::setOutputGainDb)
        engine.setBypass(!(blocks.firstOrNull { it.type == BlockType.AMP }?.enabled ?: false))
        val effects = blocks.filter { it.type.engineId != null }.take(16)
        val types = effects.map { it.type.engineId!! }.toIntArray()
        val enabled = BooleanArray(effects.size) { effects[it].enabled }
        val params = FloatArray(effects.size * 11)
        effects.forEachIndexed { index, block ->
            block.type.parameters.forEach { spec ->
                params[index * 11 + spec.engineParam] = block.parameters[spec.key] ?: spec.default
            }
        }
        engine.setEffectChain(types, enabled, params)
    }
    fun restoreAssets() {
        blocks.indices.forEach { index ->
            val block = blocks[index]
            if (block.type == BlockType.AMP || block.type == BlockType.IR) {
                val path = block.assetPath?.takeIf { File(it).isFile }
                val error = if (path == null) "Archivo ausente" else if (block.type == BlockType.AMP) onLoadModelPath(path) else engine.loadIr(path)
                if (error.isNotEmpty()) blocks[index] = block.copy(enabled = false)
            }
        }
        syncEngine()
    }
    fun loadRig(index: Int) {
        persist(); activeRigIndex = index; val rig = rigs[index]; rigName = rig.name; bpm = rig.bpm
        blocks.clear(); blocks.addAll(rig.blocks); selectedId = blocks.firstOrNull { it.type == BlockType.AMP }?.id ?: blocks.first().id; activeScene = 0
        restoreAssets(); persist()
    }
    fun updateBlock(id: String, transform: (PedalBlock) -> PedalBlock) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index >= 0) {
            blocks[index] = transform(blocks[index])
            schedulePersist()
        }
    }
    LaunchedEffect(persistRevision) {
        if (persistRevision > 0) {
            delay(300)
            persist()
        }
    }
    fun applyScene(index: Int) {
        if (index !in 0..3) return
        engine.beginTransition()
        activeScene = index; val scene = rigs[activeRigIndex].scenes[index]
        blocks.indices.forEach { blockIndex -> val block = blocks[blockIndex]; val enabled = scene.enabledByBlock[block.id] ?: block.enabled; val raw = scene.parametersByBlock[block.id] ?: block.parameters; val parameters = block.type.parameters.associate { spec -> val value = raw[spec.key] ?: spec.default; spec.key to (if (value.isFinite()) value.coerceIn(spec.range) else spec.default) }; blocks[blockIndex] = block.copy(enabled = enabled, parameters = parameters) }
        syncEngine()
    }

    val midiProgramHandler by rememberUpdatedState<(Int) -> Unit> { program -> if (program in rigs.indices) loadRig(program) }
    val midiControlHandler by rememberUpdatedState<(Int, Int) -> Unit> { control, value ->
        if (value > 0) when (control) { 20, 21, 22, 23 -> applyScene(control - 20); 24 -> showTuner = !showTuner; 25 -> engine.looperCommand(1); 26 -> engine.looperCommand(2); 27 -> engine.looperCommand(0) }
    }
    DisposableEffect(Unit) {
        val midi = MidiController(context.applicationContext, { midiProgramHandler(it) }, { control, value -> midiControlHandler(control, value) })
        midi.start(); onDispose { midi.close() }
    }

    LaunchedEffect(Unit) { restoreAssets() }
    LaunchedEffect(previewRevision) {
        if (previewRevision == 0) return@LaunchedEffect
        val path = loadedModelPath ?: return@LaunchedEffect
        var index = blocks.indexOfFirst { it.type == BlockType.AMP }
        if (index < 0) {
            index = blocks.indexOfFirst { it.type == BlockType.IR || it.type == BlockType.OUTPUT }
                .let { if (it < 0) blocks.size else it }
            blocks.add(index, PedalBlock(type = BlockType.AMP))
        }
        blocks[index] = blocks[index].copy(enabled = true, assetPath = path, assetName = loadedModelName)
        selectedId = blocks[index].id
        syncEngine()
        persist()
    }
    LaunchedEffect(loadedModelPath) {
        val path = loadedModelPath ?: return@LaunchedEffect
        val amp = blocks.firstOrNull { it.type == BlockType.AMP } ?: return@LaunchedEffect
        if (amp.assetPath != path) updateBlock(amp.id) { it.copy(assetPath = path, assetName = loadedModelName) }
    }
    LaunchedEffect(running) {
        while (running) { inputMeter = engine.getInputLevelDb(); outputMeter = engine.getOutputLevelDb(); delay(75) }
    }

    var pendingIrBlock by remember { mutableStateOf<String?>(null) }
    val pickIr = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val blockId = pendingIrBlock; pendingIrBlock = null
        if (uri != null && blockId != null) runCatching {
            val dest = File(context.filesDir, "ir_cabs/ir_${System.currentTimeMillis()}.wav"); dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } } ?: error("Could not read IR")
            val error = engine.loadIr(dest.absolutePath); require(error.isEmpty()) { error }
            updateBlock(blockId) { it.copy(assetPath = dest.absolutePath, assetName = uri.lastPathSegment?.substringAfterLast('/') ?: "Cabinet IR") }
        }
    }
    val exportRigs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { destination -> scope.launch(Dispatchers.IO) { context.contentResolver.openOutputStream(destination)?.bufferedWriter()?.use { writer -> writer.write(store.export(rigs)) } } }
    }
    val importRigs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { val result = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { store.import(it.readText()) } ?: error("Could not read backup") } }; result.onSuccess { imported -> rigs = imported; store.saveAll(rigs); activeRigIndex = 0; blocks.clear(); blocks.addAll(rigs.first().blocks); rigName = rigs.first().name; bpm = rigs.first().bpm; selectedId = blocks.first().id; restoreAssets(); persist(); notice = "Respaldo importado" }.onFailure { notice = "No se pudo importar: ${it.message}" } }
    }

    val selected = blocks.firstOrNull { it.id == selectedId }
    fun setSelectedEnabled(enabled: Boolean) {
        val block = selected ?: return
        updateBlock(block.id) { it.copy(enabled = enabled) }
        syncEngine()
    }
    fun setSelectedParameter(spec: ParameterSpec, value: Float) {
        val block = selected ?: return
        updateBlock(block.id) { it.copy(parameters = it.parameters + (spec.key to value)) }
        when (block.type) {
            BlockType.INPUT -> engine.setInputGainDb(value)
            BlockType.OUTPUT -> engine.setOutputGainDb(value)
            else -> syncEngine()
        }
    }
    fun deleteBlock(id: String) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return
        val block = blocks[index]
        if (block.type.engineId == null) return
        lastDeletedBlock = index to block
        blocks.removeAt(index)
        selectedId = blocks
            .getOrNull(index.coerceAtMost(blocks.lastIndex))
            ?.id ?: blocks.first().id
        showBlockEditor = false
        syncEngine()
        persist()
    }
    fun undoDelete() {
        val deleted = lastDeletedBlock ?: return
        blocks.add(deleted.first.coerceIn(0, blocks.size), deleted.second)
        selectedId = deleted.second.id
        lastDeletedBlock = null
        syncEngine()
        persist()
    }
    fun deleteSelected() {
        selected?.let { deleteBlock(it.id) }
    }

    fun tapTempo() {
        val now = System.currentTimeMillis()
        if (lastTap > 0 && now - lastTap in 200..2000) {
            bpm = (60000f / (now - lastTap)).roundToInt().coerceIn(30, 300)
            blocks.indexOfFirst { it.type == BlockType.DELAY }.takeIf { it >= 0 }?.let { index ->
                val time = (60000f / bpm).coerceIn(40f, 1500f)
                blocks[index] = blocks[index].copy(parameters = blocks[index].parameters + ("time" to time))
                syncEngine()
            }
            persist()
        }
        lastTap = now
    }
    StageWorkbench(
        foreground = foreground,
        rigName = rigName, blocks = blocks, selectedId = selectedId, editing = showBlockEditor,
        running = running, scene = activeScene, bpm = bpm, status = notice ?: status,
        inputDb = inputMeter, outputDb = outputMeter,
        onSelect = { selectedId = it; showBlockEditor = true },
        onCloseEditor = { showBlockEditor = false },
        onMove = { from, to ->
            if (from in blocks.indices && to in blocks.indices && blocks[from].type.engineId != null && blocks[to].type.engineId != null) {
                val block = blocks.removeAt(from); blocks.add(to, block)
                syncEngine(); persist()
            }
        },
        onToggleBlock = { id ->
            blocks.firstOrNull { it.id == id }?.let { block ->
                updateBlock(id) { it.copy(enabled = !it.enabled) }
                syncEngine()
            }
        },
        onParameter = ::setSelectedParameter,
        onDelete = ::deleteSelected,
        onDropDelete = ::deleteBlock,
        onUndoDelete = ::undoDelete,
        onScene = ::applyScene, onSaveScene = {
            val scenes = rigs[activeRigIndex].scenes.toMutableList()
            scenes[activeScene] = RigScene(('A'.code + activeScene).toChar().toString(), blocks.associate { it.id to it.enabled }, blocks.associate { it.id to it.parameters })
            rigs = rigs.toMutableList().also { it[activeRigIndex] = currentRig().copy(scenes = scenes) }
            store.saveAll(rigs)
            notice = "Escena ${('A'.code + activeScene).toChar()} guardada"
        },
        onTap = ::tapTempo, onRigs = { showLibrary = true },
        onTone = { showBlockEditor = false; onBrowseTone3000() },
        onStudio = { showBlockEditor = false; onOpenStudio() }, onAudio = onToggleAudio,
        onSettings = { showSettings = true }, onAdd = { showAddBlock = true },
        onTuner = { showTuner = true }, onLooper = { showLooper = true },
        onPickNam = onPickModel, onPickIr = { selected?.let { pendingIrBlock = it.id; pickIr.launch("audio/wav") } },
    )
    if (showLibrary) StageRigLibrary(rigs, activeRigIndex, { loadRig(it); showLibrary = false }, {
        persist(); val newRig = RigPreset(name = "Rig ${rigs.size + 1}"); rigs = rigs + newRig; store.saveAll(rigs); loadRig(rigs.lastIndex); showLibrary = false
    }, { index -> if (rigs.size > 1) { val remaining = rigs.filterIndexed { i, _ -> i != index }; rigs = remaining; activeRigIndex = 0; rigName = remaining.first().name; bpm = remaining.first().bpm; blocks.clear(); blocks.addAll(remaining.first().blocks); selectedId = blocks.firstOrNull { it.type == BlockType.AMP }?.id ?: blocks.first().id; store.saveAll(remaining); restoreAssets(); persist() } }, { from, to -> if (from in rigs.indices && to in rigs.indices) { val activeId = rigs[activeRigIndex].id; val reordered = rigs.toMutableList(); val moved = reordered.removeAt(from); reordered.add(to, moved); rigs = reordered; activeRigIndex = reordered.indexOfFirst { it.id == activeId }; store.saveAll(reordered) } }, { showLibrary = false; showRename = true }, { exportRigs.launch("NAMDroid-rigs.json") }, { importRigs.launch(arrayOf("application/json", "text/plain")) }, { showLibrary = false })
    if (showRename) RenameDialog(rigName, { rigName = it; persist(); showRename = false }, { showRename = false })
    if (showAddBlock) StageAddBlock(blocks.map { it.type }.toSet(), { type ->
        if (blocks.count { it.type.engineId != null } >= 16) {
            notice = "La cadena admite hasta 16 bloques DSP"
        } else {
            val output = blocks.indexOfFirst { it.type == BlockType.OUTPUT }.let { if (it < 0) blocks.size else it }
            val block = PedalBlock(type = type, enabled = false)
            blocks.add(output, block); selectedId = block.id; syncEngine(); persist()
        }
        showAddBlock = false
    }, { showAddBlock = false })
    if (showTuner) StageTuner(engine, running, { showTuner = false })
    if (showLooper) StageLooper(engine, running, { showLooper = false })
    if (showSettings) SettingsDialog(
        engine = engine,
        rigCount = rigs.size,
        inputDeviceId = inputDeviceId,
        outputDeviceId = outputDeviceId,
        sharingMode = sharingMode,
        inputChannelMode = inputChannelMode,
        deviceRevision = audioDeviceRevision,
        export = { exportRigs.launch("NAMDroid-rigs.json") },
        import = { importRigs.launch(arrayOf("application/json", "text/plain")) },
        refreshDevices = { audioDeviceRevision++ },
        applyRouting = { input, output, mode, channelMode ->
            inputDeviceId = input
            outputDeviceId = output
            sharingMode = mode
            inputChannelMode = channelMode
            onAudioRouteChanged(input, output, mode, channelMode)
        },
        close = { showSettings = false },
    )
}

@Composable private fun ProTopBar(name: String, running: Boolean, model: String?, input: Float, output: Float, inputRoute: String, outputRoute: String, toggle: () -> Unit, rigs: () -> Unit, tone: () -> Unit, settings: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(Panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(ElectricBlue), contentAlignment = Alignment.Center) { Text("N", color = Color.Black, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.width(10.dp))
        TextButton(onClick = rigs, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(model?.substringBeforeLast('.') ?: "Tap para elegir rig", color = MutedText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LevelMeter("IN", input); Spacer(Modifier.width(6.dp)); LevelMeter("OUT", output); Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = tone, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.Cloud, null); Spacer(Modifier.width(5.dp)); Text("TONE3000") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = settings, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Icon(Icons.Default.SettingsInputComponent, null); Spacer(Modifier.width(5.dp));
            Column { Text("I/O", fontWeight = FontWeight.Bold); Text("${shortRoute(inputRoute)} → ${shortRoute(outputRoute)}", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
        Spacer(Modifier.width(6.dp))
        Button(onClick = toggle, modifier = Modifier.heightIn(min = 50.dp), colors = ButtonDefaults.buttonColors(containerColor = if (running) SignalGreen else ElectricBlue), contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (running) "STOP" else "START", fontWeight = FontWeight.Bold) }
    }
}

private fun shortRoute(label: String): String = when {
    label.contains("USB", true) -> "USB"
    label.contains("Altavoz", true) -> "PHONE"
    label.contains("Micrófono", true) -> "MIC"
    label.contains("AUX", true) || label.contains("Jack", true) -> "AUX"
    label.contains("Bluetooth", true) -> "BT"
    else -> "AUTO"
}

@Composable private fun LevelMeter(label: String, db: Float) { Column(Modifier.width(62.dp)) { Text("$label  ${db.roundToInt()} dB", color = MutedText, style = MaterialTheme.typography.labelSmall); LinearProgressIndicator(progress = { ((db + 60f) / 60f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = if (db > -6) Color(0xFFFF5252) else SignalGreen, trackColor = Color(0xFF323942)) } }

@Composable private fun SignalHeader(bpm: Int, scene: Int, onTap: () -> Unit, onTuner: () -> Unit, onLooper: () -> Unit, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text("SIGNAL CHAIN", color = MutedText, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.weight(1f)); Text("SCENE ${('A'.code + scene).toChar()}", color = ElectricBlue, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(12.dp)); FilledTonalButton(onClick = onTap) { Text("TAP  $bpm") }; Spacer(Modifier.width(6.dp)); IconButton(onClick = onTuner) { Icon(Icons.Default.Tune, "Tuner") }; IconButton(onClick = onLooper) { Icon(Icons.Default.Loop, "Looper") }; IconButton(onClick = onAdd) { Icon(Icons.Default.AddCircle, "Add block", tint = ElectricBlue) } }
}

@Composable private fun SignalChain(blocks: List<PedalBlock>, selectedId: String, onSelect: (String) -> Unit, onMove: (Int, Int) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(15.dp)).background(Panel).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(blocks, key = { _, it -> it.id }) { index, block -> var dragX by remember(block.id) { mutableFloatStateOf(0f) }; PedalTile(block, block.id == selectedId, Modifier.pointerInput(block.id, index) { detectDragGesturesAfterLongPress(onDragEnd = { dragX = 0f }, onDragCancel = { dragX = 0f }) { change, amount -> change.consume(); dragX += amount.x; val threshold = 48.dp.toPx(); if (dragX > threshold && index < blocks.lastIndex) { onMove(index, index + 1); dragX = 0f } else if (dragX < -threshold && index > 0) { onMove(index, index - 1); dragX = 0f } } }, { onSelect(block.id) }) }
    }
}

@Composable private fun PedalTile(block: PedalBlock, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, modifier = modifier.width(110.dp).height(152.dp).border(if (selected) 2.dp else 1.dp, if (selected) block.type.color else Color(0xFF363D46), RoundedCornerShape(13.dp)), shape = RoundedCornerShape(13.dp), color = if (selected) PanelRaised else Color(0xFF191D22)) {
        Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)).background(block.type.color.copy(alpha = if (block.enabled) .2f else .05f)), contentAlignment = Alignment.Center) { Text(block.type.shortLabel, color = if (block.enabled) block.type.color else MutedText, fontWeight = FontWeight.Bold) }; Spacer(Modifier.height(8.dp)); Text(block.type.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.weight(1f)); Text(if (block.enabled) "ACTIVE" else "BYPASS", color = if (block.enabled) SignalGreen else MutedText, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun AdvancedBlockEditor(modifier: Modifier, block: PedalBlock, model: String?, onEnabled: (Boolean) -> Unit, onParameter: (ParameterSpec, Float) -> Unit, onBrowse: () -> Unit, onPickModel: () -> Unit, onPickIr: () -> Unit, onDelete: () -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)) { Column(Modifier.padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(block.type.category.uppercase(), color = MutedText, style = MaterialTheme.typography.labelSmall); Text(block.type.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }; Switch(block.enabled, onEnabled) }
        HorizontalDivider(Modifier.padding(vertical = 9.dp), color = Color(0xFF343B44))
        if (block.type == BlockType.AMP) { Text(block.assetName ?: model ?: "No NAM loaded", maxLines = 2, overflow = TextOverflow.Ellipsis); Row { Button(onClick = onBrowse, modifier = Modifier.weight(1f)) { Text("TONE3000") }; Spacer(Modifier.width(7.dp)); OutlinedButton(onClick = onPickModel, modifier = Modifier.weight(1f)) { Text("LOCAL") } }; Spacer(Modifier.height(8.dp)) }
        if (block.type == BlockType.IR) { Text(block.assetName ?: "No cabinet IR loaded", maxLines = 1, overflow = TextOverflow.Ellipsis); OutlinedButton(onClick = onPickIr, modifier = Modifier.fillMaxWidth()) { Text("LOAD WAV IR") }; Spacer(Modifier.height(5.dp)) }
        LazyColumn(Modifier.weight(1f)) { items(block.type.parameters, key = { it.key }) { spec -> val value = block.parameters[spec.key] ?: spec.default; Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.width(82.dp)) { Text(spec.label, color = MutedText, style = MaterialTheme.typography.labelSmall); Text(formatValue(value, spec.unit), color = block.type.color, fontWeight = FontWeight.Bold) }; Slider(value, { onParameter(spec, it) }, valueRange = spec.range, modifier = Modifier.weight(1f)) } } }
        if (block.type.engineId != null) { TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF7777))) { Icon(Icons.Default.Delete, null); Text(" REMOVE BLOCK") } }
    } }
}

private fun formatValue(value: Float, unit: String) = if (abs(value) < 10 && unit in listOf("Hz", "s", ":1")) "%.1f%s".format(value, unit) else "${value.roundToInt()}$unit"

@Composable private fun SceneStrip(active: Int, onScene: (Int) -> Unit, onCapture: (Int) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) { repeat(4) { index -> FilledTonalButton(onClick = { onScene(index) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (active == index) ElectricBlue else PanelRaised, contentColor = if (active == index) Color.Black else Color.White)) { Text("SCENE ${('A'.code + index).toChar()}") } }; IconButton(onClick = { onCapture(active) }) { Icon(Icons.Default.Save, "Save current scene", tint = SignalGreen) } } }

@Composable private fun StatusPanel(status: String, running: Boolean) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PanelRaised).padding(9.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.GraphicEq, null, tint = if (running) SignalGreen else MutedText); Spacer(Modifier.width(8.dp)); Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (running) SignalGreen else MutedText) } }

@Composable private fun RigLibraryDialog(rigs: List<RigPreset>, active: Int, select: (Int) -> Unit, create: () -> Unit, delete: (Int) -> Unit, move: (Int, Int) -> Unit, rename: () -> Unit, export: () -> Unit, import: () -> Unit, close: () -> Unit) { AlertDialog(onDismissRequest = close, title = { Text("Rig Library / Setlist") }, text = { Column(Modifier.heightIn(max = 430.dp)) { LazyColumn(Modifier.weight(1f)) { itemsIndexed(rigs) { index, rig -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (index == active) ElectricBlue.copy(.08f) else Color.Transparent), verticalAlignment = Alignment.CenterVertically) { TextButton({ select(index) }, Modifier.weight(1f)) { Icon(Icons.Default.QueueMusic, null, tint = if (index == active) ElectricBlue else MutedText); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(rig.name); Text("${rig.blocks.size} blocks • ${rig.bpm} BPM", color = MutedText, style = MaterialTheme.typography.labelSmall) }; Text("LOAD") }; IconButton({ move(index, index - 1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, null) }; IconButton({ move(index, index + 1) }, enabled = index < rigs.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, null) }; if (rigs.size > 1) IconButton({ delete(index) }) { Icon(Icons.Default.Delete, null) } } } }; Row { TextButton(create) { Text("NEW") }; TextButton(rename) { Text("RENAME") }; Spacer(Modifier.weight(1f)); IconButton(import) { Icon(Icons.Default.FileOpen, "Import") }; IconButton(export) { Icon(Icons.Default.SaveAlt, "Export") } } } }, confirmButton = { TextButton(close) { Text("DONE") } }) }

@Composable private fun RenameDialog(current: String, save: (String) -> Unit, close: () -> Unit) { var text by remember(current) { mutableStateOf(current) }; AlertDialog(onDismissRequest = close, title = { Text("Rename rig") }, text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true) }, confirmButton = { Button({ if (text.isNotBlank()) save(text.trim()) }) { Text("SAVE") } }, dismissButton = { TextButton(close) { Text("CANCEL") } }) }

@Composable private fun AddBlockDialog(existing: Set<BlockType>, add: (BlockType) -> Unit, close: () -> Unit) { val available = BlockType.entries.filter { it.engineId != null && it !in existing }; AlertDialog(onDismissRequest = close, title = { Text("Add effect block") }, text = { LazyColumn { items(available) { type -> ListItem(headlineContent = { Text(type.label) }, supportingContent = { Text(type.category) }, leadingContent = { Box(Modifier.size(34.dp).background(type.color.copy(.18f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text(type.shortLabel, color = type.color, style = MaterialTheme.typography.labelSmall) } }, modifier = Modifier.clip(RoundedCornerShape(9.dp))); TextButton({ add(type) }) { Text("ADD") } } } }, confirmButton = { TextButton(close) { Text("CLOSE") } }) }

@Composable private fun TunerDialog(engine: NamEngine, running: Boolean, close: () -> Unit) { var hz by remember { mutableFloatStateOf(0f) }; DisposableEffect(Unit) { engine.setTunerEnabled(true); onDispose { engine.setTunerEnabled(false) } }; LaunchedEffect(Unit) { while (true) { hz = engine.getDetectedFrequency(); delay(80) } }; val midi = if (hz > 0) (69 + 12 * log2(hz / 440f)).roundToInt() else 0; val notes = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"); val note = if (hz > 0) notes[(midi % 12 + 12) % 12] + (midi / 12 - 1) else "—"; val cents = if (hz > 0) (1200 * log2(hz / (440f * Math.pow(2.0, (midi - 69) / 12.0).toFloat()))).roundToInt() else 0; AlertDialog(onDismissRequest = close, title = { Text("Chromatic Tuner") }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Text(note, style = MaterialTheme.typography.displayLarge, color = if (abs(cents) <= 5) SignalGreen else ElectricBlue); Text(if (!running) "Start audio to tune" else if (hz <= 0) "Play a note" else "%.1f Hz   %+d cents".format(hz, cents), color = MutedText); LinearProgressIndicator(progress = { ((cents + 50) / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) } }, confirmButton = { Button(close) { Text("CLOSE") } }) }

@Composable private fun LooperDialog(engine: NamEngine, close: () -> Unit) { var state by remember { mutableIntStateOf(engine.getLooperState()) }; var progress by remember { mutableFloatStateOf(0f) }; LaunchedEffect(Unit) { while (true) { state = engine.getLooperState(); progress = engine.getLooperProgress(); delay(100) } }; AlertDialog(onDismissRequest = close, title = { Text("60 Second Looper") }, text = { Column { Text(when (state) { 1 -> "RECORDING"; 2 -> "PLAYING"; 3 -> "OVERDUB"; else -> "STOPPED" }, color = if (state == 1) Color.Red else SignalGreen, fontWeight = FontWeight.Bold); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)); Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) { Button({ engine.looperCommand(1) }) { Icon(Icons.Default.FiberManualRecord, null) }; Button({ engine.looperCommand(2) }) { Icon(Icons.Default.PlayArrow, null) }; Button({ engine.looperCommand(3) }) { Text("DUB") }; OutlinedButton({ engine.looperCommand(0) }) { Icon(Icons.Default.Stop, null) }; TextButton({ engine.looperCommand(4) }) { Icon(Icons.Default.Delete, null) } } } }, confirmButton = { TextButton(close) { Text("DONE") } }) }

@Composable
private fun SettingsDialog(
    engine: NamEngine,
    rigCount: Int,
    inputDeviceId: Int,
    outputDeviceId: Int,
    sharingMode: Int,
    inputChannelMode: Int,
    deviceRevision: Int,
    export: () -> Unit,
    import: () -> Unit,
    refreshDevices: () -> Unit,
    applyRouting: (Int, Int, Int, Int) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val devices = remember(deviceRevision) { AudioDeviceManager(context.applicationContext) }
    val inputs = remember(deviceRevision) { devices.inputDevices() }
    val outputs = remember(deviceRevision) { devices.outputDevices() }
    var pendingInput by remember(inputDeviceId, deviceRevision) { mutableIntStateOf(if (inputs.any { it.id == inputDeviceId }) inputDeviceId else 0) }
    var pendingOutput by remember(outputDeviceId, deviceRevision) { mutableIntStateOf(if (outputs.any { it.id == outputDeviceId }) outputDeviceId else 0) }
    var pendingSharingMode by remember(sharingMode) { mutableIntStateOf(sharingMode.coerceIn(0, 2)) }
    var pendingInputChannelMode by remember(inputChannelMode) { mutableIntStateOf(inputChannelMode.coerceIn(0, 2)) }
    var diagnosticsRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(engine) {
        while (true) {
            delay(750)
            diagnosticsRevision++
        }
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Audio & Global Settings") },
        text = {
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("AUDIO ROUTING", color = ElectricBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Text("Elegí entrada y salida por separado. Podés elegir entrada y salida por separado. Si Android oculta el speaker al conectar AUX, usá “Forzar altavoz del teléfono”.", color = MutedText, style = MaterialTheme.typography.bodySmall)
                }
                item { AudioDeviceDropdown("ENTRADA", inputs, pendingInput) { pendingInput = it } }
                item { AudioDeviceDropdown("SALIDA", outputs, pendingOutput) { pendingOutput = it } }
                item { InputChannelSelector(pendingInputChannelMode) { pendingInputChannelMode = it } }
                item { SharingModeSelector(pendingSharingMode) { pendingSharingMode = it } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = refreshDevices, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("DETECTAR") }
                        Button(onClick = { applyRouting(pendingInput, pendingOutput, pendingSharingMode, pendingInputChannelMode) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Default.Cable, null); Spacer(Modifier.width(6.dp)); Text("APLICAR I/O") }
                    }
                    Text("Al aplicar una ruta, si el audio está activo NAMDroid reinicia el motor para abrir los dispositivos elegidos.", color = MutedText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                item { HorizontalDivider() }
                item {
                    // Leer periodicamente: una captura estatica al abrir el dialogo
                    // no sirve para observar los picos y xruns durante la prueba.
                    val actualMode = remember(diagnosticsRevision) {
                        sharingModeLabel(engine.getActualSharingMode())
                    }
                    val modelRate = engine.getModelSampleRate().toInt()
                    val modelRateText = if (modelRate > 0) "$modelRate Hz" else "desconocida"
                    ListItem(
                        headlineContent = { Text("Audio engine") },
                        supportingContent = { Text("${engine.getStreamSampleRate()} Hz • IN ${engine.getInputChannelCount()}ch / OUT ${engine.getOutputChannelCount()}ch • $actualMode\nNAM $modelRateText • Buffer ${engine.getBufferSizeFrames()} frames\nDSP ${"%.1f".format(engine.getCallbackLoadPercent())}% • NAM pico ${"%.1f".format(engine.getNamPeakLoadPercent())}%\nSalida XRuns ${engine.getOutputXRunCount()} • Entrada sin datos ${engine.getInputUnderflowCount()}") },
                        leadingContent = { Icon(Icons.Default.AudioFile, null) },
                    )
                }
                item { ListItem(headlineContent = { Text("MIDI control") }, supportingContent = { Text("${MidiController.deviceCount(context)} device(s) • PC: rigs • CC20–23: scenes • CC24: tuner • CC25–27: looper") }, leadingContent = { Icon(Icons.Default.Usb, null) }) }
                item { ListItem(headlineContent = { Text("Rig backup") }, supportingContent = { Text("$rigCount saved rigs") }) }
                item { Row { OutlinedButton(import, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("IMPORT") }; Spacer(Modifier.width(8.dp)); Button(export, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("EXPORT") } } }
            }
        },
        confirmButton = { Button(close, modifier = Modifier.heightIn(min = 48.dp)) { Text("DONE") } },
    )
}

@Composable
private fun InputChannelSelector(selectedMode: Int, onSelected: (Int) -> Unit) {
    Column {
        Text("CANAL DE ENTRADA", color = MutedText, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "MIX / AUTO", 1 to "CANAL 1", 2 to "CANAL 2").forEach { (mode, label) ->
                if (selectedMode == mode) Button({ onSelected(mode) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(label, maxLines = 1) }
                else OutlinedButton({ onSelected(mode) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(label, maxLines = 1) }
            }
        }
        Text("En una interfaz USB estéreo, elegí el jack donde conectaste la guitarra para no mezclar ruido del otro canal.", color = MutedText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

private fun sharingModeLabel(mode: Int): String = when (mode) {
    1 -> "Exclusive"
    2 -> "Shared"
    else -> "Auto (Exclusive → Shared)"
}

@Composable
private fun SharingModeSelector(selectedMode: Int, onSelected: (Int) -> Unit) {
    Column {
        Text("MODO DE AUDIO", color = MutedText, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "AUTO", 1 to "EXCLUSIVE", 2 to "SHARED").forEach { (mode, label) ->
                if (selectedMode == mode) {
                    Button(onClick = { onSelected(mode) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(label, maxLines = 1) }
                } else {
                    OutlinedButton(onClick = { onSelected(mode) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(label, maxLines = 1) }
                }
            }
        }
        Text(
            when (selectedMode) {
                1 -> "Exclusive: menor latencia, pero puede fallar con algunos cambios de dispositivo."
                2 -> "Shared: más compatible y estable, normalmente con algo más de latencia."
                else -> "Auto: intenta Exclusive primero y cambia a Shared si Android no puede abrir la ruta."
            },
            color = MutedText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AudioDeviceDropdown(label: String, devices: List<AudioDeviceOption>, selectedId: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.id == selectedId } ?: devices.first()
    Column {
        Text(label, color = MutedText, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(5.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(if (label == "ENTRADA") Icons.Default.Input else Icons.Default.Output, null)
                Spacer(Modifier.width(10.dp))
                Text(selected.displayName, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 300.dp, max = 520.dp)) {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Column { Text(device.name, fontWeight = if (device.id == selectedId) FontWeight.Bold else FontWeight.Normal); Text(device.typeLabel, color = MutedText, style = MaterialTheme.typography.labelSmall) } },
                        leadingIcon = { if (device.id == selectedId) Icon(Icons.Default.Check, null, tint = SignalGreen) else Icon(Icons.Default.Cable, null, tint = MutedText) },
                        onClick = { onSelected(device.id); expanded = false },
                    )
                }
            }
        }
    }
}
