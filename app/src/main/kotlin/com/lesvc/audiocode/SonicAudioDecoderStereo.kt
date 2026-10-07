package com.lesvc.audiocode

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

object SonicAudioDecoderStereo {

    const val SAMPLE_RATE = 44100
    private val noteFrequencies = listOf(
        261.63, 277.18, 293.66, 311.13, 329.63, 349.23, 369.99, 392.00, 415.30, 440.00, 466.16, 493.88
    )

    fun decodeChannelToText(samplesL: ShortArray, samplesR: ShortArray): Pair<String, String> {
        val textL = decodeChannel(samplesL)
        val textR = decodeChannel(samplesR)
        return Pair(textL, textR)
    }

    fun decodeChannel(samples: ShortArray): String {
        if (samples.isEmpty()) return ""

        val result = StringBuilder()
        val symbolSize = 2205  // ~50ms a 44100 Hz
        var index = 0

        while (index < samples.size) {
            val endIndex = minOf(index + symbolSize, samples.size)
            val frame = samples.copyOfRange(index, endIndex)

            if (frame.isNotEmpty()) {
                val detected = detectSymbol(frame)
                if (detected != null) {
                    result.append(detected)
                }
            }

            index = endIndex + symbolSize
        }

        return result.toString()
    }

    private fun detectSymbol(frame: ShortArray): Char? {
        if (frame.size < 256) return null

        val dominantNote = detectDominantNote(frame)
        val durationMs = estimateDuration(frame)
        val amplitudePeak = detectAmplitude(frame)
        val repeats = detectRepetitions(frame)

        var bestChar: Char? = null
        var bestScore = Double.MAX_VALUE

        for ((char, unit) in SonicDictionaryStereo.symbols) {
            if (unit.note != dominantNote) continue

            val score = abs(unit.durationMsL - durationMs) +
                    abs((unit.amplitudeL - amplitudePeak) * 1000) +
                    abs(unit.repeatsL - repeats) * 10

            if (score < bestScore) {
                bestScore = score
                bestChar = char
            }
        }

        return bestChar
    }

    private fun detectDominantNote(frame: ShortArray): Int {
        val fftBins = performFFT(frame)
        var maxPower = 0.0
        var maxBin = 0

        for (i in fftBins.indices) {
            if (fftBins[i] > maxPower) {
                maxPower = fftBins[i]
                maxBin = i
            }
        }

        val freqFromBin = (maxBin * SAMPLE_RATE.toDouble()) / frame.size
        return frequencyToNote(freqFromBin)
    }

    private fun performFFT(samples: ShortArray): DoubleArray {
        val n = minOf(256, samples.size)
        val bins = DoubleArray(n / 2)

        for (k in 0 until n / 2) {
            var realSum = 0.0
            var imagSum = 0.0

            for (n_val in 0 until n) {
                val angle = -2.0 * PI * k * n_val / n
                val sample = (samples[n_val] / 32768.0)
                realSum += sample * cos(angle)
                imagSum += sample * sin(angle)
            }

            bins[k] = sqrt(realSum * realSum + imagSum * imagSum)
        }

        return bins
    }

    private fun frequencyToNote(frequency: Double): Int {
        var closestNote = 0
        var minDiff = Double.MAX_VALUE

        for (i in 0..11) {
            val diff = abs(noteFrequencies[i] - frequency)
            if (diff < minDiff) {
                minDiff = diff
                closestNote = i
            }
        }

        return closestNote
    }

    private fun estimateDuration(frame: ShortArray): Int {
        val threshold = detectAmplitude(frame) * 0.2
        var endSample = frame.size - 1

        for (i in frame.size - 1 downTo 0) {
            if (abs(frame[i] / 32768.0) > threshold) {
                endSample = i
                break
            }
        }

        return (endSample * 1000) / SAMPLE_RATE
    }

    private fun detectAmplitude(frame: ShortArray): Float {
        var maxAmp = 0
        for (sample in frame) {
            val amp = abs(sample)
            if (amp > maxAmp) maxAmp = amp
        }
        return maxAmp / 32768f
    }

    private fun detectRepetitions(frame: ShortArray): Int {
        val threshold = detectAmplitude(frame) * 0.7
        var peakCount = 0
        var inPeak = false

        for (sample in frame) {
            val amp = abs(sample / 32768.0)
            if (amp > threshold && !inPeak) {
                peakCount++
                inPeak = true
            } else if (amp < threshold) {
                inPeak = false
            }
        }

        return maxOf(1, minOf(5, (peakCount + 1) / 2))
    }
}
