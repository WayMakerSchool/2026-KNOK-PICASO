package com.example.ml

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Arrays
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The on-device counterpart of tools/train_audio_baseline.py.
 *
 * ESP32 recordings are already 16 kHz, mono, signed 16-bit PCM WAV files, so
 * this class deliberately accepts that format instead of adding a heavyweight
 * audio decoding dependency. The feature order and constants must stay in
 * sync with the Python training script because the TFLite model consumes the
 * resulting 501-element vector.
 */
object AudioFeatureExtractor {
    const val SAMPLE_RATE = 16_000
    const val CLIP_SECONDS = 4
    const val CLIP_SAMPLES = SAMPLE_RATE * CLIP_SECONDS
    const val FEATURE_COUNT = 501

    private const val N_FFT = 1_024
    private const val HOP_LENGTH = 320
    private const val N_MELS = 64
    private const val N_MFCC = 20
    private const val F_MIN = 20.0
    private const val F_MAX = 8_000.0
    private const val FRAME_COUNT = 201
    private const val UNKNOWN_EPSILON = 1.0e-12

    private val window = FloatArray(N_FFT) { index ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * index / (N_FFT - 1))).toFloat()
    }
    private val windowPower = window.fold(0.0) { sum, value ->
        sum + value.toDouble() * value.toDouble()
    }
    private val melFilterBank = buildMelFilterBank()
    private val dctBasis = buildDctBasis()
    private val bitReversed = buildBitReversedIndices()

    /** Extracts the exact-size feature vector used by the TFLite model. */
    fun extract(wavFile: File): FloatArray {
        return extract(readPcm16Wav(wavFile))
    }

    /** Visible for JVM tests and for callers that already decoded PCM audio. */
    fun extract(samples: FloatArray): FloatArray {
        val fixedSamples = FloatArray(CLIP_SAMPLES)
        val copyLength = minOf(samples.size, CLIP_SAMPLES)
        if (copyLength > 0) {
            samples.copyInto(fixedSamples, endIndex = copyLength)
        }

        // NumPy's reflect padding excludes the edge sample. The clip is much
        // longer than the padding, so these two branches are sufficient.
        val padded = FloatArray(CLIP_SAMPLES + N_FFT)
        for (index in padded.indices) {
            val sourceIndex = when {
                index < N_FFT / 2 -> N_FFT / 2 - index
                index >= N_FFT / 2 + CLIP_SAMPLES -> {
                    CLIP_SAMPLES - 2 - (index - (N_FFT / 2 + CLIP_SAMPLES))
                }
                else -> index - N_FFT / 2
            }
            padded[index] = fixedSamples[sourceIndex]
        }

        val logMel = Array(N_MELS) { FloatArray(FRAME_COUNT) }
        val mfcc = Array(N_MFCC) { FloatArray(FRAME_COUNT) }
        val rmsDb = FloatArray(FRAME_COUNT)
        val real = DoubleArray(N_FFT)
        val imaginary = DoubleArray(N_FFT)

        for (frameIndex in 0 until FRAME_COUNT) {
            val frameStart = frameIndex * HOP_LENGTH
            var framePower = 0.0
            for (sampleIndex in 0 until N_FFT) {
                val value = padded[frameStart + sampleIndex].toDouble() * window[sampleIndex]
                real[sampleIndex] = value
                imaginary[sampleIndex] = 0.0
                framePower += value * value
            }
            rmsDb[frameIndex] = (20.0 * log10(max(sqrt(framePower / N_FFT + 1.0e-12), 1.0e-6))).toFloat()

            fft(real, imaginary)
            val powerSpectrum = DoubleArray(N_FFT / 2 + 1)
            for (bin in powerSpectrum.indices) {
                powerSpectrum[bin] =
                    (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) / max(windowPower, UNKNOWN_EPSILON)
            }

            for (melIndex in 0 until N_MELS) {
                var melPower = 0.0
                val filter = melFilterBank[melIndex]
                for (bin in powerSpectrum.indices) {
                    melPower += powerSpectrum[bin] * filter[bin]
                }
                logMel[melIndex][frameIndex] =
                    (10.0 * log10(max(melPower, 1.0e-12))).toFloat()
            }
        }

        for (coefficient in 0 until N_MFCC) {
            for (frameIndex in 0 until FRAME_COUNT) {
                var value = 0.0
                for (melIndex in 0 until N_MELS) {
                    value += dctBasis[coefficient][melIndex].toDouble() * logMel[melIndex][frameIndex]
                }
                mfcc[coefficient][frameIndex] = value.toFloat()
            }
        }

        val delta = gradient(mfcc)
        val delta2 = gradient(delta)
        val features = FloatArray(FEATURE_COUNT)
        var offset = 0
        offset = appendStatistics(logMel, features, offset)
        offset = appendStatistics(mfcc, features, offset)
        offset = appendStatistics(delta, features, offset)
        offset = appendStatistics(delta2, features, offset)

        var maxAbs = 0.0f
        var meanAbs = 0.0
        var zeroCrossings = 0
        for (index in fixedSamples.indices) {
            val absolute = kotlin.math.abs(fixedSamples[index])
            if (absolute > maxAbs) maxAbs = absolute
            meanAbs += absolute.toDouble()
            if (index > 0 && fixedSamples[index - 1].toDouble() * fixedSamples[index].toDouble() < 0.0) {
                zeroCrossings++
            }
        }
        features[offset++] = rmsDb.average().toFloat()
        features[offset++] = standardDeviation(rmsDb).toFloat()
        features[offset++] = maxAbs
        features[offset++] = (meanAbs / fixedSamples.size).toFloat()
        features[offset] = zeroCrossings.toFloat() / (fixedSamples.size - 1)

        // Keep malformed input from poisoning the interpreter. Normal ESP32
        // WAV files never hit this branch, but it makes the boundary safe.
        for (index in features.indices) {
            if (!features[index].isFinite()) features[index] = 0.0f
        }
        check(offset + 1 == FEATURE_COUNT) { "Unexpected feature count: ${offset + 1}" }
        return features
    }

    private fun appendStatistics(matrix: Array<FloatArray>, output: FloatArray, startOffset: Int): Int {
        val count = matrix.size
        val means = FloatArray(count)
        val standardDeviations = FloatArray(count)
        val p10 = FloatArray(count)
        val p90 = FloatArray(count)

        for (rowIndex in matrix.indices) {
            val row = matrix[rowIndex]
            var sum = 0.0
            for (value in row) sum += value.toDouble()
            val mean = sum / row.size
            var squaredError = 0.0
            for (value in row) {
                val difference = value.toDouble() - mean
                squaredError += difference * difference
            }
            val sorted = row.copyOf()
            Arrays.sort(sorted)
            means[rowIndex] = mean.toFloat()
            standardDeviations[rowIndex] = sqrt(squaredError / row.size).toFloat()
            p10[rowIndex] = percentile(sorted, 0.10)
            p90[rowIndex] = percentile(sorted, 0.90)
        }

        var offset = startOffset
        means.copyInto(output, offset)
        offset += means.size
        standardDeviations.copyInto(output, offset)
        offset += standardDeviations.size
        p10.copyInto(output, offset)
        offset += p10.size
        p90.copyInto(output, offset)
        return offset + p90.size
    }

    private fun percentile(sorted: FloatArray, fraction: Double): Float {
        val position = (sorted.size - 1) * fraction
        val lower = floor(position).toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val weight = position - lower
        val lowerValue = sorted[lower].toDouble()
        val upperValue = sorted[upper].toDouble()
        return (lowerValue + (upperValue - lowerValue) * weight).toFloat()
    }

    private fun gradient(matrix: Array<FloatArray>): Array<FloatArray> {
        return Array(matrix.size) { rowIndex ->
            val source = matrix[rowIndex]
            FloatArray(source.size) { index ->
                when (index) {
                    0 -> source[1] - source[0]
                    source.lastIndex -> source[source.lastIndex] - source[source.lastIndex - 1]
                    else -> (source[index + 1] - source[index - 1]) / 2.0f
                }
            }
        }
    }

    private fun standardDeviation(values: FloatArray): Double {
        val mean = values.average()
        var sum = 0.0
        for (value in values) {
            val difference = value.toDouble() - mean
            sum += difference * difference
        }
        return sqrt(sum / values.size)
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        for (index in real.indices) {
            val reversed = bitReversed[index]
            if (index < reversed) {
                val realValue = real[index]
                real[index] = real[reversed]
                real[reversed] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        var length = 2
        while (length <= N_FFT) {
            val halfLength = length / 2
            val angle = -2.0 * Math.PI / length
            val phaseReal = cos(angle)
            val phaseImaginary = sin(angle)
            var blockStart = 0
            while (blockStart < N_FFT) {
                var currentReal = 1.0
                var currentImaginary = 0.0
                for (offset in 0 until halfLength) {
                    val evenIndex = blockStart + offset
                    val oddIndex = evenIndex + halfLength
                    val oddReal = real[oddIndex] * currentReal - imaginary[oddIndex] * currentImaginary
                    val oddImaginary = real[oddIndex] * currentImaginary + imaginary[oddIndex] * currentReal
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]
                    real[evenIndex] = evenReal + oddReal
                    imaginary[evenIndex] = evenImaginary + oddImaginary
                    real[oddIndex] = evenReal - oddReal
                    imaginary[oddIndex] = evenImaginary - oddImaginary

                    val nextReal = currentReal * phaseReal - currentImaginary * phaseImaginary
                    currentImaginary = currentReal * phaseImaginary + currentImaginary * phaseReal
                    currentReal = nextReal
                }
                blockStart += length
            }
            length = length shl 1
        }
    }

    private fun buildBitReversedIndices(): IntArray {
        val result = IntArray(N_FFT)
        val bits = Integer.numberOfTrailingZeros(N_FFT)
        for (index in result.indices) {
            var value = index
            var reversed = 0
            repeat(bits) {
                reversed = (reversed shl 1) or (value and 1)
                value = value ushr 1
            }
            result[index] = reversed
        }
        return result
    }

    private fun buildMelFilterBank(): Array<FloatArray> {
        val lowMel = hzToMel(F_MIN)
        val highMel = hzToMel(F_MAX)
        val melFrequencies = DoubleArray(N_MELS + 2) { index ->
            melToHz(lowMel + (highMel - lowMel) * index / (N_MELS + 1))
        }
        val binNumbers = IntArray(N_MELS + 2) { index ->
            floor((N_FFT + 1) * melFrequencies[index] / SAMPLE_RATE).toInt()
        }
        return Array(N_MELS) { melIndex ->
            val filter = FloatArray(N_FFT / 2 + 1)
            val left = binNumbers[melIndex]
            val center = binNumbers[melIndex + 1]
            val right = binNumbers[melIndex + 2]
            if (center > left) {
                for (bin in left until center) {
                    filter[bin] = (bin - left).toFloat() / (center - left)
                }
            }
            if (right > center) {
                for (bin in center until right) {
                    filter[bin] = (right - bin).toFloat() / (right - center)
                }
            }
            filter
        }
    }

    private fun buildDctBasis(): Array<FloatArray> {
        return Array(N_MFCC) { coefficient ->
            FloatArray(N_MELS) { melIndex ->
                val raw = cos(Math.PI / N_MELS * (melIndex + 0.5) * coefficient)
                if (coefficient == 0) {
                    (raw / sqrt(N_MELS.toDouble())).toFloat()
                } else {
                    (raw * sqrt(2.0 / N_MELS)).toFloat()
                }
            }
        }
    }

    private fun hzToMel(frequency: Double): Double = 2595.0 * log10(1.0 + frequency / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun readPcm16Wav(file: File): FloatArray {
        RandomAccessFile(file, "r").use { input ->
            require(readFourCc(input) == "RIFF") { "지원하지 않는 WAV 컨테이너입니다." }
            readUnsignedInt(input) // RIFF chunk size
            require(readFourCc(input) == "WAVE") { "WAVE 헤더가 없습니다." }

            var audioFormat = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = 0L

            while (input.filePointer + 8 <= input.length()) {
                val chunkId = readFourCc(input)
                val chunkSize = readUnsignedInt(input)
                val chunkStart = input.filePointer
                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "잘못된 fmt 청크입니다." }
                        audioFormat = readUnsignedShort(input)
                        channels = readUnsignedShort(input)
                        sampleRate = readUnsignedInt(input).toInt()
                        input.skipBytes(6) // byte rate + block align
                        bitsPerSample = readUnsignedShort(input)
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataSize = chunkSize
                    }
                }
                val nextChunk = chunkStart + chunkSize + (chunkSize and 1L)
                require(nextChunk <= input.length()) { "WAV 청크가 파일 범위를 벗어났습니다." }
                input.seek(nextChunk)
            }

            require(audioFormat == 1 && bitsPerSample == 16) {
                "ESP32 PCM WAV(16-bit)만 분류할 수 있습니다. format=$audioFormat, bits=$bitsPerSample"
            }
            require(channels > 0 && sampleRate == SAMPLE_RATE && dataOffset >= 0) {
                "지원하지 않는 WAV 형식입니다. rate=$sampleRate, channels=$channels"
            }
            require(dataSize <= Int.MAX_VALUE) { "WAV 파일이 너무 큽니다." }

            val bytes = ByteArray(dataSize.toInt())
            input.seek(dataOffset)
            input.readFully(bytes)
            val bytesPerFrame = channels * 2
            val frameCount = bytes.size / bytesPerFrame
            val samples = FloatArray(frameCount)
            var byteIndex = 0
            for (frame in 0 until frameCount) {
                var channelSum = 0.0
                repeat(channels) {
                    val unsigned = (bytes[byteIndex].toInt() and 0xff) or
                        (bytes[byteIndex + 1].toInt() shl 8)
                    val signed = if (unsigned and 0x8000 != 0) unsigned - 0x1_0000 else unsigned
                    channelSum += signed / 32768.0
                    byteIndex += 2
                }
                samples[frame] = (channelSum / channels).toFloat()
            }
            return samples
        }
    }

    private fun readFourCc(input: RandomAccessFile): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun readUnsignedShort(input: RandomAccessFile): Int {
        val low = input.readUnsignedByte()
        val high = input.readUnsignedByte()
        return low or (high shl 8)
    }

    private fun readUnsignedInt(input: RandomAccessFile): Long {
        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
