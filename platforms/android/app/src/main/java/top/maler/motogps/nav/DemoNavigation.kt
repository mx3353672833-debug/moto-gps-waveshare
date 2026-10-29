package top.maler.motogps.nav

import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.maler.motogps.model.ChinaCoordinateTransform
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.model.ManeuverType
import top.maler.motogps.model.NavigationFix
import top.maler.motogps.model.RouteEnvelope
import top.maler.motogps.model.RouteManeuver
import top.maler.motogps.model.RoutePlan
import top.maler.motogps.model.RouteRequest
import top.maler.motogps.model.WGS84Point

/**
 * The checked-in OSM/ODbL Jinan demo route (shared/demo_fixture). Loaded from
 * the bundled JSON so Android never carries a second hand-copied route.
 */
class JinanDemoFixture private constructor(document: Document) {
    @Serializable
    private data class Document(
        @SerialName("route_id") val routeId: String,
        @SerialName("destination_poi_id") val destinationPoiId: String,
        @SerialName("fallback_duration_s") val fallbackDurationS: Int,
        @SerialName("requested_origin_wgs84") val requestedOrigin: List<Double>,
        @SerialName("requested_destination_wgs84") val requestedDestination: List<Double>,
        @SerialName("route_wgs84") val route: List<List<Double>>,
        val maneuvers: List<ManeuverDocument>,
    )

    @Serializable
    private data class ManeuverDocument(
        val id: Long,
        val type: ManeuverType,
        @SerialName("route_point_index") val routePointIndex: Int,
        @SerialName("road_name") val roadName: String,
        val instruction: String,
    )

    val routeId = document.routeId
    val reroutedRouteId = "${document.routeId}-rerouted"
    val destinationPoiId = document.destinationPoiId
    val fallbackDurationS = document.fallbackDurationS
    val requestedOriginWgs84 = WGS84Point(longitudeDeg = document.requestedOrigin[1], latitudeDeg = document.requestedOrigin[0])
    val requestedDestinationWgs84 =
        WGS84Point(longitudeDeg = document.requestedDestination[1], latitudeDeg = document.requestedDestination[0])
    val routeWgs84 = document.route.map { WGS84Point(longitudeDeg = it[1], latitudeDeg = it[0]) }
    val routeGcj02 = routeWgs84.map(ChinaCoordinateTransform::wgs84ToGcj02)

    private val maneuverFixtures = document.maneuvers

    fun fallbackRoute(rerouted: Boolean, generatedAtMs: Long): RoutePlan {
        val cumulative = cumulativeDistances(routeGcj02)
        return RoutePlan(
            routeId = if (rerouted) reroutedRouteId else routeId,
            provider = FALLBACK_PROVIDER,
            generatedAtMs = generatedAtMs,
            totalDistanceM = cumulative.lastOrNull() ?: 0.0,
            totalDurationS = fallbackDurationS,
            polyline = routeGcj02,
            maneuvers = maneuverFixtures.map {
                RouteManeuver(
                    id = it.id,
                    type = it.type,
                    routeOffsetM = cumulative[it.routePointIndex.coerceIn(0, cumulative.lastIndex)],
                    roadName = it.roadName,
                    instruction = it.instruction,
                )
            },
            traffic = emptyList(),
        )
    }

    companion object {
        const val ASSET_NAME = "jinan_big_data_to_inspur.json"
        const val FALLBACK_PROVIDER = "openstreetmap-demo-fallback"
        const val ROAD_ATTRIBUTION = "道路 © OpenStreetMap contributors"
        private val fixtureJson = Json { ignoreUnknownKeys = true }

        fun parse(json: String): JinanDemoFixture =
            JinanDemoFixture(fixtureJson.decodeFromString(Document.serializer(), json))

        private fun cumulativeDistances(points: List<GCJ02Point>): List<Double> {
            val output = MutableList(points.size) { 0.0 }
            for (index in 1 until points.size) {
                output[index] = output[index - 1] + distanceM(
                    points[index - 1].latitudeDeg, points[index - 1].longitudeDeg,
                    points[index].latitudeDeg, points[index].longitudeDeg,
                )
            }
            return output
        }

        internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusM = 6_371_000.0
            val latitude = Math.toRadians((lat1 + lat2) * 0.5)
            val northM = Math.toRadians(lat2 - lat1) * earthRadiusM
            val eastM = Math.toRadians(lon2 - lon1) * cos(latitude) * earthRadiusM
            return hypot(eastM, northM)
        }
    }
}

internal class DemoDrivePath(val points: List<WGS84Point>, val cumulativeDistancesM: List<Double>) {
    val distanceM: Double get() = cumulativeDistancesM.last()

    fun point(offsetM: Double): WGS84Point {
        val offset = offsetM.coerceIn(0.0, distanceM)
        val upperIndex = cumulativeDistancesM.indexOfFirst { it > offset }
        if (upperIndex < 0) return points.last()
        if (upperIndex == 0) return points.first()
        val lowerIndex = upperIndex - 1
        val segment = cumulativeDistancesM[upperIndex] - cumulativeDistancesM[lowerIndex]
        val fraction = if (segment > 0) (offset - cumulativeDistancesM[lowerIndex]) / segment else 0.0
        val start = points[lowerIndex]
        val end = points[upperIndex]
        return WGS84Point(
            longitudeDeg = start.longitudeDeg + (end.longitudeDeg - start.longitudeDeg) * fraction,
            latitudeDeg = start.latitudeDeg + (end.latitudeDeg - start.latitudeDeg) * fraction,
        )
    }

