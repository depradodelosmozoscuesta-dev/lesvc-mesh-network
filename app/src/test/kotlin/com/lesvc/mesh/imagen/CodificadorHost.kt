package com.lesvc.mesh.imagen

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Equivalente en el PC de Bitmap.compress / BitmapFactory (el android.jar de los tests no trae
 * java.awt ni ImageIO): WebP con cwebp/dwebp (libwebp, la misma biblioteca que Android) y
 * JPEG/lectura con Pillow (libjpeg) mediante tools/imagen_host.py. Intercambio en PPM.
 */
object CodificadorHost : CodificadorConPerdidas {
    private val script = listOf(File("../tools/imagen_host.py"), File("tools/imagen_host.py")).first { it.exists() }.absoluteFile
    val hayWebp = try { ProcessBuilder("cwebp", "-version").start().waitFor() == 0 } catch (e: Exception) { false }
    override val formatos = if (hayWebp) listOf(Formato.WEBP, Formato.JPEG) else listOf(Formato.JPEG)
    private val cache = HashMap<Triple<ImagenRGB, Formato, Int>, ByteArray>()

    private fun ejecutar(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val salida = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} falló: $salida" }
    }
    private fun tmp(ext: String) = File.createTempFile("lesvc", ".$ext")

    fun escribirPpm(img: ImagenRGB, f: File) {
        val out = ByteArrayOutputStream()
        out.write("P6\n${img.ancho} ${img.alto}\n255\n".toByteArray())
        for (p in img.px) { out.write((p shr 16) and 255); out.write((p shr 8) and 255); out.write(p and 255) }
        f.writeBytes(out.toByteArray())
    }

    fun leerPpm(f: File): ImagenRGB {
        val b = f.readBytes(); var i = 0
        fun token(): String {
            while (true) {
                while (b[i].toInt().toChar().isWhitespace()) i++
                if (b[i] == '#'.code.toByte()) { while (b[i] != '\n'.code.toByte()) i++ } else break
            }
            val s = i; while (!b[i].toInt().toChar().isWhitespace()) i++
            return String(b, s, i - s)
        }
        check(token() == "P6"); val w = token().toInt(); val h = token().toInt(); check(token() == "255"); i++
        return ImagenRGB(w, h, IntArray(w * h) { k ->
            val o = i + 3 * k
            (0xFF shl 24) or ((b[o].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o + 2].toInt() and 255)
        })
    }

    fun leer(f: File): ImagenRGB {
        val ppm = tmp("ppm")
        try { ejecutar("python3", script.path, "a_ppm", f.path, ppm.path); return leerPpm(ppm) } finally { ppm.delete() }
    }

    override fun codificar(img: ImagenRGB, formato: Formato, calidad: Int): ByteArray = cache.getOrPut(Triple(img, formato, calidad)) {
        val ppm = tmp("ppm"); val sal = tmp(formato.extension)
        try {
            escribirPpm(img, ppm)
            when (formato) {
                Formato.JPEG -> ejecutar("python3", script.path, "jpeg", ppm.path, "$calidad", sal.path)
                // -m 4 ≈ el método por defecto de Bitmap.compress en Android
                Formato.WEBP -> ejecutar("cwebp", "-quiet", "-m", "4", "-q", "$calidad", ppm.path, "-o", sal.path)
                Formato.PNG -> return@getOrPut PngIndexado.codificar(img, 256)
            }
            sal.readBytes()
        } finally { ppm.delete(); sal.delete() }
    }

    override fun webpSinPerdidas(img: ImagenRGB): ByteArray? {
        if (!hayWebp) return null
        val ppm = tmp("ppm"); val sal = tmp("webp")
        try { escribirPpm(img, ppm); ejecutar("cwebp", "-quiet", "-lossless", "-z", "6", ppm.path, "-o", sal.path); return sal.readBytes() } finally { ppm.delete(); sal.delete() }
    }

    /** Decodifica lo recibido (como haría el móvil con BitmapFactory). */
    fun decodificar(datos: ByteArray, formato: Formato): ImagenRGB {
        val ent = tmp(formato.extension); val ppm = tmp("ppm")
        try {
            ent.writeBytes(datos)
            if (formato == Formato.WEBP) ejecutar("dwebp", "-quiet", ent.path, "-ppm", "-o", ppm.path)
            else ejecutar("python3", script.path, "a_ppm", ent.path, ppm.path)
            return leerPpm(ppm)
        } finally { ent.delete(); ppm.delete() }
    }

    fun guardarPng(img: ImagenRGB, f: File) {
        f.parentFile.mkdirs()
        val ppm = tmp("ppm")
        try { escribirPpm(img, ppm); ejecutar("python3", script.path, "png", ppm.path, f.path) } finally { ppm.delete() }
    }
}
