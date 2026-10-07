package com.lesvc.mesh.texto

import java.io.ByteArrayOutputStream

/**
 * Codificador de rango "carryless" (Subbotin), aritmética entera de 32 bits:
 * resultado idéntico en cualquier JVM/ART. Frecuencia total máxima: [MAX_TOTAL].
 */
class CodificadorRango {
    private var low = 0L
    private var range = 0xFFFFFFFFL
    private val out = ByteArrayOutputStream()

    fun codificar(cum: Int, frec: Int, total: Int) {
        require(frec > 0 && cum + frec <= total && total <= MAX_TOTAL)
        range /= total
        low = (low + cum * range) and M32
        range *= frec
        normalizar()
    }

    private fun normalizar() {
        while (true) {
            if ((low xor (low + range)) >= TOP) {
                if (range >= BOT) break
                range = (-low) and (BOT - 1)
            }
            out.write((low ushr 24).toInt())
            low = (low shl 8) and M32
            range = (range shl 8) and M32
        }
    }

    /** Cierra el flujo con el menor número de bytes posible (el decodificador rellena con ceros). */
    fun terminar(): ByteArray {
        for (n in 0..4) {
            val mask = if (n == 4) 0L else (1L shl (32 - 8 * n)) - 1
            if (low + mask > M32) continue
            val v = (low + mask) and mask.inv()
            if (v - low < range) {
                for (i in 0 until n) out.write(((v ushr (24 - 8 * i)) and 0xFF).toInt())
                return quitarCerosFinales(out.toByteArray())
            }
        }
        error("estado imposible del codificador de rango")
    }

    private fun quitarCerosFinales(b: ByteArray): ByteArray {
        var n = b.size
        while (n > 0 && b[n - 1].toInt() == 0) n--
        return b.copyOf(n)
    }

    companion object {
        const val MAX_TOTAL = 1 shl 16
        internal const val TOP = 1L shl 24
        internal const val BOT = 1L shl 16
        internal const val M32 = 0xFFFFFFFFL
    }
}

class DecodificadorRango(private val datos: ByteArray, private var pos: Int = 0) {
    private var low = 0L
    private var range = 0xFFFFFFFFL
    private var code = 0L

    init {
        repeat(4) { code = ((code shl 8) or siguiente().toLong()) and CodificadorRango.M32 }
    }

    private fun siguiente(): Int = if (pos < datos.size) datos[pos++].toInt() and 0xFF else { pos++; 0 }

    /** Devuelve la frecuencia acumulada objetivo; luego hay que llamar a [consumir]. */
    fun frecuencia(total: Int): Int {
        range /= total
        val v = ((code - low) and CodificadorRango.M32) / range
        return if (v >= total) total - 1 else v.toInt()
    }

    fun consumir(cum: Int, frec: Int) {
        low = (low + cum * range) and CodificadorRango.M32
        range *= frec
        while (true) {
            if ((low xor (low + range)) >= CodificadorRango.TOP) {
                if (range >= CodificadorRango.BOT) break
                range = (-low) and (CodificadorRango.BOT - 1)
            }
            code = ((code shl 8) or siguiente().toLong()) and CodificadorRango.M32
            low = (low shl 8) and CodificadorRango.M32
            range = (range shl 8) and CodificadorRango.M32
        }
    }

    /** Bytes leídos más allá del final (para detectar flujos corruptos). */
    val excesoLectura get() = maxOf(0, pos - datos.size)
}
