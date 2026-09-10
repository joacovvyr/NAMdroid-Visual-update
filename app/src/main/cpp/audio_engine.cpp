#include "audio_engine.h"

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <chrono>

#include "NAM/get_dsp.h"

#define TAG "NAMDroid/AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

float AudioEngine::dbToLinear(float db) { return std::pow(10.0f, db / 20.0f); }

AudioEngine::AudioEngine() {
    for (auto &enabled : mEffectEnabled) enabled.store(true);
    mEffectEnabled[2].store(false);
    mEffectEnabled[5].store(false);
    mEffectEnabled[6].store(false);
    mEffectEnabled[7].store(false);
    mEffectEnabled[8].store(false);
    mEffectEnabled[9].store(false);
    for (auto &amount : mEffectAmount) amount.store(0.5f);
    for (auto &effect : mEffectParams) for (auto &param : effect) param.store(0.0f);
    mEffectParams[1][0].store(-52.0f); mEffectParams[1][1].store(120.0f);
    mEffectParams[2][0].store(45.0f); mEffectParams[2][1].store(55.0f);
    mEffectParams[4][0].store(0.0f); mEffectParams[4][1].store(0.0f); mEffectParams[4][2].store(0.0f);
    mEffectParams[5][0].store(360.0f); mEffectParams[5][1].store(38.0f); mEffectParams[5][2].store(28.0f);
    mEffectParams[6][0].store(2.8f); mEffectParams[6][1].store(55.0f); mEffectParams[6][2].store(22.0f);
    mEffectParams[8][0].store(-18.0f); mEffectParams[8][1].store(4.0f); mEffectParams[8][2].store(3.0f);
    mEffectParams[9][0].store(1.2f); mEffectParams[9][1].store(45.0f); mEffectParams[9][2].store(30.0f);
    const int defaults[] = {8, 1, 2, 3, 7, 4, 9, 5, 6};
    for (int i = 0; i < 9; ++i) mEffectOrder[i].store(defaults[i]);
}

AudioEngine::~AudioEngine() { stop(); }

void AudioEngine::setEffectEnabled(int effectId, bool enabled) {
    if (effectId >= 1 && effectId <= 9) mEffectEnabled[effectId].store(enabled);
}

void AudioEngine::setEffectAmount(int effectId, float amount) {
    if (effectId >= 1 && effectId <= 9) {
        mEffectAmount[effectId].store(std::clamp(amount, 0.0f, 1.0f));
    }
}

void AudioEngine::setEffectOrder(const int *order, int count) {
    if (!order || count < 1 || count > 9) return;
    std::array<bool, 10> seen{};
    for (int i = 0; i < count; ++i) {
        if (order[i] < 1 || order[i] > 9 || seen[order[i]]) return;
        seen[order[i]] = true;
    }
    for (int i = 0; i < count; ++i) mEffectOrder[i].store(order[i]);
    mEffectCount.store(count);
}

void AudioEngine::setEffectParam(int effectId, int param, float value) {
    if (effectId >= 1 && effectId <= 9 && param >= 0 && param < 3) mEffectParams[effectId][param].store(value);
}

float AudioEngine::getLooperProgress() const {
    if (mLooperLength == 0) return 0.0f;
    return static_cast<float>(mLooperPosition) / static_cast<float>(mLooperLength);
}

void AudioEngine::looperCommand(int command) {
    if (command == 4) {
        mLooperState.store(0); mLooperPosition = 0; mLooperLength = 0;
        std::fill(mLooperBuffer.begin(), mLooperBuffer.end(), 0.0f);
    } else if (command == 1) {
        mLooperPosition = 0; mLooperLength = 0; mLooperState.store(1);
    } else if (command >= 0 && command <= 3) {
        mLooperPosition = 0; mLooperState.store(command);
    }
}

