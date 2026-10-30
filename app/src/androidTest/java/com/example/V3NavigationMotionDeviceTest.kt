package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.v3.V3BottomNavigation
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/** UI-only test: it neither opens a KNOK session nor touches the app's saved recordings. */
@RunWith(AndroidJUnit4::class)
class V3NavigationMotionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pressedTabKeepsItsCornersClearAndUpdatesSelection() {
        var route by mutableStateOf("home")
        compose.setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize()) { V3BottomNavigation(route) { route = it } }
            }
        }
        val tab = compose.onNodeWithTag("v3_tab_records")
        val before = tab.captureToImage().toPixelMap()
        tab.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        val pressed = tab.captureToImage().toPixelMap()
        val a = before[2, 2]
        val b = pressed[2, 2]
        val distance = max(max(abs(a.red - b.red), abs(a.green - b.green)), abs(a.blue - b.blue))
        assertTrue("A pressed tab must not paint a square corner; difference=$distance", distance < 0.025f)
        tab.performTouchInput { up() }
        tab.assertIsSelected()
    }
}
