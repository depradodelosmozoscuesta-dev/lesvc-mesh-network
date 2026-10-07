package com.lesvc.mesh.imagen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Codificador con pérdidas del móvil (Bitmap.compress: libwebp y libjpeg del sistema). */
object CodificadorAndroid : CodificadorConPerdidas {
    override val formatos = listOf(Formato.WEBP, Formato.JPEG)

    @Suppress("DEPRECATION")
    override fun codificar(img: ImagenRGB, formato: Formato, calidad: Int): ByteArray {
        val bmp = ImagenAndroid.aBitmap(img)
        val f = when (formato) {
            Formato.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            Formato.JPEG -> Bitmap.CompressFormat.JPEG
            Formato.PNG -> Bitmap.CompressFormat.PNG
        }
        val out = ByteArrayOutputStream()
        // En API < 30, WEBP con calidad 100 sería sin pérdidas: nunca pasamos de 90.
        bmp.compress(f, calidad.coerceIn(0, 95), out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    override fun webpSinPerdidas(img: ImagenRGB): ByteArray? {
        val bmp = ImagenAndroid.aBitmap(img)
        val out = ByteArrayOutputStream()
        // API 30+: WEBP_LOSSLESS; API 29: WEBP con calidad 100 es sin pérdidas; en API < 29 sale con pérdidas
        // (más grande), así que gana el PNG: la vista previa siempre muestra lo que de verdad llegará.
        val ok = if (Build.VERSION.SDK_INT >= 30) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                 else bmp.compress(Bitmap.CompressFormat.WEBP, 100, out)
        bmp.recycle()
        return if (ok) out.toByteArray() else null
    }
}

object ImagenAndroid {

    fun aBitmap(img: ImagenRGB): Bitmap = Bitmap.createBitmap(img.px, img.ancho, img.alto, Bitmap.Config.ARGB_8888)

    fun deBitmap(b: Bitmap): ImagenRGB {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return ImagenRGB(b.width, b.height, px)
    }

    /** Abre una imagen de la galería/cámara reducida (≈ ≤2048 px) y con la rotación EXIF aplicada. */
    fun abrir(ctx: Context, uri: Uri, maxLado: Int = 2048): ImagenRGB {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        require(o.outWidth > 0) { "No es una imagen que el móvil sepa abrir" }
        var muestreo = 1
        while (maxOf(o.outWidth, o.outHeight) / (muestreo * 2) >= maxLado) muestreo *= 2
        val bmp = cr.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestreo; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: throw IllegalArgumentException("No se pudo leer la imagen")
        val giro = try {
            cr.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                }
            }
        } catch (e: Exception) { 0f }
        val final = if (giro == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(giro) }, true).also { bmp.recycle() }
        return deBitmap(final).also { final.recycle() }
    }

    fun decodificar(datos: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(datos, 0, datos.size)

    /**
     * Guarda en Imágenes/LESVC. API 29+: MediaStore sin permisos. API < 29: carpeta de imágenes
     * propia de la app (Android/data/com.lesvc.mesh/files/Pictures/LESVC), que tampoco pide permisos.
     * @return descripción de dónde quedó + Uri para abrirla (si la hay)
     */
    fun guardar(ctx: Context, datos: ByteArray, formato: Formato, nombre: String): Pair<String, Uri?> {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$nombre.${formato.extension}")
                put(MediaStore.Images.Media.MIME_TYPE, formato.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LESVC")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("MediaStore no aceptó la imagen")
            try {
                cr.openOutputStream(uri)!!.use { it.write(datos) }
                cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { cr.delete(uri, null, null); throw e }
            return Pair("Imágenes/LESVC/$nombre.${formato.extension}", uri)
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir, "LESVC").apply { mkdirs() }
        val f = File(dir, "$nombre.${formato.extension}")
        f.writeBytes(datos)
        return Pair(f.absolutePath, null)
    }
}
