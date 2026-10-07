package com.lesvc.mesh.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Reproduce por el altavoz (mono, 48 kHz) las cargas codificadas con ggwave. */
class Emisor {
    private val ggwave = GGWave.emisor()
    @Volatile private var cancelado = false

    fun cancelar() { cancelado = true }

    /**
     * Bloquea hasta terminar (llámalo fuera del hilo principal).
     * @return true si se emitió entero, false si se canceló.
     */
    fun emitir(cargas: List<ByteArray>, protocolo: Protocolo, volumen: Int, alEmpezarParte: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        cancelado = false
        val rate = GGWave.SAMPLE_RATE_TX
        // Se codifica cada parte justo antes de emitirla (una imagen puede tener ~100 partes).
        val pausa = ShortArray(rate * 4 / 10) // 0,4 s de silencio entre partes

        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            maxOf(minBuf, rate / 2 * 2),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "No se pudo abrir el altavoz" }
            track.play()
            var escritas = 0L
            for ((i, carga) in cargas.withIndex()) {
                if (cancelado) break
                val onda = ggwave.encode(carga, protocolo, volumen)
                alEmpezarParte(i + 1, cargas.size)
                escritas += escribir(track, onda)
                if (i < cargas.lastIndex) escritas += escribir(track, pausa)
            }
            if (cancelado) {
                track.pause(); track.flush()
                return false
            }
            // Margen final para que el último bloque salga entero por el altavoz.
            escritas += escribir(track, ShortArray(rate / 5))
            val limite = System.currentTimeMillis() + 2000 + escritas * 1000 / rate
            while (!cancelado && (track.playbackHeadPosition.toLong() and 0xffffffffL) < escritas &&
                System.currentTimeMillis() < limite) {
                Thread.sleep(20)
            }
            track.stop()
            return !cancelado
        } finally {
            track.release()
        }
    }

    private fun escribir(track: AudioTrack, datos: ShortArray): Int {
        var off = 0
        while (off < datos.size && !cancelado) {
            val n = track.write(datos, off, minOf(4096, datos.size - off))
            if (n < 0) throw IllegalStateException("Error del altavoz ($n)")
            off += n
        }
        return off
    }

    fun cerrar() = ggwave.close()
}
