package top.maler.motogps.map

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import top.maler.motogps.core.BleMapPoint
import top.maler.motogps.core.BleMapSceneInput
import top.maler.motogps.core.BleMapShape

/** GCJ-02 degrees * 1e6, serialized as a two-element [lat, lon] array. */
@Serializable(with = OfflineMapPointE6Serializer::class)
data class OfflineMapPointE6(val latitudeE6: Int, val longitudeE6: Int)

object OfflineMapPointE6Serializer : KSerializer<OfflineMapPointE6> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = listSerialDescriptor<Int>()

    override fun deserialize(decoder: Decoder): OfflineMapPointE6 {
        val array = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("MapScene point must be a JSON array")
        if (array.size != 2) throw SerializationException("MapScene point must contain exactly latitudeE6 and longitudeE6")
        return OfflineMapPointE6((array[0] as JsonPrimitive).int, (array[1] as JsonPrimitive).int)
    }

    override fun serialize(encoder: Encoder, value: OfflineMapPointE6) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
        json.encodeJsonElement(JsonArray(listOf(JsonPrimitive(value.latitudeE6), JsonPrimitive(value.longitudeE6))))
    }
}

@Serializable
data class OfflineMapRoad(
    @SerialName("osm_way_id") val osmWayId: Long? = null,
    @SerialName("class") val roadClass: String,
    @SerialName("points_e6") val points: List<OfflineMapPointE6>,
)

@Serializable
data class OfflineMapBuilding(
    @SerialName("osm_way_id") val osmWayId: Long? = null,
    val name: String? = null,
    @SerialName("class") val buildingClass: String,
    @SerialName("points_e6") val points: List<OfflineMapPointE6>,
)

@Serializable
data class OfflineMapSource(
    val provider: String = "",
    val licence: String = "",
    @SerialName("attribution_url") val attributionUrl: String = "",
    @SerialName("retrieved_at") val retrievedAt: String = "",
    @SerialName("bbox_wgs84") val bboxWgs84: List<Double>? = null,
)

@Serializable
data class OfflineMapSceneDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("coordinate_system") val coordinateSystem: String,
    @SerialName("scene_revision") val sceneRevision: Long,
    @SerialName("view_origin_e6") val viewOrigin: OfflineMapPointE6,
    @SerialName("radius_m") val radiusM: Int,
    val roads: List<OfflineMapRoad>,
    val buildings: List<OfflineMapBuilding>,
    val source: OfflineMapSource = OfflineMapSource(),
)

data class OfflineMapSceneWindow(
    val revision: Long,
    val origin: OfflineMapPointE6,
    val radiusM: Int,
    val roads: List<OfflineMapRoad>,
    val buildings: List<OfflineMapBuilding>,
) {
    fun toBleInput() = BleMapSceneInput(
        sceneRevision = revision,
        originLatitudeE6 = origin.latitudeE6,
        originLongitudeE6 = origin.longitudeE6,
        radiusM = radiusM,
        roads = roads.map { road -> BleMapShape(road.roadClass, road.points.map { BleMapPoint(it.latitudeE6, it.longitudeE6) }) },
        buildings = buildings.map { b -> BleMapShape(b.buildingClass, b.points.map { BleMapPoint(it.latitudeE6, it.longitudeE6) }) },
    )
}

fun interface OfflineMapSceneQuerying {
    fun query(origin: OfflineMapPointE6, radiusM: Int, revision: Long): OfflineMapSceneWindow
}

/**
 * Exact circular clip, visual-priority ordering and the BLE MapScene capacity
 * limits (24 roads / 192 points, 16 buildings / 128 points). Port of
 * InMemoryOfflineMapSceneIndex; a SQLite city pack narrows candidates and then
 * uses this selector, so all sources behave identically.
 */
class InMemoryOfflineMapSceneIndex(roads: List<OfflineMapRoad>, buildings: List<OfflineMapBuilding>) : OfflineMapSceneQuerying {
    private class Bounds(points: List<OfflineMapPointE6>) {
        val minimumLatitudeE6 = points.minOfOrNull { it.latitudeE6 } ?: 0
        val maximumLatitudeE6 = points.maxOfOrNull { it.latitudeE6 } ?: 0
        val minimumLongitudeE6 = points.minOfOrNull { it.longitudeE6 } ?: 0
        val maximumLongitudeE6 = points.maxOfOrNull { it.longitudeE6 } ?: 0

