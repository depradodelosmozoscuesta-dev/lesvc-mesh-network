package com.lesvc.mesh.archivo

import com.lesvc.mesh.audio.GGWave
import com.lesvc.mesh.imagen.Formato
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * Transporte binario de ficheros (imágenes) sobre ggwave. ggwave transmite bytes
 * arbitrarios (0x00…0xFF, comprobado en los tests), así que NO se usa Base64.
 *
 * Todas las tramas empiezan por 0x1F (los textos nunca empiezan por un carácter de control):
 *  'H' cabecera (20 bytes): 1F 'H' id(2) versión(1) formato(1) tamaño(4) partes(2) crc32(4) ancho(2) alto(2)
 *  'D' datos (≤140):        1F 'D' id(2) índice(2) partes(2) + hasta 132 bytes del fichero
 *  'R' petición (≤140):     1F 'R' id(2) flags(1: bit0 = falta la cabecera) + rangos [inicio(2) cuántas(1)]…
 * Orden de emisión: H, D0…Dn-1 (cada una una o dos veces), H otra vez (marca el final).
 */
object ProtocoloArchivo {
    const val MARCA: Byte = 0x1F
    const val T_CABECERA = 'H'.code.toByte()
    const val T_DATOS = 'D'.code.toByte()
    const val T_PETICION = 'R'.code.toByte()
    const val VERSION = 1
    const val CAB_DATOS = 8
    const val MAX_DATOS = GGWave.MAX_PAYLOAD_BYTES - CAB_DATOS   // 132
    const val MAX_PARTES = 1000                                    // ~132 KB
    const val TAM_CABECERA = 20
    private const val MAX_RANGOS = (GGWave.MAX_PAYLOAD_BYTES - 5) / 3

    data class Cabecera(val id: Int, val formato: Formato, val tamano: Int, val partes: Int, val crc: Long, val ancho: Int, val alto: Int)

    class Envio(val cabecera: Cabecera, val datos: ByteArray) {
        val tramaCabecera: ByteArray = cabecera(cabecera)
        fun parte(i: Int): ByteArray {
            val ini = i * MAX_DATOS; val fin = minOf(datos.size, ini + MAX_DATOS)
            return ByteBuffer.allocate(CAB_DATOS + fin - ini).put(MARCA).put(T_DATOS)
                .putShort(cabecera.id.toShort()).putShort(i.toShort()).putShort(cabecera.partes.toShort())
                .put(datos, ini, fin - ini).array()
        }
        /** Tramas a emitir (todas o solo [indices]); con [repetir] cada parte va dos veces. */
        fun tramas(indices: List<Int>? = null, repetir: Boolean = false): List<ByteArray> {
            val lista = ArrayList<ByteArray>()
            lista.add(tramaCabecera)
            for (i in indices ?: (0 until cabecera.partes).toList()) {
                val p = parte(i); lista.add(p); if (repetir) lista.add(p)
            }
            lista.add(tramaCabecera)
            return lista
        }
    }

    fun crc32(b: ByteArray): Long = CRC32().also { it.update(b) }.value

    fun preparar(datos: ByteArray, formato: Formato, ancho: Int, alto: Int, id: Int = Random.nextInt(1, 65536)): Envio {
        require(datos.isNotEmpty())
        val partes = (datos.size + MAX_DATOS - 1) / MAX_DATOS
        require(partes <= MAX_PARTES) { "fichero demasiado grande ($partes partes)" }
        return Envio(Cabecera(id and 0xFFFF, formato, datos.size, partes, crc32(datos), ancho, alto), datos)
    }

    fun cabecera(c: Cabecera): ByteArray = ByteBuffer.allocate(TAM_CABECERA).put(MARCA).put(T_CABECERA)
        .putShort(c.id.toShort()).put(VERSION.toByte()).put(c.formato.codigo.toByte()).putInt(c.tamano)
        .putShort(c.partes.toShort()).putInt(c.crc.toInt()).putShort(c.ancho.toShort()).putShort(c.alto.toShort()).array()

