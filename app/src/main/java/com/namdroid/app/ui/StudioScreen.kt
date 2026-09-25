package com.namdroid.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namdroid.app.audio.NamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StudioBackground = Color(0xFF090C10)
private val StudioSurface = Color(0xFF151B22)
private val StudioLine = Color(0xFF34404D)
private val StudioAccent = Color(0xFF8BE34F)
private val RecordRed = Color(0xFFFF5252)

private data class StudioTrackUi(
    val slot: Int,
    val path: String,
    val name: String,
    val volume: Float = 1f,
    val muted: Boolean = false,
)

@Composable
fun StudioScreen(
    engine: NamEngine,
    running: Boolean,
    ensureAudio: () -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember {
        context.getSharedPreferences("namdroid_studio", 0)
    }
    val studioDir = remember { File(context.filesDir, "studio").apply { mkdirs() } }
    var tracks by remember { mutableStateOf<List<StudioTrackUi>>(emptyList()) }
    var bpm by remember { mutableFloatStateOf(preferences.getFloat("bpm", 120f)) }
    var metronome by remember { mutableStateOf(preferences.getBoolean("metronome", true)) }
    var playing by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("STUDIO listo") }

    fun freeSlot(): Int? = (0 until 8).firstOrNull { slot -> tracks.none { it.slot == slot } }
    fun persistSettings() {
        preferences.edit().putFloat("bpm", bpm).putBoolean("metronome", metronome).apply()
    }
    fun stopTransport() {
        if (recording) engine.stopStudioRecording()
        recording = false
        recordingFile = null
        playing = false
        engine.setStudioTransport(false, bpm, metronome)
        elapsedSeconds = 0
    }
    fun loadTrack(file: File, slot: Int) {
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                engine.loadStudioTrack(slot, file.absolutePath)
            }
            if (error.isEmpty()) {
                tracks = tracks + StudioTrackUi(slot, file.absolutePath, file.nameWithoutExtension)
                notice = "Pista cargada: ${file.nameWithoutExtension}"
            } else notice = error
        }
    }

    LaunchedEffect(Unit) {
        val files = withContext(Dispatchers.IO) {
            studioDir.listFiles { file -> file.extension.equals("wav", true) }
                ?.sortedBy { it.lastModified() }?.take(8).orEmpty()
        }
        files.forEachIndexed { slot, file ->
            val error = withContext(Dispatchers.IO) {
                engine.loadStudioTrack(slot, file.absolutePath)
            }
            if (error.isEmpty()) tracks = tracks +
                StudioTrackUi(slot, file.absolutePath, file.nameWithoutExtension)
        }
    }
    LaunchedEffect(playing, recording) {
        while (playing || recording) {
            val sampleRate = engine.getStreamSampleRate().coerceAtLeast(1)
            elapsedSeconds = (engine.getStudioPositionFrames() / sampleRate).toInt()
            delay(200)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            engine.stopStudioRecording()
            engine.setStudioTransport(false, bpm, metronome)
        }
    }
    BackHandler {
        stopTransport()
        onBack()
    }

    val importTrack = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot = freeSlot()
        if (uri == null || slot == null) {
            if (slot == null) notice = "Máximo de 8 pistas en esta versión"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val destination = File(studioDir, "import_${System.currentTimeMillis()}.wav")
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destination).use { output -> input.copyTo(output) }
                    } ?: error("No se pudo leer el archivo")
                }.isSuccess
            }
            if (copied) loadTrack(destination, slot) else notice = "No se pudo importar la pista"
        }
    }

    Column(Modifier.fillMaxSize().background(StudioBackground).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).background(StudioSurface)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton({ stopTransport(); onBack() }) {
                Icon(Icons.Default.ArrowBack, "Volver")
            }
            Column(Modifier.weight(1f)) {
                Text("NAMDROID / STUDIO", color = StudioAccent, fontSize = 11.sp,
                    letterSpacing = 2.sp)
                Text("Proyecto de guitarra", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 18.sp)
            }
            OutlinedButton({
                bpm = (bpm - 1f).coerceAtLeast(30f); persistSettings()
                engine.setStudioTransport(playing || recording, bpm, metronome)
            }) { Text("−") }
            Text("${bpm.toInt()} BPM", color = Color.White, fontWeight = FontWeight.Bold)
            OutlinedButton({
                bpm = (bpm + 1f).coerceAtMost(300f); persistSettings()
                engine.setStudioTransport(playing || recording, bpm, metronome)
            }) { Text("+") }
            FilterChip(
                selected = metronome,
                onClick = {
                    metronome = !metronome; persistSettings()
                    engine.setStudioTransport(playing || recording, bpm, metronome)
                },
                label = { Text("METRÓNOMO") },
                leadingIcon = { Icon(Icons.Default.MusicNote, null) },
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    if (playing || recording) stopTransport() else {
                        val audioReady = running || ensureAudio()
                        if (audioReady) {
                            engine.setStudioTransport(true, bpm, metronome)
                            playing = true
                            notice = "Reproduciendo"
                        } else notice = "No se pudo iniciar la interfaz de audio"
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (playing) StudioAccent else StudioLine,
                    contentColor = if (playing) Color.Black else Color.White,
                ),
            ) {
                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Text(if (playing) " STOP" else " PLAY")
            }
            Button(
                onClick = {
                    if (recording) {
                        val completedFile = recordingFile
                        engine.stopStudioRecording()
                        recording = false; playing = false; recordingFile = null
                        engine.setStudioTransport(false, bpm, metronome)
                        val slot = freeSlot()
                        if (completedFile != null && slot != null) {
                            loadTrack(completedFile, slot)
                        }
                        notice = if (engine.getStudioDroppedFrames() == 0)
                            "Toma guardada" else "Toma guardada con cortes: revisá el dispositivo"
                    } else {
                        val slot = freeSlot()
                        if (slot == null) {
                            notice = "Máximo de 8 pistas en esta versión"
                        } else if (running || ensureAudio()) {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                            val file = File(studioDir, "Guitarra_$stamp.wav")
                            if (playing) engine.setStudioTransport(false, bpm, metronome)
                            engine.setStudioTransport(true, bpm, metronome)
                            if (engine.startStudioRecording(file.absolutePath)) {
                                recordingFile = file
                                recording = true; playing = true; notice = "Grabando guitarra procesada"
                            } else {
                                engine.setStudioTransport(false, bpm, metronome)
                                notice = "No se pudo crear el archivo WAV"
                            }
                        } else notice = "No se pudo iniciar la interfaz de audio"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = RecordRed,
                    contentColor = Color.White),
            ) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, null)
                Text(if (recording) " DETENER TOMA" else " GRABAR")
            }
            OutlinedButton({ importTrack.launch("audio/wav") }, enabled = tracks.size < 8) {
                Icon(Icons.Default.LibraryMusic, null); Text(" IMPORTAR PISTA")
            }
            Text(
                "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60),
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            Text(notice, color = Color(0xFFB8C4CF), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        HorizontalDivider(color = StudioLine)
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.GraphicEq, null, tint = StudioAccent,
                        modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Todavía no hay pistas", color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Grabá una guitarra o importá un WAV", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tracks, key = { it.slot }) { track ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = StudioSurface),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("${track.slot + 1}", color = StudioAccent,
                                fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Column(Modifier.widthIn(min = 150.dp, max = 260.dp)) {
                                Text(track.name, color = Color.White, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("WAV · guitarra", color = Color.Gray, fontSize = 12.sp)
                            }
                            Text("VOL", color = Color.Gray, fontSize = 11.sp)
                            Slider(
                                value = track.volume,
                                onValueChange = { value ->
                                    tracks = tracks.map {
                                        if (it.slot == track.slot) it.copy(volume = value) else it
                                    }
                                    engine.setStudioTrackMix(track.slot, value, track.muted)
                                },
                                valueRange = 0f..1.5f,
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = track.muted,
                                onClick = {
                                    val muted = !track.muted
                                    tracks = tracks.map {
                                        if (it.slot == track.slot) it.copy(muted = muted) else it
                                    }
                                    engine.setStudioTrackMix(track.slot, track.volume, muted)
                                },
                                label = { Text("MUTE") },
                            )
                            IconButton({
                                stopTransport()
                                engine.clearStudioTrack(track.slot)
                                File(track.path).delete()
                                tracks = tracks.filterNot { it.slot == track.slot }
                                notice = "Pista eliminada"
                            }) { Icon(Icons.Default.DeleteOutline, "Eliminar", tint = RecordRed) }
                        }
                    }
                }
            }
        }
    }
}
