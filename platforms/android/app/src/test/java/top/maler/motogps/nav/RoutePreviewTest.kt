package top.maler.motogps.nav

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.model.RouteEnvelope
import top.maler.motogps.model.RoutePlan
import top.maler.motogps.model.RouteRequest
import top.maler.motogps.model.TrafficLevel
import top.maler.motogps.model.TrafficSegment
import top.maler.motogps.model.WGS84Point

class RoutePreviewTest {
    private fun plan(
        id: String = "route-a",
        distance: Double = 12_345.0,
        duration: Int = 1_530,
        traffic: List<TrafficSegment> = emptyList(),
        polyline: List<GCJ02Point> = listOf(GCJ02Point(117.0, 36.0), GCJ02Point(117.1, 36.1)),
    ) = RoutePlan(
        routeId = id, provider = "test", generatedAtMs = 1, totalDistanceM = distance, totalDurationS = duration,
        polyline = polyline, maneuvers = emptyList(), traffic = traffic,
    )

    private val origin = WGS84Point(longitudeDeg = 117.0, latitudeDeg = 36.0)

    @Test
    fun candidatePresentsDistanceDurationAndCongestion() {
        val congested = RoutePreviewCandidate(
            0,
            plan(traffic = listOf(TrafficSegment(0.0, 2_000.0, TrafficLevel.CONGESTED))),
        )
        assertEquals("推荐路线", congested.title)
        assertEquals("12.3 km", congested.distanceText)
        assertEquals("26 分钟", congested.durationText)
        assertEquals(TrafficSummary.HEAVY, congested.trafficSummary)

        val slow = RoutePreviewCandidate(1, plan(duration = 4_000, traffic = listOf(TrafficSegment(0.0, 2_500.0, TrafficLevel.SLOW))))
        assertEquals("备选路线一", slow.title)
        assertEquals("1 小时 7 分", slow.durationText)
        assertEquals(TrafficSummary.PARTIAL, slow.trafficSummary)
        assertEquals(TrafficSummary.NONE, RoutePreviewCandidate(2, plan()).trafficSummary)
        assertEquals("850 m", RoutePreviewCandidate(2, plan(distance = 850.0)).distanceText)
    }

    @Test
    fun selectedRouteIsSuppliedOnlyForInitialRequest() = runBlocking {
        var liveCalls = 0
        val selected = plan(id = "selected")
        val provider = PreviewSelectedRouteProvider(selected, origin, NavigationRouteProviding { request ->
            liveCalls += 1
            RouteEnvelope(requestId = request.requestId, route = plan(id = "live"))
        })
        val first = provider.route(RouteRequest(requestId = 1, origin = origin, destination = origin))
        assertEquals("selected", first.route.routeId)
        assertEquals(1L, first.requestId)
        val reroute = provider.route(RouteRequest(requestId = 2, origin = origin, destination = origin, isReroute = true))
        assertEquals("live", reroute.route.routeId)
        val second = provider.route(RouteRequest(requestId = 3, origin = origin, destination = origin))
        assertEquals("live", second.route.routeId)
        assertEquals(2, liveCalls)
    }

    @Test
    fun selectedRouteIsRejectedWhenLiveOriginMovedAwayFromPreview() = runBlocking {
        val provider = PreviewSelectedRouteProvider(plan(id = "selected"), origin, NavigationRouteProviding { request ->
            RouteEnvelope(requestId = request.requestId, route = plan(id = "live"))
        })
        val moved = WGS84Point(longitudeDeg = 117.002, latitudeDeg = 36.0)
        assertTrue(PreviewSelectedRouteProvider.distanceM(origin, moved) > 120)
        assertEquals("live", provider.route(RouteRequest(requestId = 1, origin = moved, destination = origin)).route.routeId)
    }

    @Test
    fun trafficRefreshRequiresTheSameCompleteRouteBaseline() {
        val current = plan()
        assertTrue(TrafficRefreshPolicy.sharesCompleteRouteBaseline(current, current.copy(totalDistanceM = 12_360.0)))
        assertFalse(TrafficRefreshPolicy.sharesCompleteRouteBaseline(current, current.copy(totalDistanceM = 12_500.0)))
        assertFalse(TrafficRefreshPolicy.sharesCompleteRouteBaseline(current, current.copy(polyline = current.polyline.reversed())))
        assertFalse(TrafficRefreshPolicy.sharesCompleteRouteBaseline(current, current.copy(coordinateSystem = "WGS84")))
        assertEquals(500L, TrafficRefreshPolicy.remainingDurationSeconds(1_000, 5_000.0, 10_000.0))
        assertEquals(0L, TrafficRefreshPolicy.remainingDurationSeconds(1_000, 5_000.0, 0.0))
        assertEquals(1_000L, TrafficRefreshPolicy.remainingDurationSeconds(1_000, 50_000.0, 10_000.0))
    }

    @Test
    fun formattingMatchesTheIosCopy() {
        assertEquals("--", formatDistance(0.0))
        assertEquals("1.5 km", formatDistance(1_500.0))
        assertEquals("--", formatDuration(0))
        assertEquals("1分钟", formatDuration(20))
        assertEquals("1时5分", formatDuration(3_900))
    }
}