bool AudioEngine::loadIr(const std::string &wavPath, std::string &outError) {
    std::ifstream file(wavPath, std::ios::binary);
    if (!file) { outError = "No se pudo abrir el IR"; return false; }
    char riff[4], wave[4]; uint32_t riffSize = 0;
    file.read(riff, 4); file.read(reinterpret_cast<char *>(&riffSize), 4); file.read(wave, 4);
    if (std::strncmp(riff, "RIFF", 4) || std::strncmp(wave, "WAVE", 4)) { outError = "El IR debe ser WAV PCM o Float"; return false; }
    uint16_t format = 0, channels = 0, bits = 0; uint32_t dataSize = 0; std::streampos dataPos{}; bool hasData = false;
    while (file && !hasData) {
        char id[4]; uint32_t size = 0; file.read(id, 4); file.read(reinterpret_cast<char *>(&size), 4);
        if (!file) break;
        if (!std::strncmp(id, "fmt ", 4)) {
            file.read(reinterpret_cast<char *>(&format), 2); file.read(reinterpret_cast<char *>(&channels), 2);
            file.seekg(10, std::ios::cur); file.read(reinterpret_cast<char *>(&bits), 2);
            if (size > 16) file.seekg(size - 16, std::ios::cur);
        } else if (!std::strncmp(id, "data", 4)) { dataPos = file.tellg(); dataSize = size; hasData = true; file.seekg(size, std::ios::cur); }
        else file.seekg(size, std::ios::cur);
        if (size & 1) file.seekg(1, std::ios::cur);
    }
    if (!hasData || channels == 0 || (format != 1 && format != 3)) { outError = "Formato WAV no compatible"; return false; }
    file.clear(); file.seekg(dataPos);
    const size_t bytesPerSample = bits / 8; const size_t frames = dataSize / std::max<size_t>(bytesPerSample * channels, 1);
    // 512 taps keeps direct convolution predictable on mid-range Android CPUs.
    const size_t taps = std::min<size_t>(frames, 512); std::vector<float> ir(taps);
    for (size_t i = 0; i < taps; ++i) {
        float sum = 0.0f;
        for (uint16_t ch = 0; ch < channels; ++ch) {
            if (format == 3 && bits == 32) { float value; file.read(reinterpret_cast<char *>(&value), 4); sum += value; }
            else if (format == 1 && bits == 16) { int16_t value; file.read(reinterpret_cast<char *>(&value), 2); sum += value / 32768.0f; }
            else if (format == 1 && bits == 24) { unsigned char b[3]; file.read(reinterpret_cast<char *>(b), 3); int32_t value = b[0] | (b[1] << 8) | (b[2] << 16); if (value & 0x800000) value |= ~0xFFFFFF; sum += value / 8388608.0f; }
            else { outError = "Profundidad WAV no compatible"; return false; }
        }
        ir[i] = sum / channels;
    }
    float peak = 0.0f; for (float value : ir) peak = std::max(peak, std::abs(value));
    if (peak > 0.0f) for (float &value : ir) value *= 0.95f / peak;
    std::lock_guard<std::mutex> lock(mIrMutex); mIrCoefficients = std::move(ir); mIrHistory.assign(taps, 0.0f); mIrWriteIndex = 0;
    return true;
}

