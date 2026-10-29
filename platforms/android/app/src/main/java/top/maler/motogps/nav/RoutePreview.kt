package top.maler.motogps.nav

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import top.maler.motogps.model.RouteEnvelope
import top.maler.motogps.model.RoutePlan
import top.maler.motogps.model.RouteRequest
import top.maler.motogps.model.TrafficLevel
import top.maler.motogps.model.WGS84Point

enum class TrafficSummary(val text: String) {
    NONE("暂无分段路况"),
    HEAVY("拥堵较多"),
    PARTIAL("部分路段缓行"),
    CLEAR("路况顺畅"),
}

data class RoutePreviewCandidate(val ordinal: Int, val route: RoutePlan) {
    val id: String get() = "${route.routeId}#$ordinal"

    val title: String
        get() = when (ordinal) {
            0 -> "推荐路线"
            1 -> "备选路线一"
            else -> "备选路线二"
        }

    val distanceText: String get() = formatDistance(route.totalDistanceM)

    val durationText: String
        get() {
            val minutes = max(1, (route.totalDurationS / 60.0).roundToInt())
            return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分钟"
        }

    val trafficSummary: TrafficSummary
        get() {
            if (route.traffic.isEmpty() || route.totalDistanceM <= 0) return TrafficSummary.NONE
            var slow = 0.0
            var congested = 0.0
            for (segment in route.traffic) {
                val length = max(0.0, segment.endOffsetM - segment.startOffsetM)
                when (segment.level) {
                    TrafficLevel.SLOW -> slow += length
                    TrafficLevel.CONGESTED, TrafficLevel.SEVERE -> congested += length
                    TrafficLevel.UNKNOWN, TrafficLevel.FREE_FLOW -> Unit
                }
            }
            val congestedShare = congested / route.totalDistanceM
            val affectedShare = (slow + congested) / route.totalDistanceM
            return when {
                congestedShare >= 0.12 -> TrafficSummary.HEAVY
                affectedShare >= 0.18 -> TrafficSummary.PARTIAL
                else -> TrafficSummary.CLEAR
            }
        }
}

fun formatDistance(meters: Double): String = when {
    !meters.isFinite() || meters <= 0 -> "--"
    meters >= 1_000 -> String.format(Locale.US, "%.1f km", meters / 1_000)
    else -> "${meters.roundToInt()} m"
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "--"
    val minutes = max(1, (seconds / 60.0).roundToInt())
    return if (minutes >= 60) "${minutes / 60}时${minutes % 60}分" else "${minutes}分钟"
}

/**
 * Supplies the exact route selected on the preview screen to NavCore's first
 * route request. Reroutes and traffic refreshes keep using the live provider.
 */
class PreviewSelectedRouteProvider(
    private val selectedRoute: RoutePlan,
    private val selectedRouteOrigin: WGS84Point,
    private val liveProvider: NavigationRouteProviding,
    private val maximumOriginDriftM: Double = 120.0,
) : NavigationRouteProviding {
    private val didSupplySelectedRoute = AtomicBoolean(false)

    override suspend fun route(request: RouteRequest): RouteEnvelope {
        val eligible = !request.isReroute && request.previousRouteId == null
        if (eligible && didSupplySelectedRoute.compareAndSet(false, true) &&
            distanceM(selectedRouteOrigin, request.origin) <= maximumOriginDriftM
        ) {
            return RouteEnvelope(requestId = request.requestId, route = selectedRoute)
        }
        return liveProvider.route(request)
    }

    companion object {
        fun distanceM(start: WGS84Point, end: WGS84Point): Double {
            val earthRadiusM = 6_371_000.0
            val startLatitude = Math.toRadians(start.latitudeDeg)
            val endLatitude = Math.toRadians(end.latitudeDeg)
            val latitudeDelta = Math.toRadians(end.latitudeDeg - start.latitudeDeg)
            val longitudeDelta = Math.toRadians(end.longitudeDeg - start.longitudeDeg)
            val haversine = sin(latitudeDelta / 2).pow(2) +
                cos(startLatitude) * cos(endLatitude) * sin(longitudeDelta / 2).pow(2)
            return 2 * earthRadiusM * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        }
    }
}
