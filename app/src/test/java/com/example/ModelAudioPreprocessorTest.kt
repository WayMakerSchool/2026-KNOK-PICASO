package com.example

import com.example.ml.ModelAudioPreprocessor
import org.junit.Assert.*
import org.junit.Test

class ModelAudioPreprocessorTest {
    @Test fun retainsLateImpactInsteadOfTakingOnlyFirstFourSeconds() {
        val input = FloatArray(160000)
        input[120000] = .5f
        val window = ModelAudioPreprocessor.window(input)
        assertEquals(64000, window.samples.size)
        assertEquals(88800, window.startSample)
        assertEquals(.5f, window.samples[31200], 0f)
        assertTrue(window.cropped)
    }
    @Test fun zeroPadsShortAudioWithoutNormalizingBackground() {
        val window = ModelAudioPreprocessor.window(floatArrayOf(.001f, -.001f))
        assertEquals(64000, window.samples.size)
        assertEquals(.001f, window.samples[0], 0f)
        assertEquals(0f, window.samples[63999], 0f)
        assertEquals(0, window.startSample)
    }
    @Test fun silenceIsValidButEmptyAndNonfiniteInputsAreNot() {
        assertTrue(ModelAudioPreprocessor.window(FloatArray(64000)).samples.all { it == 0f })
        assertThrows(IllegalArgumentException::class.java) { ModelAudioPreprocessor.window(floatArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { ModelAudioPreprocessor.window(floatArrayOf(Float.NaN)) }
    }
}