        fun intersects(origin: OfflineMapPointE6, radiusM: Double): Boolean {
            val metresPerLatitudeE6 = 111_195.0 / 1_000_000
            val metresPerLongitudeE6 = metresPerLatitudeE6 * cos(Math.toRadians(origin.latitudeE6 / 1_000_000.0))
            val nearestLatitude = origin.latitudeE6.coerceIn(minimumLatitudeE6, maximumLatitudeE6)
            val nearestLongitude = origin.longitudeE6.coerceIn(minimumLongitudeE6, maximumLongitudeE6)
            val northM = (nearestLatitude - origin.latitudeE6) * metresPerLatitudeE6
            val eastM = (nearestLongitude - origin.longitudeE6) * metresPerLongitudeE6
            return hypot(northM, eastM) <= radiusM
        }
    }

    private val indexedRoads = roads.map { it to Bounds(it.points) }
    private val indexedBuildings = buildings.map { it to Bounds(it.points) }

    constructor(document: OfflineMapSceneDocument) : this(document.roads, document.buildings)

    override fun query(origin: OfflineMapPointE6, radiusM: Int, revision: Long): OfflineMapSceneWindow {
        val radius = radiusM.coerceIn(500, 800).toDouble()
        val roadCandidates = indexedRoads
            .filter { (_, bounds) -> bounds.intersects(origin, radius) }
            .flatMap { (road, _) ->
                clippedRuns(road, origin, radius).map { run ->
                    Triple(run, roadImportance(run.roadClass), minimumDistanceM(run.points, origin))
                }
            }
            .sortedWith(
                compareBy<Triple<OfflineMapRoad, Int, Double>>({ it.second }, { it.third }, { it.first.osmWayId ?: Long.MAX_VALUE }),
            )

        val selectedRoads = ArrayList<OfflineMapRoad>()
        var roadPointCount = 0
        for ((road, _, _) in roadCandidates) {
            if (selectedRoads.size >= 24) break
            if (roadPointCount + road.points.size > 192) continue
            selectedRoads += road
            roadPointCount += road.points.size
        }

        data class BuildingCandidate(val building: OfflineMapBuilding, val visualScore: Double, val areaM2: Double)
        val buildingCandidates = indexedBuildings
            .filter { (building, bounds) -> bounds.intersects(origin, radius) && polygonIntersects(building.points, origin, radius) }
            .map { (building, _) ->
                val distance = minimumDistanceM(building.points, origin)
                val area = polygonAreaM2(building.points, origin)
                // Larger footprints stay visible a little farther away, while
                // nearby buildings still dominate the tiny round viewport.
                BuildingCandidate(building, distance - minOf(150.0, sqrt(area) * 0.75), area)
            }
            .sortedWith(
                compareBy<BuildingCandidate>({ it.visualScore }, { -it.areaM2 }, { it.building.osmWayId ?: Long.MAX_VALUE }),
            )

        val selectedBuildings = ArrayList<OfflineMapBuilding>()
        var buildingPointCount = 0
        for (candidate in buildingCandidates) {
            if (selectedBuildings.size >= 16) break
            if (buildingPointCount + candidate.building.points.size > 128) continue
            selectedBuildings += candidate.building
            buildingPointCount += candidate.building.points.size
        }
        return OfflineMapSceneWindow(revision, origin, radius.toInt(), selectedRoads, selectedBuildings)
    }

