package com.lesvc.mesh.texto

/**
 * Compresión de texto con diccionario estático versionado.
 *
 * Formato (1 byte de cabecera + flujo del codificador de rango):
 *   0x11  texto, diccionario v1 (modelo PPM orden 4 entrenado con español)
 *   0x12  URL,   diccionario v1 (modo LITERAL: combo esquema+www+TLD + modelo PPM orden 4 de URLs)
 *   0x13  conversacional, diccionario v1 (palabras/frases → códigos de 1–2 bytes, alineado a bytes)
 *   0x14  conversacional v1 + capa de entropía (códigos de longitud variable en bits, tablas v1)
 *   0x15–0x1A reservados para futuras versiones del diccionario.
 * El emisor prueba los modos aplicables y se queda con el más corto.
 * Un texto sin cabecera (primer byte ≥ 0x20) es UTF-8 normal (compatible con Waver).
 */
class CompresorTexto private constructor(
    private val modeloTexto: ModeloPPM,
    private val modeloUrl: ModeloPPM,
    private val frecCombos: IntArray,
    private val conversacional: CodecConversacional,
    val entropia: EntropiaConversacional,
) {
    private val cumCombos = IntArray(frecCombos.size + 1).also { for (i in frecCombos.indices) it[i + 1] = it[i] + frecCombos[i] }

    class Resultado(val bytes: ByteArray, val tipo: Byte, val textoFinal: String)

    /** Mejor codificación comprimida (texto o URL). No decide si compensa frente al UTF-8. */
    fun comprimir(texto: String): Resultado {
        val enc = CodificadorRango()
        modeloTexto.codificar(texto, enc)
        var mejor = Resultado(byteArrayOf(TIPO_TEXTO_V1) + enc.terminar(), TIPO_TEXTO_V1, texto)
        try {
            val cv = byteArrayOf(TIPO_CONVERSACIONAL_V1) + conversacional.codificar(texto)
            if (cv.size < mejor.bytes.size && conversacional.decodificar(cv, 1) == texto) mejor = Resultado(cv, TIPO_CONVERSACIONAL_V1, texto)
            val ce = byteArrayOf(TIPO_CONVERSACIONAL_EC_V1) + entropia.codificar(cv, 1)
            if (ce.size < mejor.bytes.size && descomprimir(ce) == texto) mejor = Resultado(ce, TIPO_CONVERSACIONAL_EC_V1, texto)
        } catch (e: Exception) { /* este modo no aplica */ }
        if (Url.pareceUrl(texto)) {
            val norm = Url.normalizar(texto)
            val (combo, resto) = Url.separar(norm)
            val e2 = CodificadorRango()
            e2.codificar(cumCombos[combo], frecCombos[combo], cumCombos.last())
            modeloUrl.codificar(resto, e2)
            val r = Resultado(byteArrayOf(TIPO_URL_V1) + e2.terminar(), TIPO_URL_V1, norm)
            if (r.bytes.size <= mejor.bytes.size) mejor = r
        }
        return mejor
    }

    /** Solo para medir: tamaño con cada modo (null si no aplica). */
    fun tamanosPorModo(texto: String): Map<String, Int?> {
        val t = CodificadorRango().also { modeloTexto.codificar(texto, it) }.terminar().size + 1
        val conv = try { conversacional.codificar(texto) } catch (e: Exception) { null }
        val c = conv?.let { it.size + 1 }
        val ce = conv?.let { entropia.codificar(it).size + 1 }
        val ch = conv?.let { (entropia.bitsHuffman(it) + 7) / 8 + 1 }
        val u = if (Url.pareceUrl(texto)) {
            val (combo, resto) = Url.separar(Url.normalizar(texto))
            val e2 = CodificadorRango(); e2.codificar(cumCombos[combo], frecCombos[combo], cumCombos.last())
            modeloUrl.codificar(resto, e2); e2.terminar().size + 1
        } else null
        return mapOf("ppm" to t, "conversacional" to c, "conversacional_ec" to ce, "conversacional_huffman" to ch, "url" to u)
    }

    fun descomprimir(b: ByteArray): String {
        require(esComprimido(b)) { "no es un texto comprimido LESVC" }
        val dec = DecodificadorRango(b, 1)
        return when (b[0]) {
            TIPO_TEXTO_V1 -> modeloTexto.decodificar(dec)
            TIPO_URL_V1 -> {
                val v = dec.frecuencia(cumCombos.last())
                var c = 0
                while (cumCombos[c + 1] <= v) c++
                dec.consumir(cumCombos[c], frecCombos[c])
                Url.unir(c, modeloUrl.decodificar(dec))
            }
            TIPO_CONVERSACIONAL_V1 -> conversacional.decodificar(b, 1)
            TIPO_CONVERSACIONAL_EC_V1 -> conversacional.decodificar(entropia.decodificar(b, 1))
            else -> throw IllegalArgumentException("diccionario de compresión desconocido (0x%02x): actualiza la app".format(b[0]))
        }
    }

    companion object {
        const val TIPO_TEXTO_V1: Byte = 0x11
        const val TIPO_URL_V1: Byte = 0x12
        const val TIPO_CONVERSACIONAL_V1: Byte = 0x13
        const val TIPO_CONVERSACIONAL_EC_V1: Byte = 0x14

        fun esComprimido(b: ByteArray) = b.isNotEmpty() && b[0] in 0x11..0x1A

        /** @param leer devuelve el contenido de un fichero de assets/compresion/v1/ */
        fun cargar(leer: (String) -> String): CompresorTexto {
            fun lineas(n: String) = leer(n).split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
            val libros = lineas("es_libros.txt")
            val chat = lineas("es_chat.txt")
            val urls = lineas("urls.txt").map { Url.normalizar(it) }
            val textos = ArrayList<String>(libros.size + chat.size * 20)
            textos.addAll(libros); repeat(20) { textos.addAll(chat) }; textos.addAll(urls)
            val mt = ModeloPPM.entrenar(textos, orden = 4, poda = 2)
            val restos = urls.map { Url.separar(it).second }
            val mu = ModeloPPM.entrenar(restos + chat + libros.take(300), orden = 4, poda = 1)
            val frec = IntArray(Url.NUM_COMBOS) { 1 }
            urls.forEach { frec[Url.separar(it).first]++ }
            val dic = leer("diccionario_conversacional.txt").split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            val codec = CodecConversacional(dic)
            val textosEc = ArrayList<String>(); repeat(20) { textosEc.addAll(chat) }; textosEc.addAll(libros)
            return CompresorTexto(mt, mu, frec, codec, EntropiaConversacional.entrenar(codec, textosEc))
        }
    }
}
