package com.lesvc.mesh.texto

import android.content.Context
import android.os.SystemClock
import android.util.Log

/** Carga perezosa (y una sola vez) del compresor desde assets/compresion/v1/. Seguro entre hilos. */
object Compresores {
    @Volatile private var instancia: CompresorTexto? = null
    @Volatile var fallo: String? = null
        private set
    @Volatile var msCarga = 0L
        private set

    val listo get() = instancia != null

    fun obtener(ctx: Context): CompresorTexto? {
        instancia?.let { return it }
        synchronized(this) {
            instancia?.let { return it }
            if (fallo != null) return null
            return try {
                val t0 = SystemClock.elapsedRealtime()
                val am = ctx.applicationContext.assets
                val c = CompresorTexto.cargar { n -> am.open("compresion/v1/$n").bufferedReader(Charsets.UTF_8).use { it.readText() } }
                msCarga = SystemClock.elapsedRealtime() - t0
                Log.i("LESVC", "Compresor cargado en $msCarga ms")
                instancia = c; c
            } catch (e: Throwable) {
                fallo = e.message ?: e.toString(); Log.e("LESVC", "No se pudo cargar el compresor", e); null
            }
        }
    }
}
