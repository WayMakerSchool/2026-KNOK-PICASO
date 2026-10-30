package com.example

import com.example.ml.AudioClassificationResult
import com.example.ml.SoundAnalysis
import com.example.ml.offlineAnalysisJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineClassificationPresentationTest {
    @Test fun persistsModelScoreTimingAndActualProbabilitiesWithReadableResult() {
        val result = AudioClassificationResult("footstep", .8f, true,
            mapOf("footstep" to .8f, "instant_impact" to .2f), "test-frozen-model", 42, 88800, true)
        val json = result.offlineAnalysisJson()
        val analysis = requireNotNull(SoundAnalysis.fromJsonOrNull(json))
        assertEquals("발걸음", analysis.sound)
        assertEquals("test-frozen-model", analysis.model)
        val value = JSONObject(json)
        assertEquals(42L, value.getLong("inferenceMs"))
        assertEquals(88800, value.getInt("analysisWindowStartSample"))
        assertEquals(.8, value.getJSONObject("probabilities").getDouble("footstep"), 1e-6)
        assertFalse(analysis.evidence.contains("정확도 80"))
    }
    @Test fun unknownLabelDoesNotAssertTheHighestScoringKnownSound() {
        val result = AudioClassificationResult("normal_or_unknown", .4f, false,
            mapOf("footstep" to .4f, "instant_impact" to .3f))
        val analysis = requireNotNull(SoundAnalysis.fromJsonOrNull(result.offlineAnalysisJson()))
        assertEquals("unknown", analysis.certainty)
        assertEquals("판단 불가", analysis.sound)
    }
}