    companion object {
        fun of(points: List<WGS84Point>): DemoDrivePath? {
            if (points.size < 2) return null
            val cumulative = MutableList(points.size) { 0.0 }
            for (index in 1 until points.size) {
                cumulative[index] = cumulative[index - 1] + JinanDemoFixture.distanceM(
                    points[index - 1].latitudeDeg, points[index - 1].longitudeDeg,
                    points[index].latitudeDeg, points[index].longitudeDeg,
                )
            }
            if (cumulative.last() <= 1) return null
            return DemoDrivePath(points, cumulative)
        }
    }
}

/**
 * Couples the demo route provider to the simulated GNSS source. The source
 * emits only the initial fix until the provider returns a route, then follows
 * that exact returned polyline by travelled metres.
 */
class DemoNavigationSession(private val fixture: JinanDemoFixture) {
    internal var drivePath: DemoDrivePath? = null
        private set
    internal var routeRevision = 0
        private set

    fun install(route: RoutePlan) {
        val points = if (route.provider == JinanDemoFixture.FALLBACK_PROVIDER) {
            fixture.routeWgs84
        } else {
            route.polyline.map(ChinaCoordinateTransform::gcj02ToWgs84)
        }
        drivePath = DemoDrivePath.of(points) ?: return
        routeRevision += 1
    }
}

class DemoNavigationLocationSource(
    private val scope: CoroutineScope,
    private val session: DemoNavigationSession,
    private val fixture: JinanDemoFixture,
) : NavigationLocationSource {
    private var job: Job? = null

    override fun start(onFix: (NavigationFix) -> Unit, onFailure: (String) -> Unit) {
        stop()
        // This first real POI coordinate triggers the route request. Movement
        // waits for the provider to install the returned geometry.
        emit(fixture.requestedOriginWgs84, heading = 0.0, speedMps = 0.0, onFix)
        job = scope.launch {
            var observedRevision = session.routeRevision
            var startedAt: Long? = null
            while (isActive) {
                val path = session.drivePath
                if (path == null) {
                    delay(40)
                    continue
                }
                if (startedAt == null || observedRevision != session.routeRevision) {
                    observedRevision = session.routeRevision
                    startedAt = SystemClock.elapsedRealtime()
                }
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1_000.0
                val routeOffsetM = minOf(path.distanceM, elapsed * SPEED_MPS)
                val point = path.point(routeOffsetM)
                val heading = bearing(path.point(routeOffsetM - 11.0), path.point(routeOffsetM + 11.0))
                val isFinal = routeOffsetM >= path.distanceM
                emit(point, heading, if (isFinal) 0.0 else SPEED_MPS, onFix)
                if (isFinal) {
                    // Shared NavCore confirms arrival using consecutive fixes.
                    delay(40)
                    emit(point, heading, 0.0, onFix)
                    return@launch
                }
                val nextFrame = floor(elapsed * FRAME_RATE_HZ) + 1
                val nextDeadline = startedAt + (nextFrame * 1_000 / FRAME_RATE_HZ).toLong()
                val wait = nextDeadline - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait) else yield()
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun emit(point: WGS84Point, heading: Double, speedMps: Double, onFix: (NavigationFix) -> Unit) {
        onFix(
            NavigationFix(
                coordinate = point,
                horizontalAccuracyM = 3.5,
                speedMps = speedMps,
                courseDeg = heading,
                timestampMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun bearing(start: WGS84Point, end: WGS84Point): Double {
        val latitudeA = Math.toRadians(start.latitudeDeg)
        val latitudeB = Math.toRadians(end.latitudeDeg)
        val longitudeDelta = Math.toRadians(end.longitudeDeg - start.longitudeDeg)
        val y = sin(longitudeDelta) * cos(latitudeB)
        val x = cos(latitudeA) * sin(latitudeB) - sin(latitudeA) * cos(latitudeB) * cos(longitudeDelta)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private companion object {
        const val FRAME_RATE_HZ = 25.0
        const val SPEED_MPS = 12.0
    }
}

/**
 * Uses the configured gateway for every demo route request. Without a gateway
 * or network it falls back to the checked-in OSM route, so the hardware demo
 * stays usable without persisting an AMap response in source.
 */
class DemoNavigationRouteProvider(
    private val liveProvider: NavigationRouteProviding?,
    private val session: DemoNavigationSession,
    private val fixture: JinanDemoFixture,
) : NavigationRouteProviding {
    override suspend fun route(request: RouteRequest): RouteEnvelope {
        val shouldMoveAlongResult = request.previousRouteId == null || request.isReroute
        val route = try {
            val live = liveProvider ?: throw IllegalStateException("no gateway")
            val envelope = live.route(request)
            envelope.route.copy(
                routeId = if (request.isReroute) fixture.reroutedRouteId else fixture.routeId,
                provider = "amap-gateway-demo-live",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fixture.fallbackRoute(request.isReroute, System.currentTimeMillis())
        }
        if (shouldMoveAlongResult) session.install(route)
        return RouteEnvelope(requestId = request.requestId, route = route)
    }
}
