package com.lesvc.audiocode

import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import kotlin.concurrent.thread

class AudioRecorderUtil(private val context: Context) {
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    fun startRecording(durationMs: Int, callback: (ShortArray, ShortArray) -> Unit) {
        if (isRecording) return
        
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            audioRecord?.startRecording()
            isRecording = true
            
            thread {
                recordAudio(durationMs, bufferSize, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun recordAudio(durationMs: Int, bufferSize: Int, callback: (ShortArray, ShortArray) -> Unit) {
        val buffer = ShortArray(bufferSize)
        val recordedData = mutableListOf<Short>()
        val endTime = System.currentTimeMillis() + durationMs
        
        while (isRecording && System.currentTimeMillis() < endTime) {
            val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
            if (readSize > 0) {
                recordedData.addAll(buffer.slice(0 until readSize))
            }
        }
        
        stopRecording()
        
        // Dividir en canales L y R
        val samplesL = mutableListOf<Short>()
        val samplesR = mutableListOf<Short>()
        
        for (i in recordedData.indices step 2) {
            samplesL.add(recordedData[i])
            if (i + 1 < recordedData.size) {
                samplesR.add(recordedData[i + 1])
            }
        }
        
        callback(samplesL.toShortArray(), samplesR.toShortArray())
    }
    
    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
    }
    
    fun release() {
        audioRecord?.release()
        audioRecord = null
    }
}
