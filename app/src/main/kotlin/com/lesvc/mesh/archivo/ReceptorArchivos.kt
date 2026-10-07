package com.lesvc.mesh.archivo

import com.lesvc.mesh.archivo.ProtocoloArchivo.Cabecera
import com.lesvc.mesh.archivo.ProtocoloArchivo.Trama
import java.io.ByteArrayOutputStream

/**
 * Junta las tramas 'D' de cada envío (por id) y comprueba el CRC32 al final.
 * No es seguro entre hilos: úsalo desde un único hilo (el de captura).
 */
class ReceptorArchivos(private val caducidadMs: Long = 30 * 60_000L) {

    class Transferencia(val id: Int) {
        var cabecera: Cabecera? = null
        var total: Int = 0                       // nº de partes (de la cabecera o de las tramas D)
        val partes = HashMap<Int, ByteArray>()
        var ultimo = 0L
        var crcFallido = false
        var completada = false
        val recibidas get() = partes.size
        val faltan: List<Int> get() = (0 until total).filter { it !in partes }
        val faltaCabecera get() = cabecera == null
    }

    sealed class Evento {
        class Progreso(val t: Transferencia) : Evento()
        class Completo(val t: Transferencia, val datos: ByteArray) : Evento()
        class CrcIncorrecto(val t: Transferencia) : Evento()
        class Peticion(val p: Trama.Peticion) : Evento()
        object Ignorada : Evento()
    }

    private val transferencias = LinkedHashMap<Int, Transferencia>()

    fun transferencia(id: Int) = transferencias[id]

    fun recibir(b: ByteArray, ahora: Long = System.currentTimeMillis()): Evento {
        transferencias.entries.removeAll { ahora - it.value.ultimo > caducidadMs }
        return when (val tr = ProtocoloArchivo.analizar(b) ?: return Evento.Ignorada) {
            is Trama.Peticion -> Evento.Peticion(tr)
            is Trama.Cab -> {
                val t = obtener(tr.c.id, ahora)
                if (t.completada) return Evento.Ignorada         // repetición de la cabecera final
                val previa = t.cabecera
                if (previa != null && previa != tr.c) {             // id reutilizado por otro envío
                    transferencias.remove(tr.c.id); return recibir(b, ahora)
                }
                t.cabecera = tr.c
                if (t.total != tr.c.partes) { if (t.total != 0) t.partes.clear(); t.total = tr.c.partes }
                intentarCompletar(t)
            }
            is Trama.Datos -> {
                val t = obtener(tr.id, ahora)
                if (t.completada) return Evento.Ignorada
                if (t.total != 0 && t.total != tr.partes) { t.partes.clear(); t.cabecera = null }
                t.total = tr.partes
                val c = t.cabecera
                val esperado = if (c == null) null else if (tr.indice < c.partes - 1) ProtocoloArchivo.MAX_DATOS else c.tamano - ProtocoloArchivo.MAX_DATOS * (c.partes - 1)
                if (esperado != null && tr.datos.size != esperado) return Evento.Ignorada
                t.partes[tr.indice] = tr.datos
                intentarCompletar(t)
            }
        }
    }

    private fun obtener(id: Int, ahora: Long): Transferencia {
        val t = transferencias.getOrPut(id) { Transferencia(id) }
        t.ultimo = ahora
        return t
    }

    private fun intentarCompletar(t: Transferencia): Evento {
        val c = t.cabecera
        if (c == null || t.partes.size < c.partes) return Evento.Progreso(t)
        val out = ByteArrayOutputStream(c.tamano)
        for (i in 0 until c.partes) out.write(t.partes[i]!!)
        val datos = out.toByteArray()
        if (datos.size != c.tamano || ProtocoloArchivo.crc32(datos) != c.crc) {
            // No sabemos qué parte está mal: se descarta todo y se pide de nuevo.
            t.crcFallido = true; t.partes.clear()
            return Evento.CrcIncorrecto(t)
        }
        t.completada = true; t.crcFallido = false
        return Evento.Completo(t, datos)
    }
}

/** Últimos envíos de este móvil, para atender peticiones de reenvío. Seguro entre hilos. */
object EnviosRecientes {
    private val envios = LinkedHashMap<Int, ProtocoloArchivo.Envio>()
    @Synchronized fun guardar(e: ProtocoloArchivo.Envio) {
        envios.remove(e.cabecera.id); envios[e.cabecera.id] = e
        while (envios.size > 5) envios.remove(envios.keys.first())
    }
    @Synchronized fun buscar(id: Int) = envios[id]
    @Synchronized fun ultimo() = envios.values.lastOrNull()
}
