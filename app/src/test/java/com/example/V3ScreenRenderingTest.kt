package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import com.example.data.NoiseRecord
import com.example.ml.SoundAnalysis
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.v3.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** All filled records are explicit fixtures in tests, never shipping app data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],qualifiers="w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V3ScreenRenderingTest {
    @get:Rule val compose=createComposeRule()
    private val fixture=NoiseRecord(id=42,timestamp=1792844528000,maxDb=83.5,avgDb=54.2,maxVibration=0.0,
        isExceeded=true,isNoiseExceeded=true,isVibeExceeded=false,recordingPath="/test-only.wav",durationMs=4000,
        classificationLabel="hammering",soundAnalysisJson=SoundAnalysis("반복 타격","uncertain","테스트 자료","가구 끌기 / 단발 충격","test-only").toJson())

    private fun render(content:@Composable ()->Unit) {
        compose.setContent { MyApplicationTheme(darkTheme=false,dynamicColor=false) {
            Surface(color=V3Cream) {Column(Modifier.fillMaxSize()) {
                Text("UI 검증용 예시 데이터 · 실제 기기 시험 아님",fontSize=10.sp,color=V3Blue,modifier=Modifier.padding(6.dp))
                Box(Modifier.weight(1f)) {content()}
            }}
        }}
    }
    private fun capture(name:String)=compose.onRoot().captureRoboImage("build/outputs/roborazzi/v3/$name.png")

    @Test fun homeUsesTheRealActionAndKeepsTheProductSecondaryToControls() {
        var calls=0
        render {V3HomeScreen(V3DeviceUi(name="거실 KNOK",connection=EspRecorderConnectionState.CONNECTED),fixture,{}, {calls++},{},{},{})}
        compose.onNodeWithText("소음 감지 켜기").assertIsDisplayed().performClick()
        assertEquals(1,calls)
        capture("home-412-fixture")
    }

    @Test fun disconnectedHomeGuidesToSetupWithoutStartingDetection() {
        var starts=0
        var opensSetup=0
        render { V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.DISCONNECTED),null,
            {},{starts++},{},{},{opensSetup++}) }
        compose.onNodeWithText("기기 연결하기").assertIsDisplayed()
        capture("home-disconnected-ux-fixture")
        compose.onNodeWithText("기기 연결하기").performClick()
        assertEquals(1,opensSetup)
        assertEquals(0,starts)
    }

    @Test fun checkingConnectionCannotStartDetectionOrLaunchAnotherCheck() {
        render { V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.CHECKING,busy=true),null,
            {},{},{},{},{}) }
        compose.onNodeWithTag("v3_primary_action").assertIsNotEnabled()
        compose.onNodeWithTag("v3_primary_action").assertTextContains("연결 확인 중")
    }

    @Test fun detectionCannotRestartWhileItsPreviousSessionIsStopping() {
        render { V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.CONNECTED,
            state=EspAlwaysRecordingState.MONITORING,enabled=false,busy=false),null,{},{},{},{},{}) }
        compose.onNodeWithTag("v3_primary_action").assertTextContains("감지 종료 중").assertIsNotEnabled()
    }

    @Test fun deviceReportedAutoWithoutAnAppSessionDoesNotPretendDetectionIsOff() {
        var opensSetup=0
        var toggles=0
        render { V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.CONNECTED,
            state=EspAlwaysRecordingState.ERROR,enabled=false,reportedDeviceAuto=true),null,
            {},{toggles++},{},{},{opensSetup++}) }
        compose.onNodeWithText("기기 상태 확인").assertIsDisplayed().performClick()
        assertEquals(1,opensSetup)
        assertEquals(0,toggles)
        compose.onNodeWithText("소음 감지 켜기").assertDoesNotExist()
    }

    @Test fun monitoringAndRecordingAreDifferentStatesWithOneStopAction() {
        var current by mutableStateOf(EspAlwaysRecordingState.MONITORING)
        var stops=0
        render {V3HomeScreen(V3DeviceUi(name="거실 KNOK",connection=EspRecorderConnectionState.CONNECTED,
            enabled=true,state=current,level=61.1,levelAvailable=true,threshold=75.0),null,{}, {stops++},{},{},{})}
        compose.onNodeWithTag("v3_monitor_title").assertTextEquals("감지 중")
        compose.onNodeWithText("소음 감지 중지").assertIsDisplayed()
        capture("monitoring-412-fixture")
        compose.runOnIdle {current=EspAlwaysRecordingState.RECORDING}
        compose.onNodeWithTag("v3_monitor_title").assertTextEquals("녹음 중")
        compose.onNodeWithText("녹음·감지 중지").performClick()
        assertEquals(1,stops)
        capture("recording-412-fixture")
    }

    @Test fun savingAndDisconnectedErrorsNeverClaimRecording() {
        var current by mutableStateOf(EspAlwaysRecordingState.SAVING)
        render {V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.DISCONNECTED,enabled=true,
            state=current,level=61.1,levelAvailable=true,message="기기 저장 공간 확인 필요"),null,{},{},{},{},{})}
        compose.onNodeWithTag("v3_monitor_title").assertTextEquals("저장 중")
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.runOnIdle {current=EspAlwaysRecordingState.ERROR}
        compose.onNodeWithTag("v3_monitor_title").assertTextEquals("확인 필요")
        compose.onNodeWithText("기기 저장 공간 확인 필요").performScrollTo().assertIsDisplayed()
        capture("error-fixture")
    }

    @Test fun recordsPreserveUncertaintyAndOpenTheChosenOriginal() {
        var selected:Int?=null
        render {V3RecordsScreen(listOf(fixture),null,false,{selected=it.id},{},{})}
        compose.onNodeWithText("AI 추정 · 불확실함").assertIsDisplayed()
        compose.onNodeWithText("반복 망치질·타격").performClick()
        assertEquals(42,selected)
        capture("records-412-fixture")
    }

    @Test fun waitingAndFailedAnalysisKeepTheOriginalVisible() {
        render {V3RecordsScreen(listOf(fixture.copy(id=1,classificationLabel="unclassified",soundAnalysisJson=null),
            fixture.copy(id=2,classificationLabel="analysis_failed",soundAnalysisJson=null)),null,false,{},{},{})}
        compose.onNodeWithText("분석 대기").assertIsDisplayed()
        compose.onNodeWithText("원본 보존됨").performScrollTo().assertIsDisplayed()
        capture("analysis-pending-failed-fixture")
    }

    @Test fun emptyRecordingListOffersAnActionToPrepareDetection() {
        var prepare=0
        render { V3RecordsScreen(emptyList(),null,false,{},{},{},{prepare++}) }
        compose.onNodeWithText("감지 준비하기").assertIsDisplayed().performClick()
        assertEquals(1,prepare)
    }

    @Test fun deviceSetupPassesTheEnteredAddressToTheExistingConnectionAction() {
        var requested=""
        render { V3DeviceScreen(V3DeviceUi(),"http://old.local",null,null,0,
            {requested=it},{},{},{},{},{}) }
        capture("device-connection-ux-fixture")
        compose.onNodeWithTag("v3_device_address").performTextClearance()
        compose.onNodeWithTag("v3_device_address").performTextInput(" 192.168.1.42 ")
        compose.onNodeWithText("연결 확인").performScrollTo().performClick()
        assertEquals("192.168.1.42",requested)
    }

    @Test fun activeDetectionKeepsTheAddressAndConnectionActionLocked() {
        render { V3DeviceScreen(V3DeviceUi(enabled=true,state=EspAlwaysRecordingState.MONITORING,
            connection=EspRecorderConnectionState.CONNECTED),"http://knok.local",true,null,0,
            {},{},{},{},{},{}) }
        compose.onNodeWithTag("v3_device_address").assertIsNotEnabled()
        compose.onNodeWithText("연결 확인").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("주소를 바꾸려면 감지와 녹음을 먼저 중지해 주세요.").assertIsDisplayed()
    }

    @Test fun deviceReportedAsListeningOffersAnExplicitStopRequest() {
        var stops=0
        render { V3DeviceScreen(V3DeviceUi(connection=EspRecorderConnectionState.CONNECTED,reportedDeviceAuto=true),
            "http://knok.local",true,null,0,{},{},{},{},{},{},onStopDetached={stops++}) }
        compose.onNodeWithText("기기 감지 중지 요청").performScrollTo().performClick()
        assertEquals(1,stops)
    }

    @Test fun completedDeviceEventsCanBeReceivedAfterDetectionIsOff() {
        var requests=0
        render { V3DeviceScreen(V3DeviceUi(connection=EspRecorderConnectionState.CONNECTED,pendingDeviceEvents=2),
            "http://knok.local",true,null,0,{},{},{},{},{},{},onReceivePending={requests++}) }
        compose.onNodeWithText("대기 녹음 받기").performScrollTo().performClick()
        assertEquals(1,requests)
    }

    @Test fun pendingEventsCannotInterruptAnActiveManualRecording() {
        render { V3DeviceScreen(V3DeviceUi(connection=EspRecorderConnectionState.RECORDING,
            pendingDeviceEvents=2,deviceRecording=true),"http://knok.local",true,null,0,
            {},{},{},{},{},{}) }
        compose.onNodeWithText("대기 녹음 받기").performScrollTo().assertIsNotEnabled()
    }

    @Test fun failedConnectionCheckOffersAPathToReconnect() {
        var opensSetup=0
        render { V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.CHECKING,
            state=EspAlwaysRecordingState.ERROR,busy=false),null,{},{},{},{},{opensSetup++}) }
        compose.onNodeWithText("기기 연결하기").assertIsDisplayed().performClick()
        assertEquals(1,opensSetup)
    }

    @Test fun pendingDeviceRecordingOffersTheExistingAnalysisRetry() {
        var retries=0
        val pending=fixture.copy(classificationLabel="unclassified",soundAnalysisJson=null,
            recordingSource="ESP32",deviceEventId="test-event",deviceClassificationPending=true)
        render { V3RecordDetail(pending,false,0f,false,null,{},{},{retries++},{},{}) }
        compose.onNodeWithText("대기 중인 분석 다시 시도").performScrollTo()
        capture("record-retry-ux-fixture")
        compose.onNodeWithText("대기 중인 분석 다시 시도").performClick()
        assertEquals(1,retries)
    }

    @Test fun replayDuringDetectionAsksBeforePlayingButAllowsContinuing() {
        var plays=0
        render {
            V3PlaybackGuard(true,listOf(fixture),null,false,{plays++}) { request ->
                V3RecordingCard(fixture,false,{}, {request(fixture)})
            }
        }
        compose.onNodeWithContentDescription("녹음 재생").performClick()
        compose.onNodeWithText("계속 재생").assertIsDisplayed()
        assertEquals(0,plays)
        compose.onNodeWithText("계속 재생").performClick()
        assertEquals(1,plays)
    }

    @Test fun pausingExistingPlaybackDuringDetectionNeedsNoConfirmation() {
        var plays=0
        render {
            V3PlaybackGuard(true,listOf(fixture),fixture.id,true,{plays++}) { request ->
                V3RecordingCard(fixture,true,{}, {request(fixture)})
            }
        }
        compose.onNodeWithContentDescription("녹음 일시정지").performClick()
        assertEquals(1,plays)
        compose.onNodeWithText("계속 재생").assertDoesNotExist()
    }

    @Test fun startingDetectionDuringPlaybackRequiresStoppingThePlaybackFirst() {
        var starts=0
        var stopThenStart=0
        render {
            V3DetectionStartGuard(true,{starts++},{stopThenStart++}) { request ->
                V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.CONNECTED),fixture,
                    {},request,{},{},{})
            }
        }
        compose.onNodeWithText("소음 감지 켜기").performClick()
        compose.onNodeWithText("재생 멈추고 감지").assertIsDisplayed()
        assertEquals(0,starts)
        assertEquals(0,stopThenStart)
        compose.onNodeWithText("재생 멈추고 감지").performClick()
        assertEquals(1,stopThenStart)
    }

    @Test fun ledgerRecordDeletionRequiresASeparateConfirmation() {
        var deleted=0
        render { LogRecordCard(fixture.copy(recordingSource="ESP32"),{deleted++}) }
        compose.onNodeWithContentDescription("Delete record").performClick()
        compose.onNodeWithText("이 기록을 삭제할까요?").assertIsDisplayed()
        assertEquals(0,deleted)
        compose.onNodeWithText("취소").performClick()
        assertEquals(0,deleted)
    }

    @Test fun ledgerShowsDeviceLevelsAsRelativeValues() {
        render { LogRecordCard(fixture.copy(recordingSource="ESP32"),{}) }
        compose.onNodeWithText("최대 소음 · 상대값").assertIsDisplayed()
    }

    @Test @Config(sdk=[36],qualifiers="w360dp-h640dp-xhdpi")
    fun longNameAndLargeTextKeepTheActionAvailableAt360dp() {
        render {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                V3HomeScreen(V3DeviceUi(name="우리집 아주 긴 작은방 천장 KNOK"),null,{},{},{},{},{})
            }
        }
        compose.onNodeWithText("기기 연결하기").assertIsDisplayed()
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("우리집 아주 긴 작은방 천장 KNOK").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout=layouts.single()
        assertTrue(layout.lineCount>=2)
        val textHeight=with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() }
        assertTrue("Large name lines must not overlap",layout.getLineBaseline(1)-layout.getLineBaseline(0)>=textHeight)
        capture("home-360-large-text-fixture")
    }

    @Test fun receiveFailuresRemainVisibleWhileMonitoringContinues() {
        render {V3HomeScreen(V3DeviceUi(enabled=true,state=EspAlwaysRecordingState.MONITORING,
            message="이벤트 수신 재시도 · CRC 불일치"),null,{},{},{},{},{})}
        compose.onNodeWithText("이벤트 수신 재시도 · CRC 불일치").performScrollTo().assertIsDisplayed()
    }

    @Test fun manualRecorderConnectionIsNotPresentedAsIdleDetection() {
        render {V3HomeScreen(V3DeviceUi(connection=EspRecorderConnectionState.RECORDING,
            state=EspAlwaysRecordingState.OFF,enabled=false),null,{},{},{},{},{})}
        compose.onNodeWithTag("v3_monitor_title").assertTextEquals("녹음 중")
        compose.onNodeWithText("녹음 중지").assertIsDisplayed()
    }
}
