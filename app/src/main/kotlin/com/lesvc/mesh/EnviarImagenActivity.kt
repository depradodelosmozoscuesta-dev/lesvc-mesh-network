package com.lesvc.mesh

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.lesvc.mesh.archivo.Presupuesto
import com.lesvc.mesh.audio.Duracion
import com.lesvc.mesh.audio.Protocolo
import com.lesvc.mesh.imagen.CodificadorAndroid
import com.lesvc.mesh.imagen.CompresorImagen
import com.lesvc.mesh.imagen.ImagenAndroid
import com.lesvc.mesh.imagen.ImagenRGB
import com.lesvc.mesh.imagen.Preset
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Elegir (galería sin permisos / cámara) → elegir tiempo y tipo → vista previa EXACTA
 * (se decodifican los mismos bytes que se enviarán) → devolver a MainActivity para emitir.
 */
class EnviarImagenActivity : AppCompatActivity() {

    private lateinit var opciones: View
    private lateinit var grupoPreset: RadioGroup
    private lateinit var selectorTiempo: Spinner
    private lateinit var gris: SwitchCompat
    private lateinit var repetir: SwitchCompat
    private lateinit var vista: ImageView
    private lateinit var tituloVista: View
    private lateinit var detalle: TextView
    private lateinit var botonEnviar: Button

    private val hilo = Executors.newSingleThreadExecutor()
    private val generacion = AtomicInteger()
    private var original: ImagenRGB? = null
    private var resultado: CompresorImagen.Resultado? = null
    private lateinit var protocolo: Protocolo
    private var uriCamara: Uri? = null

