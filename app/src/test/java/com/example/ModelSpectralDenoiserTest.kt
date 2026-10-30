package com.example

import com.example.ml.ModelSpectralDenoiser
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ModelSpectralDenoiserTest {
    private fun fixture(name: String): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("spectral_gate/$name.f32")!!.use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
    private fun assertParity(name: String) {
        val input = fixture("$name-input")
        val preserved = input.copyOf()
        val expected = fixture("$name-expected")
        val actual = ModelSpectralDenoiser.process(input)
        assertArrayEquals(preserved, input, 0f)
        assertEquals(expected.size, actual.size)
        assertTrue(actual.all { it.isFinite() })
        val error = expected.indices.maxOf { abs(expected[it]-actual[it]) }
        assertTrue("Python/Kotlin waveform max error=$error", error < 2e-6f)
    }
    @Test fun realDevelopmentRecordingMatchesPython() = assertParity("training_capture")
    @Test fun nonHopAlignedTailAndZeroPaddingMatchPython() = assertParity("unaligned_tail")
    @Test fun continuousContextBypassMatchesPython() = assertParity("continuous")
    @Test fun zeroStrengthIsIdentityAndInvalidInputsAreRejected() {
        val x = fixture("training_capture-input")
        assertArrayEquals(x, ModelSpectralDenoiser.process(x, 0.0), 0f)
        assertThrows(IllegalArgumentException::class.java) { ModelSpectralDenoiser.process(FloatArray(100)) }
        assertThrows(IllegalArgumentException::class.java) { ModelSpectralDenoiser.process(FloatArray(512) { Float.NaN }) }
        assertThrows(IllegalArgumentException::class.java) { ModelSpectralDenoiser.process(x, -1.0) }
    }
}
