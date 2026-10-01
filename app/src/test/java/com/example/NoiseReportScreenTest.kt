package com.example

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.auth.AccountSession
import com.example.data.NoiseRecord
import com.example.ui.NoiseReportScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoiseReportScreenTest {
    @get:Rule val compose = createComposeRule()
    @Before fun resetAccount() { AccountSession.signedOut() }
    @After fun clearAccount() { AccountSession.signedOut() }
    private fun record(id: Int, owner: String? = null) = NoiseRecord(id, 1790812800000L, 60.0, 40.0, 0.0,
        false, false, false, "/missing-$id.m4a", 5000L, userId = owner)

    @Test fun previewRequiresReviewBeforeExportAndExcludesOtherAccounts() {
        compose.setContent { MyApplicationTheme(darkTheme = true, dynamicColor = false) {
            NoiseReportScreen(listOf(record(1), record(2, "other-account"))) {}
        } }
        compose.onNodeWithText("1개 선택 / 기간 내 1개").assertExists()
        compose.onNodeWithText("작성하기").performClick()
        compose.onNodeWithText("미리보기 만들기").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("PDF 저장").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PDF 저장").assertIsNotEnabled()
        compose.onNodeWithText("PDF 공유").assertIsNotEnabled()
        compose.onNodeWithText("자동 기록·관찰 내용과 개인정보를 검토했습니다").performClick()
        compose.onNodeWithText("PDF 저장").assertIsEnabled()
        compose.onNodeWithText("PDF 공유").assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/noise-report-preview.png")
    }
}
