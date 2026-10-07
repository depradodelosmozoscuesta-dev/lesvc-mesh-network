package com.lesvc.mesh.texto

import com.lesvc.mesh.audio.Duracion
import com.lesvc.mesh.audio.Protocolo
import com.lesvc.mesh.audio.Troceador

/**
 * Decide cómo viaja un mensaje de texto:
 *  - UTF-8 tal cual (≤140 bytes: compatible con Waver/otras apps ggwave), o
 *  - comprimido (cabecera 0x11/0x12), solo si así se tarda MENOS en emitir.
 * Los mensajes largos (comprimidos o no) se trocean con [Troceador] (cabecera 0x1E).
 */
object CodecMensaje {

    class Preparado(
        val cargas: List<ByteArray>,
        val bytesOriginal: Int,
        val bytesEnviados: Int,
        val comprimido: Boolean,
        val segundos: Double,
        val segundosSinComprimir: Double,
        val textoQueLlegara: String,
    )

    fun preparar(texto: String, protocolo: Protocolo, compresor: CompresorTexto?, compatible: Boolean): Preparado {
        val crudas = Troceador.cargas(texto)
        val bytesCrudo = Troceador.bytesUtf8(texto)
        val tCrudo = Duracion.segundosEnvio(crudas.map { it.size }, protocolo)
        val sinComprimir = Preparado(crudas, bytesCrudo, bytesCrudo, false, tCrudo, tCrudo, texto)
        if (compatible || compresor == null) return sinComprimir
        val r = try { compresor.comprimir(texto) } catch (e: Exception) { return sinComprimir }
        // garantía de exactitud: si por lo que sea no se recupera lo mismo, va sin comprimir
        if (try { compresor.descomprimir(r.bytes) != r.textoFinal } catch (e: Exception) { true }) return sinComprimir
        val cargas = if (r.bytes.size <= Troceador.MAX_BYTES) listOf(r.bytes) else try {
            Troceador.cargasBytes(r.bytes)
        } catch (e: IllegalArgumentException) { return sinComprimir }
        val t = Duracion.segundosEnvio(cargas.map { it.size }, protocolo)
        return if (t < tCrudo) Preparado(cargas, bytesCrudo, r.bytes.size, true, t, tCrudo, r.textoFinal) else sinComprimir
    }

    /** Convierte los bytes recibidos (ya reensamblados) en texto. */
    fun leer(bytes: ByteArray, compresor: () -> CompresorTexto?): String {
        if (CompresorTexto.esComprimido(bytes)) {
            val c = compresor() ?: return "[mensaje comprimido: no se pudo cargar el diccionario]"
            return try { c.descomprimir(bytes) } catch (e: Exception) { "[mensaje comprimido ilegible: ${e.message}]" }
        }
        return String(bytes, Charsets.UTF_8)
    }
}
