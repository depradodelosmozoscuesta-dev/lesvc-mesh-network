package com.lesvc.audiocode

import kotlin.math.PI
import kotlin.math.sin

object SonicAudioEncoderStereo {

    const val SAMPLE_RATE = 44100
    private val noteFrequencies = listOf(
        261.63, 277.18, 293.66, 311.13, 329.63, 349.23, 369.99, 392.00, 415.30, 440.00, 466.16, 493.88
    )

    fun encodeTextStereo(text: String): Pair<ShortArray, ShortArray> {
        val channelL = mutableListOf<Short>()
        val channelR = mutableListOf<Short>()

        for (char in text) {
            val unit = SonicDictionaryStereo.symbols[char]
            if (unit != null) {
                val samplesL = generateTone(unit.note, unit.durationMsL, unit.amplitudeL, unit.repeatsL)
                val samplesR = generateTone(unit.note, unit.durationMsR, unit.amplitudeR, unit.repeatsR)

                channelL.addAll(samplesL.asIterable())
                channelR.addAll(samplesR.asIterable())
            }
        }

        return Pair(channelL.toShortArray(), channelR.toShortArray())
    }

    private fun generateTone(noteIndex: Int, durationMs: Int, amplitude: Float, repeats: Int): ShortArray {
        val frequency = noteFrequencies[noteIndex % noteFrequencies.size]
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val samples = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2.0 * PI * frequency * t
            val modulatedAmplitude = amplitude * (1 - (i.toDouble() / numSamples) * 0.3) // Fade out
            val sample = (modulatedAmplitude * 32767 * sin(phase)).toInt().toShort()
            samples[i] = sample
        }

        return samples
    }
}
