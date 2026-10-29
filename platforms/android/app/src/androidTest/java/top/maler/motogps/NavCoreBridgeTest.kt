package top.maler.motogps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.maler.motogps.core.NavCoreBridge
import top.maler.motogps.nav.JinanDemoFixture

/** Drives the real shared NavApp through JNI with the checked-in Jinan route. */
@RunWith(AndroidJUnit4::class)
class NavCoreBridgeTest {
    private val fixture = InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open(JinanDemoFixture.ASSET_NAME).bufferedReader().use { JinanDemoFixture.parse(it.readText()) }

    @Test
    fun routeRequestAcceptanceProgressAndArrival() {
        NavCoreBridge().use { core ->
            core.reset()
            core.setNetworkState("online")
            val destination = fixture.requestedDestinationWgs84
            assertTrue(core.beginNavigation(destination.longitudeDeg, destination.latitudeDeg).isEmpty())
            assertEquals("acquiring", core.snapshot.stateName)

            var now = 1_800_000_000_000L
            val start = fixture.routeWgs84.first()
            val commands = core.pushFix(start.longitudeDeg, start.latitudeDeg, 3.5, 0.0, 0.0, now)
            val request = commands.single()
            assertEquals("request_route", request.typeName)
            assertEquals(start.latitudeDeg, request.originLatitudeDeg, 1e-9)
            assertEquals(destination.longitudeDeg, request.destinationLongitudeDeg, 1e-9)

            val route = fixture.fallbackRoute(rerouted = false, generatedAtMs = now)
            core.acceptRoute(route, request.requestId, now)
            assertEquals("navigating", core.snapshot.stateName)
            assertEquals(route.routeId, core.snapshot.routeId)
            assertTrue(core.snapshot.hasNextManeuver)
            assertTrue(core.snapshot.remainingDistanceM > 0)
            val initialRemaining = core.snapshot.remainingDistanceM

            // Ride along the route: progress must increase and the route window must follow.
            for (point in fixture.routeWgs84.drop(1).take(10)) {
                now += 1_500
                core.pushFix(point.longitudeDeg, point.latitudeDeg, 3.5, 10.0, 180.0, now)
            }
            assertEquals("navigating", core.snapshot.stateName)
            assertTrue(core.snapshot.remainingDistanceM < initialRemaining)
            assertTrue(core.snapshot.hasRouteView)
            assertTrue(core.snapshot.routeViewPoints.size in 2..24)

            for (point in fixture.routeWgs84.drop(11)) {
                now += 1_500
                core.pushFix(point.longitudeDeg, point.latitudeDeg, 3.5, 10.0, 180.0, now)
            }
            val last = fixture.routeWgs84.last()
            repeat(3) {
                now += 1_000
                core.pushFix(last.longitudeDeg, last.latitudeDeg, 3.5, 0.0, 180.0, now)
            }
            assertEquals("arrived", core.snapshot.stateName)

            core.cancelNavigation()
            assertEquals("idle", core.snapshot.stateName)
        }
    }

    @Test
    fun displayPageSelectionIsOwnedByTheCore() {
        NavCoreBridge().use { core ->
            core.selectDisplayPage("music")
            assertEquals("music", core.snapshot.displayPageName)
            core.selectDisplayPage("speed")
            assertEquals("speed", core.snapshot.displayPageName)
        }
    }
}
