package com.lesvc.mesh.imagen

/** Imagen en memoria independiente de Android (píxeles 0xAARRGGBB). */
class ImagenRGB(val ancho: Int, val alto: Int, val px: IntArray) {
    init { require(ancho > 0 && alto > 0 && px.size == ancho * alto) }

    /** Reduce para que el lado mayor sea ≤ [maxLado] (promedio por áreas: buena calidad al reducir). */
    fun reducir(maxLado: Int): ImagenRGB {
        val lado = maxOf(ancho, alto)
        if (lado <= maxLado) return this
        val f = maxLado.toDouble() / lado
        val w = maxOf(1, Math.round(ancho * f).toInt()); val h = maxOf(1, Math.round(alto * f).toInt())
        val out = IntArray(w * h)
        val sx = ancho.toDouble() / w; val sy = alto.toDouble() / h
        for (y in 0 until h) {
            val y0 = y * sy; val y1 = (y + 1) * sy
            for (x in 0 until w) {
                val x0 = x * sx; val x1 = (x + 1) * sx
                var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0; var peso = 0.0
                var yy = y0.toInt()
                while (yy < y1 && yy < alto) {
                    val wy = minOf(y1, yy + 1.0) - maxOf(y0, yy.toDouble())
                    var xx = x0.toInt()
                    while (xx < x1 && xx < ancho) {
                        val wx = minOf(x1, xx + 1.0) - maxOf(x0, xx.toDouble())
                        val p = px[yy * ancho + xx]; val wgt = wx * wy
                        a += (p ushr 24) * wgt; r += ((p shr 16) and 255) * wgt
                        g += ((p shr 8) and 255) * wgt; b += (p and 255) * wgt; peso += wgt
                        xx++
                    }
                    yy++
                }
                out[y * w + x] = (Math.round(a / peso).toInt() shl 24) or (Math.round(r / peso).toInt() shl 16) or
                    (Math.round(g / peso).toInt() shl 8) or Math.round(b / peso).toInt()
            }
        }
        return ImagenRGB(w, h, out)
    }

    /** Escala de grises (luminancia BT.601), opaca. */
    fun gris(): ImagenRGB = ImagenRGB(ancho, alto, IntArray(px.size) { i ->
        val p = px[i]
        val l = ((299 * ((p shr 16) and 255) + 587 * ((p shr 8) and 255) + 114 * (p and 255)) / 1000).coerceIn(0, 255)
        (0xFF shl 24) or (l shl 16) or (l shl 8) or l
    })

    /** Compone sobre blanco (quita transparencias). */
    fun opaca(): ImagenRGB = ImagenRGB(ancho, alto, IntArray(px.size) { i ->
        val p = px[i]; val a = p ushr 24
        if (a == 255) p else {
            fun c(v: Int) = (v * a + 255 * (255 - a)) / 255
            (0xFF shl 24) or (c((p shr 16) and 255) shl 16) or (c((p shr 8) and 255) shl 8) or c(p and 255)
        }
    })
}
