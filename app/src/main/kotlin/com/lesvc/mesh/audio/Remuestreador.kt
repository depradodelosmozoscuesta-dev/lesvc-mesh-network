package com.lesvc.mesh.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Remuestreador en streaming (sinc con ventana de Hann) para micros que solo
 * graban a 44,1 kHz: convierte a 48 kHz antes de dárselo a ggwave.
 *
 * Por qué no usamos el remuestreo interno de ggwave: en ggwave-v0.4.3, cuando
 * la captura no es de 48 kHz y se entregan bloques pequeños (p. ej. 1024
 * muestras), ggwave descarta los restos de ≤128 muestras de cada bloque y el
 * mensaje no se decodifica (comprobado en GGWaveRoundTripTest).
 */
class Remuestreador(private val entrada: Int, private val salida: Int = GGWave.SAMPLE_RATE_TX, private val mitad: Int = 16) {
    private val paso = entrada.toDouble() / salida          // muestras de entrada por muestra de salida
    private val corte = minOf(1.0, salida.toDouble() / entrada) // filtro anti-aliasing si se baja de frecuencia
    private var buf = FloatArray(8192)
    private var len = mitad                                   // relleno inicial con ceros
    private var t = mitad.toDouble()

    val necesario get() = entrada != salida

    fun procesar(inp: ShortArray, n: Int): ShortArray {
        if (!necesario) return inp.copyOf(n)
        if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        for (i in 0 until n) buf[len + i] = inp[i].toFloat()
        len += n
        val out = ShortArray(((len - t) / paso).toInt() + 2)
        var k = 0
        while (t + mitad < len && k < out.size) {
            val c = floor(t).toInt()
            var acc = 0.0
            for (j in c - mitad + 1..c + mitad) {
                val x = t - j
                acc += buf[j] * h(x)
            }
            out[k++] = acc.roundToInt().coerceIn(-32768, 32767).toShort()
            t += paso
        }
        val descartar = floor(t).toInt() - mitad
        if (descartar > 0) {
            System.arraycopy(buf, descartar, buf, 0, len - descartar)
            len -= descartar
            t -= descartar
        }
        return out.copyOf(k)
    }

    private fun h(x: Double): Double {
        if (x <= -mitad || x >= mitad) return 0.0
        val v = x * corte
        val sinc = if (v == 0.0) 1.0 else sin(PI * v) / (PI * v)
        val ventana = 0.5 * (1 + cos(PI * x / mitad))
        return corte * sinc * ventana
    }
}
