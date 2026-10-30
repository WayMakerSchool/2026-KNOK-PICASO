package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only navigation/alias UI. Never enables detection, plays audio, or contacts a real KNOK. */
@RunWith(AndroidJUnit4::class)
class V3AppDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    private fun screenshot(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250) // Allow Android's pressed-state ripple to finish before the evidence capture.
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir,"v3-evidence-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }

    @Test fun separateV3NavigatesAndRemembersAliasWithoutStartingDetection() {
        check(BuildConfig.APPLICATION_ID=="com.aistudio.quietneighbors.gspqzy.v3")
        compose.onNodeWithText("내 KNOK").assertIsDisplayed()
        compose.onNodeWithText("기기 연결하기").assertIsDisplayed()
        screenshot("home-empty")
        compose.onNodeWithTag("v3_tab_records").performClick()
        compose.onNodeWithText("저장된 녹음").assertIsDisplayed()
        compose.onNodeWithText("아직 저장된 녹음이 없어요").assertIsDisplayed()
        screenshot("records-empty")
        compose.onNodeWithTag("v3_tab_report").performClick()
        compose.onNodeWithText("상담 참고보고서").assertIsDisplayed()
        screenshot("report")
        compose.onNodeWithTag("v3_tab_device").performClick()
        compose.onNodeWithText("기기 연결").assertIsDisplayed()
        screenshot("device")
        val recorder=ViewModelProvider(compose.activity)[com.example.ui.EspRecorderViewModel::class.java]
        val originalThreshold=recorder.detectionThreshold.value
        compose.onNodeWithTag("v3_threshold_settings").performScrollTo().performClick()
        compose.onNodeWithTag("v3_detection_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(85f) }
        compose.onNodeWithText("85 · 상대값").assertIsDisplayed()
        check(recorder.detectionThreshold.value==85.0)
        screenshot("threshold-control")
        compose.onNodeWithTag("v3_detection_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(originalThreshold.toFloat()) }
        compose.onNodeWithText("닫기").performClick()
        check(recorder.detectionThreshold.value==originalThreshold)
        compose.onNodeWithTag("v3_tab_home").performClick()
        compose.onNodeWithContentDescription("기기 이름 변경").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("거실 KNOK")
        compose.onNodeWithText("이름 저장").performClick()
        compose.onNodeWithText("거실 KNOK").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("거실 KNOK").assertIsDisplayed()
        compose.onNodeWithText("기기 연결하기").assertIsDisplayed()
        compose.onNodeWithText("연결 안 됨",substring=true).assertIsDisplayed()
        screenshot("home-renamed")
        compose.onNodeWithContentDescription("기기 이름 변경").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("내 KNOK")
        compose.onNodeWithText("이름 저장").performClick()
        compose.onNodeWithText("내 KNOK").assertIsDisplayed()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=com.example.data.AppDatabase.getDatabase(context).openHelper.writableDatabase
        val synchronous=database.query("PRAGMA synchronous").use { cursor -> check(cursor.moveToFirst());cursor.getInt(0) }
        File(context.filesDir,"v3-evidence-db-state.json").writeText("{\"synchronous\":$synchronous,\"physical_durability_tested\":false}")
    }
}
