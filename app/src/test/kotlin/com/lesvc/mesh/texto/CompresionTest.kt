package com.lesvc.mesh.texto

import com.lesvc.mesh.audio.Duracion
import com.lesvc.mesh.audio.Protocolo
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class CompresionTest {
    companion object {
        lateinit var c: CompresorTexto
        val informe = StringBuilder()
        var msTren = 0L

        fun cargarCompresor(): CompresorTexto {
            val dir = File("src/main/assets/compresion/v1")
            return CompresorTexto.cargar { File(dir, it).readText(Charsets.UTF_8) }
        }

        @BeforeClass @JvmStatic fun preparar() {
            val t0 = System.nanoTime(); c = cargarCompresor(); msTren = (System.nanoTime() - t0) / 1_000_000
        }

        @AfterClass @JvmStatic fun escribir() {
            println(informe)
            File(System.getProperty("tabla.salida", "build/x.md")).parentFile!!.resolve("compresion.md").writeText(informe.toString())
        }

        fun corpus(n: String) = CompresionTest::class.java.getResource("/corpus/$n")!!.readText(Charsets.UTF_8)
            .split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
    }

    private fun estad(nombre: String, items: List<String>, esperado: (String) -> String) {
        val ahorros = ArrayList<Double>(); var tot0 = 0; var tot1 = 0
        val tAhorro = ArrayList<Double>()
        val filas = StringBuilder()
        for (s in items) {
            val r = c.comprimir(s)
            val vuelta = c.descomprimir(r.bytes)
            assertEquals("ida y vuelta: $s", esperado(s), vuelta)
            val b0 = s.toByteArray().size; val b1 = r.bytes.size
            tot0 += b0; tot1 += b1
            ahorros.add(1.0 - b1.toDouble() / b0)
            val p = CodecMensaje.preparar(s, Protocolo.AUDIBLE_MUY_RAPIDO, c, false)
            tAhorro.add(p.segundosSinComprimir - p.segundos)
            filas.append("| ${s.replace("|", "\\|").take(70)} | $b0 | $b1 | ${"%.0f".format(100 * (1.0 - b1.toDouble() / b0))} % | ${when (r.tipo) { CompresorTexto.TIPO_URL_V1 -> "URL"; CompresorTexto.TIPO_TEXTO_V1 -> "PPM"; CompresorTexto.TIPO_CONVERSACIONAL_V1 -> "conv."; else -> "conv.+bits" }} | ${if (p.comprimido) "comprimido" else "UTF-8"} |\n")
        }
        val med = ahorros.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
        informe.append("## $nombre (${items.size})\n\n")
        informe.append("Total ${tot0} → ${tot1} bytes · ahorro medio ${"%.1f".format(100 * ahorros.average())} % · mediana ${"%.1f".format(100 * med)} % · ")
        informe.append("tiempo medio ahorrado (muy rápido) ${"%.2f".format(tAhorro.average())} s/mensaje\n\n")
        informe.append("| Mensaje | UTF-8 | Comprimido | Ahorro | Modelo | Se envía |\n|---|---|---|---|---|---|\n").append(filas).append("\n")
    }

    @Test fun mensajes_espanol_ida_y_vuelta() {
        informe.append("Entrenamiento de los modelos: $msTren ms (JVM del PC)\n\n")
        val m = corpus("mensajes_prueba.txt")
        assertTrue(m.size >= 50)
        estad("Mensajes en español", m) { it }
    }

    @Test fun urls_ida_y_vuelta() {
        val u = corpus("urls_prueba.txt")
        assertTrue(u.size >= 50)
        // El texto que llega es la URL normalizada (esquema y host en minúsculas) si se usó el modelo URL;
        // con el modelo de texto llega exacta.
        estad("URLs", u) { s -> val r = c.comprimir(s); if (r.tipo == CompresorTexto.TIPO_URL_V1) Url.normalizar(s) else s }
    }

    @Test fun combos_url_casos_limite() {
        val casos = listOf(
            "https://www.ejemplo.com", "https://ejemplo.com/", "http://sub.dominio.ejemplo.es:8443/ruta?x=1#f",
            "https://ejemplo.com/ruta/with.com/inside.com", "https://a.b.c.gob.es", "www.ejemplo.org?q=1",
            "https://ejemplo.com:8080", "https://www.com", "https://user:pw@ejemplo.com/x", "ejemplo.com.es/a.b",
            "https://xn--and-6ma2c.es/", "https://ejemplo.io#frag", "http://www.ejemplo.net:80/index.html?A=B&c=D",
        )
        for (s in casos) {
            val n = Url.normalizar(s)
            val (k, resto) = Url.separar(n)
            assertEquals("combo $s", n, Url.unir(k, resto))
            val r = c.comprimir(s)
            assertEquals(if (r.tipo == CompresorTexto.TIPO_URL_V1) n else s, c.descomprimir(r.bytes))
        }
    }

    @Test fun comparativa_por_modo() {
        val ejemplo = "hemos quedado a las 3"
        val tm = c.tamanosPorModo(ejemplo)
        informe.append("## Comparativa de modos (bytes, incluida la cabecera de 1 byte)\n\n")
        informe.append("Ejemplo de Jorge «$ejemplo» (${ejemplo.toByteArray().size} B): PPM ${tm["ppm"]} B · conversacional ${tm["conversacional"]} B · elegido ${c.comprimir(ejemplo).bytes.size} B\n\n")
        val dicLineas = File("src/main/assets/compresion/v1/diccionario_conversacional.txt").readLines().filter { it.isNotEmpty() }
        val sinRef = CodecConversacional(dicLineas, referencias = false)
        informe.append("| Corpus | UTF-8 | Conversacional sin referencias | Conversacional + referencias en el mensaje | PPM general | URL literal (combo+PPM) | Mejor de todos | Ahorro medio (mejor) | Mediana |\n|---|---|---|---|---|---|---|---|---|\n")
        for ((nombre, f) in listOf("Mensajes (60)" to "mensajes_prueba.txt", "URLs (59)" to "urls_prueba.txt", "Textos largos con nombres (8)" to "textos_largos.txt")) {
            val xs = corpus(f)
            var raw = 0; var cv = 0; var pp = 0; var ur = 0; var urN = 0; var best = 0; var cvSin = 0
            val ah = ArrayList<Double>()
            for (x in xs) {
                val m = c.tamanosPorModo(x); val b = c.comprimir(x).bytes.size
                raw += x.toByteArray().size; cv += m["conversacional"]!!; pp += m["ppm"]!!
                m["url"]?.let { ur += it; urN++ }; best += b
                cvSin += 1 + sinRef.codificar(x).size
                assertEquals(x, sinRef.decodificar(sinRef.codificar(x)))
                ah.add(1 - b.toDouble() / x.toByteArray().size)
            }
            val med = ah.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
            fun pc(v: Int) = "$v (${"%+.0f".format(100.0 * v / raw - 100)} %)"
            informe.append("| $nombre | $raw | ${pc(cvSin)} | ${pc(cv)} | ${pc(pp)} | ${if (urN > 0) "$ur en $urN URLs" else "—"} | ${pc(best)} | ${"%.1f".format(100 * ah.average())} % | ${"%.1f".format(100 * med)} % |\n")
        }
        informe.append("\n")
        val cvExacto = CodecConversacional(File("src/main/assets/compresion/v1/diccionario_conversacional.txt").readLines().filter { it.isNotEmpty() })
        for (x in corpus("mensajes_prueba.txt") + corpus("urls_prueba.txt") + corpus("textos_largos.txt")) {
            assertEquals(x, cvExacto.decodificar(cvExacto.codificar(x)))
        }
    }

    /** Tabla por mensaje (la usa tools/benchmark_compresores.py para comparar con Unishox2/zstd/Brotli). */
    @Test fun tabla_por_mensaje_y_bits() {
        val dir = File("src/main/assets/compresion/v1")
        fun lineas(n: String) = File(dir, n).readLines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        val libros = lineas("es_libros.txt"); val chat = lineas("es_chat.txt"); val urls = lineas("urls.txt").map { Url.normalizar(it) }
        val tren = ArrayList<String>(); tren.addAll(libros); repeat(20) { tren.addAll(chat) }; tren.addAll(urls)
        val ppm = (2..3).associateWith { ModeloPPM.entrenar(tren, orden = it, poda = 2) }
        val dic = lineas("diccionario_conversacional.txt")
        val sinRef = CodecConversacional(dic, referencias = false)
        val tsv = StringBuilder("corpus\tchars\tutf8\tconv_sinref\tconv\tconv_huffman\tconv_rango\tppm2\tppm3\tppm4\turl\telegido\ttexto\n")
        informe.append("## Capa de bits del modo conversacional (códigos de longitud variable)\n\n")
        informe.append("Huella de las tablas v1 (CRC32): %08x\n\n".format(c.entropia.huella))
        informe.append("| Corpus | Caracteres | UTF-8 bits/car | Conv. alineado a bytes | Conv. + Huffman estático (calculado) | Conv. + rango estático (enviado, 0x14) | Ahorro rango vs bytes |\n|---|---|---|---|---|---|---|\n")
        for ((nombre, f) in listOf("mensajes" to "mensajes_prueba.txt", "urls" to "urls_prueba.txt", "largos" to "textos_largos.txt")) {
            var ch = 0; var u8 = 0; var cb = 0; var chf = 0; var cr = 0
            for (x in corpus(f)) {
                val m = c.tamanosPorModo(x)
                val conv = c.tamanosPorModo(x)
                val n = x.codePointCount(0, x.length)
                val p2 = CodificadorRango().also { ppm[2]!!.codificar(x, it) }.terminar().size + 1
                val p3 = CodificadorRango().also { ppm[3]!!.codificar(x, it) }.terminar().size + 1
                val elegido = c.comprimir(x).bytes.size
                tsv.append("$nombre\t$n\t${x.toByteArray().size}\t${1 + sinRef.codificar(x).size}\t${m["conversacional"]}\t${m["conversacional_huffman"]}\t${m["conversacional_ec"]}\t$p2\t$p3\t${m["ppm"]}\t${m["url"] ?: ""}\t$elegido\t${x.replace('\t', ' ')}\n")
                ch += n; u8 += x.toByteArray().size; cb += m["conversacional"]!!; chf += m["conversacional_huffman"]!!; cr += m["conversacional_ec"]!!
                val enc = c.comprimir(x); assertEquals(x.takeIf { enc.tipo != CompresorTexto.TIPO_URL_V1 } ?: enc.textoFinal, c.descomprimir(enc.bytes))
            }
            fun bpc(b: Int) = "%.2f".format(8.0 * b / ch)
            informe.append("| $nombre | $ch | ${bpc(u8)} | $cb B (${bpc(cb)} bits/car) | $chf B (${bpc(chf)}) | $cr B (${bpc(cr)}) | ${"%.0f".format(100 - 100.0 * cr / cb)} % |\n")
        }
        val ej = "hemos quedado a las 3"; val mj = c.tamanosPorModo(ej)
        informe.append("\nEjemplo «$ej» (21 B): alineado ${mj["conversacional"]} B · Huffman ${mj["conversacional_huffman"]} B · rango ${mj["conversacional_ec"]} B · elegido ${c.comprimir(ej).bytes.size} B (cabecera incluida)\n\n")
        File(System.getProperty("tabla.salida", "build/x.md")).parentFile!!.resolve("compresion_por_mensaje.tsv").writeText(tsv.toString())
    }

    @Test fun textos_raros() {
        for (s in listOf("a", "ñ", "🎉🎉🎉", "Ünïcödé 中文 العربية", "x".repeat(500), "línea1\nlínea2", "\u0000raro",
            "  espacios  dobles  ", "HOLA QUE TAL", "iPhone y McDonald's", "¿¿Qué??", "a,b.c;d", "8:30, 10.5 y 1,50€",
            "Hola!!! ... ¿Vienes?", "ÑÚÑEZ", "x" + "ñ".repeat(300) + " fin", "( paréntesis )", "")) {
            assertEquals(s, c.descomprimir(c.comprimir(s).bytes))
            val cv = CodecConversacional(File("src/main/assets/compresion/v1/diccionario_conversacional.txt").readLines().filter { it.isNotEmpty() })
            assertEquals("conversacional: «$s»", s, cv.decodificar(cv.codificar(s)))
        }
    }

    @Test fun formula_duracion_exacta() {
        // (la comprobación contra ggwave_encode real está en GGWaveRoundTripTest)
        assertEquals(13.55, Duracion.segundosCarga(140, Protocolo.AUDIBLE_NORMAL), 0.01)
        assertEquals(4.97, Duracion.segundosCarga(140, Protocolo.AUDIBLE_MUY_RAPIDO), 0.01)
    }
}