bool AudioEngine::start() {
    // Siempre partir de un estado limpio. Esto hace que los cambios de I/O
    // sean idempotentes aunque una apertura anterior haya fallado a medias.
    stop();

    auto openStreams = [&](oboe::SharingMode sharingMode) -> bool {
        oboe::AudioStreamBuilder outBuilder;
        outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(sharingMode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(48000)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        if (mOutputDeviceId.load() > 0) outBuilder.setDeviceId(mOutputDeviceId.load());

        oboe::Result result = outBuilder.openStream(mOutStream);
        if (result != oboe::Result::OK) {
            LOGE("Salida no disponible (%s): %s",
                 sharingMode == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
                 oboe::convertToText(result));
            mOutStream.reset();
            return false;
        }

        mSampleRate.store(mOutStream->getSampleRate());
        mOutChannelCount.store(mOutStream->getChannelCount());

        oboe::AudioStreamBuilder inBuilder;
        const int32_t requestedInputChannels = mInputChannelMode.load() == 0 ? 1 : 2;
        inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(sharingMode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(requestedInputChannels)
            ->setSampleRate(mOutStream->getSampleRate())
            ->setInputPreset(oboe::InputPreset::Unprocessed);
        if (mInputDeviceId.load() > 0) inBuilder.setDeviceId(mInputDeviceId.load());

        result = inBuilder.openStream(mInStream);
        if (result != oboe::Result::OK) {
            LOGE("Entrada no disponible (%s): %s",
                 sharingMode == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
                 oboe::convertToText(result));
            if (mOutStream) {
                mOutStream->close();
                mOutStream.reset();
            }
            mInStream.reset();
            return false;
        }

        mInChannelCount.store(mInStream->getChannelCount());
        mActualSharingMode.store(sharingMode == oboe::SharingMode::Exclusive ? 1 : 2);
        mBufferSizeFrames.store(mOutStream->getBufferSizeInFrames());
        auto xruns = mOutStream->getXRunCount();
        mXRunCount.store(xruns ? xruns.value() : 0);
        return true;
    };

    // El usuario puede elegir el modo. Auto prioriza la menor latencia y
    // conserva el fallback robusto a Shared.
    const int32_t requestedSharingMode = mSharingMode.load();
    bool opened = false;
    if (requestedSharingMode == 1) {
        LOGI("Sharing mode forzado: Exclusive");
        opened = openStreams(oboe::SharingMode::Exclusive);
    } else if (requestedSharingMode == 2) {
        LOGI("Sharing mode forzado: Shared");
        opened = openStreams(oboe::SharingMode::Shared);
    } else {
        LOGI("Sharing mode: Auto (Exclusive -> Shared)");
        opened = openStreams(oboe::SharingMode::Exclusive);
        if (!opened) {
            LOGI("Auto: reintentando ruta en Shared mode");
            stop();
            opened = openStreams(oboe::SharingMode::Shared);
        }
    }
    if (!opened) {
        stop();
        return false;
    }

    LOGI("Routing IN id=%d -> OUT id=%d | salida: %d Hz, %d canal(es) | entrada: %d Hz, %d canal(es)",
         mInputDeviceId.load(), mOutputDeviceId.load(),
         mOutStream->getSampleRate(), mOutChannelCount.load(),
         mInStream->getSampleRate(), mInChannelCount.load());

    if (mInStream->getSampleRate() != mOutStream->getSampleRate()) {
        LOGE("Sample rate de entrada (%d Hz) distinto al de salida (%d Hz)",
             mInStream->getSampleRate(), mOutStream->getSampleRate());
    }

    mInputBuffer.assign(kMaxBufferFrames, 0.0f);
    mDspInPtrStorage.assign(kMaxBufferFrames, 0.0f);
    mDspOutPtrStorage.assign(kMaxBufferFrames, 0.0f);
    mMonoResult.assign(kMaxBufferFrames, 0.0f);
    mInterleavedScratch.assign(
        kMaxBufferFrames * std::max({mInChannelCount.load(), mOutChannelCount.load(), 2}), 0.0f);
    mDelayBuffer.assign(std::max(mSampleRate.load() * 2, 1), 0.0f);
    mReverbBuffer.assign(std::max(mSampleRate.load() / 2, 1), 0.0f);
    mChorusBuffer.assign(std::max(mSampleRate.load() / 10, 1), 0.0f);
    mLooperBuffer.assign(std::max(mSampleRate.load() * 60, 1), 0.0f);
    mTunerBuffer.assign(4096, 0.0f);
    mDelayWriteIndex = 0;
    mReverbWriteIndex = 0;
    mChorusWriteIndex = 0;
    mLooperPosition = 0;
    mLooperLength = 0;
    mTunerWriteIndex = 0;
    mChorusPhase = 0.0f;
    mEqLowState = 0.0f;
    mSmoothedInputGain = mInputGainLinear.load();
    mSmoothedOutputGain = mOutputGainLinear.load();

    {
        std::lock_guard<std::mutex> lock(mModelMutex);
        if (mModel) mModel->Reset(mSampleRate.load(), kMaxBufferFrames);
    }

    const oboe::Result inStart = mInStream->requestStart();
    if (inStart != oboe::Result::OK) {
        LOGE("No se pudo arrancar entrada: %s", oboe::convertToText(inStart));
        stop();
        return false;
    }

    const oboe::Result outStart = mOutStream->requestStart();
    if (outStart != oboe::Result::OK) {
        LOGE("No se pudo arrancar salida: %s", oboe::convertToText(outStart));
        stop();
        return false;
    }

    LOGI("Motor de audio iniciado a %d Hz", mSampleRate.load());
    return true;
}

void AudioEngine::stop() {
    if (mOutStream) {
        mOutStream->requestStop();
        mOutStream->close();
        mOutStream.reset();
    }
    if (mInStream) {
        mInStream->requestStop();
        mInStream->close();
        mInStream.reset();
    }
}

bool AudioEngine::loadModel(const std::string &namFilePath, std::string &outError) {
    try {
        auto newModel = nam::get_dsp(std::filesystem::path(namFilePath));
        if (!newModel) {
            outError = "get_dsp() devolvio null (archivo invalido)";
            return false;
        }
        double sr = newModel->GetExpectedSampleRate();

        // CRITICO: Reset() es quien reserva/dimensiona los buffers internos
        // del modelo (p.ej. Conv1x1::_output en dsp.cpp, que tiene
        // "assert(num_frames <= _output.cols())"). Sin este llamado esos
        // buffers quedan en tamano 0, y el primer process() con audio real
        // escribe fuera de sus limites -> crash. Se hace ANTES de publicar
        // el puntero (todavia es local a este hilo), asi el hilo de audio
        // nunca ve un modelo sin inicializar.
        newModel->Reset(mSampleRate.load(), kMaxBufferFrames);

        {
            std::lock_guard<std::mutex> lock(mModelMutex);
            mModel = std::move(newModel);
        }
        mLastModelSampleRate.store(sr);
        LOGI("Modelo NAM cargado: %s (sample rate esperado: %.0f Hz)", namFilePath.c_str(), sr);
        return true;
    } catch (const std::exception &e) {
        outError = e.what();
        LOGE("Error cargando modelo NAM: %s", e.what());
        return false;
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *,
                                                    void *audioData, int32_t numFrames) {
    const auto callbackStarted = std::chrono::steady_clock::now();
    auto *output = static_cast<float *>(audioData);

    // Nunca alocar en el callback de audio: si por lo que sea nos piden mas
    // frames que los reservados en start(), recortamos en vez de hacer
    // new/resize aca (eso si que causa glitches/xruns).
    if (numFrames > kMaxBufferFrames) {
        LOGE("numFrames (%d) supera kMaxBufferFrames (%d), recortando",
             numFrames, kMaxBufferFrames);
    }
    const int32_t frames = std::min(numFrames, kMaxBufferFrames);

    const int32_t inChannels = std::max(mInChannelCount.load(), 1);
    const int32_t outChannels = std::max(mOutChannelCount.load(), 1);

    // Lectura de entrada. En interfaces multicanal el usuario puede conservar
    // el downmix o elegir explicitamente CH1/CH2 para no sumar ruido del otro jack.
    int32_t framesRead = 0;
    if (mInStream) {
        if (inChannels <= 1) {
            auto readResult = mInStream->read(mInputBuffer.data(), frames, 0);
            if (readResult) framesRead = readResult.value();
        } else {
            auto readResult = mInStream->read(mInterleavedScratch.data(), frames, 0);
            if (readResult) {
                framesRead = readResult.value();
                const int32_t channelMode = mInputChannelMode.load();
                for (int32_t i = 0; i < framesRead; ++i) {
                    if (channelMode > 0) {
                        const int32_t selected = std::min(channelMode - 1, inChannels - 1);
                        mInputBuffer[i] = mInterleavedScratch[i * inChannels + selected];
                    } else {
                        float sum = 0.0f;
                        for (int32_t ch = 0; ch < inChannels; ++ch) sum += mInterleavedScratch[i * inChannels + ch];
                        mInputBuffer[i] = sum / static_cast<float>(inChannels);
                    }
                }
            }
        }
    }
    // Si todavia no hay suficientes frames (arranque en frio), rellenamos con
    // silencio para no trabarnos.
    for (int32_t i = framesRead; i < frames; ++i) mInputBuffer[i] = 0.0f;

    const float inputTarget = mInputGainLinear.load();
    const float outputTarget = mOutputGainLinear.load();
    const float smoothing = 1.0f - std::exp(-1.0f / (0.010f * std::max(mSampleRate.load(), 1)));
    const bool bypass = mBypass.load();

    float inputSquares = 0.0f;
    for (int32_t i = 0; i < frames; ++i) {
        mSmoothedInputGain += (inputTarget - mSmoothedInputGain) * smoothing;
        mMonoResult[i] = mInputBuffer[i] * mSmoothedInputGain;
        inputSquares += mMonoResult[i] * mMonoResult[i];
        if (mTunerEnabled.load() && !mTunerBuffer.empty()) {
            mTunerBuffer[mTunerWriteIndex++] = mMonoResult[i];
            if (mTunerWriteIndex == mTunerBuffer.size()) {
                const int sampleRate = mSampleRate.load();
                const int minLag = std::max(sampleRate / 1200, 1);
                const int maxLag = std::min<int>(sampleRate / 55, mTunerBuffer.size() / 2);
                float best = 0.0f; int bestLag = 0;
                for (int lag = minLag; lag <= maxLag; ++lag) {
                    float corr = 0.0f;
                    for (size_t n = 0; n + lag < mTunerBuffer.size(); n += 2) corr += mTunerBuffer[n] * mTunerBuffer[n + lag];
                    if (corr > best) { best = corr; bestLag = lag; }
                }
                if (bestLag > 0 && inputSquares / std::max(frames, 1) > 1e-7f) {
                    const float measured = static_cast<float>(sampleRate) / bestLag;
                    const float previous = mDetectedFrequency.load();
                    mDetectedFrequency.store(previous > 0.0f ? previous * 0.7f + measured * 0.3f : measured);
                }
                mTunerWriteIndex = 0;
            }
        }
    }
    mInputLevelDb.store(20.0f * std::log10(std::max(std::sqrt(inputSquares / std::max(frames, 1)), 1e-5f)));

    for (int slot = 0; slot < mEffectCount.load(); ++slot) {
        const int effect = mEffectOrder[slot].load();
        if (!mEffectEnabled[effect].load()) continue;

        if (effect == 1) {
            const float threshold = dbToLinear(mEffectParams[1][0].load());
            const float release = std::max(mEffectParams[1][1].load(), 20.0f);
            const float releaseCoeff = std::exp(-1.0f / (0.001f * release * mSampleRate.load()));
            for (int32_t i = 0; i < frames; ++i) {
                const float level = std::abs(mMonoResult[i]);
                const float target = level >= threshold ? 1.0f : 0.0f;
                mGateEnvelope = target > mGateEnvelope ? target : mGateEnvelope * releaseCoeff;
                mMonoResult[i] *= mGateEnvelope;
            }
        } else if (effect == 2) {
            const float drive = 1.0f + std::clamp(mEffectParams[2][0].load() / 100.0f, 0.0f, 1.0f) * 24.0f;
            const float tone = std::clamp(mEffectParams[2][1].load() / 100.0f, 0.0f, 1.0f);
            const float level = dbToLinear(mEffectParams[2][2].load());
            const float norm = std::max(std::tanh(drive), 1e-4f);
            for (int32_t i = 0; i < frames; ++i) {
                const float clipped = std::tanh(mMonoResult[i] * drive) / norm;
                mMonoResult[i] = (clipped * (0.65f + tone * 0.35f) + mMonoResult[i] * (0.35f - tone * 0.25f)) * level;
            }
        } else if (effect == 3 && !bypass) {
            std::unique_lock<std::mutex> lock(mModelMutex, std::try_to_lock);
            if (lock.owns_lock() && mModel) {
                const float pre = dbToLinear(mEffectParams[3][0].load());
                for (int32_t i = 0; i < frames; ++i) mDspInPtrStorage[i] = mMonoResult[i] * pre;
                NAM_SAMPLE *inPtr = mDspInPtrStorage.data();
                NAM_SAMPLE *outPtr = mDspOutPtrStorage.data();
                mModel->process(&inPtr, &outPtr, frames);
                const float post = dbToLinear(mEffectParams[3][1].load());
                for (int32_t i = 0; i < frames; ++i) mMonoResult[i] = mDspOutPtrStorage[i] * post;
            }
        } else if (effect == 4) {
            const float lowGain = dbToLinear(mEffectParams[4][0].load());
            const float midGain = dbToLinear(mEffectParams[4][1].load());
            const float highGain = dbToLinear(mEffectParams[4][2].load());
            for (int32_t i = 0; i < frames; ++i) {
                mEqLowState += 0.025f * (mMonoResult[i] - mEqLowState);
                mEqHighState += 0.22f * (mMonoResult[i] - mEqHighState);
                const float low = mEqLowState, high = mMonoResult[i] - mEqHighState, mid = mMonoResult[i] - low - high;
                mMonoResult[i] = low * lowGain + mid * midGain + high * highGain;
            }
        } else if (effect == 5 && !mDelayBuffer.empty()) {
            const float timeMs = std::clamp(mEffectParams[5][0].load(), 40.0f, 1500.0f);
            const size_t delaySamples = std::min<size_t>(static_cast<size_t>(mSampleRate.load() * timeMs / 1000.0f), mDelayBuffer.size() - 1);
            const float feedback = std::clamp(mEffectParams[5][1].load() / 100.0f, 0.0f, 0.92f);
            const float mix = std::clamp(mEffectParams[5][2].load() / 100.0f, 0.0f, 1.0f);
            for (int32_t i = 0; i < frames; ++i) {
                const size_t read = (mDelayWriteIndex + mDelayBuffer.size() - delaySamples) % mDelayBuffer.size();
                const float wet = mDelayBuffer[read];
                const float dry = mMonoResult[i];
                mDelayBuffer[mDelayWriteIndex] = dry + wet * feedback;
                mMonoResult[i] = dry * (1.0f - mix) + wet * mix;
                mDelayWriteIndex = (mDelayWriteIndex + 1) % mDelayBuffer.size();
            }
        } else if (effect == 6 && !mReverbBuffer.empty()) {
            const float decay = std::clamp(mEffectParams[6][0].load(), 0.2f, 12.0f);
            const float feedback = std::clamp(0.35f + decay / 20.0f, 0.35f, 0.93f);
            const float tone = std::clamp(mEffectParams[6][1].load() / 100.0f, 0.0f, 1.0f);
            const float mix = std::clamp(mEffectParams[6][2].load() / 100.0f, 0.0f, 1.0f);
            for (int32_t i = 0; i < frames; ++i) {
                const float wet = mReverbBuffer[mReverbWriteIndex];
                const float dry = mMonoResult[i];
                mReverbBuffer[mReverbWriteIndex] = dry + wet * feedback * (0.7f + tone * 0.25f);
                mMonoResult[i] = dry * (1.0f - mix) + wet * mix;
                mReverbWriteIndex = (mReverbWriteIndex + 1) % mReverbBuffer.size();
            }
        } else if (effect == 7) {
            std::unique_lock<std::mutex> lock(mIrMutex, std::try_to_lock);
            if (lock.owns_lock() && !mIrCoefficients.empty()) {
                const float level = dbToLinear(mEffectParams[7][0].load());
                const float lowCut = std::clamp(mEffectParams[7][1].load(), 20.0f, 300.0f);
                const float highCut = std::clamp(mEffectParams[7][2].load(), 3000.0f, 20000.0f);
                const float hpAlpha = std::exp(-6.2831853f * lowCut / mSampleRate.load());
                const float lpAlpha = 1.0f - std::exp(-6.2831853f * highCut / mSampleRate.load());
                for (int32_t i = 0; i < frames; ++i) {
                    mIrHistory[mIrWriteIndex] = mMonoResult[i]; float sum = 0.0f; size_t history = mIrWriteIndex;
                    for (size_t tap = 0; tap < mIrCoefficients.size(); ++tap) { sum += mIrCoefficients[tap] * mIrHistory[history]; history = history == 0 ? mIrHistory.size() - 1 : history - 1; }
                    const float convolved = sum * level;
                    mIrLowpassState += lpAlpha * (convolved - mIrLowpassState);
                    mIrHighpassState = hpAlpha * (mIrHighpassState + mIrLowpassState - mIrPreviousInput);
                    mIrPreviousInput = mIrLowpassState;
                    mMonoResult[i] = mIrHighpassState; mIrWriteIndex = (mIrWriteIndex + 1) % mIrHistory.size();
                }
            }
        } else if (effect == 8) {
            const float thresholdDb = mEffectParams[8][0].load(), ratio = std::max(mEffectParams[8][1].load(), 1.0f), makeup = dbToLinear(mEffectParams[8][2].load());
            for (int32_t i = 0; i < frames; ++i) { const float x = mMonoResult[i]; const float db = 20.0f * std::log10(std::max(std::abs(x), 1e-6f)); const float reduction = db > thresholdDb ? (thresholdDb + (db - thresholdDb) / ratio) - db : 0.0f; mMonoResult[i] = x * dbToLinear(reduction) * makeup; }
        } else if (effect == 9 && !mChorusBuffer.empty()) {
            const float rate = std::clamp(mEffectParams[9][0].load(), 0.05f, 8.0f), depth = std::clamp(mEffectParams[9][1].load() / 100.0f, 0.0f, 1.0f), mix = std::clamp(mEffectParams[9][2].load() / 100.0f, 0.0f, 1.0f);
            for (int32_t i = 0; i < frames; ++i) { const float lfo = 0.5f + 0.5f * std::sin(mChorusPhase); const size_t delay = static_cast<size_t>((0.006f + lfo * 0.018f * depth) * mSampleRate.load()); const size_t read = (mChorusWriteIndex + mChorusBuffer.size() - std::min(delay, mChorusBuffer.size() - 1)) % mChorusBuffer.size(); const float dry = mMonoResult[i], wet = mChorusBuffer[read]; mChorusBuffer[mChorusWriteIndex] = dry; mMonoResult[i] = dry * (1.0f - mix) + wet * mix; mChorusWriteIndex = (mChorusWriteIndex + 1) % mChorusBuffer.size(); mChorusPhase += 6.2831853f * rate / mSampleRate.load(); if (mChorusPhase > 6.2831853f) mChorusPhase -= 6.2831853f; }
        }
    }
    for (int32_t i = 0; i < frames; ++i) {
        mSmoothedOutputGain += (outputTarget - mSmoothedOutputGain) * smoothing;
        mMonoResult[i] *= mSmoothedOutputGain;
    }

    const int looper = mLooperState.load();
    if (!mLooperBuffer.empty() && looper != 0) {
        for (int32_t i = 0; i < frames; ++i) {
            if (looper == 1) { if (mLooperPosition < mLooperBuffer.size()) { mLooperBuffer[mLooperPosition++] = mMonoResult[i]; mLooperLength = std::max(mLooperLength, mLooperPosition); } }
            else if (mLooperLength > 0) { const float loop = mLooperBuffer[mLooperPosition]; if (looper == 3) mLooperBuffer[mLooperPosition] = std::clamp(loop + mMonoResult[i] * 0.75f, -1.0f, 1.0f); mMonoResult[i] += loop * 0.8f; mLooperPosition = (mLooperPosition + 1) % mLooperLength; }
        }
        if (looper == 1 && mLooperPosition >= mLooperBuffer.size()) { mLooperPosition = 0; mLooperState.store(2); }
    }
    float outputSquares = 0.0f; for (int32_t i = 0; i < frames; ++i) { mMonoResult[i] = std::clamp(mMonoResult[i], -1.0f, 1.0f); outputSquares += mMonoResult[i] * mMonoResult[i]; }
    mOutputLevelDb.store(20.0f * std::log10(std::max(std::sqrt(outputSquares / std::max(frames, 1)), 1e-5f)));

    // --- Escritura de salida, duplicando a todos los canales si el
    // dispositivo no nos dio mono ---
    if (outChannels <= 1) {
        std::copy(mMonoResult.begin(), mMonoResult.begin() + frames, output);
    } else {
        for (int32_t i = 0; i < frames; ++i) {
            for (int32_t ch = 0; ch < outChannels; ++ch) {
                output[i * outChannels + ch] = mMonoResult[i];
            }
        }
    }
    // Si recortamos frames por encima, el resto del buffer de salida queda
    // en silencio en vez de con memoria sin inicializar.
    for (int32_t i = frames; i < numFrames; ++i) {
        for (int32_t ch = 0; ch < outChannels; ++ch) output[i * outChannels + ch] = 0.0f;
    }

    if (mOutStream) {
        auto xruns = mOutStream->getXRunCount();
        if (xruns) mXRunCount.store(xruns.value());
    }
    const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - callbackStarted).count();
    const double budget = static_cast<double>(std::max(frames, 1)) / std::max(mSampleRate.load(), 1);
    mLastLoadPercent.store(std::clamp(elapsed / budget * 100.0, 0.0, 999.0));
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    LOGE("Stream cerrado por error: %s. Reintentando arranque...", oboe::convertToText(error));
    stop();
    start();
}
