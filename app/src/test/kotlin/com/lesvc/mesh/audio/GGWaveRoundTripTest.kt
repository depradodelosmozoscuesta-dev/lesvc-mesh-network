package com.lesvc.mesh.audio

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Prueba extremo a extremo SIN teléfono: Troceador -> JNI -> ggwave (mismo código
 * C++ fijado que va en el APK, compilado para el host) -> forma de onda ->
 * (ruido/silencio) -> ggwave decode en bloques de 1024 muestras -> Reensamblador.
 */
class GGWaveRoundTripTest {

    companion object {
        private const val RATE = GGWave.SAMPLE_RATE_TX
        private const val VOLUMEN = 50
        private val filas = ArrayList<String>()
        private lateinit var tx: GGWave

        @BeforeClass @JvmStatic fun abrir() { tx = GGWave.emisor() }

        @AfterClass @JvmStatic fun cerrar() {
            tx.close()
            val tabla = buildString {
                appendLine("| Protocolo | Caso | Texto | Partes | Audio (s) | Resultado |")
                appendLine("|---|---|---|---|---|---|")
                filas.forEach { appendLine(it) }
            }
            println(tabla)
            File(System.getProperty("tabla.salida", "build/ggwave-roundtrip.md")).apply { parentFile?.mkdirs() }.writeText(tabla)
        }
    }

    private val textos = linkedMapOf(
        "HOLA" to TextosPrueba.hola,
        "¿Qué tal, señor Ñúñez?" to TextosPrueba.pregunta,
        "300 caracteres" to TextosPrueba.largo300,
    )

    /** Igual que Emisor: ondas de cada parte separadas por 0,4 s de silencio. */
    private fun generar(texto: String, p: Protocolo): Pair<ShortArray, Int> {
        val cargas = Troceador.cargas(texto)
        val pausa = ShortArray(RATE * 4 / 10)
        val ondas = cargas.flatMapIndexed { i, c ->
            val o = tx.encode(c, p, VOLUMEN).toList()
            if (i < cargas.lastIndex) o + pausa.toList() else o
        }
        return ondas.toShortArray() to cargas.size
    }

    private class Escucha(val mensajes: List<String>, val fallos: Int)

    /** Igual que Receptor; bloques de 1000 muestras (≠ trama de ggwave) para probar el troceo interno. */
    private fun escuchar(audio: ShortArray): Escucha {
        val mensajes = ArrayList<String>()
        var fallos = 0
        GGWave.receptor().use { rx ->
            val reens = Reensamblador()
            var off = 0
            val buf = ShortArray(1000)
            while (off < audio.size) {
                val n = minOf(buf.size, audio.size - off)
                System.arraycopy(audio, off, buf, 0, n)
                off += n
                for (r in rx.alimentar(buf, n)) when (r) {
                    is GGWave.Decodificado.Fallido -> fallos++
                    is GGWave.Decodificado.Datos -> when (val m = reens.recibir(r.bytes)) {
                        is Reensamblador.Resultado.Completo -> mensajes.add(m.texto)
                        is Reensamblador.Resultado.Parcial -> {}
                        Reensamblador.Resultado.Invalido -> fallos++
                    }
                }
            }
        }
        return Escucha(mensajes, fallos)
    }

    private fun rms(a: ShortArray): Double {
        var s = 0.0; var n = 0
        for (v in a) if (v.toInt() != 0) { s += v.toDouble() * v; n++ }
        return if (n == 0) 0.0 else sqrt(s / n)
    }

