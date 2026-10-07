package com.lesvc.mesh.imagen

import com.lesvc.mesh.archivo.Presupuesto
import com.lesvc.mesh.archivo.ProtocoloArchivo
import com.lesvc.mesh.archivo.ReceptorArchivos
import com.lesvc.mesh.audio.Duracion
import com.lesvc.mesh.audio.GGWave
import com.lesvc.mesh.audio.Protocolo
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Imagen real → compresión con presupuesto de tiempo → tramas 0x1F → ggwave (onda real)
 * → [ruido] → decodificación en bloques de 1024 → ReceptorArchivos → CRC32 → bytes idénticos.
 */
class ImagenTransporteTest {

    companion object {
        const val RATE = GGWave.SAMPLE_RATE_TX
        lateinit var tx: GGWave
        val informe = StringBuilder()
        val salida = File(System.getProperty("muestras.salida") ?: "build/lesvc-img-samples")
        @BeforeClass @JvmStatic fun abrir() { tx = GGWave.emisor() }
        @AfterClass @JvmStatic fun cerrar() {
            tx.close()
            System.getProperty("tabla.salida")?.let { File(it).resolveSibling("imagenes.md").writeText(informe.toString()) }
            println(informe)
        }
        fun muestra(n: String) = File(ImagenTransporteTest::class.java.getResource("/muestras/$n")!!.toURI())
    }

    class Escucha(val tramas: List<ByteArray>, val fallos: Int)

    /** Onda completa como la emite el Emisor (0,4 s entre partes, 0,2 s al final); [quitar] = posiciones perdidas. */
    private fun onda(tramas: List<ByteArray>, p: Protocolo, quitar: Set<Int> = emptySet()): ShortArray {
        val trozos = ArrayList<ShortArray>()
        trozos.add(ShortArray(RATE / 2))
        for ((i, t) in tramas.withIndex()) {
            val w = tx.encode(t, p, 50)
            trozos.add(if (i in quitar) ShortArray(w.size) else w)
            trozos.add(ShortArray(if (i < tramas.lastIndex) RATE * 4 / 10 else RATE / 5))
        }
        trozos.add(ShortArray(RATE / 2))
        val out = ShortArray(trozos.sumOf { it.size }); var o = 0
        trozos.forEach { System.arraycopy(it, 0, out, o, it.size); o += it.size }
        return out
    }

