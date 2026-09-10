package com.namdroid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.namdroid.app.audio.NamEngine
import com.namdroid.app.audio.AudioDeviceManager
import com.namdroid.app.audio.AudioRouteController
import com.namdroid.app.ui.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private val engine = NamEngine()
    private var oauthCallback by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        oauthCallback = intent?.data?.takeIf { it.scheme == "namdroid" }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }

        setContent {
            NamDroidTheme {
                Surface(Modifier.fillMaxSize().background(Carbon)) {
                    AppNav(engine, oauthCallback) { oauthCallback = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        oauthCallback = intent.data?.takeIf { it.scheme == "namdroid" }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }
}

private enum class Screen { PEDALBOARD, TONE3000 }

@Composable
private fun AppNav(engine: NamEngine, oauthCallback: Uri?, onOAuthConsumed: () -> Unit) {
    var screen by remember { mutableStateOf(if (oauthCallback != null) Screen.TONE3000 else Screen.PEDALBOARD) }
    var loadedModelName by remember { mutableStateOf<String?>(null) }
    var loadedModelPath by remember { mutableStateOf<String?>(null) }
    var previewRevision by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Connect an audio interface and load a NAM model") }
    val context = LocalContext.current
    val audioDevices = remember { AudioDeviceManager(context.applicationContext) }
    val audioRouteController = remember { AudioRouteController(context.applicationContext) }
    val routeHandler = remember { Handler(Looper.getMainLooper()) }
    var routeChangeToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val inputId = audioDevices.resolvedInputId()
        val outputId = audioDevices.resolvedOutputId()
        val forceSpeaker = outputId == AudioDeviceManager.FORCE_PHONE_SPEAKER_ID
        audioRouteController.setForcePhoneSpeaker(forceSpeaker)
        engine.setAudioDeviceIds(inputId, if (forceSpeaker) 0 else outputId)
        engine.setSharingMode(audioDevices.savedSharingMode())
        engine.setInputChannelMode(audioDevices.savedInputChannelMode())
    }
    DisposableEffect(Unit) {
        val deviceCallback = audioDevices.registerDeviceCallback {
            routeChangeToken += 1
            val recoveryToken = routeChangeToken
            val wasRunning = running
            if (wasRunning) {
                engine.stop()
                running = false
                statusText = "Dispositivo de audio cambiado…"
            }
            val inputId = audioDevices.resolvedInputId()
            val outputId = audioDevices.resolvedOutputId()
            val forceSpeaker = outputId == AudioDeviceManager.FORCE_PHONE_SPEAKER_ID
            audioRouteController.setForcePhoneSpeaker(forceSpeaker)
            engine.setAudioDeviceIds(inputId, if (forceSpeaker) 0 else outputId)
            if (wasRunning) routeHandler.postDelayed({
                if (recoveryToken != routeChangeToken) return@postDelayed
                running = engine.start()
                statusText = if (running) "Audio recuperado • ${engine.getStreamSampleRate()} Hz"
                    else "No se pudo recuperar la ruta de audio"
            }, 500L)
        }
        onDispose {
            audioDevices.unregisterDeviceCallback(deviceCallback)
            audioRouteController.clearForcedRoute()
        }
    }

    LaunchedEffect(oauthCallback) {
        if (oauthCallback != null) screen = Screen.TONE3000
    }

    fun loadModel(file: File): String {
        val error = engine.loadModel(file.absolutePath)
        if (error.isEmpty()) {
            loadedModelName = file.name
            loadedModelPath = file.absolutePath
            statusText = "Loaded ${file.nameWithoutExtension}"
        } else statusText = "Model error: $error"
        return error
    }

    val pickNamFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeLast(80) ?: "local_model.nam"
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val dest = File(context.filesDir, "nam_models/${System.currentTimeMillis()}_$safeName")
            dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: error("Could not read selected file")
            if (loadModel(dest).isEmpty()) previewRevision += 1
        }.onFailure { statusText = "File error: ${it.message}" }
    }

    BackHandler(screen == Screen.TONE3000) { screen = Screen.PEDALBOARD }
    Box(Modifier.fillMaxSize()) {
        PedalboardScreen(
            engine = engine,
            loadedModelName = loadedModelName,
            loadedModelPath = loadedModelPath,
            previewRevision = previewRevision,
            foreground = screen == Screen.PEDALBOARD,
            running = running,
            status = statusText,
            onToggleAudio = {
                if (running) {
                    engine.stop(); running = false; statusText = "Audio stopped"
                } else {
                    running = engine.start()
                    statusText = if (running) "Audio engine running at ${engine.getStreamSampleRate()} Hz" else "Could not start audio"
                }
            },
            onPickModel = { pickNamFile.launch("*/*") },
            onBrowseTone3000 = { screen = Screen.TONE3000 },
            onLoadModelPath = { loadModel(File(it)) },
            onAudioRouteChanged = { inputId, outputId, sharingMode, inputChannelMode ->
                // Guardar primero y reiniciar de forma controlada. Android puede tardar
                // unas decenas/centenas de ms en liberar el dispositivo anterior.
                val routeSaved = audioDevices.save(inputId, outputId)
                val modeSaved = audioDevices.saveSharingMode(sharingMode)
                val channelSaved = audioDevices.saveInputChannelMode(inputChannelMode)
                val wasRunning = running
                routeChangeToken += 1
                val myToken = routeChangeToken

                if (wasRunning) {
                    engine.stop()
                    running = false
                }

                val forceSpeaker = outputId == AudioDeviceManager.FORCE_PHONE_SPEAKER_ID
                val routeApplied = audioRouteController.setForcePhoneSpeaker(forceSpeaker)
                engine.setAudioDeviceIds(inputId, if (forceSpeaker) 0 else outputId)
                engine.setSharingMode(sharingMode)
                engine.setInputChannelMode(inputChannelMode)

                if (!wasRunning) {
                    statusText = when {
                        forceSpeaker && !routeApplied -> "Android no permitió forzar el altavoz interno"
                        routeSaved && modeSaved && channelSaved -> "Ruta, modo y canal guardados"
                        else -> "Ruta lista, pero no se pudo guardar todo"
                    }
                } else {
                    statusText = "Cambiando ruta de audio…"
                    routeHandler.postDelayed({
                        // Si el usuario cambió otra vez antes de que venza el delay,
                        // este reinicio viejo queda descartado.
                        if (myToken != routeChangeToken) return@postDelayed

                        val restarted = engine.start()
                        running = restarted
                        statusText = when {
                            forceSpeaker && !routeApplied -> "Android no permitió forzar el altavoz interno"
                            restarted && routeSaved && modeSaved && channelSaved -> "Ruta aplicada • ${engine.getStreamSampleRate()} Hz"
                            restarted -> "Ruta aplicada, pero no se pudo guardar todo"
                            else -> "No se pudo abrir esa ruta; probá otra entrada/salida"
                        }
                    }, 300L)
                }
            },
        )
        if (screen == Screen.TONE3000) Surface(Modifier.fillMaxSize()) {
        BrowseToneScreen(
            oauthCallback = oauthCallback,
            onOAuthConsumed = onOAuthConsumed,
            onBack = { screen = Screen.PEDALBOARD },
            onModelDownloaded = { file ->
                val error = loadModel(file)
                if (error.isNotEmpty()) error else {
                    engine.setBypass(false)
                    engine.setEffectEnabled(3, true)
                    previewRevision += 1
                    if (!running) running = engine.start()
                    statusText = if (running) "NAM activo: ${file.nameWithoutExtension}" else "NAM cargado; no se pudo iniciar el audio"
                    if (running) null else statusText
                }
            },
        )
        }
    }
}
