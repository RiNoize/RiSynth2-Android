#include <jni.h>
#include <aaudio/AAudio.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <exception>
#include <memory>
#include <string>
#include "EngineCore/Synth.hpp"
#include "EngineCore/InitPatch.hpp"
#include "EventQueue.hpp"
#include "RenderMetrics.hpp"

using namespace oxo::s2;
namespace {
constexpr int kChunk = 512;
struct Event {
    enum Kind { Midi, Parameter, Patch, Volume, Tempo } kind = Midi;
    int a = 0, b = 0, c = 0;
    PatchValues values{};
};
class Engine {
public:
    Synth synth;
    EventQueue<Event, 512> events;
    std::array<std::atomic<int>, kParamCount> parameters{};
    std::atomic<bool> panic{false}, active{false};
    std::atomic<int> error{0}, voices{0};
    std::atomic<uint64_t> clips{0}, dropped{0};
    std::atomic<double> renderPercent{0}, peakPercent{0};
    RenderMetrics metrics;
    uint64_t streamGeneration = 0; // serialized control-side state
    int stoppedXruns = 0;
    AAudioStream* stream = nullptr; // lifecycle and stats serialized by Java
    std::array<float, kChunk> left{}, right{};
    int volume = 48;
    Engine() {
        synth.SetPatch(kInitPatchValues);
        synth.SetVolume(volume);
        for (int i = 0; i < kParamCount; ++i) parameters[i].store(kInitPatchValues[i]);
    }
    ~Engine() { stop(); }
    bool send(const Event& e) {
        if (events.push(e)) return true;
        dropped.fetch_add(1, std::memory_order_relaxed);
        panic.store(true, std::memory_order_release); // never leave a lost note-off hanging
        return false;
    }
    void drain(bool allowMidi) {
        Event e;
        for (int count = 0; count < 512 && events.pop(e); ++count) {
            switch (e.kind) {
                case Event::Midi:
                    if (!allowMidi) break;
                    switch (e.a & 0xf0) {
                        case 0x80: synth.NoteOff(e.b); break;
                        case 0x90: if (e.c) synth.NoteOn(e.b, e.c); else synth.NoteOff(e.b); break;
                        case 0xb0: synth.ControlChange(e.b, e.c); break;
                        case 0xd0: synth.ChannelPressure(e.b); break;
                        case 0xe0: synth.PitchBend(e.b | (e.c << 7)); break;
                        default: break;
                    }
                    break;
                case Event::Parameter: synth.SetParam(e.a, e.b); break;
                case Event::Patch:
                    if (allowMidi) { synth.ClearDelay(); synth.BeginPatchChange(); }
                    synth.SetPatch(e.values);
                    break;
                case Event::Volume: synth.SetVolume(e.a); break;
                case Event::Tempo: synth.SetTransport(e.a, 0, true); break;
            }
        }
        if (panic.exchange(false, std::memory_order_acq_rel)) {
            synth.Panic();
            synth.ClearDelay();
        }
    }
    static void onError(AAudioStream*, void* user, aaudio_result_t result) {
        auto* e = static_cast<Engine*>(user);
        e->error.store(result);
        e->active.store(false);
        // Do NOT close/reopen from this callback. Java's control worker does it.
    }
    static aaudio_data_callback_result_t onData(AAudioStream*, void* user, void* audio, int32_t frames) {
        auto& e = *static_cast<Engine*>(user);
        const auto begin = std::chrono::steady_clock::now();
        auto* out = static_cast<float*>(audio);
        e.drain(true);
        e.synth.BeginBlock(frames);
        uint64_t clipped = 0;
        for (int pos = 0; pos < frames; pos += kChunk) {
            const int n = std::min(kChunk, frames - pos);
            e.synth.Render(e.left.data(), e.right.data(), n);
            for (int i = 0; i < n; ++i) {
                const float l = e.left[i], r = e.right[i];
                clipped += !std::isfinite(l) || std::abs(l) > 1.0f;
                clipped += !std::isfinite(r) || std::abs(r) > 1.0f;
                out[2 * (pos + i)] = std::isfinite(l) ? std::clamp(l, -1.0f, 1.0f) : 0.0f;
                out[2 * (pos + i) + 1] = std::isfinite(r) ? std::clamp(r, -1.0f, 1.0f) : 0.0f;
            }
        }
        e.clips.fetch_add(clipped, std::memory_order_relaxed);
        uint64_t state = e.synth.VoiceStates();
        int count = 0;
        for (int i = 0; i < kMaxVoices; ++i, state >>= 2) count += (state & 3) != 0;
        e.voices.store(count, std::memory_order_relaxed);
        const auto elapsed = std::chrono::steady_clock::now() - begin;
        const auto nanos = uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count());
        const double seconds = double(nanos) / 1.0e9;
        e.metrics.record(nanos, frames, e.synth.SampleRate());
        const double percent = frames > 0 ? seconds * e.synth.SampleRate() * 100.0 / frames : 0.0;
        e.renderPercent.store(e.renderPercent.load(std::memory_order_relaxed) * 0.9 + percent * 0.1, std::memory_order_relaxed);
        e.peakPercent.store(std::max(percent, e.peakPercent.load(std::memory_order_relaxed)), std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    void stop() {
        active.store(false);
        if (stream) {
            stoppedXruns = AAudioStream_getXRunCount(stream);
            AAudioStream_requestStop(stream);
            AAudioStream_close(stream); // waits until data callbacks have stopped
            stream = nullptr;
        }
        drain(false);
        synth.Reset();
        voices.store(0);
    }
    std::string start(int device, int requestedBuffer, bool lowLatency) {
        stop();
        error.store(0); clips.store(0); dropped.store(0);
        renderPercent.store(0); peakPercent.store(0);
        metrics.clear(); stoppedXruns = 0; ++streamGeneration;
        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK) return AAudio_convertResultToText(result);
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        // Native sample rate: never force a mismatched USB/device rate.
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, lowLatency ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY : AAUDIO_PERFORMANCE_MODE_NONE);
        if (device > 0) AAudioStreamBuilder_setDeviceId(builder, device);
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, std::max(2048, requestedBuffer * 2));
        AAudioStreamBuilder_setDataCallback(builder, onData, this);
        AAudioStreamBuilder_setErrorCallback(builder, onError, this);
        result = AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK) { stream = nullptr; return AAudio_convertResultToText(result); }
        if (AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_FLOAT || AAudioStream_getChannelCount(stream) != 2) {
            stop(); return "El dispositivo no acepto audio estereo float";
        }
        if (device > 0 && AAudioStream_getDeviceId(stream) != device) {
            stop(); return "Android no acepto la salida elegida. Selecciona otra salida.";
        }
        const int sr = AAudioStream_getSampleRate(stream);
        if (sr != int(synth.SampleRate())) synth.SetSampleRate(sr);
        const int burst = std::max(1, AAudioStream_getFramesPerBurst(stream));
        AAudioStream_setBufferSizeInFrames(stream, std::max(requestedBuffer, burst * 2));
        synth.Reset();
        active.store(true);
        result = AAudioStream_requestStart(stream);
        if (result != AAUDIO_OK) { stop(); return AAudio_convertResultToText(result); }
        return {};
    }
};
Engine* engine(jlong h) { return reinterpret_cast<Engine*>(h); }
std::string utf(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* p = env->GetStringUTFChars(text, nullptr);
    if (!p) return {};
    std::string value(p);
    env->ReleaseStringUTFChars(text, p);
    return value;
}
void throwJava(JNIEnv* env, const char* text) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), text);
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_com_rinoize_risynth2_NativeEngine_create(JNIEnv* env, jclass) {
    try { return reinterpret_cast<jlong>(new Engine()); }
    catch (const std::exception& e) { throwJava(env, e.what()); return 0; }
}
JNIEXPORT void JNICALL Java_com_rinoize_risynth2_NativeEngine_destroy(JNIEnv*, jclass, jlong h) { delete engine(h); }
JNIEXPORT jstring JNICALL Java_com_rinoize_risynth2_NativeEngine_startNative(JNIEnv* env, jclass, jlong h, jint device, jint buffer, jboolean low) {
    try { return env->NewStringUTF(engine(h)->start(device, buffer, low).c_str()); }
    catch (const std::exception& e) { engine(h)->stop(); return env->NewStringUTF(e.what()); }
}
JNIEXPORT void JNICALL Java_com_rinoize_risynth2_NativeEngine_stopNative(JNIEnv*, jclass, jlong h) { engine(h)->stop(); }
JNIEXPORT void JNICALL Java_com_rinoize_risynth2_NativeEngine_midiNative(JNIEnv*, jclass, jlong h, jint status, jint d1, jint d2) {
    auto& e = *engine(h);
    if (!e.active.load(std::memory_order_acquire)) return;
    Event event; event.kind = Event::Midi; event.a = status; event.b = d1 & 127; event.c = d2 & 127;
    e.send(event);
}
JNIEXPORT void JNICALL Java_com_rinoize_risynth2_NativeEngine_panicNative(JNIEnv*, jclass, jlong h) { engine(h)->panic.store(true); }
JNIEXPORT void JNICALL Java_com_rinoize_risynth2_NativeEngine_paramNative(JNIEnv*, jclass, jlong h, jint id, jint value) {
    if (id < 0 || id >= kParamCount) return;
    Event event; event.kind = Event::Parameter; event.a = id; event.b = ClampValue(id, value);
    auto& e = *engine(h);
    if (e.send(event)) e.parameters[id].store(event.b);
}
JNIEXPORT void JNICALL Java_com_rinoize_risynth2_NativeEngine_volumeNative(JNIEnv*, jclass, jlong h, jint value) {
    Event event; event.kind = Event::Volume; event.a = std::clamp(int(value), 0, 127);
    auto& e = *engine(h);
    if (e.send(event)) e.volume = event.a;
}
JNIEXPORT void JNICALL Java_com_rinoize_risynth2_NativeEngine_tempoNative(JNIEnv*, jclass, jlong h, jint value) {
    Event event; event.kind = Event::Tempo; event.a = std::clamp(int(value), 30, 300); engine(h)->send(event);
}
JNIEXPORT jboolean JNICALL Java_com_rinoize_risynth2_NativeEngine_loadNative(JNIEnv* env, jclass, jlong h, jstring text) {
    try {
        auto& e = *engine(h);
        Event event; event.kind = Event::Patch; event.values = ParseSy1(utf(env, text)).values;
        if (!e.send(event)) return false;
        for (int i = 0; i < kParamCount; ++i) e.parameters[i].store(event.values[i]);
        return true;
    } catch (const std::exception& ex) { throwJava(env, ex.what()); return false; }
}
JNIEXPORT jstring JNICALL Java_com_rinoize_risynth2_NativeEngine_saveNative(JNIEnv* env, jclass, jlong h, jstring name) {
    Sy1Patch p; p.name = utf(env, name); p.version = 112;
    auto& e = *engine(h);
    for (int i = 0; i < kParamCount; ++i) p.values[i] = e.parameters[i].load();
    return env->NewStringUTF(WriteSy1(p).c_str());
}
JNIEXPORT jstring JNICALL Java_com_rinoize_risynth2_NativeEngine_initialPatch(JNIEnv* env, jclass) {
    return env->NewStringUTF(WriteSy1(InitPatch()).c_str());
}
JNIEXPORT jintArray JNICALL Java_com_rinoize_risynth2_NativeEngine_valuesNative(JNIEnv* env, jclass, jlong h) {
    std::array<jint, kParamCount> v{};
    for (int i = 0; i < kParamCount; ++i) v[i] = engine(h)->parameters[i].load();
    auto a = env->NewIntArray(kParamCount); if (a) env->SetIntArrayRegion(a, 0, kParamCount, v.data()); return a;
}
JNIEXPORT jobjectArray JNICALL Java_com_rinoize_risynth2_NativeEngine_parameterInfo(JNIEnv* env, jclass) {
    auto a = env->NewObjectArray(kParamCount, env->FindClass("java/lang/String"), nullptr);
    if (!a) return nullptr;
    for (int i = 0; i < kParamCount; ++i) {
        const auto& p = kParams[i];
        const std::string s = std::string(p.name) + "|" + std::to_string(p.min) + "|" + std::to_string(p.max);
        auto text = env->NewStringUTF(s.c_str()); env->SetObjectArrayElement(a, i, text); env->DeleteLocalRef(text);
    }
    return a;
}
JNIEXPORT jdoubleArray JNICALL Java_com_rinoize_risynth2_NativeEngine_statsNative(JNIEnv* env, jclass, jlong h) {
    auto& e = *engine(h);
    const double s[] = {
        e.active.load() ? 1.0 : 0.0,
        e.synth.SampleRate(),
        e.stream ? double(AAudioStream_getFramesPerBurst(e.stream)) : 0.0,
        e.stream ? double(AAudioStream_getBufferSizeInFrames(e.stream)) : 0.0,
        e.stream ? double(AAudioStream_getXRunCount(e.stream)) : double(e.stoppedXruns),
        e.renderPercent.load(), e.peakPercent.load(), double(e.voices.load()),
        double(e.clips.load()), double(e.dropped.load()),
        e.stream ? double(AAudioStream_getDeviceId(e.stream)) : 0.0,
        double(e.error.load()),
        e.stream ? double(AAudioStream_getPerformanceMode(e.stream)) : 0.0,
        double(e.metrics.callbacks.load(std::memory_order_acquire)),
        double(e.metrics.wallNanos.load(std::memory_order_relaxed)),
        double(e.metrics.framesTotal.load(std::memory_order_relaxed)),
        double(e.metrics.overruns.load(std::memory_order_relaxed)),
        e.metrics.intervalPeak.exchange(0, std::memory_order_relaxed),
        double(e.metrics.lastFrames.load(std::memory_order_relaxed)),
        e.stream ? double(AAudioStream_getBufferCapacityInFrames(e.stream)) : 0.0,
        double(e.streamGeneration)
    };
    constexpr int count = int(sizeof(s) / sizeof(s[0]));
    auto a = env->NewDoubleArray(count); if (a) env->SetDoubleArrayRegion(a, 0, count, s); return a;
}
}
