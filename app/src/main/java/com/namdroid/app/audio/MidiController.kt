package com.namdroid.app.audio

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/** Basic USB/Bluetooth MIDI input for live rig and scene switching. */
class MidiController(
    context: Context,
    private val onProgramChange: (Int) -> Unit,
    private val onControlChange: (Int, Int) -> Unit,
) : AutoCloseable {
    private val manager = context.getSystemService(MidiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var device: MidiDevice? = null
    private val outputPorts = mutableListOf<MidiOutputPort>()
    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (count < 2) return
            val status = data[offset].toInt() and 0xF0
            val first = data[offset + 1].toInt() and 0x7F
            when (status) {
                0xC0 -> mainHandler.post { onProgramChange(first) }
                0xB0 -> if (count >= 3) { val value = data[offset + 2].toInt() and 0x7F; mainHandler.post { onControlChange(first, value) } }
            }
        }
    }

    fun start() {
        val info = manager.devices.firstOrNull() ?: return
        manager.openDevice(info, { opened ->
            device = opened
            info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.forEach { portInfo ->
                opened?.openOutputPort(portInfo.portNumber)?.let { port -> port.connect(receiver); outputPorts += port }
            }
        }, mainHandler)
    }

    override fun close() {
        outputPorts.forEach { runCatching { it.disconnect(receiver); it.close() } }
        outputPorts.clear()
        runCatching { device?.close() }
        device = null
    }

    companion object {
        fun deviceCount(context: Context): Int = context.getSystemService(MidiManager::class.java).devices.size
    }
}
