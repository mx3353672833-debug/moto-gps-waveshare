package top.maler.motogps.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Raw platform GNSS output. Never passed to route matching without conversion. */
@Serializable
data class WGS84Point(
    @SerialName("coordinate_system") val coordinateSystem: String = "WGS84",
    @SerialName("longitude_deg") val longitudeDeg: Double,
    @SerialName("latitude_deg") val latitudeDeg: Double,
) {
    val isValid: Boolean
        get() = longitudeDeg.isFinite() && latitudeDeg.isFinite() &&
            longitudeDeg in -180.0..180.0 && latitudeDeg in -90.0..90.0
}

/** Provider (AMap) coordinates. Route geometry and round-screen map data use this system. */
@Serializable
data class GCJ02Point(
    @SerialName("longitude_deg") val longitudeDeg: Double,
    @SerialName("latitude_deg") val latitudeDeg: Double,
)

data class NavigationFix(
    val coordinate: WGS84Point,
    val altitudeM: Double? = null,
    val horizontalAccuracyM: Double,
    val speedMps: Double? = null,
    val courseDeg: Double? = null,
    val timestampMs: Long,
)

@Serializable
data class RouteRequest(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("request_id") val requestId: Long,
    @SerialName("route_mode") val routeMode: String = "driving",
    val origin: WGS84Point,
    val destination: WGS84Point,
    @SerialName("is_reroute") val isReroute: Boolean = false,
    @SerialName("previous_route_id") val previousRouteId: String? = null,
    @SerialName("destination_poi_id") val destinationPoiId: String? = null,
)

@Serializable
enum class ManeuverType {
    @SerialName("unknown") UNKNOWN,
    @SerialName("continue") CONTINUE,
    @SerialName("slight_left") SLIGHT_LEFT,
    @SerialName("left") LEFT,
    @SerialName("sharp_left") SHARP_LEFT,
    @SerialName("u_turn_left") U_TURN_LEFT,
    @SerialName("slight_right") SLIGHT_RIGHT,
    @SerialName("right") RIGHT,
    @SerialName("sharp_right") SHARP_RIGHT,
    @SerialName("u_turn_right") U_TURN_RIGHT,
    @SerialName("roundabout") ROUNDABOUT,
    @SerialName("exit") EXIT,
    @SerialName("arrive") ARRIVE;

    /** The shared C++ / wire name, identical to the JSON value. */
    val wireName: String get() = name.lowercase()
}

@Serializable
data class RouteManeuver(
    val id: Long,
    val type: ManeuverType,
    @SerialName("route_offset_m") val routeOffsetM: Double,
    @SerialName("road_name") val roadName: String,
    val instruction: String,
    @SerialName("roundabout_exit") val roundaboutExit: Int = 0,
)

@Serializable
enum class TrafficLevel {
    @SerialName("unknown") UNKNOWN,
    @SerialName("free_flow") FREE_FLOW,
    @SerialName("slow") SLOW,
    @SerialName("congested") CONGESTED,
    @SerialName("severe") SEVERE;

    val wireName: String get() = name.lowercase()
}

@Serializable
data class TrafficSegment(
    @SerialName("start_offset_m") val startOffsetM: Double,
    @SerialName("end_offset_m") val endOffsetM: Double,
    val level: TrafficLevel,
)

@Serializable
data class RoutePlan(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("route_id") val routeId: String,
    val provider: String,
    @SerialName("coordinate_system") val coordinateSystem: String = "GCJ-02",
    @SerialName("generated_at_ms") val generatedAtMs: Long,
    @SerialName("total_distance_m") val totalDistanceM: Double,
    @SerialName("total_duration_s") val totalDurationS: Int,
    val polyline: List<GCJ02Point>,
    val maneuvers: List<RouteManeuver>,
    val traffic: List<TrafficSegment>,
)

@Serializable
data class RouteEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("request_id") val requestId: Long,
    val route: RoutePlan,
)

@Serializable
data class RouteOptionsEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("request_id") val requestId: Long,
    val routes: List<RoutePlan>,
)

@Serializable
data class PlaceSearchResult(
    val id: String,
    val name: String,
    val address: String = "",
    val city: String = "",
    val district: String = "",
    @SerialName("display_area") val displayArea: String = "",
    val location: WGS84Point,
    @SerialName("distance_m") val distanceM: Double? = null,
)

@Serializable
data class PlaceSearchEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int,
    val query: String = "",
    val places: List<PlaceSearchResult>,
)

@Serializable
data class GatewayFailureEnvelope(val error: GatewayFailure)

@Serializable
data class GatewayFailure(val code: String, val message: String)
