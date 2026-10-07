// Evaluación de protocolos "turbo" personalizados de ggwave v0.4.3 (solo PC, no va en el APK).
// Más tonos por trama (bytesPerTx) y/o tramas más cortas (framesPerTx); misma amplitud y
// marcadores. Canal: eco de sala (respuesta al impulso sintética) + ruido blanco a SNR dada.
#include "ggwave/ggwave.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>
#include <string>

struct Cand { const char* nombre; int id; int freqStart, fpt, bpt; };

// Respuesta al impulso de sala: camino directo + cola "velvet noise" (impulsos ±1 dispersos,
// ~2000/s, técnica estándar de reverberación) con decaimiento exponencial RT60.
static std::vector<float> ir_sala(float rt60, float drr_db, std::mt19937& rng) {
    const int fs = 48000; int n = (int)(rt60 * fs);
    std::vector<float> h(n, 0.f); h[0] = 1.f;
    std::uniform_real_distribution<float> u(0, 1);
    double e = 0; int ini = fs * 5 / 1000; const int paso = 24;  // primeras reflexiones a los 5 ms
    for (int b = ini; b + paso <= n; b += paso) {
        int i = b + (int)(u(rng) * paso); float a = std::exp(-6.908f * i / (rt60 * fs));
        h[i] = (u(rng) < 0.5f ? -a : a); e += h[i] * h[i];
    }
    float k = std::sqrt(std::pow(10.f, -drr_db / 10.f) / e);    // energía directa / reverberada = drr
    for (int i = ini; i < n; i++) h[i] *= k;
    return h;
}

static std::vector<float> convolucionar(const std::vector<float>& x, const std::vector<float>& h) {
    std::vector<float> y(x.size() + h.size(), 0.f);
    std::vector<int> nz; for (int j = 0; j < (int)h.size(); j++) if (h[j] != 0.f) nz.push_back(j);
    for (size_t i = 0; i < x.size(); i++) { float v = x[i]; if (v == 0.f) continue; for (int j : nz) y[i + j] += v * h[j]; }
    return y;
}

