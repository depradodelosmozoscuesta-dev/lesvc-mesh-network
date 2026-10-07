package com.lesvc.mesh.audio

import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Divide textos UTF-8 en cargas de ggwave (máx. 140 bytes) y los vuelve a unir.
 *
 * Formato:
 *  - Si el texto cabe en 140 bytes UTF-8 se envía tal cual (compatible con
 *    cualquier otra app ggwave, p. ej. Waver).
 *  - Si no cabe, cada parte lleva una cabecera de 5 bytes ASCII:
 *      0x1E (separador de registro) + ID (2 caracteres base36, al azar)
 *      + índice (1 carácter base36, 0..35) + (total-1) (1 carácter base36)
 *    seguida de hasta 135 bytes del texto. Nunca se corta un carácter UTF-8
 *    por la mitad. Máximo 36 partes (~4.800 bytes).
 */
object Troceador {
    const val MAX_BYTES = GGWave.MAX_PAYLOAD_BYTES
    const val MARCA: Byte = 0x1E
    const val CABECERA = 5
    const val MAX_TROZO = MAX_BYTES - CABECERA
    const val MAX_PARTES = 36
    private const val B36 = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun bytesUtf8(texto: String): Int = texto.toByteArray(StandardCharsets.UTF_8).size

    /** Trozos de texto (sin cabecera) en que se dividiría. */
    fun trozos(texto: String): List<ByteArray> {
        val total = texto.toByteArray(StandardCharsets.UTF_8)
        if (total.size <= MAX_BYTES) return listOf(total)
        val res = ArrayList<ByteArray>()
        val actual = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < texto.length) {
            val cp = texto.codePointAt(i)
            val b = String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)
            if (actual.size() + b.size > MAX_TROZO) {
                res.add(actual.toByteArray()); actual.reset()
            }
            actual.write(b)
            i += Character.charCount(cp)
        }
        if (actual.size() > 0) res.add(actual.toByteArray())
        return res
    }

    fun partesNecesarias(texto: String): Int = if (texto.isEmpty()) 0 else trozos(texto).size

    /** Cargas listas para ggwave. Lanza IllegalArgumentException si excede [MAX_PARTES]. */
    fun cargas(texto: String, id: String = nuevoId()): List<ByteArray> {
        require(texto.isNotEmpty()) { "Texto vacío" }
        val tr = trozos(texto)
        if (tr.size == 1) return tr
        require(tr.size <= MAX_PARTES) { "Mensaje demasiado largo: ${tr.size} partes (máx. $MAX_PARTES)" }
        require(id.length == 2 && id.all { it in B36 })
        return tr.mapIndexed { idx, trozo ->
            val cab = byteArrayOf(MARCA, id[0].code.toByte(), id[1].code.toByte(),
                B36[idx].code.toByte(), B36[tr.size - 1].code.toByte())
            cab + trozo
        }
    }

    /** Trocea bytes arbitrarios (p. ej. texto comprimido) con la misma cabecera 0x1E. */
    fun cargasBytes(datos: ByteArray, id: String = nuevoId()): List<ByteArray> {
        require(datos.isNotEmpty())
        val n = (datos.size + MAX_TROZO - 1) / MAX_TROZO
        require(n <= MAX_PARTES) { "Mensaje demasiado largo: $n partes (máx. $MAX_PARTES)" }
        return (0 until n).map { idx ->
            val trozo = datos.copyOfRange(idx * MAX_TROZO, minOf(datos.size, (idx + 1) * MAX_TROZO))
            byteArrayOf(MARCA, id[0].code.toByte(), id[1].code.toByte(), B36[idx].code.toByte(), B36[n - 1].code.toByte()) + trozo
        }
    }

    fun nuevoId(): String = "" + B36[Random.nextInt(36)] + B36[Random.nextInt(36)]

    internal fun b36(c: Byte): Int = B36.indexOf(c.toInt().toChar())
}

/** Une las partes recibidas. No es seguro entre hilos: úsalo desde un único hilo. */
class Reensamblador(private val caducidadMs: Long = 120_000) {

    sealed class Resultado {
        class Completo(val datos: ByteArray, val partes: Int) : Resultado() {
            val texto: String get() = String(datos, Charsets.UTF_8)
        }
        class Parcial(val id: String, val recibidas: Int, val total: Int) : Resultado()
        object Invalido : Resultado()
    }

    private class Pendiente(val total: Int, var ultimo: Long) {
        val partes = arrayOfNulls<ByteArray>(total)
        val recibidas get() = partes.count { it != null }
    }

    private val pendientes = HashMap<String, Pendiente>()

    fun recibir(carga: ByteArray, ahoraMs: Long = System.currentTimeMillis()): Resultado {
        pendientes.entries.removeAll { ahoraMs - it.value.ultimo > caducidadMs }
        if (carga.isEmpty()) return Resultado.Invalido
        if (carga[0] != Troceador.MARCA || carga.size < Troceador.CABECERA) {
            return Resultado.Completo(carga, 1)
        }
        val id = String(byteArrayOf(carga[1], carga[2]), Charsets.US_ASCII)
        val idx = Troceador.b36(carga[3])
        val total = Troceador.b36(carga[4]) + 1
        if (idx < 0 || total < 1 || idx >= total) return Resultado.Invalido
        val p = pendientes[id]?.takeIf { it.total == total } ?: Pendiente(total, ahoraMs).also { pendientes[id] = it }
        p.ultimo = ahoraMs
        p.partes[idx] = carga.copyOfRange(Troceador.CABECERA, carga.size)
        if (p.recibidas < total) return Resultado.Parcial(id, p.recibidas, total)
        pendientes.remove(id)
        val todo = java.io.ByteArrayOutputStream()
        p.partes.forEach { todo.write(it!!) }
        return Resultado.Completo(todo.toByteArray(), total)
    }
}
