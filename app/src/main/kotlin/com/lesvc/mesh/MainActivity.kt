package com.lesvc.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lesvc.mesh.audio.Emisor
import com.lesvc.mesh.audio.GGWave
import com.lesvc.mesh.audio.Protocolo
import com.lesvc.mesh.audio.Receptor
import com.lesvc.mesh.audio.Troceador
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Receptor.Oyente {

    private lateinit var textoMensaje: EditText
    private lateinit var contador: TextView
    private lateinit var selector: Spinner
    private lateinit var avisoUltra: TextView
    private lateinit var etiquetaVolumen: TextView
    private lateinit var volumen: SeekBar
    private lateinit var botonEnviar: Button
    private lateinit var botonEscuchar: Button
    private lateinit var nivelMicro: ProgressBar
    private lateinit var oirme: SwitchCompat
    private lateinit var estado: TextView
    private lateinit var historial: TextView

    private val hiloEnvio = Executors.newSingleThreadExecutor()
    private var emisor: Emisor? = null
    private lateinit var receptor: Receptor
    @Volatile private var enviando = false
    private val registro = SpannableStringBuilder()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale("es", "ES"))
    private val prefs by lazy { getSharedPreferences("ajustes", Context.MODE_PRIVATE) }

    private val protocolo get() = Protocolo.values()[selector.selectedItemPosition]
    private val volumenActual get() = volumen.progress + 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textoMensaje = findViewById(R.id.textoMensaje)
        contador = findViewById(R.id.contador)
        selector = findViewById(R.id.selectorProtocolo)
        avisoUltra = findViewById(R.id.avisoUltra)
        etiquetaVolumen = findViewById(R.id.etiquetaVolumen)
        volumen = findViewById(R.id.volumen)
        botonEnviar = findViewById(R.id.botonEnviar)
        botonEscuchar = findViewById(R.id.botonEscuchar)
        nivelMicro = findViewById(R.id.nivelMicro)
        oirme = findViewById(R.id.oirme)
        estado = findViewById(R.id.estado)
        historial = findViewById(R.id.historial)

        receptor = Receptor(applicationContext, this)

        selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Protocolo.values().map { it.etiqueta })
        selector.setSelection(prefs.getInt("protocolo", 0).coerceIn(0, Protocolo.values().lastIndex))
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("protocolo", pos).apply()
                avisoUltra.visibility = if (protocolo.ultrasonido) View.VISIBLE else View.GONE
                if (protocolo.ultrasonido) avisoUltra.text = getString(R.string.aviso_ultra) + soporteUltrasonido()
                actualizarContador()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        volumen.progress = prefs.getInt("volumen", 50) - 1
        actualizarEtiquetaVolumen()
        volumen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = actualizarEtiquetaVolumen()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { prefs.edit().putInt("volumen", volumenActual).apply() }
        })

        textoMensaje.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = actualizarContador()
        })
        actualizarContador()

        botonEnviar.setOnClickListener { if (enviando) emisor?.cancelar() else enviar() }
        botonEscuchar.setOnClickListener { if (receptor.escuchando) dejarDeEscuchar() else pedirPermisoYEscuchar() }
        findViewById<Button>(R.id.botonLimpiar).setOnClickListener {
            registro.clear(); historial.setText(R.string.sin_mensajes)
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        estado.text = "Listo. Para recibir, pulsa «Empezar a escuchar»."
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.principal, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.accion_acerca) { mostrarAcercaDe(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onStop() {
        super.onStop()
        // Android no permite usar el micro en segundo plano sin servicio: paramos limpio.
        if (receptor.escuchando) {
            dejarDeEscuchar()
            estado.text = "Escucha detenida al salir de la app."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        emisor?.cancelar()
        hiloEnvio.execute { emisor?.cerrar(); emisor = null }
        hiloEnvio.shutdown()
    }

    // ---------- ENVÍO ----------

    private fun textoLimpio(): String =
        textoMensaje.text.toString().filter { it == '\n' || it >= ' ' }.trim()

    private fun actualizarContador() {
        val t = textoLimpio()
        val caracteres = t.codePointCount(0, t.length)
        val bytes = Troceador.bytesUtf8(t)
        val partes = Troceador.partesNecesarias(t)
        val seg = segundosEstimados(t)
        contador.text = when {
            t.isEmpty() -> "0 caracteres · 0/${Troceador.MAX_BYTES} bytes"
            partes > Troceador.MAX_PARTES -> "$caracteres caracteres · $bytes bytes · DEMASIADO LARGO (máx. ${Troceador.MAX_PARTES} partes)"
            partes == 1 -> "$caracteres caracteres · $bytes/${Troceador.MAX_BYTES} bytes · ≈$seg s"
            else -> "$caracteres caracteres · $bytes bytes · se enviará en $partes partes · ≈$seg s"
        }
        contador.setTextColor(when {
            partes > Troceador.MAX_PARTES -> ContextCompat.getColor(this, R.color.detener)
            partes > 1 -> ContextCompat.getColor(this, R.color.aviso)
            else -> ContextCompat.getColor(this, R.color.texto_suave)
        })
        botonEnviar.isEnabled = enviando || (t.isNotEmpty() && partes <= Troceador.MAX_PARTES)
    }

    /** Estimación (medida con los tests): duración de una carga de n bytes. */
    private fun segundosEstimados(t: String): Int {
        if (t.isEmpty()) return 0
        val cargas = try { Troceador.cargas(t, "00") } catch (e: IllegalArgumentException) { return 0 }
        val (base, porByte) = when (protocolo) {
            Protocolo.AUDIBLE_NORMAL, Protocolo.ULTRA_NORMAL -> DURACION_NORMAL
            Protocolo.AUDIBLE_RAPIDO, Protocolo.ULTRA_RAPIDO -> DURACION_RAPIDO
            else -> DURACION_MUY_RAPIDO
        }
        val total = cargas.sumOf { base + porByte * it.size } + 0.4 * (cargas.size - 1)
        return kotlin.math.ceil(total).toInt()
    }

    private fun actualizarEtiquetaVolumen() {
        etiquetaVolumen.text = "Volumen de emisión: $volumenActual %  (sube también el volumen multimedia del móvil)"
    }

    private fun enviar() {
        val texto = textoLimpio()
        if (texto.isEmpty()) { estado.text = "Escribe un mensaje primero."; return }
        val cargas = try { Troceador.cargas(texto) } catch (e: IllegalArgumentException) {
            estado.text = e.message; return
        }
        val p = protocolo
        val vol = volumenActual
        enviando = true
        botonEnviar.setText(R.string.detener_envio)
        botonEnviar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.detener)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        receptor.silenciado = !oirme.isChecked
        hiloEnvio.execute {
            var ok = false
            var error: String? = null
            try {
                val e = emisor ?: Emisor().also { emisor = it }
                ok = e.emitir(cargas, p, vol) { parte, total ->
                    runOnUiThread {
                        estado.text = if (total == 1) "Emitiendo (${p.etiqueta})…"
                        else "Emitiendo parte $parte de $total (${p.etiqueta})…"
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            Thread.sleep(300) // deja pasar el eco antes de volver a escuchar
            receptor.silenciado = false
            runOnUiThread {
                enviando = false
                botonEnviar.setText(R.string.enviar)
                botonEnviar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.enviar)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                when {
                    error != null -> estado.text = "Error al emitir: $error"
                    ok -> {
                        estado.text = "Mensaje enviado" + if (cargas.size > 1) " (${cargas.size} partes)." else "."
                        anotar("Enviado", texto, ContextCompat.getColor(this, R.color.enviar), p.etiqueta)
                    }
                    else -> estado.text = "Envío cancelado."
                }
                actualizarContador()
            }
        }
    }

    // ---------- RECEPCIÓN ----------

    private fun pedirPermisoYEscuchar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            escuchar()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISO_MICRO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISO_MICRO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) escuchar()
        else estado.setText(R.string.permiso_denegado)
    }

    private fun escuchar() {
        if (!receptor.iniciar()) return
        botonEscuchar.setText(R.string.dejar_escuchar)
        botonEscuchar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.detener)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        estado.text = "Escuchando (${receptor.descripcion}). Acerca el móvil que emite."
    }

    private fun dejarDeEscuchar() {
        receptor.detener()
        botonEscuchar.setText(R.string.escuchar)
        botonEscuchar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.escuchar)
        nivelMicro.progress = 0
        if (!enviando) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        estado.text = "Escucha detenida."
    }

    override fun mensaje(texto: String, partes: Int) = runOnUiThread {
        estado.text = "Mensaje recibido" + if (partes > 1) " ($partes partes)." else "."
        anotar("Recibido", texto, ContextCompat.getColor(this, R.color.escuchar), null)
    }

    override fun parcial(recibidas: Int, total: Int) = runOnUiThread {
        estado.text = "Recibiendo mensaje largo: $recibidas de $total partes…"
    }

    override fun fallo() = runOnUiThread {
        estado.text = "Se oyó una transmisión pero no se pudo descifrar. Acercad los móviles o subid el volumen."
    }

    override fun nivel(nivel0a1: Float) = runOnUiThread { nivelMicro.progress = (nivel0a1 * 100).toInt() }

    override fun error(texto: String) = runOnUiThread {
        estado.text = "Micrófono: $texto"
        if (!receptor.escuchando) {
            botonEscuchar.setText(R.string.escuchar)
            botonEscuchar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.escuchar)
        }
    }

    private fun anotar(tipo: String, texto: String, color: Int, detalle: String?) {
        if (registro.isEmpty()) historial.text = ""
        val linea = SpannableStringBuilder()
        val cab = "[${hora.format(Date())}] $tipo" + (detalle?.let { " · $it" } ?: "") + "\n"
        linea.append(cab)
        linea.setSpan(ForegroundColorSpan(color), 0, cab.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        linea.setSpan(StyleSpan(Typeface.BOLD), 0, cab.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        linea.append(texto).append("\n\n")
        registro.insert(0, linea) // lo más reciente arriba
        historial.text = registro
    }

    // ---------- ACERCA DE ----------

    private fun soporteUltrasonido(): String {
        if (Build.VERSION.SDK_INT < 23) return ""
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun sn(p: String) = when (am.getProperty(p)) { "true" -> "sí"; "false" -> "no"; else -> "no lo indica" }
        return "\nEste móvil declara · micro: " + sn(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND) +
            " · altavoz: " + sn(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND)
    }

    private fun mostrarAcercaDe() {
        fun asset(n: String) = try { assets.open(n).bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (e: Exception) { "" }
        val texto = buildString {
            appendLine("${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Mensajes de texto por sonido, sin internet.")
            appendLine()
            appendLine("Motor de audio: ggwave ${GGWave.GGWAVE_VERSION}")
            appendLine("https://github.com/ggerganov/ggwave")
            appendLine("Copyright (c) 2020 Georgi Gerganov · Licencia MIT")
            appendLine()
            appendLine("Incluye reed-solomon, Copyright (c) 2015 Mike Lubinets · Licencia MIT")
            appendLine()
            appendLine("Los mensajes largos se dividen en partes de hasta 140 bytes y se vuelven a unir al recibirlos.")
            appendLine()
            appendLine("──────── Licencia de ggwave ────────")
            appendLine(asset("licenses/ggwave_LICENSE.txt"))
            appendLine("──────── Licencia de reed-solomon ────────")
            appendLine(asset("licenses/reed-solomon_LICENSE.txt"))
        }
        val tv = TextView(this).apply {
            text = texto; textSize = 13f; setTextColor(Color.parseColor("#1B1F24"))
            setPadding(48, 32, 48, 32); setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.acerca_de)
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Cerrar", null)
            .show()
    }

    companion object {
        private const val PERMISO_MICRO = 1001
        // (segundos fijos por carga, segundos por byte), medidos con ggwave_encode a 48 kHz:
        // normal 140 B = 13,6 s · rápido 140 B = 9,3 s · muy rápido 140 B = 5,0 s
        private val DURACION_NORMAL = 0.95 to 0.09
        private val DURACION_RAPIDO = 0.85 to 0.06
        private val DURACION_MUY_RAPIDO = 0.77 to 0.03
    }
}
