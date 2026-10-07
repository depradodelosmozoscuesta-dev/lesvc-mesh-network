package com.lesvc.mesh.texto

import java.io.ByteArrayOutputStream
import java.util.PriorityQueue
import java.util.zip.CRC32

/**
 * Capa de entropía del modo conversacional (petición de Jorge: códigos de longitud
 * variable en bits, los símbolos frecuentes con 1–2 bits y los raros con más).
 *
 * El codec conversacional produce una secuencia de "jeroglíficos" alineada a bytes
 * (modo 0x13). Aquí cada símbolo se re-codifica con un codificador de rango ESTÁTICO
 * (equivale a un Huffman con longitudes fraccionarias de bit), con tablas de frecuencias
 * entrenadas con el corpus v1:
 *  - modelo principal: cada entrada del diccionario (1 y 2 bytes por igual) + 10 códigos de control + FIN;
 *  - modelo de bytes para los literales (palabras fuera del diccionario), con símbolo de fin en vez de longitud;
 *  - modelo de cifras (nibbles) para los números;
 *  - referencias dentro del mensaje: uniforme sobre el tamaño actual de la tabla (log2(n) bits).
 * Las tablas se derivan de forma determinista de los ficheros congelados de assets/compresion/v1
 * y su huella (CRC32) está fijada en los tests: si cambian, el test falla (= nueva versión → otro id).
 */
