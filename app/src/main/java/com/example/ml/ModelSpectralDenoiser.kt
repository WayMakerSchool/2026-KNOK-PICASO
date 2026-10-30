package com.example.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** SciPy-compatible label-blind quiet-context Wiener gate for the research model. */
object ModelSpectralDenoiser {
    private const val SIZE = 512
    private const val HOP = 128
    private const val BINS = SIZE / 2 + 1
    private val hann = DoubleArray(SIZE) { .5 - .5 * cos(2 * PI * it / SIZE) }
    private val reversed = IntArray(SIZE) { Integer.reverse(it) ushr 23 }

    fun process(samples: FloatArray, alpha: Double = 1.0): FloatArray {
        require(samples.size >= SIZE && samples.all { it.isFinite() }) { "유효한 모노 음원이 필요합니다." }
        require(alpha.isFinite() && alpha >= 0.0) { "잡음 처리 설정이 잘못되었습니다." }
        if (alpha == 0.0) return samples.copyOf()
        val count = (samples.size + HOP - 1) / HOP + 1
        val real = DoubleArray(SIZE)
        val imag = DoubleArray(SIZE)
        fun spectrum(frame: Int) {
            for (i in 0 until SIZE) {
                val position = frame * HOP + i - SIZE / 2
                real[i] = (if (position in samples.indices) samples[position].toDouble() else 0.0) * hann[i]
                imag[i] = 0.0
            }
            fft(real, imag)
            for (i in 0 until BINS) { real[i] /= 256.0; imag[i] /= 256.0 }
        }
        val energy = DoubleArray(count)
        for (frame in 0 until count) {
            spectrum(frame)
            energy[frame] = (0 until BINS).sumOf { real[it] * real[it] + imag[it] * imag[it] }
        }
        val quietCount = max(1, floor(.2 * count).toInt())
        val order = energy.indices.sortedBy { energy[it] }
        val sorted = energy.sortedArray()
        fun percentile(values: DoubleArray, proportion: Double): Double {
            val at = proportion * (values.size - 1)
            val left = floor(at).toInt()
            val right = minOf(left + 1, values.lastIndex)
            return values[left] * (1 - (at - left)) + values[right] * (at - left)
        }
        val quietEnergy = DoubleArray(quietCount) { energy[order[it]] }.apply { sort() }
        if (percentile(quietEnergy, .5) >= .5 * percentile(sorted, .9)) return samples.copyOf()
        val noise = DoubleArray(BINS)
        for (j in 0 until quietCount) {
            spectrum(order[j])
            for (k in 0 until BINS) noise[k] += (real[k] * real[k] + imag[k] * imag[k]) / quietCount
        }
        val length = (count - 1) * HOP + SIZE
        val output = DoubleArray(length)
        val normalization = DoubleArray(length)
        for (frame in 0 until count) {
            spectrum(frame)
            for (k in 0 until BINS) {
                val power = real[k] * real[k] + imag[k] * imag[k]
                val gain = sqrt(max(.01, 1.0 - alpha * noise[k] / max(power, 1e-30)))
                real[k] *= gain; imag[k] *= gain
            }
            for (k in BINS until SIZE) { real[k] = real[SIZE-k]; imag[k] = -imag[SIZE-k] }
            fft(real, imag, inverse = true)
            for (i in 0 until SIZE) {
                val at = frame * HOP + i
                output[at] += real[i] * 256.0 * hann[i]
                normalization[at] += hann[i] * hann[i]
            }
        }
        return FloatArray(samples.size) { index ->
            val at = index + SIZE / 2
            (output[at] / (if (normalization[at] > 1e-10) normalization[at] else 1.0)).toFloat()
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean = false) {
        if (inverse) for (i in imaginary.indices) imaginary[i] = -imaginary[i]
        for (i in real.indices) {
            val j = reversed[i]
            if (j > i) {
                val a = real[i]; real[i] = real[j]; real[j] = a
                val b = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = b
            }
        }
        var size = 2
        while (size <= SIZE) {
            val half = size / 2
            val stepReal = cos(-2 * PI / size)
            val stepImaginary = sin(-2 * PI / size)
            for (start in 0 until SIZE step size) {
                var wr = 1.0; var wi = 0.0
                for (offset in 0 until half) {
                    val a = start + offset; val b = a + half
                    val br = real[b] * wr - imaginary[b] * wi
                    val bi = real[b] * wi + imaginary[b] * wr
                    val ar = real[a]; val ai = imaginary[a]
                    real[a] = ar + br; imaginary[a] = ai + bi
                    real[b] = ar - br; imaginary[b] = ai - bi
                    val next = wr * stepReal - wi * stepImaginary
                    wi = wr * stepImaginary + wi * stepReal; wr = next
                }
            }
            size *= 2
        }
        if (inverse) for (i in real.indices) { real[i] /= SIZE; imaginary[i] = -imaginary[i] / SIZE }
    }
}