    /** Tramas de petición de reenvío (normalmente una). */
    fun peticion(id: Int, faltan: List<Int>, faltaCabecera: Boolean): List<ByteArray> {
        val rangos = ArrayList<Pair<Int, Int>>()
        for (i in faltan.sorted().distinct()) {
            val u = rangos.lastOrNull()
            if (u != null && u.first + u.second == i && u.second < 255) rangos[rangos.size - 1] = Pair(u.first, u.second + 1)
            else rangos.add(Pair(i, 1))
        }
        val res = ArrayList<ByteArray>()
        var k = 0
        do {
            val lote = rangos.subList(k, minOf(rangos.size, k + MAX_RANGOS))
            val bb = ByteBuffer.allocate(5 + 3 * lote.size).put(MARCA).put(T_PETICION).putShort(id.toShort())
                .put((if (faltaCabecera) 1 else 0).toByte())
            lote.forEach { bb.putShort(it.first.toShort()).put(it.second.toByte()) }
            res.add(bb.array()); k += lote.size
        } while (k < rangos.size)
        return res
    }

    sealed class Trama {
        class Cab(val c: Cabecera) : Trama()
        class Datos(val id: Int, val indice: Int, val partes: Int, val datos: ByteArray) : Trama()
        class Peticion(val id: Int, val faltaCabecera: Boolean, val indices: List<Int>) : Trama()
    }

    fun esTramaArchivo(b: ByteArray) = b.size >= 4 && b[0] == MARCA

    /** null si no es una trama válida de este protocolo. */
    fun analizar(b: ByteArray): Trama? {
        if (!esTramaArchivo(b)) return null
        val bb = ByteBuffer.wrap(b); bb.get()
        val tipo = bb.get(); val id = bb.short.toInt() and 0xFFFF
        return try {
            when (tipo) {
                T_CABECERA -> {
                    if (b.size != TAM_CABECERA || bb.get().toInt() != VERSION) return null
                    val f = Formato.de(bb.get().toInt()) ?: return null
                    val tam = bb.int; val partes = bb.short.toInt() and 0xFFFF
                    val crc = bb.int.toLong() and 0xFFFFFFFFL
                    val w = bb.short.toInt() and 0xFFFF; val h = bb.short.toInt() and 0xFFFF
                    if (tam <= 0 || partes != (tam + MAX_DATOS - 1) / MAX_DATOS || partes > MAX_PARTES) return null
                    Trama.Cab(Cabecera(id, f, tam, partes, crc, w, h))
                }
                T_DATOS -> {
                    if (b.size <= CAB_DATOS) return null
                    val i = bb.short.toInt() and 0xFFFF; val n = bb.short.toInt() and 0xFFFF
                    if (n == 0 || n > MAX_PARTES || i >= n) return null
                    Trama.Datos(id, i, n, b.copyOfRange(CAB_DATOS, b.size))
                }
                T_PETICION -> {
                    if (b.size < 5 || (b.size - 5) % 3 != 0) return null
                    val fc = bb.get().toInt() and 1 == 1
                    val idx = ArrayList<Int>()
                    while (bb.remaining() >= 3) {
                        val ini = bb.short.toInt() and 0xFFFF; val cu = bb.get().toInt() and 0xFF
                        for (j in ini until minOf(ini + cu, MAX_PARTES)) idx.add(j)
                    }
                    Trama.Peticion(id, fc, idx)
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    /** "3, 7, 12-15" → [3,7,12,13,14,15]. Admite números de 1 en adelante (como se muestran). */
    fun leerLista(s: String): List<Int> {
        val r = ArrayList<Int>()
        for (t in s.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d+)\\s*-\\s*(\\d+)$").find(t)
            if (m != null) { val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); for (i in a..b) r.add(i - 1) }
            else t.toIntOrNull()?.let { r.add(it - 1) }
        }
        return r.filter { it >= 0 }.distinct().sorted()
    }

    /** [3,7,12,13,14,15] (base 0) → "4, 8, 13-16" (base 1, para personas). */
    fun escribirLista(idx: List<Int>): String {
        val s = idx.sorted().distinct(); val out = ArrayList<String>()
        var i = 0
        while (i < s.size) {
            var j = i
            while (j + 1 < s.size && s[j + 1] == s[j] + 1) j++
            out.add(if (j - i >= 2) "${s[i] + 1}-${s[j] + 1}" else (i..j).joinToString(", ") { "${s[it] + 1}" })
            i = j + 1
        }
        return out.joinToString(", ")
    }
}
