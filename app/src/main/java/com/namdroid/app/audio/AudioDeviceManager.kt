package com.namdroid.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.os.Handler
import android.os.Looper

data class AudioDeviceOption(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val isInput: Boolean,
    val isOutput: Boolean,
) {
    val displayName: String get() = if (id == 0) name else "$name · $typeLabel"
}

class AudioDeviceManager(context: Context) {
    companion object {
        const val FORCE_PHONE_SPEAKER_ID = -100
        private const val KEY_INPUT = "input_device_id"
        private const val KEY_OUTPUT = "output_device_id"
        private const val KEY_SHARING_MODE = "sharing_mode"
        private const val KEY_INPUT_CHANNEL = "input_channel_mode"
    }
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = appContext.getSharedPreferences("audio_routing_v1", Context.MODE_PRIVATE)

    fun inputDevices(): List<AudioDeviceOption> =
        listOf(systemDefault("Sistema / automático", true, false)) +
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .map { it.toOption() }
                .sortedWith(compareBy({ devicePriority(it.typeLabel) }, { it.name.lowercase() }))

    fun outputDevices(): List<AudioDeviceOption> =
        listOf(
            systemDefault("Sistema / automático", false, true),
            AudioDeviceOption(
                FORCE_PHONE_SPEAKER_ID,
                "Forzar altavoz del teléfono",
                "Ignora la ruta automática de Android",
                isInput = false,
                isOutput = true,
            ),
        ) + audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.toOption() }
            .filterNot { it.typeLabel == "Altavoz del teléfono" }
            .sortedWith(compareBy({ devicePriority(it.typeLabel) }, { it.name.lowercase() }))

    fun savedInputId(): Int = prefs.getInt(KEY_INPUT, 0)
    fun savedOutputId(): Int = prefs.getInt(KEY_OUTPUT, 0)
    fun savedSharingMode(): Int = prefs.getInt(KEY_SHARING_MODE, 0).coerceIn(0, 2)
    fun savedInputChannelMode(): Int = prefs.getInt(KEY_INPUT_CHANNEL, 0).coerceIn(0, 2)
    fun resolvedInputId(): Int = savedInputId().takeIf { saved -> saved == 0 || inputDevices().any { it.id == saved } } ?: 0
    fun resolvedOutputId(): Int = savedOutputId().takeIf { saved -> saved == 0 || outputDevices().any { it.id == saved } } ?: 0

    fun save(inputId: Int, outputId: Int): Boolean {
        return prefs.edit()
            .putInt(KEY_INPUT, inputId)
            .putInt(KEY_OUTPUT, outputId)
            .commit()
    }

    fun saveSharingMode(mode: Int): Boolean =
        prefs.edit().putInt(KEY_SHARING_MODE, mode.coerceIn(0, 2)).commit()

    fun saveInputChannelMode(mode: Int): Boolean =
        prefs.edit().putInt(KEY_INPUT_CHANNEL, mode.coerceIn(0, 2)).commit()

    fun labelForInput(id: Int): String = inputDevices().firstOrNull { it.id == id }?.displayName ?: "Sistema / automático"
    fun labelForOutput(id: Int): String = outputDevices().firstOrNull { it.id == id }?.displayName ?: "Sistema / automático"

    fun registerDeviceCallback(onChanged: () -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChanged()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unregisterDeviceCallback(callback: AudioDeviceCallback) {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun systemDefault(name: String, input: Boolean, output: Boolean) =
        AudioDeviceOption(0, name, "Android", input, output)

    private fun AudioDeviceInfo.toOption(): AudioDeviceOption {
        val label = typeLabel(type)
        val product = productName?.toString()?.trim().orEmpty()
        val name = when {
            product.isNotBlank() && !product.equals("unknown", true) -> product
            else -> label
        }
        return AudioDeviceOption(id, name, label, isSource, isSink)
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono del teléfono"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz del teléfono"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Jack / headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares por cable"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Entrada/salida AUX analógica"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Entrada/salida digital"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX / line"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "Audio USB"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "Accesorio USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "Headset USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telefonía"
        else -> "Dispositivo de audio"
    }

    private fun devicePriority(type: String): Int = when {
        "USB" in type -> 0
        "AUX" in type || "Jack" in type -> 1
        "teléfono" in type -> 2
        "Bluetooth" in type -> 4
        else -> 3
    }

}
