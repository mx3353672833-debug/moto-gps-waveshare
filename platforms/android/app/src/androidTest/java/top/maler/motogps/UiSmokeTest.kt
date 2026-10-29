package top.maler.motogps

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline UI smoke test: no permission prompts, Bluetooth or real location
 * (MainActivity.EXTRA_UI_TEST). The demo ride uses the checked-in Jinan route.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_UI_TEST, true),
        )
    }

    @After
    fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MotoApplication)
                .model.apply { if (isNavigationActive) stopNavigation() }
        }
        scenario.close()
    }

    @Test
    fun homeShowsSearchAndGatewaySetup() {
        compose.onNodeWithText("出发").assertExists()
        compose.onNodeWithTag("destination-search-field").assertExists()
        compose.onNodeWithText("演示导航").assertExists()
        compose.onNodeWithText("我的圆屏").assertExists()
    }

    @Test
    fun gatewaySettingsRejectsAnInsecureAddress() {
        compose.onNodeWithTag("gateway-settings-toolbar").performClick()
        compose.onNodeWithTag("gateway-address-field").performTextInput("http://nav.example.com")
        compose.onNodeWithTag("gateway-save-button").performClick()
        compose.waitUntilExactlyOneExists(hasText("请填写有效的 HTTPS 网关地址", substring = true), 5_000)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("出发").assertExists()
    }

    @Test
    fun demoRideRunsOnTheSharedCoreAndEnds() {
        compose.onNodeWithText("演示导航").performClick()
        compose.waitUntilExactlyOneExists(hasText("正在演示"), 15_000)
        compose.onNodeWithText("剩余路程").assertExists()
        compose.onNodeWithText("演示位置").assertExists()
        compose.onNodeWithTag("primary-navigation-action").performClick()
        compose.waitUntilExactlyOneExists(hasText("出发"), 5_000)
    }

    @Test
    fun deviceAndMapPagesOpen() {
        compose.onNodeWithTag("device-details-button").performClick()
        compose.onNodeWithTag("device-details-sheet").assertExists()
        compose.onNodeWithText("音乐控制").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("地图与离线下载").performClick()
        compose.onNodeWithTag("map-downloads-sheet").assertExists()
        compose.onNodeWithText("济南基础地图 · 随 App 提供").assertExists()
    }
}
