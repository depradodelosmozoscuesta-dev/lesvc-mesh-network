package com.lesvc.mesh.audio

/**
 * Protocolos de ggwave ofrecidos en la app (ids = ggwave_ProtocolId).
 * Audibles: ~1,9–6,4 kHz. Ultrasonido ("casi ultrasonido"): ~15–19,5 kHz.
 */
enum class Protocolo(val id: Int, val etiqueta: String, val ultrasonido: Boolean) {
    AUDIBLE_NORMAL(0, "Audible · normal (más fiable)", false),
    AUDIBLE_RAPIDO(1, "Audible · rápido", false),
    AUDIBLE_MUY_RAPIDO(2, "Audible · muy rápido", false),
    ULTRA_NORMAL(3, "Ultrasonido · normal (experimental)", true),
    ULTRA_RAPIDO(4, "Ultrasonido · rápido (experimental)", true),
    ULTRA_MUY_RAPIDO(5, "Ultrasonido · muy rápido (experimental)", true),
    /** Protocolo propio (ggwave CUSTOM_0): 2 tramas y 4 bytes por símbolo, ≈1,76× «muy rápido». Ambos móviles con v0.3+. */
    AUDIBLE_TURBO(12, "Turbo (experimental, corta distancia)", false);
}
