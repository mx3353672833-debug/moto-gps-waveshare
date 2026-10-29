package top.maler.motogps.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.maler.motogps.model.ChinaCoordinateTransform
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.model.WGS84Point

@Serializable
data class MapTileId(val z: Int = 15, val x: Int, val y: Int) {
    val isValid: Boolean get() = z == 15 && x in 0 until 32_768 && y in 0 until 32_768
    val filename: String get() = "$z-$x-$y.json"
}

class MapException(message: String) : Exception(message)

/** One gateway tile from GET /v1/map/tiles/{z}/{x}/{y}: GCJ-02 roads and buildings. */
@Serializable
data class MapTileDocument(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("coordinate_system") val coordinateSystem: String,
    val tile: MapTileId,
    val roads: List<OfflineMapRoad>,
    val buildings: List<OfflineMapBuilding>,
    val source: OfflineMapSource,
) {
    companion object {
        const val MAXIMUM_BYTES = 8 * 1_024 * 1_024
        private val json = Json { ignoreUnknownKeys = true }
        private val roadClasses = setOf("motorway", "primary", "secondary", "residential", "service", "other")
        private val buildingClasses = setOf("generic", "landmark", "parking")

        /** Same bounds as MapTileDocument.decode on iOS; rejects anything outside them. */
        fun decode(data: ByteArray, expectedTile: MapTileId): MapTileDocument {
            if (data.size > MAXIMUM_BYTES) throw MapException("地图分块超过大小限制")
            val value = try {
                json.decodeFromString(serializer(), data.decodeToString())
            } catch (_: Exception) {
                throw MapException("地图数据格式无效")
            }
            val source = value.source
            val valid = expectedTile.isValid && value.tile == expectedTile &&
                value.schemaVersion == 1 && value.coordinateSystem == "GCJ-02" &&
                value.roads.size + value.buildings.size <= 12_000 &&
                source.provider.length <= 1_024 && source.licence.length <= 1_024 &&
                source.attributionUrl.length <= 2_048 && source.retrievedAt.length <= 128 &&
                (source.bboxWgs84?.let { it.size == 4 && it.all(Double::isFinite) } ?: true)
            if (!valid) throw MapException("地图数据格式无效")
            var pointCount = 0
            fun validPoints(points: List<OfflineMapPointE6>, minimum: Int): Boolean {
                pointCount += points.size
                return points.size in minimum..2_048 && pointCount <= 120_000 && points.all {
                    it.latitudeE6 in -90_000_000..90_000_000 && it.longitudeE6 in -180_000_000..180_000_000
                }
            }
            val shapesValid = value.roads.all { it.roadClass in roadClasses && validPoints(it.points, 2) } &&
                value.buildings.all {
                    it.buildingClass in buildingClasses && (it.name?.length ?: 0) <= 1_024 &&
                        validPoints(it.points, 3) && it.points.first() != it.points.last()
                }
            if (!shapesValid) throw MapException("地图数据格式无效")
            return value
        }
    }
}

/**
 * Tile addresses always refer to WGS84 Web Mercator z15. Geometry sent to the
 * round screen stays GCJ-02; convert only at this planning boundary.
 */
object MapTilePlanner {
    const val ZOOM = 15
    const val MAXIMUM_TILES = 40_000
    private const val COUNT = 1 shl ZOOM
    private const val MAXIMUM_LATITUDE = 85.05112878

