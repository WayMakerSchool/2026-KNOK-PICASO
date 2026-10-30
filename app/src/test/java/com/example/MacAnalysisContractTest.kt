package com.example

import com.example.ml.MacAnalysisResult
import com.example.ml.analysisEventIdDescription
import com.example.ml.floorDirectionDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacAnalysisContractTest {
    @Test fun parsesSeparateSoundAndFloorResultsAndPreservesMetricMeaning() {
        val result = MacAnalysisResult.fromJson(responseJson())
        assertEquals("hammering", result.sound.label)
        assertEquals(0.91, result.sound.score.toDouble(), 0.0001)
        assertEquals("above", result.floor.label)
        assertEquals("test-sound-v1", result.sound.modelVersion)
        assertEquals("test-floor-v1", result.floor.modelVersion)

        val stored = JSONObject(result.toStoredAnalysisJson())
        assertEquals("above", stored.getString("floorLabel"))
        assertFalse(stored.getBoolean("modelScoreIsAccuracy"))
        assertEquals("event-42", stored.getString("eventId"))
        assertEquals("기기 이벤트 ID: event-42", analysisEventIdDescription(result.toStoredAnalysisJson()))
    }

    @Test fun rejectsResponseThatMislabelsModelScoreAsAccuracy() {
        val invalid = JSONObject(responseJson()).apply {
            getJSONObject("sound").put("score_is_accuracy", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MacAnalysisResult.fromJson(invalid.toString())
        }
    }

    @Test fun floorDirectionDisplayKeepsUnregisteredAndUnsupportedResultsOnHold() {
        assertEquals("층 방향 연구 결과: 위층", floorDirectionDescription("{\"floorLabel\":\"above\"}"))
        assertEquals("층 방향 판단 보류 · 설치 위치를 등록하지 않았습니다.", floorDirectionDescription(
            "{\"floorLabel\":\"uncertain\",\"floorReason\":\"receiver_position_unregistered\"}"))
        assertEquals(null, floorDirectionDescription("{}"))
    }

    private fun responseJson(): String = """
        {
          "schema_version":1,
          "event_id":"event-42",
          "analysis_id":"analysis-42",
          "audio":{"file_sha256":"${"a".repeat(64)}","pcm_sha256":"${"b".repeat(64)}","sample_rate":16000,"sample_count":16000,"duration_seconds":1.0},
          "sound":{"label":"hammering","score":0.91,"score_is_accuracy":false,"signal_quality":"normal","model_version":"test-sound-v1","probabilities":{"dragging_furniture":0.01,"footstep":0.01,"hammering":0.91,"instant_impact":0.02,"normal_or_unknown":0.03,"vacuum_cleaner":0.02}},
          "floor":{"label":"above","receiver_position":"lower","model_version":"test-floor-v1"},
          "ground_truth_used":false
        }
    """.trimIndent()
}
