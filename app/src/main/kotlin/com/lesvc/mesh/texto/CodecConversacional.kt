package com.lesvc.mesh.texto

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Modo CONVERSACIONAL ("jeroglíficos", idea de Jorge): palabras y frases frecuentes
 * del español → códigos de 1 byte (200 entradas) o 2 bytes (hasta 10.240), con
 * escape a UTF-8 para lo que no está en el diccionario. Exacto (sin pérdidas):
 *  - mayúsculas: se presupone mayúscula inicial al empezar frase y minúscula en el
 *    resto; si no es así se añade un código de 1 byte (cambio / TODO MAYÚSCULAS);
 *  - tildes: forman parte de la palabra del diccionario ("que" y "qué" son entradas distintas);
 *  - espacios: se presupone un espacio entre palabra y palabra y tras , . ; : ! ? ),
 *    y ninguno antes de esos signos ni tras ¿ ¡ (; las excepciones llevan un código.
 * Diccionario versionado: assets/compresion/v1/diccionario_conversacional.txt.
 *
 * Referencias dentro del mensaje (idea de Jorge, estilo LZ78): cada literal (palabra fuera
 * del diccionario, nombre propio, emoji…) entra en una tabla de este mensaje; si vuelve a
 * aparecer se escribe R_REPETIR + índice (2 bytes) en vez de repetirlo. Nunca empeora:
 * 2 bytes frente a 2+n del literal. Primero diccionario estático, luego referencias.
 */
class CodecConversacional(entradas: List<String>, private val referencias: Boolean = true) {
    private val uno = entradas.take(UNO)
    private val dos = entradas.drop(UNO).take(PREF * 256)
    private val idUno = HashMap<String, Int>().also { m -> uno.forEachIndexed { i, s -> m.putIfAbsent(s, i) } }
    private val idDos = HashMap<String, Int>().also { m -> dos.forEachIndexed { i, s -> m.putIfAbsent(s, i) } }
    val numEntradas get() = uno.size + dos.size
    private val maxPalabrasFrase = uno.maxOf { it.count { c -> c == ' ' } + 1 }

    private enum class Clase { PALABRA, NUMERO, CIERRE, APERTURA, OTRO }
    private class Item(val texto: String, val clase: Clase)

    // ---------------- codificación ----------------

    private val tablaEnc = ArrayList<Pair<Int, String>>()   // solo durante codificar() (la clase no es reentrante para codificar)

    @Synchronized
    fun codificar(texto: String): ByteArray {
        tablaEnc.clear()
        val out = ByteArrayOutputStream()
        val (items, espacios) = trocear(texto)   // espacios[i] = blancos ANTES del item i; espacios[n] = finales
        if (items.isEmpty()) { literal(out, L_BLANCO, texto); return out.toByteArray() }
        if (espacios[0].isNotEmpty()) literal(out, L_BLANCO, espacios[0])
        var inicioFrase = true
        var i = 0
        var previo: Item? = null
        while (i < items.size) {
            val it = items[i]
            if (previo != null) separador(out, previo, it, espacios[i])
            when (it.clase) {
                Clase.PALABRA -> {
                    val consumidas = palabra(out, items, espacios, i, inicioFrase)
                    previo = items[i + consumidas - 1]
                    i += consumidas; inicioFrase = false; continue
                }
                Clase.NUMERO -> { numero(out, it.texto); inicioFrase = false }
                else -> {
                    val id = idUno[it.texto]
                    if (id != null) out.write(id) else literalRef(out, L_OTRO, it.texto)
                    if (it.texto in FIN_FRASE) inicioFrase = true
                    else if (it.texto != "¿" && it.texto != "¡") inicioFrase = false
                }
            }
            previo = it; i++
        }
        if (espacios[items.size].isNotEmpty()) literal(out, L_BLANCO, espacios[items.size])
        return out.toByteArray()
    }

    private fun separador(out: ByteArrayOutputStream, x: Item, y: Item, ws: String) {
        val imp = implicito(x.clase, y.clase)
        when {
            ws.isEmpty() && imp -> out.write(C_SIN_ESPACIO)
            ws == " " && !imp -> out.write(C_ESPACIO)
            ws.isEmpty() || ws == " " -> {}
            else -> literal(out, L_BLANCO, ws)
        }
    }

    /** Devuelve cuántos items (palabras) ha consumido. */
    private fun palabra(out: ByteArrayOutputStream, items: List<Item>, esp: List<String>, i: Int, inicioFrase: Boolean): Int {
        val w = items[i].texto
        val tipo = tipoMayus(w)
        if (tipo == MIXTA) { literalRef(out, L_PALABRA, w); return 1 }
        // frases (solo la primera palabra puede llevar mayúscula)
        if (tipo != MAYUS) {
            var mejor = 1; var idFrase = -1
            val sb = StringBuilder(w.lowercase(Locale.ROOT))
            var k = 1
            while (k < maxPalabrasFrase && i + k < items.size) {
                val sig = items[i + k]
                if (sig.clase != Clase.PALABRA || esp[i + k] != " " || tipoMayus(sig.texto) != MINUS) break
                sb.append(' ').append(sig.texto)
                k++
                idUno[sb.toString()]?.let { mejor = k; idFrase = it }
            }
            if (idFrase >= 0) {
                if ((tipo == CAPITAL) != inicioFrase) out.write(C_CAMBIO_MAYUS)
                out.write(idFrase)
                return mejor
            }
        }
        val low = w.lowercase(Locale.ROOT)
        if (tipo == MAYUS) out.write(C_TODO_MAYUS) else if ((tipo == CAPITAL) != inicioFrase) out.write(C_CAMBIO_MAYUS)
        val a = idUno[low]
        val b = idDos[low]
        when {
            a != null -> out.write(a)
            b != null -> { out.write(UNO + b / 256); out.write(b % 256) }
            else -> literalRef(out, L_PALABRA_MIN, low)
        }
        return 1
    }

    private fun numero(out: ByteArrayOutputStream, s: String) {
        out.write(C_NUMERO)
        val nib = ArrayList<Int>()
        for (c in s) nib.add(when (c) { ':' -> 10; '.' -> 11; ',' -> 12; else -> c - '0' })
        nib.add(15)
        if (nib.size % 2 == 1) nib.add(15)
        for (j in nib.indices step 2) out.write((nib[j] shl 4) or nib[j + 1])
    }

    /** Literal con referencia a una aparición anterior en el mismo mensaje, si la hay. */
    private fun literalRef(out: ByteArrayOutputStream, codigo: Int, s: String) {
        if (referencias) {
            val k = tablaEnc.indexOf(Pair(codigo, s))
            if (k >= 0) { out.write(R_REPETIR); out.write(k); return }
        }
        literal(out, codigo, s)
    }

    private fun anotarEnc(codigo: Int, trozo: String) {
        if (codigo != L_BLANCO && tablaEnc.size < MAX_TABLA) tablaEnc.add(Pair(codigo, trozo))
    }

    private fun literal(out: ByteArrayOutputStream, codigo: Int, s: String) {
        var b = s.toByteArray(Charsets.UTF_8)
        // trozos de ≤255 bytes sin partir caracteres
        var off = 0
        val txt = s
        if (b.size <= 255) { out.write(codigo); out.write(b.size); out.write(b); anotarEnc(codigo, s); return }
        var i = 0
        while (i < txt.length) {
            var j = i; var n = 0
            while (j < txt.length) {
                val cp = txt.codePointAt(j); val l = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (n + l > 255) break
                n += l; j += Character.charCount(cp)
            }
            val trozo = txt.substring(i, j).toByteArray(Charsets.UTF_8)
            out.write(codigo); out.write(trozo.size); out.write(trozo); anotarEnc(codigo, txt.substring(i, j))
            if (codigo == L_PALABRA || codigo == L_PALABRA_MIN || codigo == L_OTRO) {
                if (j < txt.length) out.write(C_SIN_ESPACIO)
            }
            i = j
        }
        off += 0; b = ByteArray(0)
    }

    // ---------------- decodificación ----------------

    fun decodificar(d: ByteArray, desde: Int = 0): String {
        val sb = StringBuilder()
        var p = desde
        var previo: Clase? = null
        var pendienteBlanco = false   // hubo blanco explícito o "sin espacio" desde el item anterior
        var cambio = false; var todoMayus = false
        var inicioFrase = true
        val tabla = ArrayList<Pair<Int, String>>()
        fun u8(): Int { require(p < d.size) { "datos incompletos" }; return d[p++].toInt() and 0xFF }
        fun emitir(t: String, c: Clase) {
            if (previo != null && !pendienteBlanco && implicito(previo!!, c)) sb.append(' ')
            sb.append(t); previo = c; pendienteBlanco = false
        }
        fun aplicarMayus(low: String): String {
            val r = when {
                todoMayus -> low.uppercase(Locale.ROOT)
                inicioFrase != cambio -> capital(low)
                else -> low
            }
            cambio = false; todoMayus = false
            return r
        }
        while (p < d.size) {
            val c = u8()
            when {
                c < UNO -> {
                    val e = uno[c]
                    val cl = clasePunt(e)
                    if (cl == Clase.PALABRA) {
                        emitir(aplicarMayus(e), Clase.PALABRA); inicioFrase = false
                    } else {
                        emitir(e, cl)
                        if (e in FIN_FRASE) inicioFrase = true else if (e != "¿" && e != "¡") inicioFrase = false
                    }
                }
                c < UNO + PREF -> {
                    val k = (c - UNO) * 256 + u8()
                    require(k < dos.size) { "código fuera de diccionario" }
                    emitir(aplicarMayus(dos[k]), Clase.PALABRA); inicioFrase = false
                }
                c == C_CAMBIO_MAYUS -> cambio = true
                c == C_TODO_MAYUS -> todoMayus = true
                c == C_ESPACIO -> { sb.append(' '); pendienteBlanco = true }
                c == C_SIN_ESPACIO -> pendienteBlanco = true
                c == C_NUMERO -> {
                    val s = StringBuilder()
                    loop@ while (true) {
                        val b = u8()
                        for (n in intArrayOf(b shr 4, b and 15)) {
                            if (n == 15) break@loop
                            s.append(when (n) { 10 -> ':'; 11 -> '.'; 12 -> ','; else -> '0' + n })
                        }
                    }
                    emitir(s.toString(), Clase.NUMERO); inicioFrase = false
                }
                c == L_PALABRA_MIN || c == L_PALABRA || c == L_OTRO || c == L_BLANCO || c == R_REPETIR -> {
                    val cod: Int; val s: String
                    if (c == R_REPETIR) {
                        val k = u8(); require(k < tabla.size) { "referencia inválida" }
                        cod = tabla[k].first; s = tabla[k].second
                    } else {
                        val n = u8(); require(p + n <= d.size) { "datos incompletos" }
                        s = String(d, p, n, Charsets.UTF_8); p += n; cod = c
                        if (c != L_BLANCO && tabla.size < MAX_TABLA) tabla.add(Pair(c, s))
                    }
                    when (cod) {
                        L_PALABRA_MIN -> { emitir(aplicarMayus(s), Clase.PALABRA); inicioFrase = false }
                        L_PALABRA -> { emitir(s, Clase.PALABRA); cambio = false; todoMayus = false; inicioFrase = false }
                        L_OTRO -> { val cl = clasePunt(s); emitir(s, cl); if (s in FIN_FRASE) inicioFrase = true else if (s != "¿" && s != "¡") inicioFrase = false }
                        else -> { sb.append(s); pendienteBlanco = true }
                    }
                }
                else -> throw IllegalArgumentException("código desconocido $c")
            }
        }
        return sb.toString()
    }

    // ---------------- utilidades ----------------

    private fun trocear(t: String): Pair<List<Item>, List<String>> {
        val items = ArrayList<Item>(); val esp = ArrayList<String>()
        var i = 0
        val ws = StringBuilder()
        while (i < t.length) {
            val cp = t.codePointAt(i)
            when {
                Character.isWhitespace(cp) -> { ws.appendCodePoint(cp); i += Character.charCount(cp) }
                esLetra(cp) -> {
                    var j = i
                    while (j < t.length && esLetra(t.codePointAt(j))) j += Character.charCount(t.codePointAt(j))
                    esp.add(ws.toString()); ws.setLength(0); items.add(Item(t.substring(i, j), Clase.PALABRA)); i = j
                }
                cp in '0'.code..'9'.code -> {
                    var j = i
                    while (j < t.length) {
                        val c = t[j]
                        if (c in '0'..'9') j++
                        else if ((c == ':' || c == '.' || c == ',') && j + 1 < t.length && t[j + 1] in '0'..'9') j++
                        else break
                    }
                    esp.add(ws.toString()); ws.setLength(0); items.add(Item(t.substring(i, j), Clase.NUMERO)); i = j
                }
                t.startsWith("...", i) -> { esp.add(ws.toString()); ws.setLength(0); items.add(Item("...", Clase.CIERRE)); i += 3 }
                else -> {
                    val s = String(Character.toChars(cp))
                    esp.add(ws.toString()); ws.setLength(0); items.add(Item(s, clasePunt(s))); i += Character.charCount(cp)
                }
            }
        }
        esp.add(ws.toString())
        return Pair(items, esp)
    }

    private fun esLetra(cp: Int) = Character.isLetter(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()

    private fun clasePunt(s: String): Clase = when {
        s in CIERRES -> Clase.CIERRE
        s in APERTURAS -> Clase.APERTURA
        s.isNotEmpty() && esLetra(s.codePointAt(0)) -> Clase.PALABRA
        else -> Clase.OTRO
    }

    private fun implicito(x: Clase, y: Clase): Boolean =
        (x == Clase.PALABRA || x == Clase.NUMERO || x == Clase.CIERRE) &&
        (y == Clase.PALABRA || y == Clase.NUMERO || y == Clase.APERTURA)

    private fun capital(low: String): String {
        if (low.isEmpty()) return low
        val n = Character.charCount(low.codePointAt(0))
        return low.substring(0, n).uppercase(Locale.ROOT) + low.substring(n)
    }

    private fun tipoMayus(w: String): Int {
        val low = w.lowercase(Locale.ROOT)
        if (w == low) return MINUS
        if (w == capital(low) && low.uppercase(Locale.ROOT) != w || (w == capital(low) && w.codePointCount(0, w.length) == 1)) return CAPITAL
        if (w == low.uppercase(Locale.ROOT) && low.lowercase(Locale.ROOT) == low && w.lowercase(Locale.ROOT) == low) return MAYUS
        return MIXTA
    }

    companion object {
        const val UNO = 200
        const val PREF = 40
        const val L_PALABRA_MIN = 240
        const val C_CAMBIO_MAYUS = 241
        const val C_TODO_MAYUS = 242
        const val C_ESPACIO = 243
        const val C_SIN_ESPACIO = 244
        const val C_NUMERO = 245
        const val L_PALABRA = 246
        const val L_OTRO = 247
        const val L_BLANCO = 248
        const val R_REPETIR = 249
        private const val MAX_TABLA = 256
        private const val MINUS = 0; private const val CAPITAL = 1; private const val MAYUS = 2; private const val MIXTA = 3
        private val CIERRES = setOf(",", ".", ";", ":", "!", "?", ")", "...", "»", "…")
        private val APERTURAS = setOf("¿", "¡", "(", "«")
        private val FIN_FRASE = setOf(".", "!", "?", "...", "…")
    }
}
