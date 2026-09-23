#include "audio_engine.h"

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <chrono>
#include <thread>

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
    mTunerWorker = std::thread(&AudioEngine::tunerWorkerLoop, this);
}

AudioEngine::~AudioEngine() {
    mTunerWorkerRunning.store(false, std::memory_order_release);
    if (mTunerWorker.joinable()) mTunerWorker.join();
    stop();
}

void AudioEngine::tunerWorkerLoop() {
    while (mTunerWorkerRunning.load(std::memory_order_acquire)) {
        if (mRecoveryRequested.exchange(false, std::memory_order_acq_rel)) {
            LOGI("Recuperando streams fuera del callback de error");
            if (!start()) {
                // El ID USB puede haber dejado de existir. Volver a la ruta
                // automatica mantiene la app con audio hasta que se reconecte.
                mInputDeviceId.store(0);
                mOutputDeviceId.store(0);
                start();
            }
        }
        bool analysed = false;
        for (int slot = 0; slot < 2; ++slot) {
            int expected = 2;
            if (mTunerBufferStates[slot].compare_exchange_strong(
                    expected, 3, std::memory_order_acq_rel)) {
                analyseTunerBuffer(mTunerBuffers[slot]);
                mTunerBufferStates[slot].store(0, std::memory_order_release);
                analysed = true;
            }
        }
        if (!analysed) std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
}

void AudioEngine::analyseTunerBuffer(
        const std::array<float, kTunerBufferFrames> &samples) {
    if (!mTunerEnabled.load(std::memory_order_relaxed)) return;
    const int sampleRate = std::max(mSampleRate.load(), 1);
    const int minLag = std::max(sampleRate / 1200, 1);
    const int maxLag = std::min<int>(sampleRate / 55, samples.size() / 2);
    float energy = 0.0f;
    for (float sample : samples) energy += sample * sample;
    if (energy / samples.size() <= 1e-7f) {
        mDetectedFrequency.store(0.0f);
        return;
    }
    float best = 0.0f;
    int bestLag = 0;
    for (int lag = minLag; lag <= maxLag; ++lag) {
        float correlation = 0.0f;
        for (size_t n = 0; n + lag < samples.size(); n += 2) {
            correlation += samples[n] * samples[n + lag];
        }
        if (correlation > best) {
            best = correlation;
            bestLag = lag;
        }
    }
    if (bestLag > 0) {
        const float measured = static_cast<float>(sampleRate) / bestLag;
        const float previous = mDetectedFrequency.load();
        mDetectedFrequency.store(previous > 0.0f
            ? previous * 0.7f + measured * 0.3f
            : measured);
    }
}

void AudioEngine::setEffectEnabled(int effectId, bool enabled) {
    if (effectId >= 1 && effectId <= 14) {
        const bool changed = mEffectEnabled[effectId].exchange(enabled) != enabled;
        if (changed) requestCrossfade();
    }
}

void AudioEngine::setEffectAmount(int effectId, float amount) {
    if (effectId >= 1 && effectId <= 14) {
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
    if (effectId >= 1 && effectId <= 14 && param >= 0 && param < 3) mEffectParams[effectId][param].store(value);
}

void AudioEngine::setEffectChain(const int *types, const bool *enabled,
                                 const float *params, int count) {
    if (!types || !enabled || !params || count < 0 || count > kMaxEffectSlots) return;
    bool structuralChange = mEffectSlotCount.load(std::memory_order_acquire) != count;
    for (int slot = 0; slot < count; ++slot) {
        auto &target = mEffectSlots[slot];
        const int newType = std::clamp(types[slot], 1, 14);
        structuralChange = structuralChange || target.type.load() != newType ||
            target.enabled.load() != enabled[slot];
        target.type.store(newType, std::memory_order_relaxed);
        target.enabled.store(enabled[slot], std::memory_order_relaxed);
        for (int param = 0; param < kMaxEffectParams; ++param) {
            target.params[param].store(params[slot * kMaxEffectParams + param], std::memory_order_relaxed);
        }
    }
    for (int slot = count; slot < kMaxEffectSlots; ++slot) {
        mEffectSlots[slot].enabled.store(false, std::memory_order_relaxed);
    }
    mEffectSlotCount.store(count, std::memory_order_release);
    if (structuralChange) requestCrossfade();
}

float AudioEngine::getLooperProgress() const {
    const size_t length = mLooperLengthSnapshot.load(std::memory_order_acquire);
    if (length == 0) return 0.0f;
    return static_cast<float>(mLooperPositionSnapshot.load(std::memory_order_acquire)) /
        static_cast<float>(length);
}

void AudioEngine::looperCommand(int command) {
    if (command >= 0 && command <= 4) {
        mPendingLooperCommand.store(command, std::memory_order_release);
    }
}

bool AudioEngine::loadIr(const std::string &wavPath, std::string &outError) {
    std::ifstream file(wavPath, std::ios::binary);
    if (!file) { outError = "No se pudo abrir el IR"; return false; }
    char riff[4], wave[4]; uint32_t riffSize = 0;
    file.read(riff, 4); file.read(reinterpret_cast<char *>(&riffSize), 4); file.read(wave, 4);
    if (std::strncmp(riff, "RIFF", 4) || std::strncmp(wave, "WAVE", 4)) { outError = "El IR debe ser WAV PCM o Float"; return false; }
    uint16_t format = 0, channels = 0, bits = 0; uint32_t wavSampleRate = 0, dataSize = 0; std::streampos dataPos{}; bool hasData = false;
    while (file && !hasData) {
        char id[4]; uint32_t size = 0; file.read(id, 4); file.read(reinterpret_cast<char *>(&size), 4);
        if (!file) break;
        if (!std::strncmp(id, "fmt ", 4)) {
            file.read(reinterpret_cast<char *>(&format), 2); file.read(reinterpret_cast<char *>(&channels), 2);
            file.read(reinterpret_cast<char *>(&wavSampleRate), 4);
            file.seekg(6, std::ios::cur); file.read(reinterpret_cast<char *>(&bits), 2);
            if (size > 16) file.seekg(size - 16, std::ios::cur);
        } else if (!std::strncmp(id, "data", 4)) { dataPos = file.tellg(); dataSize = size; hasData = true; file.seekg(size, std::ios::cur); }
        else file.seekg(size, std::ios::cur);
        if (size & 1) file.seekg(1, std::ios::cur);
    }
    if (!hasData || channels == 0 || (format != 1 && format != 3)) { outError = "Formato WAV no compatible"; return false; }
    file.clear(); file.seekg(dataPos);
    const size_t bytesPerSample = bits / 8; const size_t frames = dataSize / std::max<size_t>(bytesPerSample * channels, 1);
    // Un segundo cubre ampliamente cabinets habituales y mantiene una carga
    // predecible en telefonos; no se pretende usar este bloque como reverb IR.
    const size_t sourceFrames = std::min<size_t>(frames, std::max<uint32_t>(wavSampleRate, 1));
    if (sourceFrames == 0) { outError = "El IR no contiene audio"; return false; }
    std::vector<float> source(sourceFrames);
    for (size_t i = 0; i < sourceFrames; ++i) {
        float sum = 0.0f;
        for (uint16_t ch = 0; ch < channels; ++ch) {
            if (format == 3 && bits == 32) { float value; file.read(reinterpret_cast<char *>(&value), 4); sum += value; }
            else if (format == 1 && bits == 16) { int16_t value; file.read(reinterpret_cast<char *>(&value), 2); sum += value / 32768.0f; }
            else if (format == 1 && bits == 24) { unsigned char b[3]; file.read(reinterpret_cast<char *>(b), 3); int32_t value = b[0] | (b[1] << 8) | (b[2] << 16); if (value & 0x800000) value |= ~0xFFFFFF; sum += value / 8388608.0f; }
            else { outError = "Profundidad WAV no compatible"; return false; }
        }
        source[i] = sum / channels;
    }
    if (wavSampleRate == 0) { outError = "El IR no declara frecuencia de muestreo"; return false; }
    const uint32_t targetRate = std::max(mSampleRate.load(), 1);
    const size_t targetFrames = std::max<size_t>(1, static_cast<size_t>(source.size() * static_cast<double>(targetRate) / wavSampleRate));
    std::vector<float> ir(targetFrames);
    for (size_t i = 0; i < targetFrames; ++i) {
        const double sourcePosition = i * static_cast<double>(wavSampleRate) / targetRate;
        const size_t left = std::min(static_cast<size_t>(sourcePosition), source.size() - 1);
        const size_t right = std::min(left + 1, source.size() - 1);
        const float fraction = static_cast<float>(sourcePosition - left);
        ir[i] = source[left] * (1.0f - fraction) + source[right] * fraction;
    }
    float peak = 0.0f; for (float value : ir) peak = std::max(peak, std::abs(value));
    if (peak > 1.0f) for (float &value : ir) value /= peak;

    const size_t partitionCount = (ir.size() + kIrPartitionFrames - 1) / kIrPartitionFrames;
    std::vector<std::vector<std::complex<float>>> partitions(
        partitionCount, std::vector<std::complex<float>>(kIrFftFrames));
    for (size_t partition = 0; partition < partitionCount; ++partition) {
        for (size_t i = 0; i < kIrPartitionFrames; ++i) {
            const size_t sourceIndex = partition * kIrPartitionFrames + i;
            if (sourceIndex < ir.size()) partitions[partition][i] = ir[sourceIndex];
        }
        fft(partitions[partition], false);
    }
    std::lock_guard<std::mutex> lock(mIrMutex);
    mIrPartitions = std::move(partitions);
    mIrInputSpectra.assign(partitionCount, std::vector<std::complex<float>>(kIrFftFrames));
    mIrFftBuffer.assign(kIrFftFrames, {});
    mIrInputBlock.assign(kIrPartitionFrames, 0.0f);
    mIrOutputBlock.assign(kIrPartitionFrames, 0.0f);
    mIrOverlap.assign(kIrPartitionFrames, 0.0f);
    mIrBlockIndex = 0; mIrSpectrumIndex = 0;
    requestCrossfade();
    return true;
}

void AudioEngine::fft(std::vector<std::complex<float>> &data, bool inverse) {
    const size_t count = data.size();
    for (size_t i = 1, j = 0; i < count; ++i) {
        size_t bit = count >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(data[i], data[j]);
    }
    for (size_t length = 2; length <= count; length <<= 1) {
        const float angle = (inverse ? 2.0f : -2.0f) * 3.14159265358979323846f / length;
        const std::complex<float> step(std::cos(angle), std::sin(angle));
        for (size_t offset = 0; offset < count; offset += length) {
            std::complex<float> weight(1.0f, 0.0f);
            for (size_t i = 0; i < length / 2; ++i) {
                const auto even = data[offset + i];
                const auto odd = data[offset + i + length / 2] * weight;
                data[offset + i] = even + odd;
                data[offset + i + length / 2] = even - odd;
                weight *= step;
            }
        }
    }
    if (inverse) for (auto &value : data) value /= static_cast<float>(count);
}

void AudioEngine::processIrPartition() {
    if (mIrPartitions.empty()) return;
    std::fill(mIrFftBuffer.begin(), mIrFftBuffer.end(), std::complex<float>{});
    for (size_t i = 0; i < kIrPartitionFrames; ++i) mIrFftBuffer[i] = mIrInputBlock[i];
    fft(mIrFftBuffer, false);
    mIrInputSpectra[mIrSpectrumIndex] = mIrFftBuffer;
    std::fill(mIrFftBuffer.begin(), mIrFftBuffer.end(), std::complex<float>{});
    for (size_t partition = 0; partition < mIrPartitions.size(); ++partition) {
        const size_t inputIndex = (mIrSpectrumIndex + mIrPartitions.size() - partition) % mIrPartitions.size();
        for (size_t bin = 0; bin < kIrFftFrames; ++bin) {
            mIrFftBuffer[bin] += mIrInputSpectra[inputIndex][bin] * mIrPartitions[partition][bin];
        }
    }
    fft(mIrFftBuffer, true);
    for (size_t i = 0; i < kIrPartitionFrames; ++i) {
        mIrOutputBlock[i] = mIrFftBuffer[i].real() + mIrOverlap[i];
        mIrOverlap[i] = mIrFftBuffer[i + kIrPartitionFrames].real();
    }
    mIrSpectrumIndex = (mIrSpectrumIndex + 1) % mIrPartitions.size();
}

bool AudioEngine::start() {
    // Siempre partir de un estado limpio. Esto hace que los cambios de I/O
    // sean idempotentes aunque una apertura anterior haya fallado a medias.
    stop();

    auto openStreams = [&](oboe::SharingMode sharingMode) -> bool {
        auto openOutput = [&](int32_t sampleRate, int32_t channels) {
            mOutStream.reset();
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(sharingMode)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(channels)
                ->setDataCallback(this)
                ->setErrorCallback(this);
            if (sampleRate > 0) builder.setSampleRate(sampleRate);
            if (mOutputDeviceId.load() > 0) builder.setDeviceId(mOutputDeviceId.load());
            return builder.openStream(mOutStream);
        };
        // Preferencia profesional: 48 kHz mono. Si el dispositivo la rechaza,
        // negociar su frecuencia nativa y finalmente una salida estereo fija.
        oboe::Result result = openOutput(48000, 1);
        if (result != oboe::Result::OK) result = openOutput(0, 1);
        if (result != oboe::Result::OK) result = openOutput(0, 2);
        if (result != oboe::Result::OK) {
            LOGE("Salida no disponible (%s): %s",
                 sharingMode == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
                 oboe::convertToText(result));
            mOutStream.reset();
            return false;
        }

        mSampleRate.store(mOutStream->getSampleRate());
        mOutChannelCount.store(mOutStream->getChannelCount());

        const int32_t requestedInputChannels = mInputChannelMode.load() == 0 ? 1 : 2;
        auto openInput = [&](int32_t channels) {
            mInStream.reset();
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(sharingMode)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(channels)
                ->setInputPreset(oboe::InputPreset::Unprocessed)
                ->setDataCallback(this)
                ->setErrorCallback(this);
            builder.setSampleRate(mOutStream->getSampleRate());
            if (mInputDeviceId.load() > 0) builder.setDeviceId(mInputDeviceId.load());
            return builder.openStream(mInStream);
        };
        result = openInput(requestedInputChannels);
        if (result != oboe::Result::OK && requestedInputChannels > 1) {
            LOGI("Entrada estereo rechazada; fallback automatico a mono");
            result = openInput(1);
        }
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
    mInputRing.fill(0.0f);
    mInputRingRead.store(0, std::memory_order_relaxed);
    mInputRingWrite.store(0, std::memory_order_relaxed);
    mInputUnderflowCount.store(0, std::memory_order_relaxed);
    mLastInputSample = 0.0f;
    mInputRecoveryGain = 0.0f;
    mCallbackCounter = 0;
    mGainSmoothingCoefficient = 1.0f - std::exp(
        -1.0f / (0.010f * std::max(mSampleRate.load(), 1)));
    const int32_t framesPerBurst = std::max(mOutStream->getFramesPerBurst(), 64);
    mInputTargetFrames.store(framesPerBurst, std::memory_order_relaxed);
    // Dos bursts absorben jitter del scheduler sin convertir la ruta en alta latencia.
    auto tunedBuffer = mOutStream->setBufferSizeInFrames(framesPerBurst * 2);
    if (tunedBuffer) mBufferSizeFrames.store(tunedBuffer.value());
    mDelayBuffer.assign(std::max(mSampleRate.load() * 2, 1), 0.0f);
    mReverbBuffer.assign(std::max(mSampleRate.load() / 2, 1), 0.0f);
    const std::array<float, 4> combTimes{0.0297f, 0.0371f, 0.0411f, 0.0437f};
    const std::array<float, 2> allpassTimes{0.0050f, 0.0017f};
    for (size_t i = 0; i < mReverbCombs.size(); ++i) {
        mReverbCombs[i].assign(std::max<int>(mSampleRate.load() * combTimes[i], 1), 0.0f);
        mReverbCombIndices[i] = 0;
    }
    for (size_t i = 0; i < mReverbAllpasses.size(); ++i) {
        mReverbAllpasses[i].assign(std::max<int>(mSampleRate.load() * allpassTimes[i], 1), 0.0f);
        mReverbAllpassIndices[i] = 0;
    }
    for (auto &slot : mEffectSlots) {
        slot.drivePreviousInput = 0.0f; slot.driveAntiAliasState = 0.0f;
        slot.driveLowCutState = 0.0f; slot.driveLowCutPrevious = 0.0f;
        slot.driveToneState = 0.0f;
        slot.ampEqState = {}; slot.ampLowCutState = 0.0f;
        slot.ampHighCutState = 0.0f; slot.ampLowCutPrevious = 0.0f;
        slot.gateEnvelope = 0.0f; slot.gateGain = 0.0f;
        slot.gateHoldFrames = 0; slot.gateOpen = false;
        slot.compressorEnvelope = 0.0f;
        slot.compressorGain = 1.0f; slot.eqLowState = 0.0f; slot.eqHighState = 0.0f;
        slot.pedalEqState = {};
        slot.delaySmoothedSamples = mSampleRate.load() * 0.36f;
        slot.delayToneState = 0.0f; slot.delayFilterState = {};
        slot.delayModPhase = 0.0f; slot.delayWriteIndex = 0;
        slot.delayBuffer.assign(std::max(mSampleRate.load() * 2, 1), 0.0f);
        for (size_t i = 0; i < slot.reverbCombs.size(); ++i) {
            slot.reverbCombs[i].assign(std::max<int>(mSampleRate.load() * combTimes[i], 1), 0.0f);
            slot.reverbCombIndices[i] = 0;
        }
        for (size_t i = 0; i < slot.reverbAllpasses.size(); ++i) {
            slot.reverbAllpasses[i].assign(std::max<int>(mSampleRate.load() * allpassTimes[i], 1), 0.0f);
            slot.reverbAllpassIndices[i] = 0;
        }
        slot.chorusBuffer.assign(std::max(mSampleRate.load() / 10, 1), 0.0f);
        slot.chorusWriteIndex = 0; slot.chorusPhase = 0.0f; slot.chorusToneState = 0.0f;
        slot.wahState = {}; slot.autoWahEnvelope = 0.0f; slot.tremoloPhase = 0.0f;
        slot.pitchBuffer.assign(std::max(mSampleRate.load() / 5, 1), 0.0f);
        slot.pitchWriteIndex = 0; slot.pitchPhase = 0.0f; slot.pitchToneState = 0.0f;
    }
    mChorusBuffer.assign(std::max(mSampleRate.load() / 10, 1), 0.0f);
    mPitchBuffer.assign(std::max(mSampleRate.load() / 5, 1), 0.0f);
    mLooperBuffer.assign(std::max(mSampleRate.load() * 60, 1), 0.0f);
    mDelayWriteIndex = 0;
    mReverbWriteIndex = 0;
    mChorusWriteIndex = 0;
    mLooperPosition = 0;
    mLooperLength = 0;
    mTunerWriteIndex = 0;
    mChorusPhase = 0.0f;
    mChorusToneState = 0.0f;
    mWahState = {}; mAutoWahEnvelope = 0.0f; mTremoloPhase = 0.0f;
    mPitchWriteIndex = 0; mPitchPhase = 0.0f; mPitchToneState = 0.0f;
    mDrivePreviousInput = 0.0f;
    mDriveAntiAliasState = 0.0f;
    mDriveLowCutState = 0.0f;
    mDriveLowCutPrevious = 0.0f;
    mDriveToneState = 0.0f;
    mDelaySmoothedSamples = mSampleRate.load() * 0.36f;
    mDelayToneState = 0.0f;
    mDelayFilterState = {};
    mDelayModPhase = 0.0f;
    mEqLowState = 0.0f;
    mEqHighState = 0.0f;
    mCompressorEnvelope = 0.0f;
    mCompressorGain = 1.0f;
    mPedalEqState = {};
    mGateEnvelope = 0.0f;
    mGateGain = 0.0f;
    mGateHoldFrames = 0;
    mGateOpen = false;
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

    // Cebar como maximo un burst antes de iniciar la salida evita el hueco
    // inicial sin sumar buffers permanentes.
    for (int attempt = 0; attempt < 12; ++attempt) {
        const uint64_t available =
            mInputRingWrite.load(std::memory_order_acquire) -
            mInputRingRead.load(std::memory_order_relaxed);
        if (available >= static_cast<uint64_t>(mInputTargetFrames.load())) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
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
        requestCrossfade();
        LOGI("Modelo NAM cargado: %s (sample rate esperado: %.0f Hz)", namFilePath.c_str(), sr);
        return true;
    } catch (const std::exception &e) {
        outError = e.what();
        LOGE("Error cargando modelo NAM: %s", e.what());
        return false;
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *stream,
                                                    void *audioData, int32_t numFrames) {
    // La entrada publica muestras mono en un FIFO SPSC: no bloquea, no reserva
    // memoria y no ejecuta DSP en su callback de tiempo real.
    if (stream && stream->getDirection() == oboe::Direction::Input) {
        const auto *input = static_cast<const float *>(audioData);
        const int32_t channels = std::max(stream->getChannelCount(), 1);
        const int32_t channelMode = mInputChannelMode.load(std::memory_order_relaxed);
        uint64_t write = mInputRingWrite.load(std::memory_order_relaxed);
        const uint64_t read = mInputRingRead.load(std::memory_order_acquire);
        const uint64_t capacity = kInputRingFrames - 1;
        for (int32_t frame = 0; frame < numFrames && write - read < capacity; ++frame) {
            float sample = 0.0f;
            if (channels == 1) {
                sample = input[frame];
            } else if (channelMode > 0) {
                const int32_t selected = std::min(channelMode - 1, channels - 1);
                sample = input[frame * channels + selected];
            } else {
                for (int32_t channel = 0; channel < channels; ++channel) {
                    sample += input[frame * channels + channel];
                }
                sample /= static_cast<float>(channels);
            }
            mInputRing[write & kInputRingMask] = sample;
            ++write;
        }
        mInputRingWrite.store(write, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

    const bool sampleTelemetry = (++mCallbackCounter & 0x0F) == 0;
    const auto callbackStarted = sampleTelemetry
        ? std::chrono::steady_clock::now()
        : std::chrono::steady_clock::time_point{};
    auto *output = static_cast<float *>(audioData);

    // Nunca alocar en el callback de audio: si por lo que sea nos piden mas
    // frames que los reservados en start(), recortamos en vez de hacer
    // new/resize aca (eso si que causa glitches/xruns).
    if (numFrames > kMaxBufferFrames) {
        LOGE("numFrames (%d) supera kMaxBufferFrames (%d), recortando",
             numFrames, kMaxBufferFrames);
    }
    const int32_t frames = std::min(numFrames, kMaxBufferFrames);
    const int32_t outChannels = std::max(mOutChannelCount.load(), 1);

    // Consumir la entrada preparada por el callback productor.
    uint64_t read = mInputRingRead.load(std::memory_order_relaxed);
    const uint64_t write = mInputRingWrite.load(std::memory_order_acquire);
    uint64_t available = write - read;
    const uint64_t target = static_cast<uint64_t>(
        std::max(mInputTargetFrames.load(std::memory_order_relaxed), 1));

    // Si los relojes de entrada y salida derivan, descartar solamente el exceso
    // mantiene la latencia acotada. La rampa evita un salto audible.
    if (available > target * 5) {
        const uint64_t excess = available - target * 2;
        read += excess;
        available -= excess;
        mInputRecoveryGain = 0.0f;
    }

    const int32_t framesRead = static_cast<int32_t>(
        std::min<uint64_t>(available, static_cast<uint64_t>(frames)));
    for (int32_t i = 0; i < framesRead; ++i) {
        const float sample = mInputRing[read & kInputRingMask];
        ++read;
        mInputRecoveryGain = std::min(1.0f, mInputRecoveryGain + 1.0f / 48.0f);
        mLastInputSample = sample;
        mInputBuffer[i] = sample * mInputRecoveryGain;
    }
    if (framesRead < frames) {
        mInputUnderflowCount.fetch_add(1, std::memory_order_relaxed);
        for (int32_t i = framesRead; i < frames; ++i) {
            mInputRecoveryGain = std::max(0.0f, mInputRecoveryGain - 1.0f / 32.0f);
            mInputBuffer[i] = mLastInputSample * mInputRecoveryGain;
        }
        if (mInputRecoveryGain <= 0.0f) mLastInputSample = 0.0f;
    }
    mInputRingRead.store(read, std::memory_order_release);

    const float inputTarget = mInputGainLinear.load();
    const float outputTarget = mOutputGainLinear.load();
    const float smoothing = mGainSmoothingCoefficient;
    const bool bypass = mBypass.load();

    float inputSquares = 0.0f;
    for (int32_t i = 0; i < frames; ++i) {
        mSmoothedInputGain += (inputTarget - mSmoothedInputGain) * smoothing;
        mMonoResult[i] = mInputBuffer[i] * mSmoothedInputGain;
        inputSquares += mMonoResult[i] * mMonoResult[i];
        if (mTunerEnabled.load(std::memory_order_relaxed)) {
            if (mTunerWriteBuffer < 0) {
                for (int slot = 0; slot < 2; ++slot) {
                    int expected = 0;
                    if (mTunerBufferStates[slot].compare_exchange_strong(
                            expected, 1, std::memory_order_acq_rel)) {
                        mTunerWriteBuffer = slot;
                        mTunerWriteIndex = 0;
                        break;
                    }
                }
            }
            if (mTunerWriteBuffer >= 0) {
                mTunerBuffers[mTunerWriteBuffer][mTunerWriteIndex++] = mMonoResult[i];
                if (mTunerWriteIndex == kTunerBufferFrames) {
                    mTunerBufferStates[mTunerWriteBuffer].store(2, std::memory_order_release);
                    mTunerWriteBuffer = -1;
                    mTunerWriteIndex = 0;
                }
            }
        }
    }
    mInputLevelDb.store(20.0f * std::log10(std::max(std::sqrt(inputSquares / std::max(frames, 1)), 1e-5f)));

    const int configuredSlots = mEffectSlotCount.load(std::memory_order_acquire);
    const int slotsToProcess = configuredSlots > 0 ? configuredSlots : mEffectCount.load();
    for (int slotIndex = 0; slotIndex < slotsToProcess; ++slotIndex) {
        EffectSlot *instance = configuredSlots > 0 ? &mEffectSlots[slotIndex] : nullptr;
        const int effect = instance ? instance->type.load() : mEffectOrder[slotIndex].load();
        if (instance ? !instance->enabled.load() : !mEffectEnabled[effect].load()) continue;
        const auto parameter = [&](int index) {
            if (instance) return instance->params[index].load();
            // Compatibilidad con la API anterior de tres parametros. La UI
            // moderna siempre usa slots, pero estos defaults evitan cambiar
            // el sonido si un cliente JNI antiguo configura el motor.
            if (effect == 4) {
                const std::array<float, 10> defaults{30.0f, mEffectParams[4][0].load(), 120.0f,
                    mEffectParams[4][1].load(), 800.0f, 1.0f, mEffectParams[4][2].load(),
                    4200.0f, 18000.0f, 0.0f};
                return index < static_cast<int>(defaults.size()) ? defaults[index] : 0.0f;
            }
            if (effect == 8) {
                const std::array<float, 7> defaults{mEffectParams[8][0].load(),
                    mEffectParams[8][1].load(), 12.0f, 180.0f, 6.0f,
                    mEffectParams[8][2].load(), 100.0f};
                return index < static_cast<int>(defaults.size()) ? defaults[index] : 0.0f;
            }
            if (index < 3) return mEffectParams[effect][index].load();
            if (effect == 1) {
                const std::array<float, 4> defaults{2.0f, 60.0f, 80.0f, 6.0f};
                return index < 6 ? defaults[index - 2] : 0.0f;
            }
            if (effect == 2) {
                const std::array<float, 4> defaults{90.0f, 35.0f, 100.0f, 0.0f};
                return index < 7 ? defaults[index - 3] : 0.0f;
            }
            if (effect == 5) {
                const std::array<float, 8> defaults{100.0f, 0.0f, 0.0f, 35.0f,
                    12000.0f, 10.0f, 8.0f, 0.0f};
                return index < 11 ? defaults[index - 3] : 0.0f;
            }
            if (effect == 9) {
                const std::array<float, 3> defaults{60.0f, 3.0f, 0.0f};
                return index < 6 ? defaults[index - 3] : 0.0f;
            }
            return 0.0f;
        };
        float &gateEnvelope = instance ? instance->gateEnvelope : mGateEnvelope;
        float &gateGain = instance ? instance->gateGain : mGateGain;
        int32_t &gateHoldFrames = instance ? instance->gateHoldFrames : mGateHoldFrames;
        bool &gateOpen = instance ? instance->gateOpen : mGateOpen;
        float &compressorEnvelope = instance ? instance->compressorEnvelope : mCompressorEnvelope;
        float &compressorGain = instance ? instance->compressorGain : mCompressorGain;
        float &eqLowState = instance ? instance->eqLowState : mEqLowState;
        float &eqHighState = instance ? instance->eqHighState : mEqHighState;
        auto &pedalEqState = instance ? instance->pedalEqState : mPedalEqState;
        float &drivePreviousInput = instance ? instance->drivePreviousInput : mDrivePreviousInput;
        float &driveAntiAliasState = instance ? instance->driveAntiAliasState : mDriveAntiAliasState;
        float &driveLowCutState = instance ? instance->driveLowCutState : mDriveLowCutState;
        float &driveLowCutPrevious = instance ? instance->driveLowCutPrevious : mDriveLowCutPrevious;
        float &driveToneState = instance ? instance->driveToneState : mDriveToneState;
        auto &delayBuffer = instance ? instance->delayBuffer : mDelayBuffer;
        size_t &delayWriteIndex = instance ? instance->delayWriteIndex : mDelayWriteIndex;
        float &delaySmoothedSamples = instance ? instance->delaySmoothedSamples : mDelaySmoothedSamples;
        float &delayToneState = instance ? instance->delayToneState : mDelayToneState;
        auto &delayFilterState = instance ? instance->delayFilterState : mDelayFilterState;
        float &delayModPhase = instance ? instance->delayModPhase : mDelayModPhase;
        auto &reverbCombs = instance ? instance->reverbCombs : mReverbCombs;
        auto &reverbAllpasses = instance ? instance->reverbAllpasses : mReverbAllpasses;
        auto &reverbCombIndices = instance ? instance->reverbCombIndices : mReverbCombIndices;
        auto &reverbAllpassIndices = instance ? instance->reverbAllpassIndices : mReverbAllpassIndices;
        auto &chorusBuffer = instance ? instance->chorusBuffer : mChorusBuffer;
        size_t &chorusWriteIndex = instance ? instance->chorusWriteIndex : mChorusWriteIndex;
        float &chorusPhase = instance ? instance->chorusPhase : mChorusPhase;
        float &chorusToneState = instance ? instance->chorusToneState : mChorusToneState;
        auto &wahState = instance ? instance->wahState : mWahState;
        float &autoWahEnvelope = instance ? instance->autoWahEnvelope : mAutoWahEnvelope;
        float &tremoloPhase = instance ? instance->tremoloPhase : mTremoloPhase;
        auto &pitchBuffer = instance ? instance->pitchBuffer : mPitchBuffer;
        size_t &pitchWriteIndex = instance ? instance->pitchWriteIndex : mPitchWriteIndex;
        float &pitchPhase = instance ? instance->pitchPhase : mPitchPhase;
        float &pitchToneState = instance ? instance->pitchToneState : mPitchToneState;

        if (effect == 1) {
            const float threshold = dbToLinear(parameter(0));
            const float closeThreshold = dbToLinear(parameter(0) - std::clamp(parameter(5), 0.0f, 18.0f));
            const float release = std::clamp(parameter(1), 20.0f, 1000.0f);
            const float attack = std::clamp(parameter(2), 0.1f, 25.0f);
            const int32_t holdFrames = static_cast<int32_t>(
                std::clamp(parameter(3), 0.0f, 500.0f) * 0.001f * mSampleRate.load());
            const float closedGain = dbToLinear(-std::clamp(parameter(4), 10.0f, 100.0f));
            const float detectorAttack = std::exp(-1.0f / (0.001f * 1.0f * mSampleRate.load()));
            const float detectorRelease = std::exp(-1.0f / (0.001f * 35.0f * mSampleRate.load()));
            const float openCoeff = std::exp(-1.0f / (0.001f * attack * mSampleRate.load()));
            const float closeCoeff = std::exp(-1.0f / (0.001f * release * mSampleRate.load()));
            for (int32_t i = 0; i < frames; ++i) {
                const float level = std::abs(mMonoResult[i]);
                const float detectorCoeff = level > gateEnvelope ? detectorAttack : detectorRelease;
                gateEnvelope = detectorCoeff * gateEnvelope + (1.0f - detectorCoeff) * level;
                if (!gateOpen && gateEnvelope >= threshold) {
                    gateOpen = true;
                    gateHoldFrames = holdFrames;
                } else if (gateOpen) {
                    if (gateEnvelope >= closeThreshold) gateHoldFrames = holdFrames;
                    else if (gateHoldFrames > 0) --gateHoldFrames;
                    else gateOpen = false;
                }
                const float target = gateOpen ? 1.0f : closedGain;
                const float smoothingCoeff = target > gateGain ? openCoeff : closeCoeff;
                gateGain = smoothingCoeff * gateGain + (1.0f - smoothingCoeff) * target;
                mMonoResult[i] *= gateGain;
            }
        } else if (effect == 2) {
            const float drive = 1.0f + std::clamp(parameter(0) / 100.0f, 0.0f, 1.0f) * 24.0f;
            const float tone = std::clamp(parameter(1) / 100.0f, 0.0f, 1.0f);
            const float level = dbToLinear(parameter(2));
            const float tightHz = std::clamp(parameter(3), 20.0f, 650.0f);
            const float character = std::clamp(parameter(4) / 100.0f, 0.0f, 1.0f);
            const float mix = std::clamp(parameter(5) / 100.0f, 0.0f, 1.0f);
            const float bias = std::clamp(parameter(6) / 100.0f, -0.5f, 0.5f);
            const float norm = std::max(std::tanh(drive), 1e-4f);
            const float hpCoeff = std::exp(-6.2831853f * tightHz / mSampleRate.load());
            const float toneHz = 650.0f + tone * 9500.0f;
            const float toneCoeff = 1.0f - std::exp(-6.2831853f * toneHz / mSampleRate.load());
            for (int32_t i = 0; i < frames; ++i) {
                const float input = mMonoResult[i];
                driveLowCutState = hpCoeff * (driveLowCutState + input - driveLowCutPrevious);
                driveLowCutPrevious = input;
                float accumulated = 0.0f;
                // 4x oversampling por interpolacion reduce aliasing de la
                // saturacion sin reservar buffers adicionales.
                for (int phase = 1; phase <= 4; ++phase) {
                    const float oversampled = drivePreviousInput +
                        (driveLowCutState - drivePreviousInput) * (phase * 0.25f);
                    const float soft = std::tanh((oversampled + bias) * drive) / norm;
                    const float hard = std::clamp((oversampled + bias * 0.65f) * drive * 0.45f, -1.0f, 1.0f);
                    const float shaped = soft * (1.0f - character) + hard * character;
                    driveAntiAliasState += 0.45f * (shaped - driveAntiAliasState);
                    accumulated += driveAntiAliasState;
                }
                drivePreviousInput = driveLowCutState;
                const float clipped = accumulated * 0.25f;
                driveToneState += toneCoeff * (clipped - driveToneState);
                const float wet = driveToneState * (0.78f + tone * 0.22f);
                mMonoResult[i] = (input * (1.0f - mix) + wet * mix) * level;
            }
        } else if (effect == 3 && !bypass) {
            const float sampleRate = std::max(mSampleRate.load(), 1);
            const float pre = dbToLinear(parameter(0));
            const float lowCut = instance ? std::clamp(parameter(8), 20.0f, 250.0f) : 20.0f;
            const float highCut = instance ? std::clamp(parameter(9), 3000.0f, 20000.0f) : 20000.0f;
            const float hpCoefficient = std::exp(-6.2831853f * lowCut / sampleRate);
            const float lpCoefficient = 1.0f - std::exp(-6.2831853f * highCut / sampleRate);
            for (int32_t i = 0; i < frames; ++i) {
                float value = mMonoResult[i] * pre;
                if (instance) {
                    instance->ampLowCutState = hpCoefficient *
                        (instance->ampLowCutState + value - instance->ampLowCutPrevious);
                    instance->ampLowCutPrevious = value;
                    instance->ampHighCutState += lpCoefficient *
                        (instance->ampLowCutState - instance->ampHighCutState);
                    value = instance->ampHighCutState;
                }
                mDspInPtrStorage[i] = value;
            }
            {
                std::unique_lock<std::mutex> lock(mModelMutex, std::try_to_lock);
                if (lock.owns_lock() && mModel) {
                    NAM_SAMPLE *inPtr = mDspInPtrStorage.data();
                    NAM_SAMPLE *outPtr = mDspOutPtrStorage.data();
                    mModel->process(&inPtr, &outPtr, frames);
                    for (int32_t i = 0; i < frames; ++i) mMonoResult[i] = mDspOutPtrStorage[i];
                }
            }
            if (instance) {
                struct Coefficients { float b0, b1, b2, a1, a2; };
                const auto peak = [&](float frequency, float gainDb, float q) {
                    const float a = std::pow(10.0f, gainDb / 40.0f);
                    const float omega = 6.2831853f * std::clamp(frequency, 20.0f, sampleRate * 0.45f) / sampleRate;
                    const float alpha = std::sin(omega) / (2.0f * std::max(q, 0.1f));
                    const float a0 = 1.0f + alpha / a;
                    return Coefficients{(1.0f + alpha * a) / a0,
                        (-2.0f * std::cos(omega)) / a0,
                        (1.0f - alpha * a) / a0,
                        (-2.0f * std::cos(omega)) / a0,
                        (1.0f - alpha / a) / a0};
                };
                const std::array<Coefficients, 5> filters{
                    peak(90.0f, parameter(1), 0.65f),
                    peak(std::clamp(parameter(3), 150.0f, 4000.0f), parameter(2), std::clamp(parameter(4), 0.3f, 4.0f)),
                    peak(3500.0f, parameter(5), 0.7f),
                    peak(6500.0f, parameter(6), 0.8f),
                    peak(110.0f, parameter(7), 1.2f)};
                for (int32_t i = 0; i < frames; ++i) {
                    float value = mMonoResult[i];
                    for (size_t band = 0; band < filters.size(); ++band) {
                        const auto &c = filters[band];
                        auto &state = instance->ampEqState[band];
                        const float output = c.b0 * value + state[0];
                        state[0] = c.b1 * value - c.a1 * output + state[1];
                        state[1] = c.b2 * value - c.a2 * output;
                        value = output;
                    }
                    mMonoResult[i] = value * dbToLinear(parameter(10));
                }
            } else {
                const float post = dbToLinear(parameter(1));
                for (int32_t i = 0; i < frames; ++i) mMonoResult[i] *= post;
            }
        } else if (effect == 4) {
            struct Coefficients { float b0, b1, b2, a1, a2; };
            const float sampleRate = std::max(mSampleRate.load(), 1);
            const auto peak = [&](float frequency, float gainDb, float q) {
                const float a = std::pow(10.0f, gainDb / 40.0f);
                const float omega = 6.2831853f * std::clamp(frequency, 20.0f, sampleRate * 0.45f) / sampleRate;
                const float alpha = std::sin(omega) / (2.0f * std::max(q, 0.1f));
                const float a0 = 1.0f + alpha / a;
                return Coefficients{(1.0f + alpha * a) / a0, -2.0f * std::cos(omega) / a0,
                    (1.0f - alpha * a) / a0, -2.0f * std::cos(omega) / a0,
                    (1.0f - alpha / a) / a0};
            };
            const auto cut = [&](float frequency, bool highPass) {
                const float omega = 6.2831853f * std::clamp(frequency, 20.0f, sampleRate * 0.45f) / sampleRate;
                const float cosine = std::cos(omega), alpha = std::sin(omega) / 1.41421356f;
                const float a0 = 1.0f + alpha;
                const float b0 = highPass ? (1.0f + cosine) * 0.5f : (1.0f - cosine) * 0.5f;
                const float b1 = highPass ? -(1.0f + cosine) : 1.0f - cosine;
                return Coefficients{b0 / a0, b1 / a0, b0 / a0,
                    -2.0f * cosine / a0, (1.0f - alpha) / a0};
            };
            const std::array<Coefficients, 5> filters{
                cut(parameter(0), true), peak(parameter(2), parameter(1), 0.7f),
                peak(parameter(4), parameter(3), parameter(5)),
                peak(parameter(7), parameter(6), 0.7f), cut(parameter(8), false)};
            const float output = dbToLinear(parameter(9));
            for (int32_t i = 0; i < frames; ++i) {
                float value = mMonoResult[i];
                for (size_t band = 0; band < filters.size(); ++band) {
                    const auto &c = filters[band];
                    auto &state = pedalEqState[band];
                    const float filtered = c.b0 * value + state[0];
                    state[0] = c.b1 * value - c.a1 * filtered + state[1];
                    state[1] = c.b2 * value - c.a2 * filtered;
                    value = filtered;
                }
                mMonoResult[i] = value * output;
            }
        } else if (effect == 5 && !delayBuffer.empty()) {
            const float timeMs = std::clamp(parameter(0), 40.0f, 1500.0f);
            const float targetDelaySamples = std::min<float>(mSampleRate.load() * timeMs / 1000.0f, delayBuffer.size() - 2);
            const float feedback = std::clamp(parameter(1) / 100.0f, 0.0f, 0.96f);
            const float mix = std::clamp(parameter(2) / 100.0f, 0.0f, 1.0f);
            const float quarterLevel = std::clamp(parameter(3) / 100.0f, 0.0f, 1.0f);
            const float sixteenthLevel = std::clamp(parameter(4) / 100.0f, 0.0f, 1.0f);
            const float tripletLevel = std::clamp(parameter(5) / 100.0f, 0.0f, 1.0f);
            const float character = std::clamp((parameter(6) + 100.0f) / 200.0f, 0.0f, 1.0f);
            const float cutoff = std::clamp(parameter(7), 250.0f, 18000.0f);
            const float resonance = std::clamp(parameter(8) / 100.0f, 0.0f, 1.0f);
            const float modulation = std::clamp(parameter(9) / 100.0f, 0.0f, 1.0f);
            const float delayLevel = dbToLinear(parameter(10));
            const float filterG = std::clamp(1.0f - std::exp(-6.2831853f * cutoff / mSampleRate.load()), 0.001f, 0.95f);
            const float layerNorm = std::max(quarterLevel + sixteenthLevel + tripletLevel, 1.0f);
            const auto readTap = [&](float delaySamples) {
                const float bounded = std::clamp(delaySamples, 1.0f, static_cast<float>(delayBuffer.size() - 2));
                const size_t whole = static_cast<size_t>(bounded);
                const float fraction = bounded - static_cast<float>(whole);
                const size_t readA = (delayWriteIndex + delayBuffer.size() - whole) % delayBuffer.size();
                const size_t readB = (readA + delayBuffer.size() - 1) % delayBuffer.size();
                return delayBuffer[readA] * (1.0f - fraction) + delayBuffer[readB] * fraction;
            };
            for (int32_t i = 0; i < frames; ++i) {
                delaySmoothedSamples += (targetDelaySamples - delaySmoothedSamples) * 0.0008f;
                const float dry = mMonoResult[i];
                const float wow = std::sin(delayModPhase) * modulation * mSampleRate.load() * 0.0025f;
                const float quarter = readTap(delaySmoothedSamples + wow);
                const float sixteenth = readTap(delaySmoothedSamples * 0.25f + wow * 0.45f);
                const float triplet = readTap(delaySmoothedSamples * 0.6666667f - wow * 0.7f);
                float layered = (quarter * quarterLevel + sixteenth * sixteenthLevel +
                    triplet * tripletLevel) / layerNorm;

                // Cuatro polos resonantes sobre la ruta wet. El drive suave y
                // la perdida de agudos aumentan hacia el extremo "tape";
                // el extremo digital conserva mejor los transientes.
                const float ladderInput = std::tanh(layered * (1.0f + (1.0f - character) * 1.8f) -
                    delayFilterState[3] * resonance * 3.2f);
                float stageInput = ladderInput;
                for (float &stage : delayFilterState) {
                    stage += filterG * (stageInput - stage);
                    stageInput = stage;
                }
                const float filtered = delayFilterState[3];
                delayToneState += (0.035f + character * 0.35f) * (filtered - delayToneState);
                const float repeat = delayToneState * (0.35f + character * 0.65f);
                delayBuffer[delayWriteIndex] = std::tanh(dry + repeat * feedback);
                mMonoResult[i] = (dry * (1.0f - mix) + repeat * mix) * delayLevel;
                delayWriteIndex = (delayWriteIndex + 1) % delayBuffer.size();
                delayModPhase += 6.2831853f * (0.12f + modulation * 0.55f) / mSampleRate.load();
                if (delayModPhase > 6.2831853f) delayModPhase -= 6.2831853f;
            }
        } else if (effect == 6 && !reverbCombs[0].empty()) {
            const float decay = std::clamp(parameter(0), 0.2f, 12.0f);
            const float tone = std::clamp(parameter(1) / 100.0f, 0.0f, 1.0f);
            const float mix = std::clamp(parameter(2) / 100.0f, 0.0f, 1.0f);
            const int mode = std::clamp(
                static_cast<int>(std::round(parameter(3))), 0, 4);

            // ROOM, HALL, PLATE, SHIMMER y AMBIENT comparten un tanque
            // estable, pero cada posicion cambia tiempo aparente, absorcion,
            // difusion, modulacion e inyeccion de octava.
            const std::array<float, 5> decayScale{0.46f, 0.82f, 0.68f, 0.92f, 1.12f};
            const std::array<float, 5> dampingScale{0.58f, 0.72f, 0.94f, 0.88f, 0.62f};
            const std::array<float, 5> diffusion{0.38f, 0.54f, 0.70f, 0.62f, 0.76f};
            const std::array<float, 5> inputSpread{1.15f, 0.92f, 0.78f, 0.72f, 0.60f};
            const std::array<float, 5> shimmerMix{0.0f, 0.0f, 0.0f, 0.52f, 0.14f};
            const float feedback = std::clamp(
                0.28f + decay * 0.052f * decayScale[mode], 0.30f, 0.945f);
            const float damping = std::clamp(
                0.48f + tone * 0.48f * dampingScale[mode], 0.42f, 0.965f);
            const float allpassGain = diffusion[mode];
            const float shimmerAmount = shimmerMix[mode];
            const float shimmerWindow = std::min<float>(
                mSampleRate.load() * 0.050f,
                static_cast<float>(pitchBuffer.size() - 2));
            const float shimmerPhaseIncrement = shimmerWindow > 1.0f
                ? -1.0f / shimmerWindow : 0.0f;

            const auto shimmerGrain = [&](float phase) {
                phase -= std::floor(phase);
                const float delay = 1.0f + phase * shimmerWindow;
                const size_t whole = static_cast<size_t>(delay);
                const float fraction = delay - static_cast<float>(whole);
                const size_t a = (pitchWriteIndex + pitchBuffer.size() - whole) %
                    pitchBuffer.size();
                const size_t b = (a + pitchBuffer.size() - 1) % pitchBuffer.size();
                const float sample = pitchBuffer[a] * (1.0f - fraction) +
                    pitchBuffer[b] * fraction;
                const float envelope = 0.5f - 0.5f * std::cos(6.2831853f * phase);
                return std::array<float, 2>{sample, envelope};
            };

            for (int32_t i = 0; i < frames; ++i) {
                const float dry = mMonoResult[i];
                const float ambientMotion = mode == 4
                    ? 1.0f + std::sin(pitchPhase * 6.2831853f) * 0.012f : 1.0f;
                const float tankInput = dry * inputSpread[mode] +
                    pitchToneState * shimmerAmount * 0.62f;
                float wet = 0.0f;
                for (size_t comb = 0; comb < reverbCombs.size(); ++comb) {
                    auto &buffer = reverbCombs[comb];
                    const size_t index = reverbCombIndices[comb];
                    const float delayed = buffer[index];
                    const float combColor = 0.91f + static_cast<float>(comb) * 0.025f;
                    buffer[index] = std::tanh(
                        tankInput + delayed * feedback * damping *
                        combColor * ambientMotion);
                    wet += delayed * 0.25f;
                    reverbCombIndices[comb] = (index + 1) % buffer.size();
                }
                for (size_t stage = 0; stage < reverbAllpasses.size(); ++stage) {
                    auto &buffer = reverbAllpasses[stage];
                    const size_t index = reverbAllpassIndices[stage];
                    const float delayed = buffer[index];
                    const float input = wet;
                    wet = delayed - input * allpassGain;
                    buffer[index] = input + delayed * allpassGain;
                    reverbAllpassIndices[stage] = (index + 1) % buffer.size();
                }

                if (shimmerAmount > 0.0f && pitchBuffer.size() > 4) {
                    pitchBuffer[pitchWriteIndex] = wet;
                    const auto first = shimmerGrain(pitchPhase);
                    const auto second = shimmerGrain(pitchPhase + 0.5f);
                    const float weight = std::max(first[1] + second[1], 1e-4f);
                    const float octave = (first[0] * first[1] +
                        second[0] * second[1]) / weight;
                    pitchToneState += 0.08f * (octave - pitchToneState);
                    wet = wet * (1.0f - shimmerAmount * 0.32f) +
                        pitchToneState * shimmerAmount;
                    pitchWriteIndex = (pitchWriteIndex + 1) % pitchBuffer.size();
                    pitchPhase += shimmerPhaseIncrement;
                    pitchPhase -= std::floor(pitchPhase);
                } else if (mode == 4) {
                    pitchPhase += 0.07f / std::max(mSampleRate.load(), 1);
                    if (pitchPhase >= 1.0f) pitchPhase -= 1.0f;
                }
                mMonoResult[i] = dry * (1.0f - mix) + wet * mix;
            }
        } else if (effect == 7) {
            std::unique_lock<std::mutex> lock(mIrMutex, std::try_to_lock);
            if (lock.owns_lock() && !mIrPartitions.empty()) {
                const float level = dbToLinear(parameter(0));
                const float lowCut = std::clamp(parameter(1), 20.0f, 300.0f);
                const float highCut = std::clamp(parameter(2), 3000.0f, 20000.0f);
                const float hpAlpha = std::exp(-6.2831853f * lowCut / mSampleRate.load());
                const float lpAlpha = 1.0f - std::exp(-6.2831853f * highCut / mSampleRate.load());
                for (int32_t i = 0; i < frames; ++i) {
                    const float convolved = mIrOutputBlock[mIrBlockIndex] * level;
                    mIrInputBlock[mIrBlockIndex] = mMonoResult[i];
                    mIrLowpassState += lpAlpha * (convolved - mIrLowpassState);
                    mIrHighpassState = hpAlpha * (mIrHighpassState + mIrLowpassState - mIrPreviousInput);
                    mIrPreviousInput = mIrLowpassState;
                    mMonoResult[i] = mIrHighpassState;
                    if (++mIrBlockIndex == kIrPartitionFrames) {
                        processIrPartition();
                        mIrBlockIndex = 0;
                    }
                }
            }
        } else if (effect == 8) {
            const float thresholdDb = parameter(0), ratio = std::max(parameter(1), 1.0f);
            const float attack = std::clamp(parameter(2), 0.1f, 100.0f);
            const float release = std::clamp(parameter(3), 20.0f, 1000.0f);
            const float knee = std::clamp(parameter(4), 0.0f, 18.0f);
            const float makeup = dbToLinear(parameter(5));
            const float mix = std::clamp(parameter(6) / 100.0f, 0.0f, 1.0f);
            const float attackCoeff = std::exp(-1.0f / (0.001f * attack * mSampleRate.load()));
            const float releaseCoeff = std::exp(-1.0f / (0.001f * release * mSampleRate.load()));
            for (int32_t i = 0; i < frames; ++i) {
                const float dry = mMonoResult[i];
                const float detected = std::abs(dry);
                const float detectorCoeff = detected > compressorEnvelope ? attackCoeff : releaseCoeff;
                compressorEnvelope = detectorCoeff * compressorEnvelope + (1.0f - detectorCoeff) * detected;
                const float levelDb = 20.0f * std::log10(std::max(compressorEnvelope, 1e-6f));
                const float over = levelDb - thresholdDb;
                float reductionDb = 0.0f;
                if (knee > 0.0f && over > -knee * 0.5f && over < knee * 0.5f) {
                    const float kneeInput = over + knee * 0.5f;
                    reductionDb = (1.0f / ratio - 1.0f) * kneeInput * kneeInput / (2.0f * knee);
                } else if (over >= knee * 0.5f) {
                    reductionDb = (1.0f / ratio - 1.0f) * over;
                }
                const float targetGain = dbToLinear(reductionDb);
                const float gainCoeff = targetGain < compressorGain ? attackCoeff : releaseCoeff;
                compressorGain = gainCoeff * compressorGain + (1.0f - gainCoeff) * targetGain;
                const float wet = dry * compressorGain * makeup;
                mMonoResult[i] = dry * (1.0f - mix) + wet * mix;
            }
        } else if (effect == 9 && !chorusBuffer.empty()) {
            const float rate = std::clamp(parameter(0), 0.05f, 8.0f);
            const float depth = std::clamp(parameter(1) / 100.0f, 0.0f, 1.0f);
            const float mix = std::clamp(parameter(2) / 100.0f, 0.0f, 1.0f);
            const float tone = std::clamp(parameter(3) / 100.0f, 0.0f, 1.0f);
            const int voices = std::clamp(static_cast<int>(std::round(parameter(4))), 1, 4);
            const float level = dbToLinear(parameter(5));
            const float toneHz = 900.0f + tone * 10500.0f;
            const float toneCoeff = 1.0f - std::exp(-6.2831853f * toneHz / mSampleRate.load());
            for (int32_t i = 0; i < frames; ++i) {
                const float dry = mMonoResult[i];
                chorusBuffer[chorusWriteIndex] = dry;
                float wet = 0.0f;
                for (int voice = 0; voice < voices; ++voice) {
                    const float phase = chorusPhase + 6.2831853f * static_cast<float>(voice) / voices;
                    const float lfo = 0.5f + 0.5f * std::sin(phase);
                    const float delaySamples = (0.006f + lfo * 0.018f * depth) * mSampleRate.load();
                    const size_t whole = std::min(static_cast<size_t>(delaySamples), chorusBuffer.size() - 2);
                    const float fraction = delaySamples - static_cast<float>(whole);
                    const size_t readA = (chorusWriteIndex + chorusBuffer.size() - whole) % chorusBuffer.size();
                    const size_t readB = (readA + chorusBuffer.size() - 1) % chorusBuffer.size();
                    wet += chorusBuffer[readA] * (1.0f - fraction) + chorusBuffer[readB] * fraction;
                }
                wet /= static_cast<float>(voices);
                chorusToneState += toneCoeff * (wet - chorusToneState);
                mMonoResult[i] = (dry * (1.0f - mix) + chorusToneState * mix) * level;
                chorusWriteIndex = (chorusWriteIndex + 1) % chorusBuffer.size();
                chorusPhase += 6.2831853f * rate / mSampleRate.load();
                if (chorusPhase > 6.2831853f) chorusPhase -= 6.2831853f;
            }
        } else if (effect == 10 || effect == 11) {
            const float sampleRate = std::max(mSampleRate.load(), 1);
            const float minFrequency = std::clamp(parameter(effect == 10 ? 1 : 3), 80.0f, 2000.0f);
            const float maxFrequency = std::max(minFrequency + 50.0f,
                std::clamp(parameter(effect == 10 ? 2 : 4), 400.0f, sampleRate * 0.42f));
            const float q = std::clamp(parameter(effect == 10 ? 3 : 5), 0.4f, 10.0f);
            const float mix = std::clamp(parameter(effect == 10 ? 4 : 7) / 100.0f, 0.0f, 1.0f);
            const float level = dbToLinear(parameter(effect == 10 ? 5 : 8));
            const float sensitivity = effect == 11 ? dbToLinear(parameter(0)) : 1.0f;
            const float attackCoeff = effect == 11 ? std::exp(-1.0f /
                (0.001f * std::clamp(parameter(1), 0.5f, 100.0f) * sampleRate)) : 0.0f;
            const float releaseCoeff = effect == 11 ? std::exp(-1.0f /
                (0.001f * std::clamp(parameter(2), 20.0f, 1200.0f) * sampleRate)) : 0.0f;
            const float direction = effect == 11 ? std::clamp(parameter(6) / 100.0f, -1.0f, 1.0f) : 1.0f;
            for (int32_t i = 0; i < frames; ++i) {
                const float dry = mMonoResult[i];
                float position = std::clamp(parameter(0) / 100.0f, 0.0f, 1.0f);
                if (effect == 11) {
                    const float detected = std::abs(dry) * sensitivity;
                    const float coefficient = detected > autoWahEnvelope ? attackCoeff : releaseCoeff;
                    autoWahEnvelope = coefficient * autoWahEnvelope + (1.0f - coefficient) * detected;
                    const float envelopePosition = std::clamp(autoWahEnvelope * 4.0f, 0.0f, 1.0f);
                    position = direction >= 0.0f ? envelopePosition : 1.0f - envelopePosition;
                }
                // Barrido exponencial: musicalmente uniforme entre graves y agudos.
                const float frequency = minFrequency * std::pow(maxFrequency / minFrequency, position);
                const float omega = 6.2831853f * frequency / sampleRate;
                const float alpha = std::sin(omega) / (2.0f * q);
                const float a0 = 1.0f + alpha;
                const float b0 = alpha / a0, b2 = -alpha / a0;
                const float a1 = -2.0f * std::cos(omega) / a0;
                const float a2 = (1.0f - alpha) / a0;
                const float filtered = b0 * dry + wahState[0];
                wahState[0] = -a1 * filtered + wahState[1];
                wahState[1] = b2 * dry - a2 * filtered;
                mMonoResult[i] = (dry * (1.0f - mix) + filtered * mix * (1.0f + q * 0.12f)) * level;
            }
        } else if (effect == 12) {
            const float rate = std::clamp(parameter(0), 0.1f, 20.0f);
            const float depth = std::clamp(parameter(1) / 100.0f, 0.0f, 1.0f);
            const float shape = std::clamp(parameter(2) / 100.0f, 0.0f, 1.0f);
            const float symmetry = std::clamp(parameter(3) / 100.0f, 0.1f, 0.9f);
            const float phaseOffset = std::clamp(parameter(4), 0.0f, 360.0f) / 360.0f;
            const float level = dbToLinear(parameter(5));
            for (int32_t i = 0; i < frames; ++i) {
                float phase = std::fmod(tremoloPhase / 6.2831853f + phaseOffset, 1.0f);
                const float warped = phase < symmetry ? phase * 0.5f / symmetry :
                    0.5f + (phase - symmetry) * 0.5f / (1.0f - symmetry);
                const float sine = 0.5f + 0.5f * std::sin(6.2831853f * warped);
                const float square = sine >= 0.5f ? 1.0f : 0.0f;
                const float lfo = sine * (1.0f - shape) + square * shape;
                mMonoResult[i] *= ((1.0f - depth) + lfo * depth) * level;
                tremoloPhase += 6.2831853f * rate / mSampleRate.load();
                if (tremoloPhase >= 6.2831853f) tremoloPhase -= 6.2831853f;
            }
        } else if ((effect == 13 || effect == 14) && pitchBuffer.size() > 4) {
            // DETUNE usa posiciones discretas: UP +2/+1, standard, -1…-7 y
            // octava abajo. Los valores 0..8 conservan compatibilidad con rigs
            // guardados; -1/-2 representan las dos posiciones UP nuevas.
            const float selectedDrop = std::round(std::clamp(parameter(0), -2.0f, 8.0f));
            const float detuneSemitones = selectedDrop < 0.0f ? -selectedDrop :
                (selectedDrop >= 8.0f ? -12.0f : -selectedDrop);
            const float semitones = effect == 14 ? detuneSemitones :
                std::clamp(parameter(0) + parameter(1) / 100.0f, -12.0f, 12.0f);
            if (std::abs(semitones) < 0.001f) continue;
            const float ratio = std::pow(2.0f, semitones / 12.0f);
            const int mixParam = effect == 14 ? 1 : 2;
            const int windowParam = effect == 14 ? 2 : 3;
            const int toneParam = effect == 14 ? 3 : 4;
            const int levelParam = effect == 14 ? 4 : 5;
            const float mix = std::clamp(parameter(mixParam) / 100.0f, 0.0f, 1.0f);
            const float window = std::clamp(parameter(windowParam) * 0.001f * mSampleRate.load(),
                128.0f, static_cast<float>(pitchBuffer.size() - 2));
            const float tone = std::clamp(parameter(toneParam) / 100.0f, 0.0f, 1.0f);
            const float level = dbToLinear(parameter(levelParam));
            const float phaseIncrement = (1.0f - ratio) / window;
            const float toneHz = 1200.0f + tone * 12500.0f;
            const float toneCoeff = 1.0f - std::exp(-6.2831853f * toneHz / mSampleRate.load());
            const auto grain = [&](float phase) {
                phase -= std::floor(phase);
                const float delay = 1.0f + phase * window;
                const size_t whole = static_cast<size_t>(delay);
                const float fraction = delay - static_cast<float>(whole);
                const size_t a = (pitchWriteIndex + pitchBuffer.size() - whole) % pitchBuffer.size();
                const size_t b = (a + pitchBuffer.size() - 1) % pitchBuffer.size();
                const float sample = pitchBuffer[a] * (1.0f - fraction) + pitchBuffer[b] * fraction;
                const float envelope = 0.5f - 0.5f * std::cos(6.2831853f * phase);
                return std::array<float, 2>{sample, envelope};
            };
            for (int32_t i = 0; i < frames; ++i) {
                const float dry = mMonoResult[i];
                pitchBuffer[pitchWriteIndex] = dry;
                const auto first = grain(pitchPhase);
                const auto second = grain(pitchPhase + 0.5f);
                const float weight = std::max(first[1] + second[1], 1e-4f);
                const float shifted = (first[0] * first[1] + second[0] * second[1]) / weight;
                pitchToneState += toneCoeff * (shifted - pitchToneState);
                mMonoResult[i] = (dry * (1.0f - mix) + pitchToneState * mix) * level;
                pitchWriteIndex = (pitchWriteIndex + 1) % pitchBuffer.size();
                pitchPhase += phaseIncrement;
                pitchPhase -= std::floor(pitchPhase);
            }
        }
    }
    for (int32_t i = 0; i < frames; ++i) {
        mSmoothedOutputGain += (outputTarget - mSmoothedOutputGain) * smoothing;
        mMonoResult[i] *= mSmoothedOutputGain;
    }

    // Crossfade corto contra la cola previa al cambiar escena, NAM, IR o
    // bypass. No reserva memoria y puede continuar entre callbacks.
    if (mCrossfadeRequested.exchange(false, std::memory_order_acq_rel)) {
        mCrossfadeRead = mTransitionTailWrite;
        mCrossfadeRemaining = kTransitionFrames;
    }
    for (int32_t i = 0; i < frames; ++i) {
        if (mCrossfadeRemaining > 0) {
            const size_t progressed = kTransitionFrames - mCrossfadeRemaining;
            const float t = static_cast<float>(progressed + 1) /
                static_cast<float>(kTransitionFrames);
            const float previous = mTransitionTail[mCrossfadeRead];
            mCrossfadeRead = (mCrossfadeRead + 1) % kTransitionFrames;
            mMonoResult[i] = previous * (1.0f - t) + mMonoResult[i] * t;
            --mCrossfadeRemaining;
        }
        mTransitionTail[mTransitionTailWrite] = mMonoResult[i];
        mTransitionTailWrite = (mTransitionTailWrite + 1) % kTransitionFrames;
    }

    const int pendingLooper = mPendingLooperCommand.exchange(-1, std::memory_order_acq_rel);
    if (pendingLooper == 4) {
        mLooperPosition = 0;
        mLooperLength = 0;
        mLooperState.store(0, std::memory_order_release);
    } else if (pendingLooper == 1) {
        mLooperPosition = 0;
        mLooperLength = 0;
        mLooperState.store(1, std::memory_order_release);
    } else if (pendingLooper >= 0 && pendingLooper <= 3) {
        // Cerrar la costura del loop antes de reproducirlo. Se hace una sola
        // vez al recibir el comando, nunca desde el hilo de interfaz.
        if (mLooperState.load(std::memory_order_relaxed) == 1 &&
            (pendingLooper == 2 || pendingLooper == 3) && mLooperLength > 2) {
            const size_t fadeFrames = std::min<size_t>(128, mLooperLength / 2);
            for (size_t i = 0; i < fadeFrames; ++i) {
                const float t = static_cast<float>(i) / std::max<size_t>(fadeFrames - 1, 1);
                const size_t tail = mLooperLength - fadeFrames + i;
                const float blended = mLooperBuffer[tail] * (1.0f - t) + mLooperBuffer[i] * t;
                mLooperBuffer[tail] = blended;
                mLooperBuffer[i] = blended;
            }
        }
        mLooperPosition = 0;
        mLooperState.store(pendingLooper, std::memory_order_release);
    }
    const int looper = mLooperState.load(std::memory_order_acquire);
    if (!mLooperBuffer.empty() && looper != 0) {
        for (int32_t i = 0; i < frames; ++i) {
            if (looper == 1) { if (mLooperPosition < mLooperBuffer.size()) { mLooperBuffer[mLooperPosition++] = mMonoResult[i]; mLooperLength = std::max(mLooperLength, mLooperPosition); } }
            else if (mLooperLength > 0) { const float loop = mLooperBuffer[mLooperPosition]; if (looper == 3) mLooperBuffer[mLooperPosition] = std::clamp(loop + mMonoResult[i] * 0.75f, -1.0f, 1.0f); mMonoResult[i] += loop * 0.8f; mLooperPosition = (mLooperPosition + 1) % mLooperLength; }
        }
        if (looper == 1 && mLooperPosition >= mLooperBuffer.size()) { mLooperPosition = 0; mLooperState.store(2); }
    }
    mLooperPositionSnapshot.store(mLooperPosition, std::memory_order_release);
    mLooperLengthSnapshot.store(mLooperLength, std::memory_order_release);
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

    if (sampleTelemetry) {
        if (mOutStream) {
            auto xruns = mOutStream->getXRunCount();
            if (xruns) {
                mXRunCount.store(
                    xruns.value() + static_cast<int32_t>(
                        mInputUnderflowCount.load(std::memory_order_relaxed)));
            }
        }
        const auto elapsed = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - callbackStarted).count();
        const double budget = static_cast<double>(std::max(frames, 1)) /
            std::max(mSampleRate.load(), 1);
        const double measured = std::clamp(elapsed / budget * 100.0, 0.0, 999.0);
        const double previous = mLastLoadPercent.load(std::memory_order_relaxed);
        mLastLoadPercent.store(previous * 0.8 + measured * 0.2,
            std::memory_order_relaxed);
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *, oboe::Result error) {
    LOGE("Stream cerrado por error: %s. Recuperacion solicitada...", oboe::convertToText(error));
    mRecoveryRequested.store(true, std::memory_order_release);
}