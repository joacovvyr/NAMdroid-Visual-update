#pragma once

#include <atomic>
#include <array>
#include <chrono>
#include <complex>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "NAM/dsp.h"

// Motor de audio full-duplex: toma la entrada (guitarra via interfaz de audio
// USB o el microfono), la pasa por el modelo NAM cargado, y la manda a la
// salida (auriculares/parlante), todo en tiempo real dentro del callback de
// audio de Oboe.
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool start();
    void stop();

    // Carga (o reemplaza en caliente) un modelo .nam desde una ruta absoluta.
    // Devuelve true si se cargo bien. Es seguro llamarlo mientras el motor
    // esta corriendo: el intercambio del puntero es atomico y sin locks en
    // el hilo de audio.
    bool loadModel(const std::string &namFilePath, std::string &outError);
    bool loadIr(const std::string &wavPath, std::string &outError);

    void setInputGainDb(float db) { mInputGainLinear.store(dbToLinear(db)); }
    void setOutputGainDb(float db) { mOutputGainLinear.store(dbToLinear(db)); }
    void setBypass(bool bypass) { mBypass.store(bypass); requestCrossfade(); }
    void setEffectEnabled(int effectId, bool enabled);
    void setEffectAmount(int effectId, float amount);
    void setEffectOrder(const int *order, int count);
    void setEffectParam(int effectId, int param, float value);
    void beginTransition() { requestCrossfade(); }
    void setTunerEnabled(bool enabled) { mTunerEnabled.store(enabled); }
    void setAudioDeviceIds(int32_t inputDeviceId, int32_t outputDeviceId) {
        mInputDeviceId.store(inputDeviceId);
        mOutputDeviceId.store(outputDeviceId);
    }
    // 0 = Auto (Exclusive con fallback a Shared), 1 = Exclusive, 2 = Shared.
    void setSharingMode(int32_t mode) { mSharingMode.store(mode < 0 ? 0 : (mode > 2 ? 2 : mode)); }
    // 0 = mezcla/mono automatico, 1 = canal 1, 2 = canal 2.
    void setInputChannelMode(int32_t mode) { mInputChannelMode.store(mode < 0 ? 0 : (mode > 2 ? 2 : mode)); }
    void looperCommand(int command);
    float getInputLevelDb() const { return mInputLevelDb.load(); }
    float getOutputLevelDb() const { return mOutputLevelDb.load(); }
    float getDetectedFrequency() const { return mDetectedFrequency.load(); }
    int getLooperState() const { return mLooperState.load(); }
    float getLooperProgress() const;

    double getLastModelSampleRate() const { return mLastModelSampleRate.load(); }
    int32_t getStreamSampleRate() const { return mSampleRate.load(); }
    double getLastCallbackLoadPercent() const { return mLastLoadPercent.load(); }
    int32_t getInputChannelCount() const { return mInChannelCount.load(); }
    int32_t getOutputChannelCount() const { return mOutChannelCount.load(); }
    int32_t getActualSharingMode() const { return mActualSharingMode.load(); }
    int32_t getBufferSizeFrames() const { return mBufferSizeFrames.load(); }
    int32_t getXRunCount() const { return mXRunCount.load(); }

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *outputStream, void *audioData,
                                           int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    static float dbToLinear(float db);

    // Tamano maximo de bloque que el motor va a pedirle al modelo NAM.
    // Se usa para reservar TODOS los buffers de una sola vez (nada de allocs
    // en el callback de audio) y para llamar a DSP::Reset(), que es quien
    // dimensiona los buffers internos del modelo (ver loadModel()).
    static constexpr int32_t kMaxBufferFrames = 4096;
    static constexpr size_t kTunerBufferFrames = 4096;
    static constexpr size_t kTransitionFrames = 256;

    void tunerWorkerLoop();
    void analyseTunerBuffer(const std::array<float, kTunerBufferFrames> &samples);
    void requestCrossfade() { mCrossfadeRequested.store(true, std::memory_order_release); }
    static void fft(std::vector<std::complex<float>> &data, bool inverse);
    void processIrPartition();

    std::shared_ptr<oboe::AudioStream> mOutStream;
    std::shared_ptr<oboe::AudioStream> mInStream;

    // El modelo se accede solo desde el hilo de audio salvo por este mutex
    // que protege el *reemplazo* del puntero (carga de un nuevo .nam).
    std::mutex mModelMutex;
    std::unique_ptr<nam::DSP> mModel;

    std::atomic<float> mInputGainLinear{1.0f};
    std::atomic<float> mOutputGainLinear{1.0f};
    std::atomic<bool> mBypass{false};
    // IDs: 1 gate, 2 drive, 3 NAM, 4 EQ, 5 delay, 6 reverb,
    // 7 IR cabinet, 8 compressor, 9 chorus.
    std::array<std::atomic<bool>, 10> mEffectEnabled{};
    std::array<std::atomic<float>, 10> mEffectAmount{};
    std::array<std::array<std::atomic<float>, 3>, 10> mEffectParams{};
    std::array<std::atomic<int>, 9> mEffectOrder{};
    std::atomic<int> mEffectCount{9};
    std::atomic<float> mInputLevelDb{-90.0f};
    std::atomic<float> mOutputLevelDb{-90.0f};
    std::atomic<bool> mTunerEnabled{false};
    std::atomic<float> mDetectedFrequency{0.0f};
    std::atomic<int> mLooperState{0}; // 0 stopped, 1 record, 2 play, 3 overdub
    std::atomic<int> mPendingLooperCommand{-1};
    std::atomic<size_t> mLooperPositionSnapshot{0};
    std::atomic<size_t> mLooperLengthSnapshot{0};
    std::atomic<int32_t> mSampleRate{48000};
    std::atomic<double> mLastModelSampleRate{-1.0};
    std::atomic<double> mLastLoadPercent{0.0};

    // Cantidad real de canales que nos dio el dispositivo en cada stream.
    // OJO: setChannelCount(Mono) al abrir el stream es solo un pedido; el
    // dispositivo (sobre todo interfaces USB) puede devolver estereo igual.
    // Hay que leer esto DESPUES de abrir el stream, nunca asumirlo.
    std::atomic<int32_t> mInChannelCount{1};
    std::atomic<int32_t> mOutChannelCount{1};
    // 0 = Android/Oboe elige el dispositivo. Un ID > 0 fuerza el dispositivo
    // enumerado por AudioManager en la capa Kotlin.
    std::atomic<int32_t> mInputDeviceId{0};
    std::atomic<int32_t> mOutputDeviceId{0};
    std::atomic<int32_t> mSharingMode{0};
    std::atomic<int32_t> mActualSharingMode{0};
    std::atomic<int32_t> mInputChannelMode{0};
    std::atomic<int32_t> mBufferSizeFrames{0};
    std::atomic<int32_t> mXRunCount{0};
    std::atomic<bool> mCrossfadeRequested{false};

    // Objetivos atomicos + valores suavizados usados solamente por audio.
    float mSmoothedInputGain{1.0f};
    float mSmoothedOutputGain{1.0f};
    std::array<float, kTransitionFrames> mTransitionTail{};
    size_t mTransitionTailWrite{0};
    size_t mCrossfadeRead{0};
    size_t mCrossfadeRemaining{0};

    // Buffers de trabajo reutilizados en el hilo de audio (nada de allocs ahi)
    std::vector<float> mInputBuffer;       // mono, tras downmix si hace falta
    std::vector<float> mDspInPtrStorage;
    std::vector<float> mDspOutPtrStorage;
    std::vector<float> mMonoResult;        // salida mono antes de "upmix"
    std::vector<float> mInterleavedScratch; // lectura/escritura cruda multicanal
    std::vector<float> mDelayBuffer;
    std::vector<float> mReverbBuffer;
    std::array<std::vector<float>, 4> mReverbCombs;
    std::array<std::vector<float>, 2> mReverbAllpasses;
    std::vector<float> mChorusBuffer;
    std::vector<float> mLooperBuffer;
    // Doble buffer SPSC para que la autocorrelacion nunca corra en el callback.
    // Estados: 0 libre, 1 escribiendo, 2 listo, 3 analizando.
    std::array<std::array<float, kTunerBufferFrames>, 2> mTunerBuffers{};
    std::array<std::atomic<int>, 2> mTunerBufferStates{{1, 0}};
    std::atomic<bool> mTunerWorkerRunning{true};
    std::atomic<bool> mRecoveryRequested{false};
    std::thread mTunerWorker;
    static constexpr size_t kIrPartitionFrames = 256;
    static constexpr size_t kIrFftFrames = kIrPartitionFrames * 2;
    std::vector<std::vector<std::complex<float>>> mIrPartitions;
    std::vector<std::vector<std::complex<float>>> mIrInputSpectra;
    std::vector<std::complex<float>> mIrFftBuffer;
    std::vector<float> mIrInputBlock;
    std::vector<float> mIrOutputBlock;
    std::vector<float> mIrOverlap;
    std::mutex mIrMutex;
    size_t mDelayWriteIndex{0};
    size_t mReverbWriteIndex{0};
    std::array<size_t, 4> mReverbCombIndices{};
    std::array<size_t, 2> mReverbAllpassIndices{};
    size_t mChorusWriteIndex{0};
    size_t mLooperPosition{0};
    size_t mLooperLength{0};
    size_t mTunerWriteIndex{0};
    int mTunerWriteBuffer{0};
    size_t mIrBlockIndex{0};
    size_t mIrSpectrumIndex{0};
    float mChorusPhase{0.0f};
    float mDrivePreviousInput{0.0f};
    float mDriveAntiAliasState{0.0f};
    float mDelaySmoothedSamples{17280.0f};
    float mDelayToneState{0.0f};
    float mEqLowState{0.0f};
    float mEqHighState{0.0f};
    float mGateEnvelope{0.0f};
    float mIrLowpassState{0.0f};
    float mIrHighpassState{0.0f};
    float mIrPreviousInput{0.0f};
};
