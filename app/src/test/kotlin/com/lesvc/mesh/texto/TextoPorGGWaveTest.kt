package com.lesvc.mesh.texto

import com.lesvc.mesh.audio.GGWave
import com.lesvc.mesh.audio.Protocolo
import com.lesvc.mesh.audio.Reensamblador
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Mensajes comprimidos → ggwave (onda real) → ruido 10 dB → decodificar → reensamblar → descomprimir. */
class TextoPorGGWaveTest {
    private val c = CompresionTest.cargarCompresor()

    private fun canal(cargas: List<ByteArray>, p: Protocolo, snr: Double, seed: Long): List<ByteArray> {
        val tx = GGWave.emisor()
        val partes = ArrayList<ShortArray>(); partes.add(ShortArray(24000))
        for (k in cargas) { partes.add(tx.encode(k, p, 50)); partes.add(ShortArray(48000 * 4 / 10)) }
        partes.add(ShortArray(24000)); tx.close()
        val a = ShortArray(partes.sumOf { it.size }); var o = 0
        for (x in partes) { System.arraycopy(x, 0, a, o, x.size); o += x.size }
        var e = 0.0; var n = 0; for (s in a) if (s.toInt() != 0) { e += s.toDouble() * s; n++ }
        val sigma = sqrt(e / n) / Math.pow(10.0, snr / 20); val r = Random(seed)
        val ruidoso = ShortArray(a.size) { (a[it] + r.nextGaussian() * sigma).roundToInt().coerceIn(-32768, 32767).toShort() }
        val res = ArrayList<ByteArray>()
        GGWave.receptor().use { rx ->
            var i = 0
            while (i < ruidoso.size) { val m = minOf(1000, ruidoso.size - i); rx.alimentar(ruidoso.copyOfRange(i, i + m)).filterIsInstance<GGWave.Decodificado.Datos>().forEach { res.add(it.bytes) }; i += m }
        }
        return res
    }

    @Test fun comprimidos_con_ruido_10dB() {
        val textos = listOf(
            "hemos quedado a las 3",
            "¿Mañana a qué hora salimos hacia la sierra? Yo puedo a partir de las 9:30.",
            "https://www.Ejemplo.com/Ruta/Con/Mayusculas?q=Árbol&x=1",
            "Itziar dice que mañana no puede ir a Valdemorillo porque tiene turno en el hospital. Si Itziar no va, yo tampoco voy a Valdemorillo, así que lo dejamos para el sábado y se lo decimos a Begoña y a Txema. Recordad que la reunión es el jueves a las 19:30 en el local de la calle Fuencarral; Fuencarral está cortada por obras.",
        )
        for ((i, t) in textos.withIndex()) for (p in listOf(Protocolo.AUDIBLE_MUY_RAPIDO, Protocolo.AUDIBLE_NORMAL)) {
            val prep = CodecMensaje.preparar(t, p, c, compatible = false)
            assertTrue("debería comprimirse: $t", prep.comprimido)
            val reens = Reensamblador(); var llegado: String? = null
            for (b in canal(prep.cargas, p, 10.0, 31L * i + p.id)) {
                val r = reens.recibir(b)
                if (r is Reensamblador.Resultado.Completo) llegado = CodecMensaje.leer(r.datos) { c }
            }
            assertEquals(prep.textoQueLlegara, llegado)
            println("ruido 10 dB · ${p.name} · ${prep.bytesOriginal}→${prep.bytesEnviados} B · ${prep.cargas.size} parte(s) · ${"%.1f".format(prep.segundos)} s (sin comprimir ${"%.1f".format(prep.segundosSinComprimir)} s) · OK")
        }
    }

    @Test fun modo_compatible_y_textos_cortos_van_en_utf8_tal_cual() {
        val t = "Hola"
        val prep = CodecMensaje.preparar(t, Protocolo.AUDIBLE_MUY_RAPIDO, c, compatible = true)
        assertEquals(listOf(t), prep.cargas.map { String(it, Charsets.UTF_8) })
        // Un texto que llegue sin cabecera (p. ej. desde Waver) se lee tal cual
        assertEquals("¿Qué tal?", CodecMensaje.leer("¿Qué tal?".toByteArray()) { c })
        // Cuando comprimir no acorta la emisión, se manda UTF-8 normal aunque la compresión esté activa
        val corto = CodecMensaje.preparar("ok", Protocolo.AUDIBLE_MUY_RAPIDO, c, compatible = false)
        assertTrue(!corto.comprimido); assertEquals("ok", String(corto.cargas.single(), Charsets.UTF_8))
    }

    @Test fun tablas_de_entropia_v1_congeladas() {
        // Si esto falla, ha cambiado el diccionario/corpus v1 o el tokenizador: hay que crear una versión nueva (otro id).
        assertEquals(0x4198363aL, c.entropia.huella)
    }
}
