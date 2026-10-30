package com.example

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.data.NoiseRecord
import com.example.ml.SoundAnalysis
import com.example.ui.EspAlwaysRecordingState
import com.example.ui.EspRecorderConnectionState
import com.example.ui.v3.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class V3PresentationTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    @Before fun clearAliases(){context.getSharedPreferences("knok_v3_display_names",0).edit().clear().commit()}

    @Test fun waitingForNoiseIsNeverRenderedAsRecording() {
        assertEquals("감지 중",v3MonitorPresentation(EspAlwaysRecordingState.MONITORING).title)
        assertFalse(v3MonitorPresentation(EspAlwaysRecordingState.MONITORING).recording)
        assertTrue(v3MonitorPresentation(EspAlwaysRecordingState.RECORDING).recording)
        assertEquals("저장 중",v3MonitorPresentation(EspAlwaysRecordingState.SAVING).title)
        assertEquals("확인 필요",v3MonitorPresentation(EspAlwaysRecordingState.ERROR).title)
    }

    @Test fun thresholdCannotChangeWhenDetachedDeviceStillReportsAutoDetection() {
        assertFalse(v3CanEditThreshold(false,false,EspRecorderConnectionState.CONNECTED,
            EspAlwaysRecordingState.ERROR,true))
        assertTrue(v3CanEditThreshold(false,false,EspRecorderConnectionState.CONNECTED,
            EspAlwaysRecordingState.OFF,false))
    }

    @Test fun unavailableOrInvalidLevelDoesNotInventZeroDb() {
        assertEquals("—",v3RelativeLevel(61.1,false))
        assertEquals("—",v3RelativeLevel(Double.NaN,true))
        assertEquals("61.1",v3RelativeLevel(61.1,true))
    }

    @Test fun aliasesSurviveRestartAndDeviceDiscoveryWithoutChangingRecorderPrefs() {
        val recorderPrefs=context.getSharedPreferences("esp32_recorder",0)
        recorderPrefs.edit().putString("base_url","http://original-knok").commit()
        assertTrue(V3DeviceAliasStore(context).save("","http://knok-a/"," 거실 KNOK "))
        assertEquals("거실 KNOK",V3DeviceAliasStore(context).read("discovered-id","http://knok-a"))
        assertTrue(V3DeviceAliasStore(context).save("discovered-id","http://knok-a","옷방 KNOK"))
        assertEquals("옷방 KNOK",V3DeviceAliasStore(context).read("discovered-id","http://new-ip"))
        assertEquals("내 KNOK",V3DeviceAliasStore(context).read("other-id","http://other-knok"))
        assertEquals("http://original-knok",recorderPrefs.getString("base_url",null))
        assertFalse(V3DeviceAliasStore(context).save("discovered-id","http://knok-a","  "))
    }

    @Test fun pendingAndFailureDoNotMasqueradeAsSuccessfulClassification() {
        val record=NoiseRecord(timestamp=1,maxDb=70.0,avgDb=40.0,maxVibration=0.0,isExceeded=true,
            isNoiseExceeded=true,isVibeExceeded=false,recordingPath="/test.wav",durationMs=4000)
        assertEquals("분석 대기",v3RecordPresentation(record).title)
        assertNull(v3RecordPresentation(record).alternative)
        assertEquals("분석 실패",v3RecordPresentation(record.copy(classificationLabel="analysis_failed")).title)
        val json=SoundAnalysis("반복 타격","uncertain","테스트 자료","가구 끌기 / 단발 충격","fixture").toJson()
        val result=v3RecordPresentation(record.copy(classificationLabel="hammering",soundAnalysisJson=json))
        assertTrue(result.badge.contains("불확실"))
        assertEquals("가구 끌기 / 단발 충격",result.alternative)
    }

    @Test fun storedModelScoreAppearsAsAScoreOnTheRecordedSound() {
        val json=org.json.JSONObject(SoundAnalysis("반복 타격","uncertain","테스트 자료","가구 끌기","fixture").toJson())
            .put("modelScore",0.953149974).toString()
        val record=NoiseRecord(timestamp=1,maxDb=70.0,avgDb=40.0,maxVibration=0.0,isExceeded=true,
            isNoiseExceeded=true,isVibeExceeded=false,recordingPath="/test.wav",durationMs=4000,
            classificationLabel="hammering",soundAnalysisJson=json)
        assertEquals("AI 추정 · 모델 점수 95.3%",v3RecordPresentation(record).badge)
    }

    @Test fun missingOrInvalidScoresNeverBecomeInventedPercentages() {
        val json=SoundAnalysis("반복 타격","uncertain","테스트 자료","가구 끌기","fixture").toJson()
        val record=NoiseRecord(timestamp=1,maxDb=70.0,avgDb=40.0,maxVibration=0.0,isExceeded=true,
            isNoiseExceeded=true,isVibeExceeded=false,recordingPath="/test.wav",durationMs=4000,
            classificationLabel="hammering",classificationConfidence=0.95,soundAnalysisJson=json)
        assertEquals("AI 추정 · 불확실함",v3RecordPresentation(record).badge)
        for (value in listOf(org.json.JSONObject.NULL,-0.1,1.01,"0.95")) {
            val changed=org.json.JSONObject(json).put("modelScore",value).toString()
            assertEquals("AI 추정 · 불확실함",v3RecordPresentation(record.copy(soundAnalysisJson=changed)).badge)
        }
    }

    @Test fun unknownSoundRemainsOnHoldEvenWhenItsModelScoreIsPresent() {
        val json=org.json.JSONObject(SoundAnalysis("일반·미지 소음","unknown","테스트 자료","다른 소리","fixture").toJson())
            .put("modelScore",0.71).toString()
        val record=NoiseRecord(timestamp=1,maxDb=70.0,avgDb=40.0,maxVibration=0.0,isExceeded=true,
            isNoiseExceeded=true,isVibeExceeded=false,recordingPath="/test.wav",durationMs=4000,
            classificationLabel="normal_or_unknown",soundAnalysisJson=json)
        assertEquals("AI 판단 보류 · 모델 점수 71.0%",v3RecordPresentation(record).badge)
    }
}
