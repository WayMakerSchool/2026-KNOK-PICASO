package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.v3.V3BottomNavigation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],qualifiers="w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V3NavigationMotionTest {
    @get:Rule val compose=createComposeRule()

    @Test fun selectedCapsuleMovesBetweenTabsRatherThanJumping() {
        var route by mutableStateOf("home")
        compose.setContent { MyApplicationTheme(darkTheme=false,dynamicColor=false) {
            Box(Modifier.fillMaxSize()) { V3BottomNavigation(route) { route=it } }
        }}
        compose.mainClock.autoAdvance=false
        val indicator=compose.onNodeWithTag("v3_nav_indicator")
        indicator.assertExists()
        val start=indicator.fetchSemanticsNode().boundsInRoot.left
        compose.onNodeWithTag("v3_tab_device").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        val middle=indicator.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.advanceTimeBy(1200)
        val end=indicator.fetchSemanticsNode().boundsInRoot.left
        assertTrue("The pill should move right",end>start+20)
        assertTrue("The pill should visibly travel between tabs",middle>start+1 && middle<end-1)
        compose.onNodeWithTag("v3_tab_device").assertIsSelected()
    }

    private fun checkLargeTextNavigation() {
        compose.setContent { MyApplicationTheme(darkTheme=false,dynamicColor=false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.fillMaxSize()) { V3BottomNavigation("home") {} }
            }
        }}
        listOf("home" to "홈", "records" to "기록", "report" to "리포트", "device" to "기기")
            .forEach { (id, label) ->
                val tab = compose.onNodeWithTag("v3_tab_$id").fetchSemanticsNode().boundsInRoot
                val text = compose.onNodeWithText(label, useUnmergedTree=true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("$id keeps a 48dp touch width", tab.width / compose.density.density >= 48f)
                assertTrue("$id keeps its label inside its touch area", text.left >= tab.left &&
                    text.right <= tab.right && text.top >= tab.top && text.bottom <= tab.bottom)
            }
    }

    @Test fun largeTextKeepsNavigationReachableAt360Dp() = checkLargeTextNavigation()

    @Test @Config(sdk=[36],qualifiers="w412dp-h892dp-xhdpi")
    fun largeTextKeepsNavigationReachableAt412Dp() = checkLargeTextNavigation()
}
