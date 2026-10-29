package top.maler.motogps.nav

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import top.maler.motogps.gateway.GatewayClient
import top.maler.motogps.model.NavigationFix
import top.maler.motogps.model.RouteEnvelope
import top.maler.motogps.model.RoutePlan
import top.maler.motogps.model.RouteRequest

fun interface NavigationRouteProviding {
    suspend fun route(request: RouteRequest): RouteEnvelope
}

/** Location input for one navigation session. All callbacks arrive on the main thread. */
interface NavigationLocationSource {
    /** Throws [NavigationSourceException] when the source cannot start at all. */
    fun start(onFix: (NavigationFix) -> Unit, onFailure: (String) -> Unit)
    fun stop()
}

class NavigationSourceException(message: String) : Exception(message)

class GatewayRouteProvider(private val client: GatewayClient) : NavigationRouteProviding {
    override suspend fun route(request: RouteRequest): RouteEnvelope = client.route(request)
}

object TrafficRefreshPolicy {
    /**
     * Traffic offsets are measured from the beginning of the complete route.
     * They may only replace the current route's traffic when both responses use
     * exactly the same GCJ-02 geometry and effectively the same route length.
     */
    fun sharesCompleteRouteBaseline(current: RoutePlan, refreshed: RoutePlan): Boolean {
        if (current.coordinateSystem != "GCJ-02" ||
            refreshed.coordinateSystem != current.coordinateSystem ||
            refreshed.polyline != current.polyline
        ) {
            return false
        }
        val toleranceM = max(5.0, current.totalDistanceM * 0.002)
        return abs(refreshed.totalDistanceM - current.totalDistanceM) <= toleranceM
    }

    /** Scales the complete-route duration to the rider's remaining fraction. */
    fun remainingDurationSeconds(
        refreshedCompleteDurationS: Int,
        remainingDistanceM: Double,
        completeDistanceM: Double,
    ): Long {
        if (refreshedCompleteDurationS <= 0 || !remainingDistanceM.isFinite() ||
            !completeDistanceM.isFinite() || completeDistanceM <= 0
        ) {
            return 0
        }
        val fraction = (remainingDistanceM / completeDistanceM).coerceIn(0.0, 1.0)
        return (refreshedCompleteDurationS * fraction).roundToLong().coerceIn(0, 4_294_967_295)
    }
}
