package com.lesvc.mesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.lesvc.mesh.archivo.EnviosRecientes
import com.lesvc.mesh.archivo.Presupuesto
import com.lesvc.mesh.archivo.ProtocoloArchivo
import com.lesvc.mesh.archivo.ReceptorArchivos
import com.lesvc.mesh.audio.Duracion
import com.lesvc.mesh.audio.Emisor
import com.lesvc.mesh.audio.GGWave
import com.lesvc.mesh.audio.Protocolo
import com.lesvc.mesh.audio.Receptor
import com.lesvc.mesh.audio.Troceador
import com.lesvc.mesh.imagen.Formato
import com.lesvc.mesh.imagen.ImagenAndroid
import com.lesvc.mesh.texto.CodecMensaje
import com.lesvc.mesh.texto.Compresores
import java.io.File
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
    private lateinit var historial: LinearLayout
    private lateinit var sinMensajes: TextView
    private lateinit var compatible: SwitchCompat
    private lateinit var tarjetaTransferencia: View
    private lateinit var tituloTransferencia: TextView
    private lateinit var progresoTransferencia: ProgressBar
    private lateinit var faltanTransferencia: TextView
    private lateinit var tarjetaReenvio: View
    private lateinit var tituloReenvio: TextView
    private lateinit var listaReenvio: EditText

    /** Último estado de una imagen que estamos recibiendo (copiado del hilo de captura). */
    private class EstadoRx(val id: Int, val total: Int, val recibidas: Int, val faltan: List<Int>, val faltaCabecera: Boolean)
    private var estadoRx: EstadoRx? = null
    private var envioImagen: ProtocoloArchivo.Envio? = null

    private val elegirImagen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) r.data?.let { enviarImagen(it) }
    }

    private val hiloEnvio = Executors.newSingleThreadExecutor()
    private val hiloGuardar = Executors.newSingleThreadExecutor()
    private var emisor: Emisor? = null
    private lateinit var receptor: Receptor
    @Volatile private var enviando = false
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
        sinMensajes = findViewById(R.id.sinMensajes)
        compatible = findViewById(R.id.compatible)
        tarjetaTransferencia = findViewById(R.id.tarjetaTransferencia)
        tituloTransferencia = findViewById(R.id.tituloTransferencia)
        progresoTransferencia = findViewById(R.id.progresoTransferencia)
        faltanTransferencia = findViewById(R.id.faltanTransferencia)
        tarjetaReenvio = findViewById(R.id.tarjetaReenvio)
        tituloReenvio = findViewById(R.id.tituloReenvio)
        listaReenvio = findViewById(R.id.listaReenvio)

        val app = applicationContext
        receptor = Receptor(app, this) { Compresores.obtener(app) }
        // El diccionario de compresión se prepara en segundo plano (≈1–3 s en un móvil).
        Thread({ Compresores.obtener(app); runOnUiThread { actualizarContador() } }, "lesvc-dic").start()

        compatible.isChecked = prefs.getBoolean("compatible", false)
        compatible.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("compatible", v).apply(); actualizarContador() }
        findViewById<Button>(R.id.botonImagen).setOnClickListener {
            if (enviando) return@setOnClickListener
            elegirImagen.launch(Intent(this, EnviarImagenActivity::class.java).putExtra(EnviarImagenActivity.EXTRA_PROTOCOLO, protocolo.ordinal))
        }
        findViewById<Button>(R.id.botonPedirFaltan).setOnClickListener { pedirFaltan() }
        findViewById<Button>(R.id.botonReenviar).setOnClickListener {
            val lista = ProtocoloArchivo.leerLista(listaReenvio.text.toString())
            val e = envioImagen
            if (e == null || lista.isEmpty()) { estado.text = "Escribe qué partes faltan, p. ej. 3, 6-8"; return@setOnClickListener }
            reenviar(e, lista.filter { it < e.cabecera.partes }, "a mano")
        }

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
            historial.removeAllViews(); historial.addView(sinMensajes)
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

    private fun compresorSiListo() = if (Compresores.listo) Compresores.obtener(applicationContext) else null

    private fun actualizarContador() {
        val t = textoLimpio()
        val caracteres = t.codePointCount(0, t.length)
        val bytes = Troceador.bytesUtf8(t)
        if (t.isEmpty()) {
            contador.text = "0 caracteres · 0/${Troceador.MAX_BYTES} bytes"
            contador.setTextColor(ContextCompat.getColor(this, R.color.texto_suave))
            botonEnviar.isEnabled = enviando
            return
        }
        val p = try { CodecMensaje.preparar(t, protocolo, compresorSiListo(), compatible.isChecked) } catch (e: IllegalArgumentException) { null }
        if (p == null) {
            contador.text = "$caracteres caracteres · $bytes bytes · DEMASIADO LARGO (máx. ${Troceador.MAX_PARTES} partes)"
            contador.setTextColor(ContextCompat.getColor(this, R.color.detener))
            botonEnviar.isEnabled = enviando
            return
        }
        val partes = p.cargas.size
        val base = "$caracteres caracteres · " + when {
            p.comprimido -> "Comprimido: ${p.bytesOriginal} → ${p.bytesEnviados} bytes (−${Math.round(100.0 * (p.bytesOriginal - p.bytesEnviados) / p.bytesOriginal)} %) · ahorra ≈${"%.1f".format(p.segundosSinComprimir - p.segundos)} s"
            partes == 1 -> "$bytes/${Troceador.MAX_BYTES} bytes"
            else -> "$bytes bytes"
        }
        val trozos = if (partes > 1) " · $partes partes" else ""
        val aviso = if (p.comprimido && p.textoQueLlegara != t) "\nOjo: la dirección llegará en minúsculas: ${p.textoQueLlegara}" else ""
        val cargando = if (!compatible.isChecked && !Compresores.listo) " · (preparando compresión…)" else ""
        contador.text = "$base$trozos · ≈${Duracion.formatear(p.segundos)}$cargando$aviso"
        contador.setTextColor(ContextCompat.getColor(this, if (partes > 1) R.color.aviso else R.color.texto_suave))
        botonEnviar.isEnabled = enviando || partes <= Troceador.MAX_PARTES
    }

    private fun actualizarEtiquetaVolumen() {
        etiquetaVolumen.text = "Volumen de emisión: $volumenActual %  (sube también el volumen multimedia del móvil)"
    }

    private fun enviar() {
        val texto = textoLimpio()
        if (texto.isEmpty()) { estado.text = "Escribe un mensaje primero."; return }
        val prep = try { CodecMensaje.preparar(texto, protocolo, compresorSiListo(), compatible.isChecked) } catch (e: IllegalArgumentException) {
            estado.text = e.message; return
        }
        val p = protocolo
        emitir(prep.cargas, { parte, total -> if (total == 1) "Emitiendo (${p.etiqueta})…" else "Emitiendo parte $parte de $total (${p.etiqueta})…" }) { ok ->
            if (ok) {
                estado.text = "Mensaje enviado" + if (prep.cargas.size > 1) " (${prep.cargas.size} partes)." else "."
                val det = p.etiqueta + if (prep.comprimido) " · comprimido ${prep.bytesOriginal}→${prep.bytesEnviados} B" else ""
                anotar("Enviado", prep.textoQueLlegara, ContextCompat.getColor(this, R.color.enviar), det)
            }
        }
    }

    /**
     * Emite cargas en el hilo de envío (silenciando el micro salvo «oírme»).
     * [alTerminar] se llama en el hilo principal con true si se emitió entero.
     */
    private fun emitir(cargas: List<ByteArray>, texto: (Int, Int) -> String, alTerminar: (Boolean) -> Unit) {
        if (enviando) { estado.text = "Espera a que termine el envío actual."; return }
        val p = protocolo
        val vol = volumenActual
        enviando = true
        botonEnviar.setText(R.string.detener_envio)
        botonEnviar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.detener)
        botonEnviar.isEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        receptor.silenciado = !oirme.isChecked
        hiloEnvio.execute {
            var ok = false
            var error: String? = null
            try {
                val e = emisor ?: Emisor().also { emisor = it }
                ok = e.emitir(cargas, p, vol) { parte, total -> runOnUiThread { estado.text = texto(parte, total) } }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            Thread.sleep(300) // deja pasar el eco antes de volver a escuchar
            receptor.silenciado = false
            runOnUiThread {
                enviando = false
                botonEnviar.setText(R.string.enviar)
                botonEnviar.backgroundTintList = ContextCompat.getColorStateList(this, R.color.enviar)
                if (!receptor.escuchando) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                when {
                    error != null -> estado.text = "Error al emitir: $error"
                    !ok -> estado.text = "Envío cancelado."
                }
                alTerminar(ok && error == null)
                actualizarContador()
            }
        }
    }

    // ---------- IMÁGENES: ENVÍO ----------

    private fun enviarImagen(datos: Intent) {
        val ruta = datos.getStringExtra(EnviarImagenActivity.EXTRA_RUTA) ?: return
        val formato = Formato.de(datos.getIntExtra(EnviarImagenActivity.EXTRA_FORMATO, 0)) ?: return
        val bytes = try { File(ruta).readBytes() } catch (e: Exception) { estado.text = "No se pudo leer la imagen preparada."; return }
        val rep = datos.getBooleanExtra(EnviarImagenActivity.EXTRA_REPETIR, false)
        val envio = ProtocoloArchivo.preparar(bytes, formato, datos.getIntExtra(EnviarImagenActivity.EXTRA_ANCHO, 0), datos.getIntExtra(EnviarImagenActivity.EXTRA_ALTO, 0))
        EnviosRecientes.guardar(envio)
        envioImagen = envio
        val tramas = envio.tramas(repetir = rep)
        val p = protocolo
        val total = Duracion.segundosEnvio(tramas.map { it.size }, p)
        val inicio = System.currentTimeMillis()
        emitir(tramas, { parte, n ->
            val quedan = maxOf(0.0, total - (System.currentTimeMillis() - inicio) / 1000.0)
            "Enviando imagen: trama $parte de $n · quedan ≈${Duracion.formatear(quedan)}"
        }) { ok ->
            if (!ok) return@emitir
            val det = "${datos.getStringExtra(EnviarImagenActivity.EXTRA_DETALLE) ?: ""} · ${bytes.size} B · ${envio.cabecera.partes} partes · ${p.etiqueta}"
            anotarImagen("Imagen enviada", bytes, ContextCompat.getColor(this, R.color.enviar), det, null)
            tarjetaReenvio.visibility = View.VISIBLE
            tituloReenvio.text = "Imagen enviada (${envio.cabecera.partes} partes, id ${"%04X".format(envio.cabecera.id)}). " +
                "Si el otro móvil pulsa «Pedir las que faltan», este móvil lo oirá y reenviará esas partes solo (déjalo escuchando). " +
                "También puedes escribir aquí las partes que te digan:"
            estado.text = "Imagen enviada. Escuchando por si piden partes que falten…"
            if (!receptor.escuchando && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) escuchar()
        }
    }

    private fun reenviar(e: ProtocoloArchivo.Envio, partes: List<Int>, motivo: String) {
        if (partes.isEmpty()) return
        emitir(e.tramas(partes), { i, n -> "Reenviando ${partes.size} parte(s) ($motivo): trama $i de $n…" }) { ok ->
            if (ok) estado.text = "Reenviadas las partes ${ProtocoloArchivo.escribirLista(partes)}."
        }
    }

    // ---------- IMÁGENES: RECEPCIÓN ----------

    override fun archivo(evento: ReceptorArchivos.Evento) {
        // Se ejecuta en el hilo de captura: copiamos el estado antes de pasar al hilo principal.
        when (evento) {
            is ReceptorArchivos.Evento.Progreso -> {
                val t = evento.t
                val st = EstadoRx(t.id, t.total, t.recibidas, t.faltan, t.faltaCabecera)
                runOnUiThread { mostrarTransferencia(st, null) }
            }
            is ReceptorArchivos.Evento.CrcIncorrecto -> {
                val t = evento.t
                val st = EstadoRx(t.id, t.total, 0, t.faltan, t.faltaCabecera)
                runOnUiThread { mostrarTransferencia(st, "La imagen llegó dañada (no cuadra el CRC). Pulsa «Pedir las que faltan» para pedirla entera.") }
            }
            is ReceptorArchivos.Evento.Completo -> {
                val c = evento.t.cabecera!!
                val datos = evento.datos
                runOnUiThread { imagenRecibida(c, datos) }
            }
            is ReceptorArchivos.Evento.Peticion -> {
                val pet = evento.p
                runOnUiThread {
                    val e = EnviosRecientes.buscar(pet.id)
                    if (e == null) { estado.text = "Alguien pide partes de una imagen que no envió este móvil (id ${"%04X".format(pet.id)})."; return@runOnUiThread }
                    val partes = pet.indices.filter { it < e.cabecera.partes }
                    estado.text = "El otro móvil pide ${partes.size} parte(s): ${ProtocoloArchivo.escribirLista(partes)}. Reenviando…"
                    if (enviando) estado.text = "Piden partes, pero ya estoy emitiendo. Pulsa «Reenviar» luego." else reenviar(e, partes, "pedidas por sonido")
                    listaReenvio.setText(ProtocoloArchivo.escribirLista(partes))
                }
            }
            ReceptorArchivos.Evento.Ignorada -> {}
        }
    }

    private fun mostrarTransferencia(st: EstadoRx, aviso: String?) {
        estadoRx = st
        tarjetaTransferencia.visibility = View.VISIBLE
        val total = maxOf(st.total, 1)
        tituloTransferencia.text = "Recibiendo imagen ${st.recibidas}/${st.total}"
        progresoTransferencia.max = total; progresoTransferencia.progress = st.recibidas
        faltanTransferencia.text = aviso ?: buildString {
            if (st.faltan.isEmpty()) append("Todas las partes recibidas") else append("Faltan (${st.faltan.size}): ${ProtocoloArchivo.escribirLista(st.faltan)}")
            if (st.faltaCabecera) append("\nAún no ha llegado la cabecera (llega al principio y al final).")
        }
        estado.text = "Recibiendo imagen: ${st.recibidas} de ${st.total} partes…"
    }

    private fun pedirFaltan() {
        val st = estadoRx ?: return
        val faltan = if (st.faltan.isEmpty() && st.faltaCabecera) emptyList() else st.faltan
        val tramas = ProtocoloArchivo.peticion(st.id, faltan, st.faltaCabecera)
        emitir(tramas, { _, _ -> "Pidiendo por sonido las partes ${ProtocoloArchivo.escribirLista(faltan)}…" }) { ok ->
            if (ok) estado.text = "Petición enviada. Sigue escuchando: el otro móvil reenviará esas partes."
        }
    }

    private fun imagenRecibida(c: ProtocoloArchivo.Cabecera, datos: ByteArray) {
        tarjetaTransferencia.visibility = View.GONE
        estadoRx = null
        estado.text = "Imagen recibida (${datos.size} bytes, CRC correcto). Guardando…"
        val nombre = "LESVC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        hiloGuardar.execute {
            val (donde, uri) = try { ImagenAndroid.guardar(applicationContext, datos, c.formato, nombre) } catch (e: Exception) { Pair("no se pudo guardar: ${e.message}", null) }
            runOnUiThread {
                anotarImagen("Imagen recibida", datos, ContextCompat.getColor(this, R.color.escuchar), "${c.ancho}×${c.alto} px · ${datos.size} B · ${c.partes} partes · guardada en $donde", uri)
                estado.text = "Imagen recibida y guardada en $donde"
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

    private fun cabeceraHistorial(tipo: String, color: Int, detalle: String?): TextView = TextView(this).apply {
        text = "[${hora.format(Date())}] $tipo" + (detalle?.let { " · $it" } ?: "")
        setTextColor(color); setTypeface(typeface, Typeface.BOLD); textSize = 14f
    }

    private fun anadirAlHistorial(vararg vistas: View) {
        if (sinMensajes.parent != null) historial.removeView(sinMensajes)
        val bloque = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 24) }
        vistas.forEach { bloque.addView(it) }
        historial.addView(bloque, 0) // lo más reciente arriba
    }

    private fun anotar(tipo: String, texto: String, color: Int, detalle: String?) {
        val cuerpo = TextView(this).apply {
            text = texto; textSize = 16f; setTextColor(ContextCompat.getColor(context, R.color.texto)); setTextIsSelectable(true)
        }
        anadirAlHistorial(cabeceraHistorial(tipo, color, detalle), cuerpo)
    }

    private fun anotarImagen(tipo: String, datos: ByteArray, color: Int, detalle: String, uri: Uri?) {
        val bmp = ImagenAndroid.decodificar(datos)
        val vista = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            if (bmp != null) {
                val f = maxOf(1, 480 / maxOf(bmp.width, bmp.height))
                setImageDrawable(BitmapDrawable(resources, Bitmap.createScaledBitmap(bmp, bmp.width * f, bmp.height * f, false)).apply { isFilterBitmap = false })
            }
            contentDescription = tipo
            if (uri != null) setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) {}
            }
        }
        anadirAlHistorial(cabeceraHistorial(tipo, color, detalle), vista)
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
            appendLine("Mensajes de texto e imágenes por sonido, sin internet.")
            appendLine()
            appendLine("Motor de audio: ggwave ${GGWave.GGWAVE_VERSION}")
            appendLine("https://github.com/ggerganov/ggwave")
            appendLine("Copyright (c) 2020 Georgi Gerganov · Licencia MIT")
            appendLine()
            appendLine("Incluye reed-solomon, Copyright (c) 2015 Mike Lubinets · Licencia MIT")
            appendLine()
            appendLine("Los mensajes largos y las imágenes se dividen en partes de hasta 140 bytes y se vuelven a unir al recibirlos (las imágenes con comprobación CRC32).")
            appendLine()
            appendLine("Compresión de texto (diccionario v1): modelo de contexto PPM entrenado con novelas en dominio público de Project Gutenberg (Niebla de Unamuno, Marianela de Galdós y Los cuatro jinetes del Apocalipsis de Blasco Ibáñez), un corpus de chat escrito para el proyecto y un diccionario conversacional de 7.000 palabras y frases derivado de esos textos. " +
                (Compresores.fallo?.let { "No se pudo cargar: $it" } ?: if (Compresores.listo) "Cargada en ${Compresores.msCarga} ms." else "Cargando…"))
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
    }
}
