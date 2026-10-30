package com.example

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.example.data.NoiseRecord
import com.example.ml.SoundAnalysis
import com.example.ml.offlineAnalysisJson
import com.example.ui.SoundAnalysisPanel
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoundAnalysisPanelTest {
    @get:Rule val compose = createComposeRule()
    private val record = NoiseRecord(timestamp = 1, maxDb = 60.0, avgDb = 40.0,
        maxVibration = 0.0, isExceeded = true, isNoiseExceeded = true,
        isVibeExceeded = false, recordingPath = "/clip.wav", durationMs = 5_000)

    @Test fun retryIsExplicitAndPendingRequestDisablesButton() {
        var clicks = 0
        compose.setContent {
            MyApplicationTheme {
                SoundAnalysisPanel(record, false, true, "인터넷 연결을 확인해 주세요.") { clicks++ }
            }
        }
        if (BuildConfig.KNOK_CANDIDATE) {
            compose.onNodeWithText("검증 앱은 맥 분석 서버로 수신된 ESP32 녹음만 분석합니다.").assertIsDisplayed()
            compose.onNodeWithText("소리 분석 다시 시도").assertDoesNotExist()
            assertEquals(0, clicks)
        } else {
            compose.onNodeWithText("소리 분석 다시 시도").performClick()
            assertEquals(1, clicks)
        }
        compose.onNodeWithText("인터넷 연결을 확인해 주세요.").assertIsDisplayed()
    }

    @Test fun analyzingCannotSubmitAgain() {
        compose.setContent {
            MyApplicationTheme { SoundAnalysisPanel(record, true, false, null) {} }
        }
        if (BuildConfig.KNOK_CANDIDATE) {
            compose.onNodeWithText("검증 앱은 맥 분석 서버로 수신된 ESP32 녹음만 분석합니다.").assertIsDisplayed()
        } else {
            compose.onNodeWithText("소리 분석 중…").assertIsNotEnabled()
        }
    }

    @Test fun savedResultShowsUncertaintyAndAlternativeWithoutCallingAgain() {
        val analysis = SoundAnalysis("문 두드림", "uncertain", "짧고 둔탁한 충격음이 일정한 간격으로 반복됩니다.", "물건이 바닥에 부딪히는 소리")
        compose.setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.width(340.dp)) {
                    SoundAnalysisPanel(record.copy(soundAnalysisJson = analysis.toJson()), false, true, null) {}
                }
            }
        }
        compose.onNodeWithText("추정 소리: 문 두드림").assertIsDisplayed()
        compose.onNodeWithText("불확실함", substring = true).assertIsDisplayed()
        compose.onNodeWithText("다른 가능성:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("소리 분석").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/sound-analysis.png")
    }
    @Test fun longEventShowsTheActualRepresentativeWindowAndModel() {
        val result=com.example.ml.AudioClassificationResult("footstep",.8f,true,mapOf("footstep" to .8f),"frozen-v2-test",10,32000,true)
        compose.setContent {
            MyApplicationTheme { SoundAnalysisPanel(record.copy(durationMs=15000,soundAnalysisJson=result.offlineAnalysisJson()),false,true,null){} }
        }
        compose.onNodeWithText("2.0–6.0초",substring=true).assertIsDisplayed()
        compose.onNodeWithText("분류 모델: frozen-v2-test").assertIsDisplayed()
    }
}
