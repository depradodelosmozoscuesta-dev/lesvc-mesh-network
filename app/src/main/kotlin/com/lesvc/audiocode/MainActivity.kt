package com.lesvc.audiocode

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var creditSystem: CreditSystem
    private lateinit var textInput: EditText
    private lateinit var textOutputL: TextView
    private lateinit var textOutputR: TextView
    private lateinit var statusText: TextView
    private lateinit var balanceText: TextView
    private lateinit var btnEncode: Button
    private lateinit var btnDecode: Button
    private lateinit var btnClear: Button
    private lateinit var btnCredits: Button
    private lateinit var btnDaily: Button
    
    private var audioRecorder: AudioRecorderUtil? = null
    private var audioPlayer: AudioPlayerUtil? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        creditSystem = CreditSystem(this)
        initUI()
        requestAudioPermissions()
        updateBalance()
    }

    private fun initUI() {
        textInput = findViewById(R.id.textInput)
        textOutputL = findViewById(R.id.textOutputL)
        textOutputR = findViewById(R.id.textOutputR)
        statusText = findViewById(R.id.statusText)
        balanceText = findViewById(R.id.balanceText)
        btnEncode = findViewById(R.id.btnEncode)
        btnDecode = findViewById(R.id.btnDecode)
        btnClear = findViewById(R.id.btnClear)
        btnCredits = findViewById(R.id.btnCredits)
        btnDaily = findViewById(R.id.btnDaily)

        btnEncode.setOnClickListener { onEncodeClick() }
        btnDecode.setOnClickListener { onDecodeClick() }
        btnClear.setOnClickListener { onClearClick() }
        btnCredits.setOnClickListener { showCreditsMenu() }
        btnDaily.setOnClickListener { claimDailyBonus() }
        
        audioRecorder = AudioRecorderUtil(this)
        audioPlayer = AudioPlayerUtil()
    }

    private fun onEncodeClick() {
        val text = textInput.text.toString().uppercase()
        
        if (text.isEmpty()) {
            statusText.text = "❌ Ingresa texto"
            return
        }

        if (!creditSystem.canEncode()) {
            statusText.text = "❌ Créditos insuficientes (Necesitas ${CreditSystem.COST_ENCODE})"
            return
        }

        try {
            val (samplesL, samplesR) = SonicAudioEncoderStereo.encodeTextStereo(text)
            
            if (creditSystem.consumeEncode()) {
                audioPlayer?.playStereo(samplesL, samplesR)
                statusText.text = "✅ Audio reproducido"
                updateBalance()
            }
        } catch (e: Exception) {
            statusText.text = "❌ Error: ${e.message}"
        }
    }

    private fun onDecodeClick() {
        if (!creditSystem.canDecode()) {
            statusText.text = "❌ Créditos insuficientes (Necesitas ${CreditSystem.COST_DECODE})"
            return
        }

        statusText.text = "⏺ Grabando... (10s)"
        
        audioRecorder?.startRecording(10000) { samplesL, samplesR ->
            try {
                val (textL, textR) = SonicAudioDecoderStereo.decodeChannelToText(samplesL, samplesR)
                
                if (creditSystem.consumeDecode()) {
                    textOutputL.text = "L: $textL"
                    textOutputR.text = "R: $textR"
                    statusText.text = "✅ Decodificado"
                    updateBalance()
                }
            } catch (e: Exception) {
                statusText.text = "❌ Error: ${e.message}"
            }
        }
    }

    private fun onClearClick() {
        textInput.text.clear()
        textOutputL.text = "L: ---"
        textOutputR.text = "R: ---"
        statusText.text = "Limpiado"
    }

    private fun showCreditsMenu() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("💳 Sistema de Créditos")
        
        val stats = creditSystem.getStats()
        val message = """
            📊 TUS ESTADÍSTICAS
            ━━━━━━━━━━━━━━━━━━━━━━
            💰 Balance: ${stats.currentBalance} créditos
            ✨ Total ganado: ${stats.totalEarned}
            🔥 Total gastado: ${stats.totalSpent}
            📝 Codificaciones: ${stats.totalEncodings}
            🎧 Decodificaciones: ${stats.totalDecodings}
            
            💼 PAQUETES DISPONIBLES
            ━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent()
        
        builder.setMessage(message)
        
        val packages = creditSystem.getPricingPackages()
        val packageNames = packages.map { 
            "${it.credits} créditos + ${it.bonus} BONUS - \$${String.format("%.2f", it.price)}" 
        }.toTypedArray()
        
        builder.setItems(packageNames) { _, which ->
            creditSystem.purchasePackage(packages[which].id)
            updateBalance()
            Toast.makeText(this, "✅ Compra simulada", Toast.LENGTH_SHORT).show()
        }
        
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun claimDailyBonus() {
        if (creditSystem.claimDailyBonus()) {
            statusText.text = "🎁 ¡Recibiste ${CreditSystem.BONUS_DAILY} créditos!"
            updateBalance()
        } else {
            val timeLeft = creditSystem.getTimeToNextBonus()
            val hours = timeLeft / (60 * 60 * 1000)
            val minutes = (timeLeft % (60 * 60 * 1000)) / (60 * 1000)
            statusText.text = "⏳ Próximo bonus en ${hours}h ${minutes}m"
        }
    }

    private fun updateBalance() {
        balanceText.text = "💰 ${creditSystem.getBalance()} créditos"
    }

    private fun requestAudioPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        
        if (!hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions, 1)
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty()) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                statusText.text = "❌ Permiso de audio requerido"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder?.release()
        audioPlayer?.release()
    }
}
