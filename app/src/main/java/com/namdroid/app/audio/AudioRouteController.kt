package com.namdroid.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Fallback de ruteo para fabricantes que dejan de publicar TYPE_BUILTIN_SPEAKER
 * en GET_DEVICES_OUTPUTS cuando se conecta un jack/AUX.
 */
class AudioRouteController(context: Context) {
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setForcePhoneSpeaker(enabled: Boolean): Boolean {
        return if (enabled) forcePhoneSpeaker() else {
            clearForcedRoute()
            true
        }
    }

    private fun forcePhoneSpeaker(): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return false
            audioManager.setCommunicationDevice(speaker)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
    }

    fun clearForcedRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run { audioManager.isSpeakerphoneOn = false }
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
