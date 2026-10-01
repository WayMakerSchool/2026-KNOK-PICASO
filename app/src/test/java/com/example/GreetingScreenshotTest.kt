package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.TelemetryDial
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.QuietAlert
import com.example.ui.theme.QuietPrimary
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun telemetry_dial_screenshot() {
    composeTestRule.setContent { 
      MyApplicationTheme { 
        TelemetryDial(
          label = "소음 테스트 계기판",
          value = 58.2,
          maxValue = 64.0,
          unit = "dB",
          threshold = 57.0,
          isAlertActive = true,
          colors = Pair(QuietPrimary, QuietAlert)
        )
      } 
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
