package com.lesvc.mesh.texto

/**
 * Modelo de contexto estático tipo PPM (método C de escape, con exclusión),
 * entrenado con un corpus fijo. Emisor y receptor construyen exactamente el
 * mismo modelo a partir de los mismos ficheros (determinista: solo enteros y
 * ordenaciones estables). Símbolos = caracteres Unicode (code points).
 */
class ModeloPPM private constructor(
    private val orden: Int,
    private val simbolos: IntArray,              // code points conocidos, ordenados
    private val claves: Array<LongArray>,        // por orden: claves de contexto ordenadas
    private val inicio: Array<IntArray>,         // por orden: offset de cada contexto (+1 final)
    private val sims: Array<IntArray>,           // por orden: símbolos de cada contexto
    private val frecs: Array<IntArray>,          // por orden: frecuencias (escaladas)
) {
    private val n = simbolos.size
    private val idxEOF = n
    private val idxESC = n + 1                   // carácter desconocido (se codifica en crudo)
    private val idxSTART = n + 2                 // solo como relleno de contexto
    private val alfabeto = n + 2                 // símbolos codificables

    val tamano: Int get() = sims.sumOf { it.size }

    private fun indice(cp: Int): Int {
        val i = simbolos.binarySearch(cp)
        return if (i >= 0) i else idxESC
    }

    // ---------- codificación ----------

    fun codificar(texto: String, enc: CodificadorRango) {
        val hist = IntArray(orden) { idxSTART }
        val excl = BooleanArray(alfabeto)
        var i = 0
        while (i <= texto.length) {
            val cp: Int; val s: Int
            if (i == texto.length) { cp = -1; s = idxEOF; i++ } else {
                cp = texto.codePointAt(i); s = indice(cp); i += Character.charCount(cp)
            }
            codificarSimbolo(s, hist, excl, enc)
            if (s == idxESC) {
                enc.codificar((cp shr 14) and 0x7F, 1, 128)
                enc.codificar((cp shr 7) and 0x7F, 1, 128)
                enc.codificar(cp and 0x7F, 1, 128)
            }
            desplazar(hist, s)
        }
    }

    private fun codificarSimbolo(s: Int, hist: IntArray, excl: BooleanArray, enc: CodificadorRango) {
        java.util.Arrays.fill(excl, false)
        for (o in orden downTo 0) {
            val c = buscar(o, hist) ; if (c < 0) continue
            val ini = inicio[o][c]; val fin = inicio[o][c + 1]
            val sm = sims[o]; val fr = frecs[o]
            var tot = 0; var d = 0; var cum = -1; var f = 0
            for (j in ini until fin) {
                val x = sm[j]; if (excl[x]) continue
                if (x == s) { cum = tot; f = fr[j] }
                tot += fr[j]; d++
            }
            if (d == 0) continue
            if (cum >= 0) { enc.codificar(cum, f, tot + d); return }
            enc.codificar(tot, d, tot + d)
            for (j in ini until fin) excl[sm[j]] = true
        }
        var cnt = 0; var cum = 0
        for (x in 0 until alfabeto) if (!excl[x]) { if (x < s) cum++; cnt++ }
        enc.codificar(cum, 1, cnt)
    }

    // ---------- decodificación ----------

    fun decodificar(dec: DecodificadorRango, maxCaracteres: Int = 20000): String {
        val sb = StringBuilder()
        val hist = IntArray(orden) { idxSTART }
        val excl = BooleanArray(alfabeto)
        repeat(maxCaracteres + 1) {
            val s = decodificarSimbolo(hist, excl, dec)
            if (s == idxEOF) return sb.toString()
            if (s == idxESC) {
                var cp = 0
                repeat(3) { val v = dec.frecuencia(128); dec.consumir(v, 1); cp = (cp shl 7) or v }
                require(Character.isValidCodePoint(cp)) { "carácter inválido" }
                sb.appendCodePoint(cp)
            } else sb.appendCodePoint(simbolos[s])
            require(dec.excesoLectura <= 8) { "datos comprimidos incompletos" }
            desplazar(hist, s)
        }
        throw IllegalArgumentException("texto comprimido sin final")
    }

    private fun decodificarSimbolo(hist: IntArray, excl: BooleanArray, dec: DecodificadorRango): Int {
        java.util.Arrays.fill(excl, false)
        for (o in orden downTo 0) {
            val c = buscar(o, hist) ; if (c < 0) continue
            val ini = inicio[o][c]; val fin = inicio[o][c + 1]
            val sm = sims[o]; val fr = frecs[o]
            var tot = 0; var d = 0
            for (j in ini until fin) { if (!excl[sm[j]]) { tot += fr[j]; d++ } }
            if (d == 0) continue
            val v = dec.frecuencia(tot + d)
            if (v >= tot) {
                dec.consumir(tot, d)
                for (j in ini until fin) excl[sm[j]] = true
                continue
            }
            var cum = 0
            for (j in ini until fin) {
                val x = sm[j]; if (excl[x]) continue
                if (v < cum + fr[j]) { dec.consumir(cum, fr[j]); return x }
                cum += fr[j]
            }
            error("modelo inconsistente")
        }
        var cnt = 0
        for (x in 0 until alfabeto) if (!excl[x]) cnt++
        val v = dec.frecuencia(cnt)
        var k = 0
        for (x in 0 until alfabeto) if (!excl[x]) { if (k == v) { dec.consumir(v, 1); return x }; k++ }
        error("modelo inconsistente")
    }

    // ---------- utilidades ----------

    private fun desplazar(hist: IntArray, s: Int) {
        if (orden == 0) return
        System.arraycopy(hist, 1, hist, 0, orden - 1)
        hist[orden - 1] = s
    }

    private fun buscar(o: Int, hist: IntArray): Int {
        var k = 0L
        for (j in orden - o until orden) k = (k shl BITS) or hist[j].toLong()
        return claves[o].binarySearch(k).let { if (it >= 0) it else -1 }
    }

    companion object {
        private const val BITS = 12
        private const val MAX_ESCALA = 60000

        /**
         * @param textos líneas de entrenamiento (cada una es un "mensaje")
         * @param poda descarta contextos de orden ≥ 2 vistos menos de [poda] veces
         */
        fun entrenar(textos: List<String>, orden: Int, poda: Int = 1): ModeloPPM {
            require(orden in 0..4)
            val set = java.util.TreeSet<Int>()
            for (t in textos) t.codePoints().forEach { set.add(it) }
            val simbolos = set.toIntArray()
            require(simbolos.size + 3 < (1 shl BITS)) { "alfabeto demasiado grande" }
            val n = simbolos.size
            val idxEOF = n; val idxSTART = n + 2
            // secuencias de índices
            val secs = textos.map { t ->
                val cps = t.codePoints().toArray()
                IntArray(cps.size + 1) { i -> if (i < cps.size) simbolos.binarySearch(cps[i]) else idxEOF }
            }
            val total = secs.sumOf { it.size }
            val claves = arrayOfNulls<LongArray>(orden + 1)
            val inicio = arrayOfNulls<IntArray>(orden + 1)
            val sims = arrayOfNulls<IntArray>(orden + 1)
            val frecs = arrayOfNulls<IntArray>(orden + 1)
            for (o in 0..orden) {
                // cada aparición = (contexto << BITS) | símbolo
                val occ = LongArray(total)
                var p = 0
                for (sq in secs) for (i in sq.indices) {
                    var k = 0L
                    for (j in i - o until i) k = (k shl BITS) or (if (j < 0) idxSTART else sq[j]).toLong()
                    occ[p++] = (k shl BITS) or sq[i].toLong()
                }
                occ.sort()
                val ks = ArrayList<Long>(); val ini = ArrayList<Int>(); val ss = ArrayList<Int>(); val fs = ArrayList<Int>()
                var a = 0
                while (a < occ.size) {
                    val ctx = occ[a] ushr BITS
                    var b = a
                    while (b < occ.size && (occ[b] ushr BITS) == ctx) b++
                    if (o < 2 || b - a >= poda) {
                        ks.add(ctx); ini.add(ss.size)
                        val c0 = ss.size
                        var x = a
                        while (x < b) {
                            var y = x
                            while (y < b && occ[y] == occ[x]) y++
                            ss.add((occ[x] and ((1L shl BITS) - 1)).toInt()); fs.add(y - x)
                            x = y
                        }
                        // escalado para que total + distintos quepa en el codificador
                        var t = 0L; for (q in c0 until ss.size) t += fs[q]
                        val d = ss.size - c0
                        if (t + d > MAX_ESCALA) {
                            for (q in c0 until ss.size) fs[q] = maxOf(1, (fs[q] * MAX_ESCALA.toLong() / (t + d)).toInt())
                        }
                    }
                    a = b
                }
                ini.add(ss.size)
                claves[o] = ks.toLongArray(); inicio[o] = ini.toIntArray()
                sims[o] = ss.toIntArray(); frecs[o] = fs.toIntArray()
            }
            @Suppress("UNCHECKED_CAST")
            return ModeloPPM(orden, simbolos, claves as Array<LongArray>, inicio as Array<IntArray>,
                sims as Array<IntArray>, frecs as Array<IntArray>)
        }
    }
}
