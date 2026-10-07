package com.lesvc.mesh.audio

/**
 * Envoltorio Kotlin de ggwave (C/C++, MIT, versión fijada ggwave-v0.4.3).
 * Audio siempre mono PCM 16 bits. Frecuencia de trabajo interna de ggwave: 48 kHz.
 *
 * Una instancia NO es segura para usarla desde dos hilos a la vez: usa una
 * para emitir y otra para escuchar.
 */
class GGWave private constructor(private var id: Int) : AutoCloseable {

    /** Genera la forma de onda (mono, 16 bits, [SAMPLE_RATE_TX] Hz) de hasta 140 bytes. */
    fun encode(payload: ByteArray, protocolo: Protocolo, volumen: Int): ShortArray {
        require(id >= 0) { "Instancia cerrada" }
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "La carga debe tener entre 1 y $MAX_PAYLOAD_BYTES bytes (tiene ${payload.size})"
        }
        return nativeEncode(id, payload, protocolo.id, volumen.coerceIn(1, 100))
            ?: throw IllegalStateException("ggwave no pudo codificar el mensaje")
    }

    private val trama = ShortArray(SAMPLES_PER_FRAME)
    private var enTrama = 0

    /**
     * Entrega muestras capturadas (48 kHz, mono) de cualquier tamaño de bloque.
     * Internamente se pasan a ggwave en tramas de exactamente [SAMPLES_PER_FRAME]
     * muestras: ggwave-v0.4.3 solo decodifica bien así (con bloques de otro
     * tamaño pierde la cuenta de muestras y no descifra nada; ver tests).
     *
     * @return lista (casi siempre vacía) de resultados: [Decodificado.Datos] con
     * los bytes recibidos o [Decodificado.Fallido] si se oyó una transmisión que
     * no se pudo descifrar.
     */
    fun alimentar(samples: ShortArray, count: Int = samples.size): List<Decodificado> {
        require(id >= 0) { "Instancia cerrada" }
        var res: MutableList<Decodificado>? = null
        var off = 0
        while (off < count) {
            val k = minOf(SAMPLES_PER_FRAME - enTrama, count - off)
            System.arraycopy(samples, off, trama, enTrama, k)
            enTrama += k; off += k
            if (enTrama == SAMPLES_PER_FRAME) {
                enTrama = 0
                val r = nativeDecode(id, trama, SAMPLES_PER_FRAME) ?: continue
                if (res == null) res = ArrayList(1)
                res.add(if (r.isEmpty()) Decodificado.Fallido else Decodificado.Datos(r))
            }
        }
        return res ?: emptyList()
    }

    /** Solo pruebas: llamada directa a ggwave_ndecode sin agrupar en tramas. */
    internal fun decodeCrudoParaPruebas(samples: ShortArray): ByteArray? =
        nativeDecode(id, samples, samples.size)?.takeIf { it.isNotEmpty() }

    override fun close() {
        if (id >= 0) {
            nativeFree(id)
            id = -1
        }
    }

    sealed class Decodificado {
        object Fallido : Decodificado()
        class Datos(val bytes: ByteArray) : Decodificado()
    }

    companion object {
        const val SAMPLE_RATE_TX = 48000
        const val MAX_PAYLOAD_BYTES = 140
        const val SAMPLES_PER_FRAME = 1024 // ggwave kDefaultSamplesPerFrame
        const val GGWAVE_VERSION = "ggwave-v0.4.3 (a38e38b)"

        private const val MODE_RX = 1
        private const val MODE_TX = 2

        init {
            System.loadLibrary("lesvc_ggwave")
        }

        fun emisor(): GGWave = create(MODE_TX, SAMPLE_RATE_TX, SAMPLE_RATE_TX)

        /**
         * Receptor a 48 kHz. Si el micro graba a otra frecuencia, pasa el audio
         * antes por [Remuestreador] (ver allí por qué no se usa el de ggwave).
         */
        fun receptor(): GGWave = create(MODE_RX, SAMPLE_RATE_TX, SAMPLE_RATE_TX)

        /** Solo para pruebas: usa el remuestreo interno de ggwave. */
        internal fun receptorConRemuestreoInterno(sampleRateCaptura: Int): GGWave =
            create(MODE_RX, sampleRateCaptura, SAMPLE_RATE_TX)

        private fun create(mode: Int, inp: Int, out: Int): GGWave {
            val id = nativeInit(mode, inp, out)
            check(id >= 0) { "No se pudo iniciar ggwave (¿demasiadas instancias abiertas?)" }
            return GGWave(id)
        }

        @JvmStatic private external fun nativeInit(mode: Int, sampleRateInp: Int, sampleRateOut: Int): Int
        @JvmStatic private external fun nativeFree(id: Int)
        @JvmStatic private external fun nativeEncode(id: Int, payload: ByteArray, protocol: Int, volume: Int): ShortArray?
        @JvmStatic private external fun nativeDecode(id: Int, samples: ShortArray, count: Int): ByteArray?
    }
}
