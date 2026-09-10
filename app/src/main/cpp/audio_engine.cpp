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
    if (effectId >= 1 && effectId <= 9) {
        const bool changed = mEffectEnabled[effectId].exchange(enabled) != enabled;
        if (changed) requestCrossfade();
    }
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
                ->setInputPreset(oboe::InputPreset::Unprocessed);
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
    mChorusBuffer.assign(std::max(mSampleRate.load() / 10, 1), 0.0f);
    mLooperBuffer.assign(std::max(mSampleRate.load() * 60, 1), 0.0f);
    mDelayWriteIndex = 0;
    mReverbWriteIndex = 0;
    mChorusWriteIndex = 0;
    mLooperPosition = 0;
    mLooperLength = 0;
    mTunerWriteIndex = 0;
    mChorusPhase = 0.0f;
    mDrivePreviousInput = 0.0f;
    mDriveAntiAliasState = 0.0f;
    mDelaySmoothedSamples = mSampleRate.load() * 0.36f;
    mDelayToneState = 0.0f;
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
        requestCrossfade();
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
                const float input = mMonoResult[i];
                float accumulated = 0.0f;
                // 4x oversampling por interpolacion reduce aliasing de la
                // saturacion sin reservar buffers adicionales.
                for (int phase = 1; phase <= 4; ++phase) {
                    const float oversampled = mDrivePreviousInput +
                        (input - mDrivePreviousInput) * (phase * 0.25f);
                    const float shaped = std::tanh(oversampled * drive) / norm;
                    mDriveAntiAliasState += 0.45f * (shaped - mDriveAntiAliasState);
                    accumulated += mDriveAntiAliasState;
                }
                mDrivePreviousInput = input;
                const float clipped = accumulated * 0.25f;
                mMonoResult[i] = (clipped * (0.65f + tone * 0.35f) + input * (0.35f - tone * 0.25f)) * level;
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
            const float targetDelaySamples = std::min<float>(mSampleRate.load() * timeMs / 1000.0f, mDelayBuffer.size() - 2);
            const float feedback = std::clamp(mEffectParams[5][1].load() / 100.0f, 0.0f, 0.92f);
            const float mix = std::clamp(mEffectParams[5][2].load() / 100.0f, 0.0f, 1.0f);
            for (int32_t i = 0; i < frames; ++i) {
                mDelaySmoothedSamples += (targetDelaySamples - mDelaySmoothedSamples) * 0.0008f;
                const size_t wholeDelay = static_cast<size_t>(mDelaySmoothedSamples);
                const float fraction = mDelaySmoothedSamples - wholeDelay;
                const size_t readA = (mDelayWriteIndex + mDelayBuffer.size() - wholeDelay) % mDelayBuffer.size();
                const size_t readB = (readA + mDelayBuffer.size() - 1) % mDelayBuffer.size();
                const float wet = mDelayBuffer[readA] * (1.0f - fraction) + mDelayBuffer[readB] * fraction;
                const float dry = mMonoResult[i];
                mDelayToneState += 0.18f * (wet - mDelayToneState);
                mDelayBuffer[mDelayWriteIndex] = dry + mDelayToneState * feedback;
                mMonoResult[i] = dry * (1.0f - mix) + mDelayToneState * mix;
                mDelayWriteIndex = (mDelayWriteIndex + 1) % mDelayBuffer.size();
            }
        } else if (effect == 6 && !mReverbCombs[0].empty()) {
            const float decay = std::clamp(mEffectParams[6][0].load(), 0.2f, 12.0f);
            const float feedback = std::clamp(0.35f + decay / 20.0f, 0.35f, 0.93f);
            const float tone = std::clamp(mEffectParams[6][1].load() / 100.0f, 0.0f, 1.0f);
            const float mix = std::clamp(mEffectParams[6][2].load() / 100.0f, 0.0f, 1.0f);
            for (int32_t i = 0; i < frames; ++i) {
                const float dry = mMonoResult[i];
                float wet = 0.0f;
                for (size_t comb = 0; comb < mReverbCombs.size(); ++comb) {
                    auto &buffer = mReverbCombs[comb];
                    const size_t index = mReverbCombIndices[comb];
                    const float delayed = buffer[index];
                    buffer[index] = dry + delayed * feedback * (0.72f + tone * 0.22f);
                    wet += delayed * 0.25f;
                    mReverbCombIndices[comb] = (index + 1) % buffer.size();
                }
                for (size_t stage = 0; stage < mReverbAllpasses.size(); ++stage) {
                    auto &buffer = mReverbAllpasses[stage];
                    const size_t index = mReverbAllpassIndices[stage];
                    const float delayed = buffer[index];
                    const float input = wet;
                    wet = delayed - input * 0.5f;
                    buffer[index] = input + delayed * 0.5f;
                    mReverbAllpassIndices[stage] = (index + 1) % buffer.size();
                }
                mMonoResult[i] = dry * (1.0f - mix) + wet * mix;
            }
        } else if (effect == 7) {
            std::unique_lock<std::mutex> lock(mIrMutex, std::try_to_lock);
            if (lock.owns_lock() && !mIrPartitions.empty()) {
                const float level = dbToLinear(mEffectParams[7][0].load());
                const float lowCut = std::clamp(mEffectParams[7][1].load(), 20.0f, 300.0f);
                const float highCut = std::clamp(mEffectParams[7][2].load(), 3000.0f, 20000.0f);
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
    LOGE("Stream cerrado por error: %s. Recuperacion solicitada...", oboe::convertToText(error));
    mRecoveryRequested.store(true, std::memory_order_release);
}
