package com.lesvc.mesh.texto

import java.util.Locale

/**
 * Utilidades de URL para el compresor:
 *  - detección de mensajes que son una URL,
 *  - normalización (esquema y host en minúsculas; ruta, consulta y fragmento intactos),
 *  - "combo" inicial (idea de Jorge): un único código para esquema + "www." + dominio
 *    de primer nivel. El TLD se quita del final del host y se vuelve a insertar al
 *    decodificar delante del primer '/', ':', '?', '#' (o al final).
 */
object Url {
    val ESQUEMAS = listOf("", "http://", "https://")
    val WWW = listOf("", "www.")
    /** Orden importa: los compuestos antes que sus sufijos (.gob.es antes que .es). */
    val TLDS = listOf("", ".gob.es", ".com.es", ".org.es", ".co.uk", ".org.uk", ".com.mx", ".com.ar",
        ".com", ".es", ".org", ".net", ".eu", ".info", ".io", ".cat", ".gal", ".eus", ".edu", ".gov",
        ".uk", ".de", ".fr", ".it", ".pt", ".tv", ".me", ".co", ".app", ".dev", ".be", ".us", ".mx", ".ar", ".int")
    val NUM_COMBOS = ESQUEMAS.size * WWW.size * TLDS.size

    private val reEsquema = Regex("^(?i)(https?://)")
    private val reUrl = Regex("^(?i)(https?://\\S+|www\\.\\S+\\.\\S+)$")
    private val reDominio = Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}([/:?#]\\S*)?$")

    fun pareceUrl(s: String): Boolean = s.isNotEmpty() && s.length < 2000 && (reUrl.matches(s) || reDominio.matches(s))

    /** Esquema y host en minúsculas (no rompe la URL: son insensibles a mayúsculas). */
    fun normalizar(s: String): String {
        val m = reEsquema.find(s)
        val esquema = m?.value?.lowercase(Locale.ROOT) ?: ""
        val resto = s.substring(m?.value?.length ?: 0)
        val finAut = resto.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) resto.length else it }
        val aut = resto.substring(0, finAut)
        val arroba = aut.lastIndexOf('@')
        val autNorm = if (arroba >= 0) aut.substring(0, arroba + 1) + aut.substring(arroba + 1).lowercase(Locale.ROOT)
                      else aut.lowercase(Locale.ROOT)
        return esquema + autNorm + resto.substring(finAut)
    }

    /** Devuelve (índice de combo, resto). Requiere una URL ya normalizada. */
    fun separar(u: String): Pair<Int, String> {
        var r = u
        val e = when { r.startsWith("https://") -> 2; r.startsWith("http://") -> 1; else -> 0 }
        r = r.substring(ESQUEMAS[e].length)
        val finAut = r.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) r.length else it }
        val conUsuario = r.substring(0, finAut).contains('@')
        var w = 0; var t = 0
        if (!conUsuario) {
            if (r.startsWith("www.") && finHost(r.substring(4)) > 0) { w = 1; r = r.substring(4) }
            val fh = finHost(r)
            val host = r.substring(0, fh)
            for (i in 1 until TLDS.size) {
                if (host.length > TLDS[i].length && host.endsWith(TLDS[i])) { t = i; break }
            }
            if (t > 0) r = host.substring(0, host.length - TLDS[t].length) + r.substring(fh)
        }
        return Pair((e * WWW.size + w) * TLDS.size + t, r)
    }

    fun unir(combo: Int, resto: String): String {
        val t = combo % TLDS.size
        val w = (combo / TLDS.size) % WWW.size
        val e = combo / (TLDS.size * WWW.size)
        val fh = finHost(resto)
        val conTld = if (t == 0) resto else resto.substring(0, fh) + TLDS[t] + resto.substring(fh)
        return ESQUEMAS[e] + WWW[w] + conTld
    }

    private fun finHost(s: String): Int = s.indexOfAny(charArrayOf('/', ':', '?', '#')).let { if (it < 0) s.length else it }
}
