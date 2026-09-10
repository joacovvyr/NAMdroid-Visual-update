package com.namdroid.app.audio

/**
 * Puente Kotlin -> C++ (audio_engine.cpp / jni_bridge.cpp).
 * Todo el procesamiento de audio real ocurre en el hilo nativo de Oboe;
 * esta clase solo dispara comandos.
 */
class NamEngine {

    companion object {
        init {
            System.loadLibrary("namdroid_native")
        }
    }

    fun start(): Boolean = nativeStart()

    fun stop() = nativeStop()

    /** Devuelve "" si cargo bien, o un mensaje de error si algo fallo. */
    fun loadModel(absolutePath: String): String = nativeLoadModel(absolutePath)

    fun setInputGainDb(db: Float) = nativeSetInputGainDb(db)

    fun setOutputGainDb(db: Float) = nativeSetOutputGainDb(db)

    fun setBypass(bypass: Boolean) = nativeSetBypass(bypass)

    fun setEffectEnabled(effectId: Int, enabled: Boolean) = nativeSetEffectEnabled(effectId, enabled)
    fun setEffectAmount(effectId: Int, amount: Float) = nativeSetEffectAmount(effectId, amount)
    fun setEffectOrder(order: IntArray) = nativeSetEffectOrder(order)
    fun setEffectParam(effectId: Int, param: Int, value: Float) = nativeSetEffectParam(effectId, param, value)
    fun loadIr(absolutePath: String): String = nativeLoadIr(absolutePath)
    fun setTunerEnabled(enabled: Boolean) = nativeSetTunerEnabled(enabled)
    fun getInputLevelDb(): Float = nativeGetInputLevelDb()
    fun getOutputLevelDb(): Float = nativeGetOutputLevelDb()
    fun getDetectedFrequency(): Float = nativeGetDetectedFrequency()
    fun looperCommand(command: Int) = nativeLooperCommand(command)
    fun getLooperState(): Int = nativeGetLooperState()
    fun getLooperProgress(): Float = nativeGetLooperProgress()

    fun getModelSampleRate(): Double = nativeGetModelSampleRate()

    fun getStreamSampleRate(): Int = nativeGetStreamSampleRate()

    fun setAudioDeviceIds(inputDeviceId: Int, outputDeviceId: Int) =
        nativeSetAudioDeviceIds(inputDeviceId, outputDeviceId)

    /** 0 = Auto (Exclusive -> Shared), 1 = Exclusive, 2 = Shared. */
    fun setSharingMode(mode: Int) = nativeSetSharingMode(mode.coerceIn(0, 2))

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeLoadModel(path: String): String
    private external fun nativeSetInputGainDb(db: Float)
    private external fun nativeSetOutputGainDb(db: Float)
    private external fun nativeSetBypass(bypass: Boolean)
    private external fun nativeSetEffectEnabled(effectId: Int, enabled: Boolean)
    private external fun nativeSetEffectAmount(effectId: Int, amount: Float)
    private external fun nativeSetEffectOrder(order: IntArray)
    private external fun nativeSetEffectParam(effectId: Int, param: Int, value: Float)
    private external fun nativeLoadIr(path: String): String
    private external fun nativeSetTunerEnabled(enabled: Boolean)
    private external fun nativeGetInputLevelDb(): Float
    private external fun nativeGetOutputLevelDb(): Float
    private external fun nativeGetDetectedFrequency(): Float
    private external fun nativeLooperCommand(command: Int)
    private external fun nativeGetLooperState(): Int
    private external fun nativeGetLooperProgress(): Float
    private external fun nativeGetModelSampleRate(): Double
    private external fun nativeGetStreamSampleRate(): Int
    private external fun nativeSetAudioDeviceIds(inputDeviceId: Int, outputDeviceId: Int)
    private external fun nativeSetSharingMode(mode: Int)
}