int main(int argc, char** argv) {
    int pruebas = argc > 1 ? atoi(argv[1]) : 20;
    int tam = argc > 2 ? atoi(argv[2]) : 140;
    int desde = argc > 3 ? atoi(argv[3]) : 0;   // primer candidato a evaluar
    GGWave::setLogFile(nullptr);
    Cand cands[] = {
        {"Estándar muy rápido (fpt3, 3 B/tx)", GGWAVE_PROTOCOL_AUDIBLE_FASTEST, 40, 3, 3},
        {"Turbo A (fpt2, 3 B/tx)", GGWAVE_PROTOCOL_CUSTOM_0, 40, 2, 3},
        {"Turbo B (fpt3, 4 B/tx)", GGWAVE_PROTOCOL_CUSTOM_1, 40, 3, 4},
        {"Turbo C (fpt2, 4 B/tx)", GGWAVE_PROTOCOL_CUSTOM_2, 40, 2, 4},
        {"Turbo D (fpt1, 3 B/tx)", GGWAVE_PROTOCOL_CUSTOM_3, 40, 1, 3},
    };
    for (auto& c : cands) {
        if (c.id >= GGWAVE_PROTOCOL_CUSTOM_0) {
            GGWave::Protocols::tx()[c.id] = { c.nombre, (int16_t)c.freqStart, (int8_t)c.fpt, (int8_t)c.bpt, 1, true };
            GGWave::Protocols::rx()[c.id] = { c.nombre, (int16_t)c.freqStart, (int8_t)c.fpt, (int8_t)c.bpt, 1, true };
        }
    }
    struct Cond { const char* nombre; float snr; int eco; float ppm; };   // eco: 0 no, 1 sala (RT60 0,4 s, DRR +3 dB), 2 sala fuerte (0,8 s, −3 dB), 3 medio (0,6 s, 0 dB)
    Cond conds[] = { {"limpio", 99, 0, 0}, {"10 dB", 10, 0, 0}, {"10 dB + eco", 10, 1, 0}, {"3 dB", 3, 0, 0}, {"3 dB + eco", 3, 1, 0},
                     {"0 dB + eco", 0, 1, 0}, {"3 dB + eco fuerte", 3, 2, 0}, {"10 dB + eco + reloj 100 ppm", 10, 1, 100},
                     {"10 dB + eco medio", 10, 3, 0}, {"10 dB + eco fuerte", 10, 2, 0} };
    const int NC = sizeof(conds) / sizeof(conds[0]);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    printf("| Protocolo | Duración %d B | Bytes/s |", tam);
    for (auto& d : conds) printf(" %s |", d.nombre);
    printf("\n|---|---|---|"); for (auto& d : conds) { (void)d; printf("---|"); } printf("\n");
    std::mt19937 rng(1234);
    int hasta = argc > 4 ? desde + atoi(argv[4]) : (int)(sizeof(cands) / sizeof(cands[0]));
    for (int ci = desde; ci < hasta; ci++) {
        auto& c = cands[ci];
        // Solo el protocolo candidato activo en tx y rx (como haría la app)
        GGWave::Protocols::tx().only((GGWave::ProtocolId)c.id);
        GGWave::Protocols::rx().only((GGWave::ProtocolId)c.id);
        GGWave::Parameters ptx = GGWave::getDefaultParameters();
        ptx.operatingMode = GGWAVE_OPERATING_MODE_TX; ptx.sampleFormatOut = GGWAVE_SAMPLE_FORMAT_F32;
        GGWave tx(ptx);
        double dur = 0; int okN[16] = {0};
        for (int t = 0; t < pruebas; t++) {
            std::vector<char> datos(tam); for (auto& b : datos) b = (char)(rng() & 0xFF);
            if (!tx.init(tam, datos.data(), (GGWave::TxProtocolId)c.id, 50)) { printf("init falló\n"); return 1; }
            uint32_t nb = tx.encode(); std::vector<float> w(nb / 4); memcpy(w.data(), tx.txWaveform(), nb);
            dur = w.size() / 48000.0;
            std::vector<float> sen(48000 / 2, 0.f); sen.insert(sen.end(), w.begin(), w.end()); sen.resize(sen.size() + 48000, 0.f);
            double p = 0; int n = 0; for (float v : w) { p += v * v; n++; } p /= n;
            for (int k = 0; k < NC; k++) {
                auto& d = conds[k];
                std::vector<float> x = d.eco == 1 ? convolucionar(sen, ir_sala(0.4f, 3.f, rng)) : d.eco == 2 ? convolucionar(sen, ir_sala(0.8f, -3.f, rng))
                                    : d.eco == 3 ? convolucionar(sen, ir_sala(0.6f, 0.f, rng)) : sen;
                if (d.ppm != 0) {   // el reloj del emisor va 100 ppm más rápido que el del receptor
                    double f = 1.0 + d.ppm * 1e-6; std::vector<float> y((size_t)(x.size() / f));
                    for (size_t i = 0; i < y.size(); i++) { double t = i * f; size_t j = (size_t)t; double a = t - j; y[i] = (float)((1 - a) * x[j] + a * (j + 1 < x.size() ? x[j + 1] : 0)); }
                    x.swap(y);
                }
                if (d.snr < 90) { std::normal_distribution<float> g(0, std::sqrt(p / std::pow(10.f, d.snr / 10.f))); for (auto& v : x) v += g(rng); }
                GGWave::Parameters prx = GGWave::getDefaultParameters();
                prx.operatingMode = GGWAVE_OPERATING_MODE_RX; prx.sampleFormatInp = GGWAVE_SAMPLE_FORMAT_F32;
                GGWave rx(prx);
                bool ok = false;
                GGWave::TxRxData out;
                for (size_t i = 0; i + 1024 <= x.size() && !ok; i += 1024) {
                    rx.decode(x.data() + i, 1024 * 4);
                    int r = rx.rxTakeData(out);
                    if (r == tam && memcmp(out.data(), datos.data(), tam) == 0) ok = true;
                }
                okN[k] += ok;
            }
        }
        printf("| %s | %.2f s | %.1f |", c.nombre, dur, tam / dur);
        for (int k = 0; k < NC; k++) printf(" %d/%d |", okN[k], pruebas);
        printf("\n"); fflush(stdout);
    }
}
