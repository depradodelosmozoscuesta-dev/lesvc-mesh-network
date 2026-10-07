package com.lesvc.audiocode

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var inputText: EditText
    private lateinit var outputTextL: TextView
    private lateinit var outputTextR: TextView
    private lateinit var statusText: TextView
    private lateinit var playButton: Button
    private lateinit var recordButton: Button
    private lateinit var clearButton: Button

    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputText = findViewById(R.id.inputText)
        outputTextL = findViewById(R.id.outputTextL)
        outputTextR = findViewById(R.id.outputTextR)
        statusText = findViewById(R.id.statusText)
        playButton = findViewById(R.id.playButton)
        recordButton = findViewById(R.id.recordButton)
        clearButton = findViewById(R.id.clearButton)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    1001
                )
            }
        }

        playButton.setOnClickListener { playAudio() }
        recordButton.setOnClickListener { toggleRecording() }
        clearButton.setOnClickListener { clearAll() }
    }

    private fun playAudio() {
        val text = inputText.text.toString()
        if (text.isEmpty()) {
            statusText.text = "Ingresa texto para codificar"
            return
        }

        GlobalScope.launch(Dispatchers.Default) {
            try {
                statusText.post { statusText.text = "Codificando..." }

                val (channelL, channelR) = SonicAudioEncoderStereo.encodeTextStereo(text)
                val combined = combineStereoChannels(channelL, channelR)

                statusText.post { statusText.text = "Reproduciendo: ${text.length} caracteres" }

                playAudioTrack(combined)

                statusText.post { statusText.text = "Completado" }
            } catch (e: Exception) {
                statusText.post { statusText.text = "Error: ${e.message}" }
            }
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        recordButton.text = "Detener grabación"
        statusText.text = "Grabando..."
        outputTextL.text = ""
        outputTextR.text = ""

        GlobalScope.launch(Dispatchers.Default) {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SonicAudioDecoderStereo.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SonicAudioDecoderStereo.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2
                )

                recorder.startRecording()
                val audioBuffer = mutableListOf<Short>()
                val recordingDuration = 10000L
                val startTime = System.currentTimeMillis()

                while (isRecording && (System.currentTimeMillis() - startTime) < recordingDuration) {
                    val buffer = ShortArray(minBufferSize)
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        audioBuffer.addAll(buffer.take(read))
                    }
                }

                recorder.stop()
                recorder.release()

                val samplesL = mutableListOf<Short>()
                val samplesR = mutableListOf<Short>()
                for (i in audioBuffer.indices step 2) {
                    samplesL.add(audioBuffer[i])
                    if (i + 1 < audioBuffer.size) {
                        samplesR.add(audioBuffer[i + 1])
                    }
                }

                val (decodedL, decodedR) = SonicAudioDecoderStereo.decodeChannelToText(
                    samplesL.toShortArray(),
                    samplesR.toShortArray()
                )

                outputTextL.post { outputTextL.text = "L: $decodedL" }
                outputTextR.post { outputTextR.text = "R: $decodedR" }
                statusText.post { statusText.text = "Decodificado exitosamente" }

            } catch (e: Exception) {
                statusText.post { statusText.text = "Error: ${e.message}" }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.text = "Grabar"
        statusText.text = "Grabación detenida"
    }

    private fun clearAll() {
        inputText.text.clear()
        outputTextL.text = ""
        outputTextR.text = ""
        statusText.text = "Limpiar"
    }

    private fun playAudioTrack(samples: ShortArray) {
        val track = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            SonicAudioDecoderStereo.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            samples.size * 2,
            AudioTrack.MODE_STATIC
        )
        track.write(samples, 0, samples.size)
        track.play()
        Thread.sleep((samples.size * 1000L) / SonicAudioDecoderStereo.SAMPLE_RATE)
        track.release()
    }

    private fun combineStereoChannels(channelL: ShortArray, channelR: ShortArray): ShortArray {
        val maxSize = maxOf(channelL.size, channelR.size)
        val combined = ShortArray(maxSize * 2)

        for (i in 0 until maxSize) {
            combined[i * 2] = if (i < channelL.size) channelL[i] else 0
            combined[i * 2 + 1] = if (i < channelR.size) channelR[i] else 0
        }

        return combined
    }
}