class EntropiaConversacional private constructor(
    private val numEntradas: Int,
    private val frecPrincipal: IntArray,
    private val frecBytes: IntArray,
    private val frecCifras: IntArray,
) {
    private val cumPrincipal = acumular(frecPrincipal)
    private val cumBytes = acumular(frecBytes)
    private val cumCifras = acumular(frecCifras)
    private val especial0 = numEntradas
    private val fin = numEntradas + NUM_ESPECIALES

    /** Huella de las tablas (para fijar la versión en los tests). */
    val huella: Long = CRC32().also { c ->
        for (a in listOf(frecPrincipal, frecBytes, frecCifras)) for (v in a) { c.update(v ushr 8); c.update(v) }
    }.value

    // ---- símbolos de la secuencia alineada a bytes ----
    private enum class M { PRINCIPAL, BYTE, CIFRA, UNIFORME }
    private class Sim(val m: M, val s: Int, val n: Int = 0)

    private fun simbolos(b: ByteArray, desde: Int): List<Sim> {
        val l = ArrayList<Sim>()
        var p = desde; var tabla = 0
        fun u8(): Int { require(p < b.size) { "datos incompletos" }; return b[p++].toInt() and 0xFF }
        while (p < b.size) {
            val c = u8()
            when {
                c < CodecConversacional.UNO -> l.add(Sim(M.PRINCIPAL, c))
                c < CodecConversacional.UNO + CodecConversacional.PREF ->
                    l.add(Sim(M.PRINCIPAL, CodecConversacional.UNO + (c - CodecConversacional.UNO) * 256 + u8()))
                c in CodecConversacional.L_PALABRA_MIN..CodecConversacional.R_REPETIR -> {
                    l.add(Sim(M.PRINCIPAL, especial0 + c - CodecConversacional.L_PALABRA_MIN))
                    when (c) {
                        CodecConversacional.L_PALABRA_MIN, CodecConversacional.L_PALABRA, CodecConversacional.L_OTRO, CodecConversacional.L_BLANCO -> {
                            val n = u8()
                            repeat(n) { l.add(Sim(M.BYTE, u8())) }
                            l.add(Sim(M.BYTE, 256))
                            if (c != CodecConversacional.L_BLANCO && tabla < 256) tabla++
                        }
                        CodecConversacional.C_NUMERO -> {
                            loop@ while (true) {
                                val x = u8()
                                for (nb in intArrayOf(x shr 4, x and 15)) { l.add(Sim(M.CIFRA, nb)); if (nb == 15) break@loop }
                            }
                        }
                        CodecConversacional.R_REPETIR -> { val k = u8(); require(k < tabla); l.add(Sim(M.UNIFORME, k, tabla)) }
                    }
                }
                else -> throw IllegalArgumentException("código $c")
            }
        }
        l.add(Sim(M.PRINCIPAL, fin))
        return l
    }

    /** Secuencia alineada a bytes (sin cabecera) → flujo de bits. */
    fun codificar(conv: ByteArray, desde: Int = 0): ByteArray {
        val enc = CodificadorRango()
        for (s in simbolos(conv, desde)) when (s.m) {
            M.PRINCIPAL -> enc.codificar(cumPrincipal[s.s], frecPrincipal[s.s], cumPrincipal.last())
            M.BYTE -> enc.codificar(cumBytes[s.s], frecBytes[s.s], cumBytes.last())
            M.CIFRA -> enc.codificar(cumCifras[s.s], frecCifras[s.s], cumCifras.last())
            M.UNIFORME -> enc.codificar(s.s, 1, s.n)
        }
        return enc.terminar()
    }

    /** Flujo de bits → secuencia alineada a bytes (que luego lee CodecConversacional). */
    fun decodificar(d: ByteArray, desde: Int): ByteArray {
        val dec = DecodificadorRango(d, desde)
        val out = ByteArrayOutputStream()
        var tabla = 0
        fun leer(cum: IntArray, frec: IntArray): Int {
            val v = dec.frecuencia(cum.last())
            var lo = 0; var hi = frec.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (cum[mid] <= v) lo = mid else hi = mid - 1 }
            dec.consumir(cum[lo], frec[lo]); return lo
        }
        var guardia = 0
        while (true) {
            require(++guardia < 100_000 && dec.excesoLectura <= 8) { "flujo corrupto" }
            val s = leer(cumPrincipal, frecPrincipal)
            if (s == fin) break
            if (s < CodecConversacional.UNO) { out.write(s); continue }
            if (s < numEntradas) { val k = s - CodecConversacional.UNO; out.write(CodecConversacional.UNO + k / 256); out.write(k % 256); continue }
            val c = CodecConversacional.L_PALABRA_MIN + (s - especial0)
            out.write(c)
            when (c) {
                CodecConversacional.L_PALABRA_MIN, CodecConversacional.L_PALABRA, CodecConversacional.L_OTRO, CodecConversacional.L_BLANCO -> {
                    val lit = ByteArrayOutputStream()
                    while (true) { val x = leer(cumBytes, frecBytes); if (x == 256) break; lit.write(x); require(lit.size() <= 255) { "literal demasiado largo" } }
                    out.write(lit.size()); out.write(lit.toByteArray())
                    if (c != CodecConversacional.L_BLANCO && tabla < 256) tabla++
                }
                CodecConversacional.C_NUMERO -> {
                    val nib = ArrayList<Int>()
                    while (true) { val x = leer(cumCifras, frecCifras); nib.add(x); if (x == 15) break; require(nib.size < 1000) }
                    if (nib.size % 2 == 1) nib.add(15)
                    for (j in nib.indices step 2) out.write((nib[j] shl 4) or nib[j + 1])
                }
                CodecConversacional.R_REPETIR -> {
                    require(tabla > 0) { "referencia sin tabla" }
                    val k = dec.frecuencia(tabla); dec.consumir(k, 1); out.write(k)
                }
            }
        }
        return out.toByteArray()
    }

    /** Solo para el informe: bits que ocuparía con un Huffman estático de las mismas tablas. */
    fun bitsHuffman(conv: ByteArray, desde: Int = 0): Int {
        var bits = 0
        for (s in simbolos(conv, desde)) bits += when (s.m) {
            M.PRINCIPAL -> lonPrincipal[s.s]; M.BYTE -> lonBytes[s.s]; M.CIFRA -> lonCifras[s.s]
            M.UNIFORME -> 32 - Integer.numberOfLeadingZeros(maxOf(1, s.n - 1))
        }
        return bits
    }
    private val lonPrincipal by lazy { longitudesHuffman(frecPrincipal) }
    private val lonBytes by lazy { longitudesHuffman(frecBytes) }
    private val lonCifras by lazy { longitudesHuffman(frecCifras) }

    /** Para el informe: bits medios por símbolo del modelo principal en estos textos. */
    companion object {
        const val NUM_ESPECIALES = CodecConversacional.R_REPETIR - CodecConversacional.L_PALABRA_MIN + 1  // 10

        private fun acumular(f: IntArray) = IntArray(f.size + 1).also { for (i in f.indices) it[i + 1] = it[i] + f[i] }

        /** Escala cuentas a un total ≤ 2^16 con mínimo 1 (todo símbolo es codificable). */
        private fun escalar(c: LongArray): IntArray {
            val libre = CodificadorRango.MAX_TOTAL - c.size
            val suma = c.sum().coerceAtLeast(1)
            return IntArray(c.size) { 1 + (c[it] * libre / suma).toInt() }
        }

        fun entrenar(codec: CodecConversacional, textos: List<String>): EntropiaConversacional {
            val n = codec.numEntradas
            val proto = EntropiaConversacional(n, IntArray(n + NUM_ESPECIALES + 1) { 1 }, IntArray(257) { 1 }, IntArray(16) { 1 })
            val cp = LongArray(n + NUM_ESPECIALES + 1); val cb = LongArray(257); val cc = LongArray(16)
            for (t in textos) {
                val b = try { codec.codificar(t) } catch (e: Exception) { continue }
                for (s in proto.simbolos(b, 0)) when (s.m) {
                    M.PRINCIPAL -> cp[s.s]++; M.BYTE -> cb[s.s]++; M.CIFRA -> cc[s.s]++; M.UNIFORME -> {}
                }
            }
            return EntropiaConversacional(n, escalar(cp), escalar(cb), escalar(cc))
        }

        fun longitudesHuffman(f: IntArray): IntArray {
            class Nodo(val peso: Long, val sim: Int, val a: Nodo?, val b: Nodo?)
            val pq = PriorityQueue<Nodo>(compareBy<Nodo> { it.peso }.thenBy { it.sim })
            f.forEachIndexed { i, v -> pq.add(Nodo(v.toLong(), i, null, null)) }
            var k = f.size
            while (pq.size > 1) { val x = pq.poll(); val y = pq.poll(); pq.add(Nodo(x.peso + y.peso, k++, x, y)) }
            val lon = IntArray(f.size)
            fun rec(nd: Nodo, d: Int) { if (nd.a == null) lon[nd.sim] = maxOf(1, d) else { rec(nd.a, d + 1); rec(nd.b!!, d + 1) } }
            rec(pq.poll(), 0)
            return lon
        }
    }
}