    private val elegirFoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { abrir(it) } }
    private val elegirContenido = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { abrir(it) } }
    private val hacerFoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) uriCamara?.let { abrir(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enviar_imagen)
        title = getString(R.string.titulo_enviar_imagen)
        protocolo = Protocolo.values()[intent.getIntExtra(EXTRA_PROTOCOLO, 2).coerceIn(0, Protocolo.values().lastIndex)]
        opciones = findViewById(R.id.opciones)
        grupoPreset = findViewById(R.id.grupoPreset)
        selectorTiempo = findViewById(R.id.selectorTiempo)
        gris = findViewById(R.id.interruptorGris)
        repetir = findViewById(R.id.interruptorRepetir)
        vista = findViewById(R.id.vistaPrevia)
        tituloVista = findViewById(R.id.tituloVista)
        detalle = findViewById(R.id.detalle)
        botonEnviar = findViewById(R.id.botonEnviarImagen)

        selectorTiempo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, TIEMPOS.map { Duracion.formatear(it.toDouble()) })
        selectorTiempo.setSelection(1)
        selectorTiempo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = recomprimir()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        grupoPreset.setOnCheckedChangeListener { _, _ -> recomprimir() }
        gris.setOnCheckedChangeListener { _, _ -> recomprimir() }
        repetir.setOnCheckedChangeListener { _, _ -> recomprimir() }

        findViewById<Button>(R.id.botonGaleria).setOnClickListener {
            try {
                elegirFoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (e: ActivityNotFoundException) {
                elegirContenido.launch("image/*")
            }
        }
        findViewById<Button>(R.id.botonCamara).setOnClickListener {
            val dir = File(cacheDir, "camara").apply { mkdirs() }
            val f = File(dir, "foto.jpg")
            uriCamara = FileProvider.getUriForFile(this, "$packageName.archivos", f)
            try { hacerFoto.launch(uriCamara) } catch (e: ActivityNotFoundException) { detalle.text = "No hay ninguna app de cámara disponible." }
        }
        botonEnviar.setOnClickListener { devolver() }
        detalle.text = "Modo de transmisión: ${protocolo.etiqueta} (se cambia en la pantalla principal)."
    }

    override fun onDestroy() { super.onDestroy(); hilo.shutdownNow() }

    private fun abrir(uri: Uri) {
        detalle.text = "Abriendo la imagen…"
        botonEnviar.isEnabled = false
        hilo.execute {
            try {
                val img = ImagenAndroid.abrir(this, uri)
                runOnUiThread { original = img; opciones.visibility = View.VISIBLE; recomprimir() }
            } catch (e: Throwable) {
                runOnUiThread { detalle.text = "No se pudo abrir: ${e.message}" }
            }
        }
    }

    private fun recomprimir() {
        val img = original ?: return
        val g = generacion.incrementAndGet()
        val preset = if (grupoPreset.checkedRadioButtonId == R.id.presetGrafico) Preset.GRAFICO else Preset.FOTO
        val segundos = TIEMPOS[selectorTiempo.selectedItemPosition.coerceAtLeast(0)]
        val rep = repetir.isChecked
        val enGris = gris.isChecked
        val presupuesto = Presupuesto.bytesParaSegundos(segundos.toDouble(), protocolo, rep)
        botonEnviar.isEnabled = false
        detalle.text = "Comprimiendo para ${Duracion.formatear(segundos.toDouble())} (≤ $presupuesto bytes)…"
        hilo.execute {
            val r = try { CompresorImagen(CodificadorAndroid).comprimir(img, preset, presupuesto, enGris) } catch (e: Throwable) { null }
            val bmp = r?.let { ImagenAndroid.decodificar(it.bytes) }
            runOnUiThread {
                if (g != generacion.get()) return@runOnUiThread
                resultado = r
                if (r == null || bmp == null) {
                    detalle.text = "No cabe ni en tamaño mínimo en ${Duracion.formatear(segundos.toDouble())}. Elige más tiempo o un modo más rápido."
                    vista.visibility = View.GONE; tituloVista.visibility = View.GONE
                    return@runOnUiThread
                }
                mostrar(bmp)
                val partes = Presupuesto.partes(r.bytes.size)
                val porModo = listOf(Protocolo.AUDIBLE_TURBO, Protocolo.AUDIBLE_MUY_RAPIDO, Protocolo.AUDIBLE_RAPIDO, Protocolo.AUDIBLE_NORMAL).joinToString("\n") { p ->
                    "   · ${p.etiqueta}: ${Duracion.formatear(Presupuesto.segundos(r.bytes.size, p, rep))}" + if (p == protocolo) "  ← modo actual" else ""
                }
                detalle.text = "${r.detalle} · ${r.formato.name}\n" +
                    "${r.bytes.size} bytes · $partes partes${if (rep) " (cada una 2 veces)" else ""} · " +
                    "≈${Duracion.formatear(Presupuesto.segundos(r.bytes.size, protocolo, rep))} en ${protocolo.etiqueta}\n" +
                    "Tiempo estimado según el modo:\n$porModo"
                botonEnviar.isEnabled = true
            }
        }
    }

    /** Amplía sin suavizar para ver los píxeles tal cual llegarán. */
    private fun mostrar(bmp: Bitmap) {
        val factor = maxOf(1, 640 / maxOf(bmp.width, bmp.height))
        val grande = Bitmap.createScaledBitmap(bmp, bmp.width * factor, bmp.height * factor, false)
        vista.setImageDrawable(BitmapDrawable(resources, grande).apply { isFilterBitmap = false })
        vista.visibility = View.VISIBLE; tituloVista.visibility = View.VISIBLE
    }

    private fun devolver() {
        val r = resultado ?: return
        val f = File(cacheDir, "imagen_a_enviar.bin")
        f.writeBytes(r.bytes)
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_RUTA, f.absolutePath)
            .putExtra(EXTRA_FORMATO, r.formato.codigo)
            .putExtra(EXTRA_ANCHO, r.ancho).putExtra(EXTRA_ALTO, r.alto)
            .putExtra(EXTRA_REPETIR, repetir.isChecked)
            .putExtra(EXTRA_DETALLE, r.detalle))
        finish()
    }

    companion object {
        const val EXTRA_PROTOCOLO = "protocolo"
        const val EXTRA_RUTA = "ruta"
        const val EXTRA_FORMATO = "formato"
        const val EXTRA_ANCHO = "ancho"
        const val EXTRA_ALTO = "alto"
        const val EXTRA_REPETIR = "repetir"
        const val EXTRA_DETALLE = "detalle"
        val TIEMPOS = intArrayOf(30, 60, 120, 180)

    }
}