    fun aroundGcj02(latitudeDeg: Double, longitudeDeg: Double, radiusM: Double): List<MapTileId> {
        if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || abs(latitudeDeg) > MAXIMUM_LATITUDE ||
            abs(longitudeDeg) > 180 || !radiusM.isFinite() || radiusM !in 0.0..10_000.0
        ) {
            throw MapException("地图范围无效，请重新选择城市或路线。")
        }
        val point = ChinaCoordinateTransform.gcj02ToWgs84(GCJ02Point(longitudeDeg = longitudeDeg, latitudeDeg = latitudeDeg))
        return aroundWgs84(point, radiusM)
    }

    fun boundsWgs84(bounds: List<Double>): List<MapTileId> {
        if (bounds.size != 4 || !bounds.all(Double::isFinite) ||
            bounds[0] !in -180.0..180.0 || bounds[2] !in -180.0..180.0 ||
            abs(bounds[1]) > MAXIMUM_LATITUDE || abs(bounds[3]) > MAXIMUM_LATITUDE ||
            bounds[0] >= bounds[2] || bounds[1] >= bounds[3]
        ) {
            throw MapException("地图范围无效，请重新选择城市或路线。")
        }
        val topLeft = tile(bounds[0], bounds[3])
        val bottomRight = tile(bounds[2], bounds[1])
        val columns = bottomRight.first - topLeft.first + 1
        val rows = bottomRight.second - topLeft.second + 1
        if (columns <= 0 || rows <= 0 || columns.toLong() * rows > MAXIMUM_TILES) {
            throw MapException("这个范围太大，请选择市内的区县，或分段下载路线。")
        }
        return (topLeft.second..bottomRight.second).flatMap { y ->
            (topLeft.first..bottomRight.first).map { x -> MapTileId(ZOOM, x, y) }
        }
    }

    /**
     * Samples each segment densely, including long sparse ones. A 1.2 km square
     * half-width and <=400 m sampling cover an 800 m display window anywhere
     * along the route without downloading its bounding box.
     */
    fun corridorGcj02(polyline: List<GCJ02Point>): List<MapTileId> {
        if (polyline.size < 2) throw MapException("请先规划一条路线。")
        if (polyline.size > 100_000 || !polyline.all {
                it.latitudeDeg.isFinite() && it.longitudeDeg.isFinite() &&
                    abs(it.latitudeDeg) <= MAXIMUM_LATITUDE && abs(it.longitudeDeg) <= 180
            }
        ) {
            throw MapException("地图范围无效，请重新选择城市或路线。")
        }
        val points = polyline.map(ChinaCoordinateTransform::gcj02ToWgs84)
        val result = HashSet<MapTileId>()
        var sampleCount = 0
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            val latitude = Math.toRadians((a.latitudeDeg + b.latitudeDeg) / 2)
            val distance = hypot(
                (b.latitudeDeg - a.latitudeDeg) * 111_195,
                (b.longitudeDeg - a.longitudeDeg) * 111_195 * cos(latitude),
            )
            if (!distance.isFinite() || abs(b.longitudeDeg - a.longitudeDeg) > 180) {
                throw MapException("地图范围无效，请重新选择城市或路线。")
            }
            val steps = max(1, ceil(distance / 400).toInt())
            sampleCount += steps
            if (sampleCount > 100_000) throw MapException("这个范围太大，请选择市内的区县，或分段下载路线。")
            for (step in 0..steps) {
                val fraction = step.toDouble() / steps
                val point = WGS84Point(
                    longitudeDeg = a.longitudeDeg + (b.longitudeDeg - a.longitudeDeg) * fraction,
                    latitudeDeg = a.latitudeDeg + (b.latitudeDeg - a.latitudeDeg) * fraction,
                )
                result += aroundWgs84(point, 1_200.0)
                if (result.size > MAXIMUM_TILES) throw MapException("这个范围太大，请选择市内的区县，或分段下载路线。")
            }
        }
        return result.sortedWith(compareBy<MapTileId>({ it.y }, { it.x }))
    }

    private fun aroundWgs84(point: WGS84Point, radiusM: Double): List<MapTileId> {
        val dy = max(1.0, radiusM) / 111_195
        val dx = dy / max(0.01, cos(Math.toRadians(point.latitudeDeg)))
        return boundsWgs84(
            listOf(
                max(-180.0, point.longitudeDeg - dx),
                max(-MAXIMUM_LATITUDE, point.latitudeDeg - dy),
                minOf(180.0, point.longitudeDeg + dx),
                minOf(MAXIMUM_LATITUDE, point.latitudeDeg + dy),
            ),
        )
    }

    fun tile(longitude: Double, latitude: Double): Pair<Int, Int> {
        val x = floor((longitude + 180) / 360 * COUNT).toInt()
        val y = floor((1 - asinh(tan(Math.toRadians(latitude))) / PI) / 2 * COUNT).toInt()
        return Pair(x.coerceIn(0, COUNT - 1), y.coerceIn(0, COUNT - 1))
    }
}
