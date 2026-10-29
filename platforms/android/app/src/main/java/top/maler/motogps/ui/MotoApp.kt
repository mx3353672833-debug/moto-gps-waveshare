package top.maler.motogps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import top.maler.motogps.app.AppModel

enum class Overlay { DEVICE, MAPS, GATEWAY, DATA_USE }

private enum class MainScreen { HOME, ROUTE_PREVIEW, ACTIVE_NAVIGATION }

class PlatformActions(
    val requestLocation: () -> Unit,
    val requestBluetooth: () -> Unit,
    val openAppSettings: () -> Unit,
    val openBluetoothSettings: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val moveToBackground: () -> Unit,
)

/**
 * One primary flow: destination → route → ride. The stack is derived from the
 * model (as on iOS), so system Back runs the same cleanup as the on-screen
 * controls. Device, maps, gateway and privacy pages are overlays.
 */
@Composable
fun MotoApp(model: AppModel, platform: PlatformActions) {
    var overlay by rememberSaveable { mutableStateOfOverlay() }
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val screen = when {
        model.isNavigationActive -> MainScreen.ACTIVE_NAVIGATION
        model.selectedPlace != null -> MainScreen.ROUTE_PREVIEW
        else -> MainScreen.HOME
    }
    LaunchedEffect(screen) { if (screen != MainScreen.HOME) focusManager.clearFocus() }

    val actions = ScreenActions(
        openDevice = { focusManager.clearFocus(); overlay = Overlay.DEVICE },
        openMaps = { overlay = Overlay.MAPS },
        openGateway = { overlay = Overlay.GATEWAY },
        openDataUse = { overlay = Overlay.DATA_USE },
        openAppSettings = platform.openAppSettings,
        requestLocation = platform.requestLocation,
    )

    // A ride keeps running in the background; Back never ends it silently.
    BackHandler(enabled = overlay == null && screen == MainScreen.ACTIVE_NAVIGATION) { platform.moveToBackground() }
    BackHandler(enabled = overlay == null && screen == MainScreen.ROUTE_PREVIEW) { model.clearDestination() }
    BackHandler(enabled = overlay != null) { overlay = null }

    AnimatedContent(
        targetState = overlay to screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { (currentOverlay, currentScreen) ->
        when (currentOverlay) {
            Overlay.DEVICE -> DeviceScreen(
                model,
                DeviceActions(
                    requestBluetooth = platform.requestBluetooth,
                    openBluetoothSettings = platform.openBluetoothSettings,
                    requestLocation = platform.requestLocation,
                    openNotificationAccess = platform.openNotificationAccess,
                ),
                onBack = { overlay = null },
            )
            Overlay.MAPS -> MapDownloadsScreen(model) { overlay = null }
            Overlay.GATEWAY -> GatewaySettingsScreen(model) { overlay = null }
            Overlay.DATA_USE -> DataUseScreen { overlay = null }
            null -> when (currentScreen) {
                MainScreen.HOME -> HomeScreen(model, actions, searchFocus)
                MainScreen.ROUTE_PREVIEW -> RoutePreviewScreen(model, actions) {
                    model.clearDestination()
                }
                MainScreen.ACTIVE_NAVIGATION -> ActiveNavigationScreen(model, actions)
            }
        }
    }
}

private fun mutableStateOfOverlay() = androidx.compose.runtime.mutableStateOf<Overlay?>(null)
