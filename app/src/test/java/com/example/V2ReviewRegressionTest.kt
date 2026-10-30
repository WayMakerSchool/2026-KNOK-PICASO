package com.example

import com.example.ml.ModelAudioPreprocessor
import com.example.ml.AudioClassificationResult
import com.example.ml.offlineAnalysisJson
import com.example.ml.classificationSummary
import com.example.ml.analysisWindowDescription
import com.example.ml.storedClassificationSummary
import com.example.ui.canEditDeviceEndpoint
import com.example.ui.EspRecorderConnectionState
import com.example.ui.recordingLimitDescription
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class V2ReviewRegressionTest {
    @Test fun quantizationLimitedSamplesAreUnresolvedBeforeNormalization() {
        val alternating=FloatArray(64000){if(it%2==0)1f/32768 else -1f/32768}
        val impulse=FloatArray(64000).apply{this[100]=1f/32768}
        assertTrue(ModelAudioPreprocessor.belowQuantizationFloor(alternating,2.0,1.0))
        assertTrue(ModelAudioPreprocessor.belowQuantizationFloor(impulse,2.0,1.0))
        assertFalse(ModelAudioPreprocessor.belowQuantizationFloor(FloatArray(64000){if(it%2==0).01f else -.01f},2.0,1.0))
    }
    @Test fun unresolvedDecisionHasNoInventedModelScore() {
        val result=AudioClassificationResult("normal_or_unknown",0f,false,emptyMap(),signalQuality="quantization_limited")
        val json=JSONObject(result.offlineAnalysisJson())
        assertEquals("signal_quality_gate",json.getString("source"))
        assertTrue(json.isNull("modelScore"))
        assertFalse(result.classificationSummary().contains("0%"))
        assertTrue(result.classificationSummary().contains("신호"))
        assertFalse(storedClassificationSummary(result.label,0.0,json.toString()).contains("%"))
    }
    @Test fun endpointCannotChangeDuringAutomaticManualOrBusyWork() {
        assertFalse(canEditDeviceEndpoint(false,true,EspRecorderConnectionState.CHECKING))
        assertFalse(canEditDeviceEndpoint(false,false,EspRecorderConnectionState.RECORDING))
        assertFalse(canEditDeviceEndpoint(true,false,EspRecorderConnectionState.DISCONNECTED))
        assertTrue(canEditDeviceEndpoint(false,false,EspRecorderConnectionState.DISCONNECTED))
    }
    @Test fun representativeWindowAndAdvertisedLimitAreExplicit() {
        val result=AudioClassificationResult("footstep",.8f,true,mapOf("footstep" to .8f),"test-version",10,32000,true)
        val text=requireNotNull(analysisWindowDescription(result.offlineAnalysisJson()))
        assertTrue(text.contains("2.0–6.0초"));assertTrue(text.contains("대표 4초"))
        assertTrue(recordingLimitDescription(15000).contains("15초"))
        assertFalse(recordingLimitDescription(15000).contains("30초"))
    }
}
