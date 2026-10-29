package top.maler.motogps.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.maler.motogps.model.RoutePlan
import top.maler.motogps.model.TrafficSegment

/** One immutable NavCore projection. Mirrors MotoNavCoreSnapshot on iOS. */
@Serializable
data class NavCoreSnapshot(
    val stateName: String = "idle",
    val networkName: String = "offline",
    val displayPageName: String = "navigation",
    val hasDestination: Boolean = false,
    val hasUsableFix: Boolean = false,
    val gnssStale: Boolean = true,
    val offRoute: Boolean = false,
    val routeRequestInFlight: Boolean = false,
    val trafficRequestInFlight: Boolean = false,
    val positionLatitudeDeg: Double = 0.0,
    val positionLongitudeDeg: Double = 0.0,
    val speedMps: Double = 0.0,
    val headingDeg: Double = 0.0,
    val horizontalAccuracyM: Double = 0.0,
    val crossTrackDistanceM: Double = 0.0,
    val speedLimitKph: Int = 0,
    val hasRouteView: Boolean = false,
    val routeViewOriginLatitudeDeg: Double = 0.0,
    val routeViewOriginLongitudeDeg: Double = 0.0,
    /** GCJ-02 [latitude, longitude] pairs, at most 24. */
    val routeViewPoints: List<List<Double>> = emptyList(),
    val routeId: String = "",
    val routeProgressM: Double = 0.0,
    val totalDistanceM: Double = 0.0,
    val remainingDistanceM: Double = 0.0,
    val remainingDurationS: Long = 0,
    val hasNextManeuver: Boolean = false,
    val maneuverId: Long = 0,
    val maneuverTypeName: String = "unknown",
    val distanceToManeuverM: Double = 0.0,
    val roadName: String = "",
    val instructionText: String = "",
    val roundaboutExit: Int = 0,
    val trafficName: String = "unknown",
    val nowMs: Long = 0,
    val routeGeneration: Long = 0,
)

@Serializable
data class NavCoreCommand(
    val typeName: String,
    val requestId: Long,
    val originLatitudeDeg: Double,
    val originLongitudeDeg: Double,
    val destinationLatitudeDeg: Double,
    val destinationLongitudeDeg: Double,
    val reroute: Boolean,
    val routeId: String,
)

@Serializable
private data class NavCoreResult(
    val snapshot: NavCoreSnapshot,
    val commands: List<NavCoreCommand>,
)

/**
 * Owns the real shared NavApp/NavCore. Kotlin executes returned network
 * commands, but never reproduces route matching, rerouting or progression.
 * Not thread-safe: use from the main thread only.
 */
class NavCoreBridge : AutoCloseable {
    private var handle: Long = MotoNative.navCreate()

    var snapshot: NavCoreSnapshot = decode(MotoNative.navSnapshot(handle)).snapshot
        private set

    fun reset() = apply(MotoNative.navReset(liveHandle()))

    fun beginNavigation(longitudeDeg: Double, latitudeDeg: Double) =
        apply(MotoNative.navBegin(liveHandle(), longitudeDeg, latitudeDeg))

    fun cancelNavigation() = apply(MotoNative.navCancel(liveHandle()))

    fun setNetworkState(name: String) = apply(MotoNative.navSetNetwork(liveHandle(), name.utf8()))

    fun selectDisplayPage(name: String) = apply(MotoNative.navSelectPage(liveHandle(), name.utf8()))

    fun pushFix(
        longitudeDeg: Double,
        latitudeDeg: Double,
        accuracyM: Double,
        speedMps: Double,
        headingDeg: Double,
        timestampMs: Long,
    ) = apply(
        MotoNative.navPushFix(liveHandle(), longitudeDeg, latitudeDeg, accuracyM, speedMps, headingDeg, timestampMs),
    )

    fun acceptRoute(route: RoutePlan, requestId: Long, receivedAtMs: Long): List<NavCoreCommand> {
        val polyline = DoubleArray(route.polyline.size * 2)
        route.polyline.forEachIndexed { index, point ->
            polyline[index * 2] = point.latitudeDeg
            polyline[index * 2 + 1] = point.longitudeDeg
        }
        val maneuvers = route.maneuvers
        return apply(
            MotoNative.navAcceptRoute(
                liveHandle(),
                requestId,
                route.routeId.utf8(),
                polyline,
                LongArray(maneuvers.size) { maneuvers[it].id },
                Array(maneuvers.size) { maneuvers[it].type.wireName.utf8() },
                DoubleArray(maneuvers.size) { maneuvers[it].routeOffsetM },
                Array(maneuvers.size) { maneuvers[it].roadName.utf8() },
                Array(maneuvers.size) { maneuvers[it].instruction.utf8() },
                IntArray(maneuvers.size) { maneuvers[it].roundaboutExit.coerceIn(0, 255) },
                DoubleArray(route.traffic.size) { route.traffic[it].startOffsetM },
                DoubleArray(route.traffic.size) { route.traffic[it].endOffsetM },
                Array(route.traffic.size) { route.traffic[it].level.wireName.utf8() },
                route.totalDistanceM,
                route.totalDurationS.toLong().coerceAtLeast(0),
                0,
                route.generatedAtMs,
                receivedAtMs,
            ),
        )
    }

    fun rejectRoute(requestId: Long, retryable: Boolean, receivedAtMs: Long) =
        apply(MotoNative.navRejectRoute(liveHandle(), requestId, retryable, receivedAtMs))

    fun acceptTraffic(
        routeId: String,
        segments: List<TrafficSegment>,
        remainingDurationS: Long,
        requestId: Long,
        observedAtMs: Long,
    ) = apply(
        MotoNative.navAcceptTraffic(
            liveHandle(),
            routeId.utf8(),
            DoubleArray(segments.size) { segments[it].startOffsetM },
            DoubleArray(segments.size) { segments[it].endOffsetM },
            Array(segments.size) { segments[it].level.wireName.utf8() },
            remainingDurationS,
            requestId,
            observedAtMs,
        ),
    )

    fun rejectTraffic(requestId: Long, receivedAtMs: Long) =
        apply(MotoNative.navRejectTraffic(liveHandle(), requestId, receivedAtMs))

    fun tick(nowMs: Long) = apply(MotoNative.navTick(liveHandle(), nowMs))

    override fun close() {
        if (handle != 0L) {
            MotoNative.navDestroy(handle)
            handle = 0L
        }
    }

    private fun liveHandle(): Long {
        check(handle != 0L) { "NavCoreBridge is closed" }
        return handle
    }

    private fun apply(bytes: ByteArray): List<NavCoreCommand> {
        val result = decode(bytes)
        snapshot = result.snapshot
        return result.commands
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun decode(bytes: ByteArray): NavCoreResult =
            json.decodeFromString(NavCoreResult.serializer(), bytes.toString(Charsets.UTF_8))
    }
}