    private fun conRuido(audio: ShortArray, snrDb: Double, padSeg: Double, seed: Long): ShortArray {
        val pad = (RATE * padSeg).toInt()
        val total = ShortArray(pad + audio.size + pad)
        System.arraycopy(audio, 0, total, pad, audio.size)
        val sigma = rms(audio) / Math.pow(10.0, snrDb / 20)
        val rnd = Random(seed)
        for (i in total.indices) {
            total[i] = (total[i] + rnd.nextGaussian() * sigma).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return total
    }

    private fun fila(p: Protocolo, caso: String, nombre: String, partes: Int, muestras: Int, ok: Boolean, extra: String = "") {
        val seg = "%.1f".format(muestras.toDouble() / RATE)
        filas.add("| ${p.name} | $caso | $nombre | $partes | $seg | ${if (ok) "PASA" else "FALLA"}$extra |")
    }

    @Test fun limpio_todos_los_protocolos() {
        var errores = 0
        for (p in Protocolo.values()) for ((nombre, t) in textos) {
            val (audio, partes) = generar(t, p)
            val r = escuchar(audio)
            val ok = r.mensajes == listOf(t)
            if (!ok) errores++
            fila(p, "limpio", nombre, partes, audio.size, ok, if (ok) "" else " (recibido=${r.mensajes})")
        }
        assertEquals(0, errores)
    }

    @Test fun ruido_blanco_10dB_y_silencio_1_5s() {
        var errores = 0
        for (p in Protocolo.values()) for ((nombre, t) in textos) {
            val (audio, partes) = generar(t, p)
            val ruidoso = conRuido(audio, 10.0, 1.5, 1234L + p.id)
            val r = escuchar(ruidoso)
            val ok = r.mensajes == listOf(t)
            if (!ok) errores++
            fila(p, "ruido 10 dB + 1,5 s silencio", nombre, partes, ruidoso.size, ok, if (ok) "" else " (recibido=${r.mensajes})")
        }
        assertEquals(0, errores)
    }

    /** Informativo (no bloquea): hasta dónde aguanta con más ruido. */
    @Test fun ruido_fuerte_informativo() {
        for (snr in doubleArrayOf(3.0, 0.0)) for (p in Protocolo.values()) {
            val t = TextosPrueba.pregunta
            val (audio, partes) = generar(t, p)
            val r = escuchar(conRuido(audio, snr, 1.0, 99L + p.id))
            fila(p, "INFO ruido ${snr.toInt()} dB", "¿Qué tal…?", partes, audio.size + 2 * RATE, r.mensajes == listOf(t))
        }
    }

    @Test fun silencio_y_ruido_solo_no_producen_nada() {
        val silencio = ShortArray(RATE * 2)
        val rs = escuchar(silencio)
        val okS = rs.mensajes.isEmpty() && rs.fallos == 0
        fila(Protocolo.AUDIBLE_NORMAL, "solo silencio 2 s (todos los protocolos escuchando)", "—", 0, silencio.size, okS)

        val rnd = Random(7)
        val ruido = ShortArray(RATE * 10) { (rnd.nextGaussian() * 1500).roundToInt().toShort() }
        val rr = escuchar(ruido)
        val okR = rr.mensajes.isEmpty()
        fila(Protocolo.AUDIBLE_NORMAL, "solo ruido blanco 10 s", "—", 0, ruido.size, okR, " (fallos detectados=${rr.fallos})")
        assertTrue(okS)
        assertTrue(okR)
    }

    /** Micro a 44,1 kHz (algunos móviles): Remuestreador -> ggwave a 48 kHz, como en Receptor. */
    @Test fun captura_a_44100_hz_con_remuestreador() {
        var errores = 0
        for (p in listOf(Protocolo.AUDIBLE_NORMAL, Protocolo.AUDIBLE_MUY_RAPIDO, Protocolo.ULTRA_NORMAL)) {
            for ((nombre, t) in listOf("¿Qué tal, señor Ñúñez?" to TextosPrueba.pregunta, "300 caracteres" to TextosPrueba.largo300)) {
                val (audio48, partes) = generar(t, p)
                val ruidoso48 = conRuido(audio48, 10.0, 1.0, 555L + p.id)
                val mic44 = Remuestreador(RATE, 44100).procesar(ruidoso48, ruidoso48.size) // "lo que graba" un micro a 44,1 kHz
                val remu = Remuestreador(44100)
                val mensajes = ArrayList<String>()
                GGWave.receptor().use { rx ->
                    val reens = Reensamblador()
                    var off = 0; val buf = ShortArray(1024)
                    while (off < mic44.size) {
                        val k = minOf(1024, mic44.size - off); System.arraycopy(mic44, off, buf, 0, k); off += k
                        val b = remu.procesar(buf, k)
                        for (r in rx.alimentar(b, b.size)) {
                            if (r !is GGWave.Decodificado.Datos) continue
                            (reens.recibir(r.bytes) as? Reensamblador.Resultado.Completo)?.let { mensajes.add(it.texto) }
                        }
                    }
                }
                val ok = mensajes == listOf(t)
                if (!ok) errores++
                fila(p, "micro 44,1 kHz + ruido 10 dB", nombre, partes, ruidoso48.size, ok)
            }
        }
        assertEquals(0, errores)
    }

    /** Informativo: qué pasa si se entregan a ggwave bloques de 1000 muestras sin agrupar en tramas. */
    @Test fun bloques_sin_trama_informativo() {
        val t = TextosPrueba.hola
        val (audio, _) = generar(t, Protocolo.AUDIBLE_NORMAL)
        var ok = false
        GGWave.receptor().use { rx ->
            var off = 0
            while (off < audio.size) {
                val k = minOf(1000, audio.size - off)
                val r = rx.decodeCrudoParaPruebas(audio.copyOfRange(off, off + k))
                if (r != null && String(r, Charsets.UTF_8) == t) ok = true
                off += k
            }
        }
        fila(Protocolo.AUDIBLE_NORMAL, "INFO ggwave sin agrupar en tramas de 1024 (bloques de 1000)", "HOLA", 1, audio.size, ok)
    }

    /** Informativo: el remuestreo interno de ggwave con bloques de 1024 (motivo del Remuestreador). */
    @Test fun remuestreo_interno_de_ggwave_informativo() {
        val t = TextosPrueba.pregunta
        val (audio48, _) = generar(t, Protocolo.AUDIBLE_NORMAL)
        val mic44 = Remuestreador(RATE, 44100).procesar(audio48, audio48.size)
        var ok = false
        GGWave.receptorConRemuestreoInterno(44100).use { rx ->
            var off = 0; val buf = ShortArray(1024)
            while (off < mic44.size + 44100) {
                val k = minOf(1024, maxOf(0, mic44.size - off)); java.util.Arrays.fill(buf, 0)
                if (k > 0) System.arraycopy(mic44, off, buf, 0, k); off += 1024
                for (r in rx.alimentar(buf, 1024)) if (r is GGWave.Decodificado.Datos) ok = String(r.bytes, Charsets.UTF_8) == t
            }
        }
        fila(Protocolo.AUDIBLE_NORMAL, "INFO remuestreo interno ggwave 44,1 kHz (bloques 1024)", "¿Qué tal…?", 1, audio48.size, ok)
    }
}
