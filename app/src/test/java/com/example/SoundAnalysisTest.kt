package com.example

import com.example.ml.GeminiSoundAnalyzer
import com.example.ml.SoundAnalysis
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SoundAnalysisTest {
    private val response = """{"sound":"문 두드림","certainty":"uncertain","evidence":"둔탁한 충격음이 반복됩니다.","alternative":"물건 부딪힘"}"""

    @Test fun persistsModelAndResultWithoutInventingProbability() {
        val analysis = SoundAnalysis.fromJson(response).copy(model = "gemini-3.5-flash-lite", analyzedAt = 123L)
        assertEquals(analysis, SoundAnalysis.fromJson(analysis.toJson()))
        assertEquals("불확실함", analysis.certaintyLabel)
    }

    @Test fun unknownCannotDisplayAnAssertedSound() {
        val analysis = SoundAnalysis.fromJson(response.replace("uncertain", "unknown"))
        assertEquals("판단 불가", analysis.sound)
        assertEquals("판단 불가", analysis.certaintyLabel)
    }

    @Test fun malformedOrIncompleteResponsesAreNotResults() {
        assertNull(SoundAnalysis.fromJsonOrNull("{}"))
        assertNull(SoundAnalysis.fromJsonOrNull("not json"))
        assertNull(SoundAnalysis.fromJsonOrNull(response.replace("uncertain", "100%")))
        assertNull(SoundAnalysis.fromJsonOrNull(response.replace("둔탁한 충격음이 반복됩니다.", "")))
    }

    @Test fun supportsPhoneAndEsp32RecordingFormats() {
        assertEquals("audio/wav", GeminiSoundAnalyzer.mimeType("WAV"))
        assertEquals("audio/mp4", GeminiSoundAnalyzer.mimeType("m4a"))
    }

    @Test fun rejectsEmptyOversizeAndLongInputBeforePaidRequest() {
        GeminiSoundAnalyzer.validateInput(1_024, 5_000)
        listOf(0L to 5_000L, 45L to 0L, (GeminiSoundAnalyzer.MAX_BYTES + 1) to 5_000L,
            1_024L to (GeminiSoundAnalyzer.MAX_DURATION_MS + 1)).forEach { (size, duration) ->
            assertThrows(IllegalArgumentException::class.java) { GeminiSoundAnalyzer.validateInput(size, duration) }
        }
    }
}