    companion object {
        private fun clippedRuns(road: OfflineMapRoad, origin: OfflineMapPointE6, radiusM: Double): List<OfflineMapRoad> {
            if (road.points.size < 2) return emptyList()
            val result = ArrayList<OfflineMapRoad>()
            var run = ArrayList<OfflineMapPointE6>()
            for (index in 1 until road.points.size) {
                val start = road.points[index - 1]
                val end = road.points[index]
                if (segmentDistanceM(origin, start, end) <= radiusM) {
                    if (run.isEmpty()) run += start
                    if (run.last() != end) run += end
                } else {
                    if (run.size >= 2) result += road.copy(points = run)
                    run = ArrayList()
                }
            }
            if (run.size >= 2) result += road.copy(points = run)
            return result
        }

        private fun polygonIntersects(points: List<OfflineMapPointE6>, origin: OfflineMapPointE6, radiusM: Double): Boolean {
            if (points.size < 3) return false
            if (points.any { distanceM(origin, it) <= radiusM }) return true
            for (index in points.indices) {
                if (segmentDistanceM(origin, points[index], points[(index + 1) % points.size]) <= radiusM) return true
            }
            return pointInPolygon(origin, points)
        }

        private fun roadImportance(roadClass: String): Int = when (roadClass) {
            "primary" -> 0
            "secondary" -> 1
            "motorway" -> 2
            "residential" -> 3
            "service" -> 4
            else -> 5
        }

        private fun minimumDistanceM(points: List<OfflineMapPointE6>, origin: OfflineMapPointE6): Double {
            if (points.isEmpty()) return Double.POSITIVE_INFINITY
            if (points.size == 1) return distanceM(origin, points[0])
            var minimum = Double.POSITIVE_INFINITY
            for (index in 1 until points.size) minimum = minOf(minimum, segmentDistanceM(origin, points[index - 1], points[index]))
            return minimum
        }

        private fun polygonAreaM2(points: List<OfflineMapPointE6>, origin: OfflineMapPointE6): Double {
            if (points.size < 3) return 0.0
            var twiceArea = 0.0
            for (index in points.indices) {
                val (x1, y1) = localMetres(points[index], origin)
                val (x2, y2) = localMetres(points[(index + 1) % points.size], origin)
                twiceArea += x1 * y2 - x2 * y1
            }
            return kotlin.math.abs(twiceArea) / 2
        }

        private fun pointInPolygon(point: OfflineMapPointE6, polygon: List<OfflineMapPointE6>): Boolean {
            var inside = false
            var previous = polygon.last()
            for (current in polygon) {
                val crosses = (current.latitudeE6 > point.latitudeE6) != (previous.latitudeE6 > point.latitudeE6)
                if (crosses) {
                    val longitude = (previous.longitudeE6 - current.longitudeE6).toDouble() *
                        (point.latitudeE6 - current.latitudeE6).toDouble() /
                        (previous.latitudeE6 - current.latitudeE6).toDouble() + current.longitudeE6
                    if (point.longitudeE6 < longitude) inside = !inside
                }
                previous = current
            }
            return inside
        }

        private fun segmentDistanceM(point: OfflineMapPointE6, start: OfflineMapPointE6, end: OfflineMapPointE6): Double {
            val (px, py) = localMetres(point, point)
            val (ax, ay) = localMetres(start, point)
            val (bx, by) = localMetres(end, point)
            val dx = bx - ax
            val dy = by - ay
            val denominator = dx * dx + dy * dy
            val ratio = if (denominator == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / denominator).coerceIn(0.0, 1.0)
            return hypot(px - (ax + ratio * dx), py - (ay + ratio * dy))
        }

        private fun distanceM(lhs: OfflineMapPointE6, rhs: OfflineMapPointE6): Double {
            val (x, y) = localMetres(lhs, rhs)
            return hypot(x, y)
        }

        private fun localMetres(point: OfflineMapPointE6, origin: OfflineMapPointE6): Pair<Double, Double> {
            val metresPerLatitudeE6 = 111_195.0 / 1_000_000
            val metresPerLongitudeE6 = metresPerLatitudeE6 * cos(Math.toRadians(origin.latitudeE6 / 1_000_000.0))
            return Pair(
                (point.longitudeE6 - origin.longitudeE6) * metresPerLongitudeE6,
                (point.latitudeE6 - origin.latitudeE6) * metresPerLatitudeE6,
            )
        }

        fun distanceBetween(first: OfflineMapPointE6, second: OfflineMapPointE6): Double {
            val latitude = Math.toRadians(second.latitudeE6 / 1_000_000.0)
            return hypot(
                (first.latitudeE6 - second.latitudeE6) * 0.111195,
                (first.longitudeE6 - second.longitudeE6) * 0.111195 * cos(latitude),
            )
        }
    }
}
