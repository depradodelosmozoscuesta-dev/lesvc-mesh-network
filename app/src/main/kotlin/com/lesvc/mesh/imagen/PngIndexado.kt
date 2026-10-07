package com.lesvc.mesh.imagen

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Cuantización a pocos colores + PNG indexado (1/2/4/8 bits por píxel): ideal para
 * gráficos y dibujos. Es un PNG estándar: cualquier móvil o PC lo abre.
 */
object PngIndexado {

    /** Paleta de hasta [n] colores (popularidad con separación mínima + 3 pasadas de k-medias). */
    fun paleta(img: ImagenRGB, n: Int): IntArray {
        val cubos = HashMap<Int, LongArray>() // clave 4 bits/canal -> [cuenta, sumR, sumG, sumB]
        for (p in img.px) {
            val k = ((p shr 20) and 0xF shl 8) or ((p shr 12) and 0xF shl 4) or ((p shr 4) and 0xF)
            val v = cubos.getOrPut(k) { LongArray(4) }
            v[0]++; v[1] += ((p shr 16) and 255).toLong(); v[2] += ((p shr 8) and 255).toLong(); v[3] += (p and 255).toLong()
        }
        val orden = cubos.entries.sortedWith(compareByDescending<Map.Entry<Int, LongArray>> { it.value[0] }.thenBy { it.key })
        val medios = orden.map { e -> val v = e.value; rgb((v[1] / v[0]).toInt(), (v[2] / v[0]).toInt(), (v[3] / v[0]).toInt()) }
        var pal = ArrayList<Int>()
        var umbral = 64 * 64 * 3
        while (pal.size < n && umbral >= 0) {
            for (c in medios) {
                if (pal.size >= n) break
                if (pal.none { dist(it, c) <= umbral }) pal.add(c)
            }
            umbral = if (umbral == 0) -1 else umbral / 4
        }
        if (pal.isEmpty()) pal.add(0xFFFFFF)
        repeat(3) {
            val suma = Array(pal.size) { LongArray(4) }
            for (p in img.px) {
                val i = cercano(pal, p); val s = suma[i]
                s[0]++; s[1] += ((p shr 16) and 255).toLong(); s[2] += ((p shr 8) and 255).toLong(); s[3] += (p and 255).toLong()
            }
            pal = ArrayList(pal.indices.map { i -> val s = suma[i]; if (s[0] == 0L) pal[i] else rgb((s[1] / s[0]).toInt(), (s[2] / s[0]).toInt(), (s[3] / s[0]).toInt()) })
        }
        return pal.toIntArray()
    }

    fun codificar(img: ImagenRGB, colores: Int): ByteArray {
        val pal = paleta(img, colores)
        val idx = ByteArray(img.px.size) { cercano(pal.asList(), img.px[it]).toByte() }
        return png(img.ancho, img.alto, pal, idx)
    }

    /** La misma imagen ya reducida a la paleta (para codificarla con WebP sin pérdidas). */
    fun cuantizar(img: ImagenRGB, colores: Int): ImagenRGB {
        val pal = paleta(img, colores).asList()
        return ImagenRGB(img.ancho, img.alto, IntArray(img.px.size) { (0xFF shl 24) or pal[cercano(pal, img.px[it])] })
    }

    fun png(w: Int, h: Int, pal: IntArray, idx: ByteArray): ByteArray {
        val bits = when { pal.size <= 2 -> 1; pal.size <= 4 -> 2; pal.size <= 16 -> 4; else -> 8 }
        val porFila = (w * bits + 7) / 8
        val crudo = ByteArray((porFila + 1) * h)
        for (y in 0 until h) {
            val base = y * (porFila + 1)
            crudo[base] = 0 // filtro "ninguno" (el mejor para paletas)
            for (x in 0 until w) {
                val v = idx[y * w + x].toInt() and 0xFF
                val bitPos = x * bits
                val o = base + 1 + bitPos / 8
                val desp = 8 - bits - (bitPos % 8)
                crudo[o] = (crudo[o].toInt() or (v shl desp)).toByte()
            }
        }
        val def = Deflater(9)
        def.setInput(crudo); def.finish()
        val comp = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) { val n = def.deflate(buf); comp.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); writeByte(bits); writeByte(3); writeByte(0); writeByte(0); writeByte(0) } }
        trozo(out, "IHDR", ihdr.toByteArray())
        trozo(out, "PLTE", ByteArray(pal.size * 3) { i -> ((pal[i / 3] shr (16 - 8 * (i % 3))) and 255).toByte() })
        trozo(out, "IDAT", comp.toByteArray())
        trozo(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun trozo(out: ByteArrayOutputStream, tipo: String, datos: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(datos.size)
        val t = tipo.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(datos)
        val crc = CRC32(); crc.update(t); crc.update(datos)
        d.writeInt(crc.value.toInt())
    }

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255); val dg = ((a shr 8) and 255) - ((b shr 8) and 255); val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db
    }
    private fun cercano(pal: List<Int>, p: Int): Int {
        var m = 0; var md = Int.MAX_VALUE
        for (i in pal.indices) { val d = dist(pal[i], p); if (d < md) { md = d; m = i } }
        return m
    }
}
