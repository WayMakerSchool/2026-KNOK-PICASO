package com.example

import com.example.ml.AudioFeatureExtractor
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioFeatureExtractorTest {
    @Test
    fun extractsFiniteFeatureVectorFromEsp32StyleWav() {
        val wavFile = File.createTempFile("feature-test-", ".wav")
        try {
            writePcmWav(wavFile)
            val features = AudioFeatureExtractor.extract(wavFile)

            assertEquals(AudioFeatureExtractor.FEATURE_COUNT, features.size)
            assertTrue(features.all { it.isFinite() })
            assertTrue(features.any { it != 0.0f })
        } finally {
            wavFile.delete()
        }
    }

    @Test
    fun matchesPythonFeatureRecipeForPreparedClip() {
        val wavFile = File(
            System.getProperty("user.dir"),
            "dataset/with_unknown/processed_16k/footstep0.wav"
        )
        // The dataset is kept outside the Android module in some checkouts.
        // Skip this parity check there, while still running it in this workspace.
        assumeTrue(wavFile.exists())

        val actual = AudioFeatureExtractor.extract(wavFile)
        val expectedPrefix = floatArrayOf(
            -39.487461f, -44.642212f, -48.342415f, -52.180054f,
            -57.192123f, -62.287506f, -65.681198f, -66.624687f,
            -68.806717f, -69.073082f, -71.117111f, -74.000557f,
            -76.423218f, -75.817657f, -75.309708f, -75.552849f,
            -74.917587f, -75.481064f, -75.572380f, -74.518036f
        )
        val expectedSuffix = floatArrayOf(
            -59.525200f, 4.871950f, 0.009246826f, 0.001726358f, 0.002484414f
        )
        expectedPrefix.forEachIndexed { index, expected ->
            assertEquals(expected.toDouble(), actual[index].toDouble(), 0.05)
        }
        expectedSuffix.forEachIndexed { index, expected ->
            assertEquals(expected.toDouble(), actual[actual.lastIndex - expectedSuffix.lastIndex + 1 + index].toDouble(), 0.0005)
        }
    }

    private fun writePcmWav(file: File) {
        val sampleCount = AudioFeatureExtractor.CLIP_SAMPLES
        val bytesPerSample = 2
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { output ->
            val dataSize = sampleCount * bytesPerSample
            output.writeAscii("RIFF")
            output.writeLittleEndianInt(36 + dataSize)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianInt(AudioFeatureExtractor.SAMPLE_RATE)
            output.writeLittleEndianInt(AudioFeatureExtractor.SAMPLE_RATE * bytesPerSample)
            output.writeLittleEndianShort(bytesPerSample)
            output.writeLittleEndianShort(16)
            output.writeAscii("data")
            output.writeLittleEndianInt(dataSize)

            repeat(sampleCount) { index ->
                val sample = (sin(2.0 * PI * 440.0 * index / AudioFeatureExtractor.SAMPLE_RATE) * 8_000.0).toInt()
                output.writeLittleEndianShort(sample)
            }
        }
    }

    private fun DataOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun DataOutputStream.writeLittleEndianShort(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
    }

    private fun DataOutputStream.writeLittleEndianInt(value: Int) {
        writeByte(value and 0xff)
        writeByte((value ushr 8) and 0xff)
        writeByte((value ushr 16) and 0xff)
        writeByte((value ushr 24) and 0xff)
    }
}
