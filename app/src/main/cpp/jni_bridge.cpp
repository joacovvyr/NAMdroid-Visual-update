#include <jni.h>
#include <memory>
#include <string>

#include "audio_engine.h"

namespace {
std::unique_ptr<AudioEngine> gEngine;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeStart(JNIEnv *, jobject) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    return gEngine->start();
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeStop(JNIEnv *, jobject) {
    if (gEngine) gEngine->stop();
}

JNIEXPORT jstring JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeLoadModel(JNIEnv *env, jobject, jstring path) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    const char *cpath = env->GetStringUTFChars(path, nullptr);
    std::string pathStr(cpath);
    env->ReleaseStringUTFChars(path, cpath);

    std::string error;
    bool ok = gEngine->loadModel(pathStr, error);
    return env->NewStringUTF(ok ? "" : error.c_str());
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetInputGainDb(JNIEnv *, jobject, jfloat db) {
    if (gEngine) gEngine->setInputGainDb(db);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetOutputGainDb(JNIEnv *, jobject, jfloat db) {
    if (gEngine) gEngine->setOutputGainDb(db);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetBypass(JNIEnv *, jobject, jboolean bypass) {
    if (gEngine) gEngine->setBypass(bypass);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetEffectEnabled(JNIEnv *, jobject, jint effectId,
                                                              jboolean enabled) {
    if (gEngine) gEngine->setEffectEnabled(effectId, enabled);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetEffectAmount(JNIEnv *, jobject, jint effectId,
                                                             jfloat amount) {
    if (gEngine) gEngine->setEffectAmount(effectId, amount);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetEffectOrder(JNIEnv *env, jobject, jintArray order) {
    if (!gEngine || !order) return;
    const jsize count = env->GetArrayLength(order);
    jint *values = env->GetIntArrayElements(order, nullptr);
    gEngine->setEffectOrder(reinterpret_cast<int *>(values), count);
    env->ReleaseIntArrayElements(order, values, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetEffectParam(JNIEnv *, jobject, jint effectId,
                                                            jint param, jfloat value) {
    if (gEngine) gEngine->setEffectParam(effectId, param, value);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeBeginTransition(JNIEnv *, jobject) {
    if (gEngine) gEngine->beginTransition();
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetEffectChain(
        JNIEnv *env, jobject, jintArray types, jbooleanArray enabled, jfloatArray params) {
    if (!gEngine || !types || !enabled || !params) return;
    const jsize count = env->GetArrayLength(types);
    if (count < 0 || count > 16 || env->GetArrayLength(enabled) != count ||
        env->GetArrayLength(params) != count * 11) return;
    std::array<jint, 16> typeValues{};
    std::array<jboolean, 16> enabledValues{};
    std::array<jfloat, 176> paramValues{};
    std::array<bool, 16> enabledBools{};
    env->GetIntArrayRegion(types, 0, count, typeValues.data());
    env->GetBooleanArrayRegion(enabled, 0, count, enabledValues.data());
    env->GetFloatArrayRegion(params, 0, count * 11, paramValues.data());
    for (int i = 0; i < count; ++i) enabledBools[i] = enabledValues[i] == JNI_TRUE;
    gEngine->setEffectChain(reinterpret_cast<int *>(typeValues.data()),
                            enabledBools.data(), paramValues.data(), count);
}

JNIEXPORT jstring JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeLoadIr(JNIEnv *env, jobject, jstring path) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string error;
    const bool ok = gEngine->loadIr(chars, error);
    env->ReleaseStringUTFChars(path, chars);
    return env->NewStringUTF(ok ? "" : error.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeLoadMikuSamples(
        JNIEnv *env, jobject, jstring directory, jstring manifestPath) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    if (!directory || !manifestPath) return env->NewStringUTF("Rutas vocales ausentes");
    const char *dirChars = env->GetStringUTFChars(directory, nullptr);
    const char *manifestChars = env->GetStringUTFChars(manifestPath, nullptr);
    std::string error;
    const bool ok = gEngine->loadMikuSamples(dirChars, manifestChars, error);
    env->ReleaseStringUTFChars(directory, dirChars);
    env->ReleaseStringUTFChars(manifestPath, manifestChars);
    return env->NewStringUTF(ok ? "" : error.c_str());
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetTunerEnabled(JNIEnv *, jobject, jboolean enabled) {
    if (gEngine) gEngine->setTunerEnabled(enabled);
}

JNIEXPORT jfloat JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetInputLevelDb(JNIEnv *, jobject) {
    return gEngine ? gEngine->getInputLevelDb() : -90.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetOutputLevelDb(JNIEnv *, jobject) {
    return gEngine ? gEngine->getOutputLevelDb() : -90.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetDetectedFrequency(JNIEnv *, jobject) {
    return gEngine ? gEngine->getDetectedFrequency() : 0.0f;
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeLooperCommand(JNIEnv *, jobject, jint command) {
    if (gEngine) gEngine->looperCommand(command);
}

JNIEXPORT jint JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetLooperState(JNIEnv *, jobject) {
    return gEngine ? gEngine->getLooperState() : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetLooperProgress(JNIEnv *, jobject) {
    return gEngine ? gEngine->getLooperProgress() : 0.0f;
}

JNIEXPORT jdouble JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetModelSampleRate(JNIEnv *, jobject) {
    return gEngine ? gEngine->getLastModelSampleRate() : -1.0;
}

JNIEXPORT jint JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetStreamSampleRate(JNIEnv *, jobject) {
    return gEngine ? gEngine->getStreamSampleRate() : 0;
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetAudioDeviceIds(JNIEnv *, jobject, jint inputDeviceId, jint outputDeviceId) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    gEngine->setAudioDeviceIds(inputDeviceId, outputDeviceId);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetSharingMode(JNIEnv *, jobject, jint mode) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    gEngine->setSharingMode(mode);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetInputChannelMode(JNIEnv *, jobject, jint mode) {
    if (!gEngine) gEngine = std::make_unique<AudioEngine>();
    gEngine->setInputChannelMode(mode);
}

JNIEXPORT jint JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetInputChannelCount(JNIEnv *, jobject) { return gEngine ? gEngine->getInputChannelCount() : 0; }
JNIEXPORT jint JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetOutputChannelCount(JNIEnv *, jobject) { return gEngine ? gEngine->getOutputChannelCount() : 0; }
JNIEXPORT jint JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetActualSharingMode(JNIEnv *, jobject) { return gEngine ? gEngine->getActualSharingMode() : 0; }
JNIEXPORT jint JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetBufferSizeFrames(JNIEnv *, jobject) { return gEngine ? gEngine->getBufferSizeFrames() : 0; }
JNIEXPORT jint JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetXRunCount(JNIEnv *, jobject) { return gEngine ? gEngine->getXRunCount() : 0; }
JNIEXPORT jint JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetOutputXRunCount(JNIEnv *, jobject) { return gEngine ? gEngine->getOutputXRunCount() : 0; }
JNIEXPORT jint JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetInputUnderflowCount(JNIEnv *, jobject) { return gEngine ? static_cast<jint>(gEngine->getInputUnderflowCount()) : 0; }
JNIEXPORT jdouble JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetCallbackLoadPercent(JNIEnv *, jobject) { return gEngine ? gEngine->getLastCallbackLoadPercent() : 0.0; }
JNIEXPORT jdouble JNICALL Java_com_namdroid_app_audio_NamEngine_nativeGetNamPeakLoadPercent(JNIEnv *, jobject) { return gEngine ? gEngine->getNamPeakLoadPercent() : 0.0; }

JNIEXPORT jboolean JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeStartStudioRecording(JNIEnv *env, jobject, jstring path) {
    if (!gEngine || !path) return JNI_FALSE;
    const char *chars = env->GetStringUTFChars(path, nullptr);
    const bool started = gEngine->startStudioRecording(chars);
    env->ReleaseStringUTFChars(path, chars);
    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeStopStudioRecording(JNIEnv *, jobject) {
    if (gEngine) gEngine->stopStudioRecording();
}

JNIEXPORT jstring JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeLoadStudioTrack(
        JNIEnv *env, jobject, jint slot, jstring path) {
    if (!gEngine || !path) return env->NewStringUTF("Motor de audio no disponible");
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string error;
    const bool loaded = gEngine->loadStudioTrack(slot, chars, error);
    env->ReleaseStringUTFChars(path, chars);
    return env->NewStringUTF(loaded ? "" : error.c_str());
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeClearStudioTrack(JNIEnv *, jobject, jint slot) {
    if (gEngine) gEngine->clearStudioTrack(slot);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetStudioTrackMix(
        JNIEnv *, jobject, jint slot, jfloat volume, jboolean muted) {
    if (gEngine) gEngine->setStudioTrackMix(slot, volume, muted);
}

JNIEXPORT void JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeSetStudioTransport(
        JNIEnv *, jobject, jboolean playing, jfloat bpm, jboolean metronome) {
    if (gEngine) gEngine->setStudioTransport(playing, bpm, metronome);
}

JNIEXPORT jboolean JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeIsStudioRecording(JNIEnv *, jobject) {
    return gEngine && gEngine->isStudioRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetStudioPositionFrames(JNIEnv *, jobject) {
    return gEngine ? static_cast<jlong>(gEngine->getStudioPositionFrames()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_namdroid_app_audio_NamEngine_nativeGetStudioDroppedFrames(JNIEnv *, jobject) {
    return gEngine ? static_cast<jint>(gEngine->getStudioDroppedFrames()) : 0;
}

} // extern "C"
