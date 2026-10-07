package com.lesvc.mesh.archivo

import com.lesvc.mesh.audio.Duracion
import com.lesvc.mesh.audio.Protocolo

/** Relación entre bytes de un fichero y segundos de sonido (cabecera + partes + cabecera final). */
object Presupuesto {

    fun tamanosTramas(bytes: Int, repetir: Boolean = false): List<Int> {
        val n = (bytes + ProtocoloArchivo.MAX_DATOS - 1) / ProtocoloArchivo.MAX_DATOS
        val l = ArrayList<Int>()
        l.add(ProtocoloArchivo.TAM_CABECERA)
        for (i in 0 until n) {
            val t = ProtocoloArchivo.CAB_DATOS + if (i < n - 1) ProtocoloArchivo.MAX_DATOS else bytes - ProtocoloArchivo.MAX_DATOS * (n - 1)
            l.add(t); if (repetir) l.add(t)
        }
        l.add(ProtocoloArchivo.TAM_CABECERA)
        return l
    }

    fun partes(bytes: Int) = (bytes + ProtocoloArchivo.MAX_DATOS - 1) / ProtocoloArchivo.MAX_DATOS

    fun segundos(bytes: Int, p: Protocolo, repetir: Boolean = false) = Duracion.segundosEnvio(tamanosTramas(bytes, repetir), p)

    /** Máximo de bytes que caben en [segundos] (0 si ni la cabecera cabe). */
    fun bytesParaSegundos(segundos: Double, p: Protocolo, repetir: Boolean = false): Int {
        var lo = 0; var hi = ProtocoloArchivo.MAX_DATOS * ProtocoloArchivo.MAX_PARTES
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (segundos(mid, p, repetir) <= segundos) lo = mid else hi = mid - 1
        }
        return lo
    }
}
