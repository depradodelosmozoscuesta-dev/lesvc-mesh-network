package com.lesvc.mesh.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.lesvc.mesh.archivo.ProtocoloArchivo
import com.lesvc.mesh.archivo.ReceptorArchivos
import com.lesvc.mesh.texto.CodecMensaje
import com.lesvc.mesh.texto.CompresorTexto
import kotlin.math.sqrt

/**
 * Escucha el micrófono de forma continua (mono) y entrega a ggwave cada bloque.
 * Los callbacks se llaman desde el hilo de captura.
 */
class Receptor(
    private val context: Context,
    private val oyente: Oyente,
    private val compresor: () -> CompresorTexto? = { null },
) {

    interface Oyente {
        fun mensaje(texto: String, partes: Int)
        fun parcial(recibidas: Int, total: Int)
        fun fallo()
        fun nivel(nivel0a1: Float)
        fun error(texto: String)
        /** Tramas de imagen/fichero (cabecera 0x1F): progreso, completado, CRC, peticiones de reenvío. */
        fun archivo(evento: ReceptorArchivos.Evento) {}
    }

    /** Estado de las transferencias de ficheros (solo desde el hilo de captura o tras detener). */
    val archivos = ReceptorArchivos()

    /** Si es true, se descarta el audio captado (p. ej. mientras este móvil emite). */
    @Volatile var silenciado = false
    @Volatile private var activo = false
    private var hilo: Thread? = null
    var descripcion: String = ""
        private set

    val escuchando get() = activo

    @SuppressLint("MissingPermission") // el permiso se comprueba en MainActivity antes de llamar
    fun iniciar(): Boolean {
        if (activo) return true
        val rec = abrirMicro() ?: return false
        activo = true
        hilo = Thread({ bucle(rec.first, rec.second) }, "lesvc-rx").also { it.start() }
        return true
    }

    fun detener() {
        activo = false
        hilo?.join(1500)
        hilo = null
    }

    @SuppressLint("MissingPermission")
    private fun abrirMicro(): Pair<AudioRecord, Int>? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val fuentes = ArrayList<Pair<Int, String>>()
        if (Build.VERSION.SDK_INT >= 24 &&
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            fuentes.add(MediaRecorder.AudioSource.UNPROCESSED to "sin procesar")
        }
        fuentes.add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "reconocimiento de voz")
        fuentes.add(MediaRecorder.AudioSource.MIC to "micrófono estándar")
        for ((fuente, nombre) in fuentes) {
            for (rate in intArrayOf(48000, 44100)) {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val rec = try {
                    AudioRecord(fuente, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2 * 2))
                } catch (e: Exception) { null } ?: continue
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    descripcion = "$rate Hz, $nombre"
                    return rec to rate
                }
                rec.release()
            }
        }
        oyente.error("No se pudo abrir el micrófono")
        return null
    }

    private fun bucle(rec: AudioRecord, rate: Int) {
        val ggwave = try { GGWave.receptor() } catch (e: Exception) {
            rec.release(); activo = false; oyente.error(e.message ?: "Error de ggwave"); return
        }
        val reens = Reensamblador()
        val remu = Remuestreador(rate)
        val buf = ShortArray(1024)
        var acum = 0.0; var nAcum = 0
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                oyente.error("El micrófono está ocupado por otra app"); return
            }
            while (activo) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { oyente.error("Error leyendo el micrófono ($n)"); break }
                if (n == 0) continue
                for (i in 0 until n) { val s = buf[i] / 32768.0; acum += s * s }
                nAcum += n
                if (nAcum >= rate / 10) {
                    oyente.nivel(minOf(1f, (sqrt(acum / nAcum) * 4).toFloat()))
                    acum = 0.0; nAcum = 0
                }
                if (silenciado) continue
                val bloque = if (remu.necesario) remu.procesar(buf, n) else buf
                val nb = if (remu.necesario) bloque.size else n
                if (nb == 0) continue
                for (r in ggwave.alimentar(bloque, nb)) when (r) {
                    is GGWave.Decodificado.Fallido -> oyente.fallo()
                    is GGWave.Decodificado.Datos -> if (ProtocoloArchivo.esTramaArchivo(r.bytes)) {
                        val ev = archivos.recibir(r.bytes)
                        if (ev === ReceptorArchivos.Evento.Ignorada) Unit else oyente.archivo(ev)
                    } else when (val m = reens.recibir(r.bytes)) {
                        is Reensamblador.Resultado.Completo -> oyente.mensaje(CodecMensaje.leer(m.datos, compresor), m.partes)
                        is Reensamblador.Resultado.Parcial -> oyente.parcial(m.recibidas, m.total)
                        Reensamblador.Resultado.Invalido -> oyente.fallo()
                    }
                }
            }
        } catch (e: Exception) {
            oyente.error(e.message ?: e.toString())
        } finally {
            activo = false
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            ggwave.close()
            oyente.nivel(0f)
        }
    }
}
