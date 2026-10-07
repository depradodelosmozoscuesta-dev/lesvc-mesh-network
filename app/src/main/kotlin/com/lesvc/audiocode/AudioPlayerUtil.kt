package com.lesvc.audiocode

import android.media.AudioTrack
import android.media.AudioManager
import android.media.AudioFormat

class AudioPlayerUtil {
    
    private var audioTrack: AudioTrack? = null
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    fun playStereo(samplesL: ShortArray, samplesR: ShortArray) {
        // Intercalar muestras L y R
        val maxSize = maxOf(samplesL.size, samplesR.size)
        val combined = ShortArray(maxSize * 2)
        
        for (i in 0 until maxSize) {
            combined[i * 2] = if (i < samplesL.size) samplesL[i] else 0
            combined[i * 2 + 1] = if (i < samplesR.size) samplesR[i] else 0
        }
        
        playAudio(combined)
    }
    
    private fun playAudio(samples: ShortArray) {
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        
        audioTrack?.play()
        audioTrack?.write(samples, 0, samples.size)
    }
    
    fun release() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
