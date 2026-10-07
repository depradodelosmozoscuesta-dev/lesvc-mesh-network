package com.lesvc.mesh.imagen

/** Formatos de salida (el código numérico va en la cabecera del envío). */
enum class Formato(val codigo: Int, val mime: String, val extension: String) {
    WEBP(1, "image/webp", "webp"), JPEG(2, "image/jpeg", "jpg"), PNG(3, "image/png", "png");
    companion object { fun de(c: Int) = values().firstOrNull { it.codigo == c } }
}

/** Codificador con pérdidas (en el móvil: Bitmap.compress; en los tests del PC: ImageIO / cwebp). */
interface CodificadorConPerdidas {
    val formatos: List<Formato>   // WEBP y/o JPEG, por orden de preferencia
    fun codificar(img: ImagenRGB, formato: Formato, calidad: Int): ByteArray
    /** WebP sin pérdidas (null si el sistema no lo ofrece). */
    fun webpSinPerdidas(img: ImagenRGB): ByteArray? = null
}

enum class Preset(val etiqueta: String) { FOTO("Foto"), GRAFICO("Gráfico / dibujo") }

/**
 * Busca la mejor imagen que quepa en [presupuestoBytes]:
 *  - FOTO: lado mayor de 320 a 96 px; WebP (o JPEG) con búsqueda binaria de calidad.
 *    Se elige el MAYOR tamaño que alcance una calidad mínima aceptable.
 *  - GRÁFICO: PNG indexado (paleta reducida 16/8/4 colores, o 1 bit) desde 640 px hacia abajo,
 *    priorizando resolución sobre colores; si ni así cabe, recurre al modo FOTO.
 */
class CompresorImagen(private val cod: CodificadorConPerdidas) {

    class Resultado(val bytes: ByteArray, val formato: Formato, val ancho: Int, val alto: Int, val detalle: String)

    fun comprimir(original: ImagenRGB, preset: Preset, presupuestoBytes: Int, gris: Boolean): Resultado? {
        val base0 = original.reducir(1024).opaca()
        val base = if (gris) base0.gris() else base0
        return when (preset) {
            Preset.FOTO -> foto(base, presupuestoBytes)
            Preset.GRAFICO -> grafico(base, presupuestoBytes) ?: foto(base, presupuestoBytes)
        }
    }

    /**
     * 1) lado 320→96 px con calidad ≥ mínima (40/45), el mayor tamaño que quepa;
     * 2) si no cabe, 96 px bajando la calidad hasta 10 (mejor algo pequeño y reconocible);
     * 3) último recurso: menos de 96 px.
     */
    private fun foto(base: ImagenRGB, presupuesto: Int): Resultado? {
        for (lado in LADOS_FOTO) buscar(base.reducir(lado), presupuesto, null)?.let { return it }
        buscar(base.reducir(96), presupuesto, 10)?.let { return it }
        for (lado in LADOS_ULTIMO_RECURSO) buscar(base.reducir(lado), presupuesto, 10)?.let { return it }
        return null
    }

    /** Mayor calidad (búsqueda binaria) con la que cabe, en el primer formato que quepa. */
    private fun buscar(img: ImagenRGB, presupuesto: Int, qMinForzada: Int?): Resultado? {
        for (f in cod.formatos) {
            val qMin = qMinForzada ?: CALIDAD_MIN[f] ?: 40
            val enMin = cod.codificar(img, f, qMin)
            if (enMin.size > presupuesto) continue
            var lo = qMin; var hi = 90; var bytes = enMin
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val b = cod.codificar(img, f, mid)
                if (b.size <= presupuesto) { lo = mid; bytes = b } else hi = mid - 1
            }
            return Resultado(bytes, f, img.ancho, img.alto, "${f.name} ${img.ancho}×${img.alto} px, calidad $lo")
        }
        return null
    }

    /** Prioridad: resolución (legibilidad del texto y las líneas) > número de colores. */
    private fun grafico(base: ImagenRGB, presupuesto: Int): Resultado? {
        for (lado in LADOS_GRAFICO) {
            val img = base.reducir(lado)
            for (colores in intArrayOf(16, 8, 4)) paleta(img, colores, presupuesto)?.let { return it }
        }
        for (lado in LADOS_GRAFICO.filter { it <= 320 }) {   // blanco y negro (1 bit) como último recurso
            paleta(base.reducir(lado), 2, presupuesto)?.let { return it }
        }
        return null
    }

    /** PNG indexado o WebP sin pérdidas de la imagen con paleta reducida: el menor que quepa. */
    private fun paleta(img: ImagenRGB, colores: Int, presupuesto: Int): Resultado? {
        val png = PngIndexado.codificar(img, colores)
        val webp = cod.webpSinPerdidas(PngIndexado.cuantizar(img, colores))
        val desc = "${img.ancho}×${img.alto} px, " + if (colores == 2) "2 colores (1 bit)" else "$colores colores"
        val r = if (webp != null && webp.size < png.size) Resultado(webp, Formato.WEBP, img.ancho, img.alto, "WebP sin pérdidas $desc")
                else Resultado(png, Formato.PNG, img.ancho, img.alto, "PNG $desc")
        return r.takeIf { it.bytes.size <= presupuesto }
    }

    companion object {
        val LADOS_FOTO = intArrayOf(320, 288, 256, 224, 192, 176, 160, 144, 128, 112, 96)
        val LADOS_ULTIMO_RECURSO = intArrayOf(80, 64, 48, 32)
        val LADOS_GRAFICO = intArrayOf(640, 560, 480, 400, 360, 320, 288, 256, 224, 192, 160, 128, 96, 64)
        val CALIDAD_MIN = mapOf(Formato.WEBP to 40, Formato.JPEG to 45)
    }
}
