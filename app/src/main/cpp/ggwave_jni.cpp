// JNI bridge between Kotlin (com.lesvc.mesh.audio.GGWave) and the ggwave C API.
// Samples are mono PCM 16-bit (I16) for both capture and playback.
#include <jni.h>
#include <mutex>
#include <vector>
#include <cstdio>

#include "ggwave/ggwave.h"

namespace {
std::mutex g_mutex;           // ggwave keeps a global instance table + global protocol flags
bool g_protocolsConfigured = false;

// Only audible + ultrasound protocols are offered in the app. Disabling the
// rest for RX reduces false positives (as recommended in ggwave.h).
void configureProtocolsLocked() {
    if (g_protocolsConfigured) return;
    for (int p = 0; p < GGWAVE_PROTOCOL_COUNT; ++p) {
        const bool on = p <= GGWAVE_PROTOCOL_ULTRASOUND_FASTEST;
        ggwave_rxToggleProtocol((ggwave_ProtocolId) p, on ? 1 : 0);
        ggwave_txToggleProtocol((ggwave_ProtocolId) p, on ? 1 : 0);
    }
    g_protocolsConfigured = true;
}
}

extern "C" {

// mode: 1 = RX, 2 = TX, 3 = RX+TX. Returns instance id (>=0) or -1.
JNIEXPORT jint JNICALL
Java_com_lesvc_mesh_audio_GGWave_nativeInit(JNIEnv *, jclass, jint mode, jint sampleRateInp, jint sampleRateOut) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ggwave_setLogFile(nullptr);  // silence ggwave's stderr logging
    configureProtocolsLocked();

    ggwave_Parameters p = ggwave_getDefaultParameters();
    p.payloadLength   = -1;  // variable length (up to 140 bytes)
    p.sampleRateInp   = (float) sampleRateInp;
    p.sampleRateOut   = (float) sampleRateOut;
    p.sampleRate      = 48000.0f;
    p.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_I16;
    p.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_I16;
    int opMode = 0;
    if (mode & 1) opMode |= GGWAVE_OPERATING_MODE_RX;
    if (mode & 2) opMode |= GGWAVE_OPERATING_MODE_TX;
    p.operatingMode = opMode;

    return ggwave_init(p);
}

JNIEXPORT void JNICALL
Java_com_lesvc_mesh_audio_GGWave_nativeFree(JNIEnv *, jclass, jint id) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (id >= 0) ggwave_free(id);
}

// Returns the mono I16 waveform or null on error.
JNIEXPORT jshortArray JNICALL
Java_com_lesvc_mesh_audio_GGWave_nativeEncode(JNIEnv *env, jclass, jint id, jbyteArray payload, jint protocol, jint volume) {
    const jsize n = env->GetArrayLength(payload);
    if (n <= 0 || n > 140) return nullptr;
    std::vector<jbyte> data(n);
    env->GetByteArrayRegion(payload, 0, n, data.data());

    const int nBytes = ggwave_encode(id, data.data(), n, (ggwave_ProtocolId) protocol, volume, nullptr, 1);
    if (nBytes <= 0) return nullptr;
    std::vector<jshort> wave((nBytes + 1) / 2);
    const int written = ggwave_encode(id, data.data(), n, (ggwave_ProtocolId) protocol, volume, wave.data(), 0);
    if (written <= 0) return nullptr;
    const jsize nSamples = written / 2;

    jshortArray out = env->NewShortArray(nSamples);
    if (out == nullptr) return nullptr;
    env->SetShortArrayRegion(out, 0, nSamples, wave.data());
    return out;
}

// Feeds `count` captured samples to the decoder.
// Returns: null = nothing decoded yet; empty array = a transmission was detected
// but could not be decoded; otherwise the payload bytes.
JNIEXPORT jbyteArray JNICALL
Java_com_lesvc_mesh_audio_GGWave_nativeDecode(JNIEnv *env, jclass, jint id, jshortArray samples, jint count) {
    const jsize len = env->GetArrayLength(samples);
    if (count <= 0) return nullptr;
    if (count > len) count = len;

    jshort *buf = env->GetShortArrayElements(samples, nullptr);
    if (buf == nullptr) return nullptr;
    unsigned char payload[256];
    const int ret = ggwave_ndecode(id, buf, count * 2, payload, sizeof(payload));
    env->ReleaseShortArrayElements(samples, buf, JNI_ABORT);

    if (ret == 0 || ret == -2) return nullptr;
    if (ret < 0) return env->NewByteArray(0);
    jbyteArray out = env->NewByteArray(ret);
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, ret, (const jbyte *) payload);
    return out;
}

} // extern "C"
