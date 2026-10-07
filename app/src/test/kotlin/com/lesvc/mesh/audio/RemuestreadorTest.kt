package com.lesvc.mesh.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class RemuestreadorTest {
    private fun seno(f: Double, rate: Int, n: Int) = ShortArray(n) { (10000 * sin(2 * PI * f * it / rate)).roundToInt().toShort() }

    /** 44,1 kHz -> 48 kHz en bloques de 1024: el seno sale con la frecuencia y amplitud correctas. */
    @Test fun seno_44100_a_48000_en_bloques() {
        for (f in doubleArrayOf(1000.0, 5000.0, 18000.0)) {
            val inp = seno(f, 44100, 44100)
            val r = Remuestreador(44100)
            val out = ArrayList<Short>()
            var off = 0
            while (off < inp.size) { val k = minOf(1024, inp.size - off); out.addAll(r.procesar(inp.copyOfRange(off, off + k), k).toList()); off += k }
            val ref = seno(f, 48000, out.size)
            // compara en la zona central (descarta 100 muestras de retardo/bordes): busca el retardo óptimo
            var mejor = Double.MAX_VALUE
            for (d in 0..40) {
                var e = 0.0; var s = 0.0
                for (i in 1000 until out.size - 1000) { val x = out[i] - ref[i - d].toDouble(); e += x * x; s += ref[i - d].toDouble() * ref[i - d] }
                mejor = minOf(mejor, sqrt(e / s))
            }
            println("Remuestreador f=$f muestras=${out.size} errorRel=$mejor")
            assertTrue("f=$f err=$mejor n=${out.size}", mejor < 0.05 && abs(out.size - 48000) < 100)
        }
    }
}
