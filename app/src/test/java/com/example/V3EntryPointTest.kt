package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class V3EntryPointTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startsAtTheRealEmptyHomeWithoutInventedRecordings() {
        compose.onNodeWithText("내 KNOK").assertIsDisplayed()
        compose.onNodeWithText("아직 저장된 녹음이 없어요").assertIsDisplayed()
        compose.onNodeWithText("기기 연결하기").assertIsDisplayed()
        compose.onNodeWithText("리포트").assertIsDisplayed()
    }

    @Test fun switchingTabsAnimatesTheNewScreenWithoutStartingDetection() {
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("v3_tab_records").performClick()
        compose.mainClock.advanceTimeByFrame()
        val page=compose.onNodeWithTag("v3_page_records")
        page.assertExists()
        val start=page.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.advanceTimeBy(90)
        val middle=page.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.advanceTimeBy(500)
        val end=page.fetchSemanticsNode().boundsInRoot.left
        assertTrue("Screen should move into its final position",start>middle && middle>end-1)
        compose.onNodeWithTag("v3_tab_home").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("기기 연결하기").assertIsDisplayed()
    }

    @Test fun stoppedDeviceKeepsItsDetectionThresholdControlReachable() {
        compose.onNodeWithTag("v3_tab_device").performClick()
        compose.onNodeWithTag("v3_threshold_settings").performScrollTo().performClick()
        compose.onNodeWithText("감지 기준 조절").assertIsDisplayed()
        compose.onNodeWithTag("v3_detection_slider").assertIsDisplayed()
    }

    @Test fun disconnectedHomeLeadsToAConnectionFirstSetupAndRetainsAdvancedControls() {
        compose.onNodeWithTag("v3_primary_action").performClick()
        compose.onNodeWithText("기기 연결").assertIsDisplayed()
        compose.onNodeWithText("KNOK 주소").assertIsDisplayed()
        compose.onNodeWithText("자세한 기기 설정").performScrollTo().performClick()
        compose.onNodeWithText("ESP32 Wi-Fi 기기").assertIsDisplayed()
    }

    @Test fun ledgerRequiresConfirmationBeforeClearingRecords() {
        compose.onNodeWithContentDescription("프로필과 더보기").performClick()
        compose.onNodeWithText("소음대장").performClick()
        compose.onNodeWithContentDescription("Clear All Records").performClick()
        compose.onNodeWithText("모든 기록을 목록에서 삭제할까요?").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("모든 기록을 목록에서 삭제할까요?").assertDoesNotExist()
    }
}
