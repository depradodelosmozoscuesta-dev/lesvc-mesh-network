package com.lesvc.mesh.audio

import kotlin.math.ceil

/**
 * Duración exacta de las transmisiones ggwave-v0.4.3 (longitud variable, 48 kHz,
 * 1024 muestras/trama). Fórmula comprobada contra ggwave_encode en los tests:
 *   tramas(n) = 32 + framesPorTx * ceil((n + ecc(n) + 3) / bytesPorTx)   (bytesPorTx = 3; Turbo 4)
 *   ecc(n)    = 2 si n < 4, si no max(4, 2·(n/5))
 */
object Duracion {
    const val PAUSA_S = 0.4      // silencio entre partes (Emisor)
    const val COLA_S = 0.2       // margen final (Emisor)

    fun framesPorTx(p: Protocolo) = when (p) {
        Protocolo.AUDIBLE_NORMAL, Protocolo.ULTRA_NORMAL -> 9
        Protocolo.AUDIBLE_RAPIDO, Protocolo.ULTRA_RAPIDO -> 6
        Protocolo.AUDIBLE_MUY_RAPIDO, Protocolo.ULTRA_MUY_RAPIDO -> 3
        Protocolo.AUDIBLE_TURBO -> 2
    }

    fun bytesPorTx(p: Protocolo) = if (p == Protocolo.AUDIBLE_TURBO) 4 else 3

    fun tramas(bytes: Int, p: Protocolo): Int {
        require(bytes in 1..GGWave.MAX_PAYLOAD_BYTES)
        val ecc = if (bytes < 4) 2 else maxOf(4, 2 * (bytes / 5))
        return 32 + framesPorTx(p) * ceil((bytes + ecc + 3) / bytesPorTx(p).toDouble()).toInt()
    }

    fun segundosCarga(bytes: Int, p: Protocolo): Double =
        tramas(bytes, p) * GGWave.SAMPLES_PER_FRAME.toDouble() / GGWave.SAMPLE_RATE_TX

    /** Duración de emitir estas cargas seguidas con el [Emisor]. */
    fun segundosEnvio(tamanos: List<Int>, p: Protocolo): Double =
        if (tamanos.isEmpty()) 0.0
        else tamanos.sumOf { segundosCarga(it, p) } + PAUSA_S * (tamanos.size - 1) + COLA_S

    fun formatear(s: Double): String {
        val t = ceil(s).toInt()
        return if (t < 60) "$t s" else "${t / 60} min ${"%02d".format(t % 60)} s"
    }
}