    private fun conRuido(a: ShortArray, snrDb: Double, seed: Long): ShortArray {
        var e = 0.0; var n = 0
        for (s in a) if (s.toInt() != 0) { e += s.toDouble() * s; n++ }   // potencia de la señal (sin silencios)
        val sigma = sqrt(e / maxOf(1, n)) / Math.pow(10.0, snrDb / 20)
        val r = Random(seed)
        return ShortArray(a.size) { (a[it] + r.nextGaussian() * sigma).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun escuchar(audio: ShortArray): Escucha {
        GGWave.receptor().use { rx ->
            val l = ArrayList<ByteArray>(); var f = 0
            var i = 0
            while (i < audio.size) {
                val n = minOf(1000, audio.size - i)   // bloques "raros", como un micro real
                for (d in rx.alimentar(audio.copyOfRange(i, i + n))) when (d) {
                    is GGWave.Decodificado.Datos -> l.add(d.bytes)
                    is GGWave.Decodificado.Fallido -> f++
                }
                i += n
            }
            return Escucha(l, f)
        }
    }

    data class Caso(val fichero: String, val preset: Preset)
    private val casos = listOf(Caso("foto_paisaje.jpg", Preset.FOTO), Caso("foto_retrato.jpg", Preset.FOTO), Caso("grafico_barras.png", Preset.GRAFICO))
    private val compresor = CompresorImagen(CodificadorHost)

    private fun nombreProto(p: Protocolo) = when (p) { Protocolo.AUDIBLE_MUY_RAPIDO -> "muy rápido"; Protocolo.AUDIBLE_NORMAL -> "normal"; else -> p.name }

    @Test fun imagenes_con_presupuesto_de_tiempo_limpio_y_ruido_10dB() {
        informe.append("## Imágenes: presupuesto de tiempo → compresión → ggwave → CRC\n\n")
        informe.append("| Imagen | Preset | Modo | Presupuesto | Resultado | Bytes | Partes | Estimado | Medido (onda) | Limpio | Ruido 10 dB |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
        var fallosRuido = 0
        for (c in casos) {
            val orig = CodificadorHost.leer(muestra(c.fichero))
            for (seg in intArrayOf(60, 120)) for (p in listOf(Protocolo.AUDIBLE_MUY_RAPIDO, Protocolo.AUDIBLE_NORMAL)) {
                val presupuesto = Presupuesto.bytesParaSegundos(seg.toDouble(), p)
                val r = compresor.comprimir(orig, c.preset, presupuesto, gris = false)!!
                assertTrue(r.bytes.size <= presupuesto)
                val envio = ProtocoloArchivo.preparar(r.bytes, r.formato, r.ancho, r.alto, id = 0x1000 + seg + p.id)
                val tramas = envio.tramas()
                val estimado = Duracion.segundosEnvio(tramas.map { it.size }, p)
                assertEquals(estimado, Presupuesto.segundos(r.bytes.size, p), 1e-9)
                val audio = onda(tramas, p)
                val medido = (audio.size - RATE) / RATE.toDouble()   // sin el silencio de prueba añadido delante/detrás
                assertTrue(medido <= seg + 0.01)
                fun recibir(a: ShortArray): Pair<Boolean, ReceptorArchivos.Transferencia?> {
                    val rx = ReceptorArchivos()
                    var datos: ByteArray? = null
                    for (t in escuchar(a).tramas) { val ev = rx.recibir(t); if (ev is ReceptorArchivos.Evento.Completo) datos = ev.datos }
                    return Pair(datos != null && datos!!.contentEquals(r.bytes) && ProtocoloArchivo.crc32(datos!!) == envio.cabecera.crc, rx.transferencia(envio.cabecera.id))
                }
                val (okLimpio, _) = recibir(audio)
                assertTrue("limpio ${c.fichero} $seg s ${p.name}", okLimpio)
                // Con ruido: si se pierde alguna parte se usa la recuperación real (petición 'R' por sonido
                // con ruido → el emisor reenvía solo esas partes con ruido), como haría la app.
                val rxR = ReceptorArchivos(); var datosR: ByteArray? = null
                for (t in escuchar(conRuido(audio, 10.0, 77L + seg + p.id)).tramas) { val ev = rxR.recibir(t); if (ev is ReceptorArchivos.Evento.Completo) datosR = ev.datos }
                val faltaronPrimera = rxR.transferencia(envio.cabecera.id)?.faltan?.size ?: envio.cabecera.partes
                var rondas = 0; var segExtra = 0.0
                while (datosR == null && rondas < 3) {
                    rondas++
                    val tr = rxR.transferencia(envio.cabecera.id)
                    val pet = ProtocoloArchivo.peticion(envio.cabecera.id, tr?.faltan ?: (0 until envio.cabecera.partes).toList(), tr?.faltaCabecera ?: true)
                    val oido = escuchar(conRuido(onda(pet, p), 10.0, 900L + rondas)).tramas.mapNotNull { (ReceptorArchivos().recibir(it) as? ReceptorArchivos.Evento.Peticion)?.p }
                    val pedidas = oido.flatMap { it.indices }.distinct()
                    val reenvio = envio.tramas(pedidas)
                    segExtra += Duracion.segundosEnvio(pet.map { it.size }, p) + Duracion.segundosEnvio(reenvio.map { it.size }, p)
                    for (t in escuchar(conRuido(onda(reenvio, p), 10.0, 500L + rondas)).tramas) { val ev = rxR.recibir(t); if (ev is ReceptorArchivos.Evento.Completo) datosR = ev.datos }
                }
                val okRuido = datosR != null && datosR!!.contentEquals(r.bytes)
                if (!okRuido) fallosRuido++
                val ruidoTxt = when {
                    !okRuido -> "❌"
                    rondas == 0 -> "✅ a la primera"
                    else -> "✅ tras pedir $faltaronPrimera parte(s) por sonido (+${"%.1f".format(segExtra)} s)"
                }
                // muestras
                val base = c.fichero.substringBefore('.')
                val dir = File(salida, "${base}_${seg}s_${if (p == Protocolo.AUDIBLE_NORMAL) "normal" else "muyrapido"}")
                dir.mkdirs()
                File(dir, "enviado.${r.formato.extension}").writeBytes(r.bytes)
                CodificadorHost.guardarPng(CodificadorHost.decodificar(r.bytes, r.formato), File(dir, "decodificado.png"))
                informe.append("| ${c.fichero} | ${c.preset.etiqueta} | ${nombreProto(p)} | $seg s → $presupuesto B | ${r.detalle} | ${r.bytes.size} | ${envio.cabecera.partes} | ${"%.1f".format(estimado)} s | ${"%.1f".format(medido)} s | ✅ CRC ok | $ruidoTxt |\n")
            }
        }
        informe.append("\n")
        assertEquals("fallos con ruido 10 dB", 0, fallosRuido)
    }

    @Test fun partes_perdidas_peticion_por_sonido_y_reenvio() {
        val orig = CodificadorHost.leer(muestra("grafico_barras.png"))
        val p = Protocolo.AUDIBLE_MUY_RAPIDO
        val r = compresor.comprimir(orig, Preset.GRAFICO, Presupuesto.bytesParaSegundos(60.0, p), false)!!
        val envio = ProtocoloArchivo.preparar(r.bytes, r.formato, r.ancho, r.alto, id = 0xBEEF)
        val tramas = envio.tramas()
        val n = envio.cabecera.partes
        // posiciones en la lista: 0 = cabecera, 1..n = partes, n+1 = cabecera final
        val perdidas = setOf(0, 3, 6, 7, 8, n)          // la cabecera inicial (hay copia al final) y las partes 2, 5-7, n-1
        val rx = ReceptorArchivos()
        for (t in escuchar(onda(tramas, p, perdidas)).tramas) rx.recibir(t)
        val tr = rx.transferencia(0xBEEF)!!
        val esperadas = listOf(2, 5, 6, 7, n - 1)
        assertEquals(esperadas, tr.faltan)
        val listaTxt = ProtocoloArchivo.escribirLista(tr.faltan)
        assertEquals(esperadas, ProtocoloArchivo.leerLista(listaTxt))
        assertEquals("3, 6-8, 12", ProtocoloArchivo.escribirLista(listOf(2, 5, 6, 7, 11)))
        assertEquals("1, 2, 4-6", ProtocoloArchivo.escribirLista(listOf(0, 1, 3, 4, 5)))
        assertTrue(!tr.faltaCabecera)
        // El receptor pide lo que falta POR SONIDO (trama 'R'); el emisor la oye y la entiende.
        val pet = ProtocoloArchivo.peticion(tr.id, tr.faltan, tr.faltaCabecera)
        assertEquals(1, pet.size)
        val oidoEmisor = escuchar(onda(pet, p)).tramas.map { ReceptorArchivos().recibir(it) }
        val ev = oidoEmisor.single() as ReceptorArchivos.Evento.Peticion
        assertEquals(esperadas, ev.p.indices)
        // Reenvío solo de lo pedido
        val reenvio = envio.tramas(ev.p.indices)
        var completo: ByteArray? = null
        for (t in escuchar(onda(reenvio, p)).tramas) { val e = rx.recibir(t); if (e is ReceptorArchivos.Evento.Completo) completo = e.datos }
        assertArrayEquals(r.bytes, completo)
        // Lista tecleada a mano en el emisor
        assertEquals(esperadas, ProtocoloArchivo.leerLista(listaTxt))
        informe.append("## Recuperación\n\nGráfico ${r.bytes.size} B en $n partes (muy rápido); perdidas la cabecera inicial y las partes «$listaTxt» → " +
            "lista de faltan correcta → petición por sonido (${pet[0].size} B, ${"%.1f".format(Duracion.segundosEnvio(pet.map { it.size }, p))} s) → " +
            "reenvío de ${reenvio.size} tramas (${"%.1f".format(Duracion.segundosEnvio(reenvio.map { it.size }, p))} s) → CRC ok, bytes idénticos ✅\n\n")
    }

    @Test fun repetir_cada_parte_aguanta_perdidas_sin_pedir_nada() {
        val datos = ByteArray(1500) { (it * 37).toByte() }
        val envio = ProtocoloArchivo.preparar(datos, Formato.PNG, 1, 1, id = 77)
        val tramas = envio.tramas(repetir = true)
        val perdidas = setOf(1, 4, 9, 14)                 // una de cada copia, nunca las dos
        val rx = ReceptorArchivos(); var ok: ByteArray? = null
        for (t in escuchar(onda(tramas, Protocolo.AUDIBLE_MUY_RAPIDO, perdidas)).tramas) { val e = rx.recibir(t); if (e is ReceptorArchivos.Evento.Completo) ok = e.datos }
        assertArrayEquals(datos, ok)
    }

    @Test fun binario_todos_los_valores_de_byte_por_ggwave() {
        val todos = ByteArray(256) { it.toByte() }
        val envio = ProtocoloArchivo.preparar(todos, Formato.PNG, 16, 16, id = 0x00FF)
        val rx = ReceptorArchivos(); var ok: ByteArray? = null
        for (t in escuchar(onda(envio.tramas(), Protocolo.AUDIBLE_MUY_RAPIDO)).tramas) { val e = rx.recibir(t); if (e is ReceptorArchivos.Evento.Completo) ok = e.datos }
        assertArrayEquals(todos, ok)
        // cada carga de 140 bytes con valores 0..255 tal cual
        for (k in 0 until 2) {
            val carga = ByteArray(140) { ((it + k * 140) % 256).toByte() }
            val e = escuchar(onda(listOf(carga), Protocolo.AUDIBLE_MUY_RAPIDO)).tramas
            assertArrayEquals(carga, e.single())
        }
    }

    @Test fun duracion_exacta_contra_ggwave_todos_los_tamanos_y_modos() {
        for (p in Protocolo.values()) for (n in 1..GGWave.MAX_PAYLOAD_BYTES) {
            val w = tx.encode(ByteArray(n) { (it * 7).toByte() }, p, 50)
            assertEquals("${p.name} n=$n", Duracion.tramas(n, p) * GGWave.SAMPLES_PER_FRAME, w.size)
        }
    }

    @Test fun crc_incorrecto_se_detecta() {
        val datos = ByteArray(400) { it.toByte() }
        val envio = ProtocoloArchivo.preparar(datos, Formato.JPEG, 1, 1, id = 5)
        val rx = ReceptorArchivos()
        rx.recibir(envio.tramaCabecera)
        val malas = (0 until envio.cabecera.partes).map { envio.parte(it).clone() }
        malas[1][20] = (malas[1][20] + 1).toByte()
        val evs = malas.map { rx.recibir(it) }
        assertTrue(evs.last() is ReceptorArchivos.Evento.CrcIncorrecto)
        assertEquals((0 until envio.cabecera.partes).toList(), rx.transferencia(5)!!.faltan)
        var ok: ByteArray? = null
        for (i in 0 until envio.cabecera.partes) { val e = rx.recibir(envio.parte(i)); if (e is ReceptorArchivos.Evento.Completo) ok = e.datos }
        assertArrayEquals(datos, ok)
    }

    @Test fun limite_de_partes_muy_por_encima_de_36() {
        val datos = ByteArray(10_240) { (it xor (it shr 8)).toByte() }   // ~10 KB = 78 partes
        val envio = ProtocoloArchivo.preparar(datos, Formato.WEBP, 1, 1, id = 9)
        assertEquals(78, envio.cabecera.partes)
        val rx = ReceptorArchivos(); var ok: ByteArray? = null
        for (t in envio.tramas().reversed()) { val e = rx.recibir(t); if (e is ReceptorArchivos.Evento.Completo) ok = e.datos }
        assertArrayEquals(datos, ok)
        assertEquals(1000, ProtocoloArchivo.MAX_PARTES)
    }

    @Test fun textos_y_tramas_archivo_no_se_confunden() {
        assertTrue(!ProtocoloArchivo.esTramaArchivo("Hola".toByteArray()))
        assertTrue(ProtocoloArchivo.analizar(byteArrayOf(0x1E, 'a'.code.toByte(), 'b'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())) == null)
        assertEquals(null, ProtocoloArchivo.analizar(byteArrayOf(0x1F, 'X'.code.toByte(), 0, 1, 2)))
    }
}
