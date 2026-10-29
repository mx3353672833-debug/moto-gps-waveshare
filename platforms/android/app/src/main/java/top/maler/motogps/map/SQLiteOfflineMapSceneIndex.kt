package top.maler.motogps.map

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/**
 * Read-only repository for a full-city `jinan-v1.sqlite` pack. The R-tree (or,
 * when the platform SQLite lacks the rtree module, the indexed bounding-box
 * columns) only narrows candidates; [InMemoryOfflineMapSceneIndex] performs the
 * exact clip, ordering and BLE capacity limits.
 */
class SQLiteOfflineMapSceneIndex(file: File) : OfflineMapSceneQuerying {
    private val database: SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    private var rtreeAvailable = true

    init {
        try {
            val valid = metadata("schema_version") == "1" && metadata("coordinate_system") == "GCJ-02" &&
                database.version == 1
            if (!valid) throw MapException("离线地图格式无效")
        } catch (error: Exception) {
            database.close()
            throw error
        }
    }

    @Synchronized
    override fun query(origin: OfflineMapPointE6, radiusM: Int, revision: Long): OfflineMapSceneWindow {
        val radius = radiusM.coerceIn(500, 800)
        val bounds = queryBounds(origin, radius.toDouble())
        return try {
            val roads = loadRoads(bounds, origin)
            val buildings = loadBuildings(bounds, origin)
            InMemoryOfflineMapSceneIndex(roads, buildings).query(origin, radius, revision)
        } catch (_: Exception) {
            // A damaged pack must never poison the navigation stream: an empty,
            // correctly revisioned scene clears stale geometry on the device.
            OfflineMapSceneWindow(revision, origin, radius, emptyList(), emptyList())
        }
    }

    private data class Bounds(val minimumLatitude: Int, val maximumLatitude: Int, val minimumLongitude: Int, val maximumLongitude: Int)

    private fun loadRoads(bounds: Bounds, origin: OfflineMapPointE6): List<OfflineMapRoad> {
        val order = """
            ORDER BY CASE r.class WHEN 1 THEN 0 WHEN 2 THEN 1 WHEN 0 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 4 ELSE 5 END,
              abs(((r.min_lat_e6 + r.max_lat_e6) / 2) - ?5) + abs(((r.min_lon_e6 + r.max_lon_e6) / 2) - ?6), r.id
            LIMIT 512
        """
        val rtree = """
            SELECT r.osm_way_id, r.class, r.points FROM road_rtree AS x JOIN roads AS r ON r.id = x.id
            WHERE x.max_lat_e6 >= ?1 AND x.min_lat_e6 <= ?2 AND x.max_lon_e6 >= ?3 AND x.min_lon_e6 <= ?4 $order
        """
        val plain = """
            SELECT r.osm_way_id, r.class, r.points FROM roads AS r
            WHERE r.max_lat_e6 >= ?1 AND r.min_lat_e6 <= ?2 AND r.max_lon_e6 >= ?3 AND r.min_lon_e6 <= ?4 $order
        """
        return select(rtree, plain, bounds, origin) { cursor ->
            val points = decodePoints(cursor.getBlob(2)) ?: return@select null
            if (points.size < 2) return@select null
            OfflineMapRoad(
                osmWayId = if (cursor.isNull(0)) null else cursor.getLong(0),
                roadClass = roadClass(cursor.getInt(1)),
                points = points,
            )
        }
    }

    private fun loadBuildings(bounds: Bounds, origin: OfflineMapPointE6): List<OfflineMapBuilding> {
        val order = """
            ORDER BY abs(((b.min_lat_e6 + b.max_lat_e6) / 2) - ?5) + abs(((b.min_lon_e6 + b.max_lon_e6) / 2) - ?6),
              ((b.max_lat_e6 - b.min_lat_e6) * (b.max_lon_e6 - b.min_lon_e6)) DESC, b.id
            LIMIT 512
        """
        val rtree = """
            SELECT b.osm_way_id, b.name, b.class, b.points FROM building_rtree AS x JOIN buildings AS b ON b.id = x.id
            WHERE x.max_lat_e6 >= ?1 AND x.min_lat_e6 <= ?2 AND x.max_lon_e6 >= ?3 AND x.min_lon_e6 <= ?4 $order
        """
        val plain = """
            SELECT b.osm_way_id, b.name, b.class, b.points FROM buildings AS b
            WHERE b.max_lat_e6 >= ?1 AND b.min_lat_e6 <= ?2 AND b.max_lon_e6 >= ?3 AND b.min_lon_e6 <= ?4 $order
        """
        return select(rtree, plain, bounds, origin) { cursor ->
            val points = decodePoints(cursor.getBlob(3)) ?: return@select null
            if (points.size < 3 || points.first() == points.last()) return@select null
            OfflineMapBuilding(
                osmWayId = if (cursor.isNull(0)) null else cursor.getLong(0),
                name = if (cursor.isNull(1)) null else cursor.getString(1),
                buildingClass = buildingClass(cursor.getInt(2)),
                points = points,
            )
        }
    }

    private fun <T : Any> select(
        rtreeSql: String,
        plainSql: String,
        bounds: Bounds,
        origin: OfflineMapPointE6,
        row: (Cursor) -> T?,
    ): List<T> {
        // Integers are inlined (they are our own values, not user text) so the
        // R-tree and INTEGER columns compare numerically, never as strings.
        fun bind(sql: String) = sql
            .replace("?1", bounds.minimumLatitude.toString())
            .replace("?2", bounds.maximumLatitude.toString())
            .replace("?3", bounds.minimumLongitude.toString())
            .replace("?4", bounds.maximumLongitude.toString())
            .replace("?5", origin.latitudeE6.toString())
            .replace("?6", origin.longitudeE6.toString())
        val cursor = if (rtreeAvailable) {
            try {
                database.rawQuery(bind(rtreeSql), null)
            } catch (_: Exception) {
                rtreeAvailable = false
                database.rawQuery(bind(plainSql), null)
            }
        } else {
            database.rawQuery(bind(plainSql), null)
        }
        return cursor.use {
            buildList {
                while (it.moveToNext()) row(it)?.let(::add)
            }
        }
    }

    private fun metadata(key: String): String? =
        database.rawQuery("SELECT value FROM metadata WHERE key = ?1", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    companion object {
        private fun queryBounds(origin: OfflineMapPointE6, radiusM: Double): Bounds {
            val latitudeDelta = ceil(radiusM / 111_195 * 1_000_000).toInt()
            val longitudeDelta = ceil(
                radiusM / max(1.0, 111_195 * cos(Math.toRadians(origin.latitudeE6 / 1_000_000.0))) * 1_000_000,
            ).toInt()
            return Bounds(
                origin.latitudeE6 - latitudeDelta,
                origin.latitudeE6 + latitudeDelta,
                origin.longitudeE6 - longitudeDelta,
                origin.longitudeE6 + longitudeDelta,
            )
        }

        /** Blob of repeated little-endian [int32 lat_e6, int32 lon_e6]. */
        fun decodePoints(bytes: ByteArray?): List<OfflineMapPointE6>? {
            if (bytes == null || bytes.size < 8 || bytes.size % 8 != 0) return null
            fun int32(offset: Int): Int =
                (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            return (0 until bytes.size / 8).map { index -> OfflineMapPointE6(int32(index * 8), int32(index * 8 + 4)) }
        }

        private fun roadClass(raw: Int) = when (raw) {
            0 -> "motorway"
            1 -> "primary"
            2 -> "secondary"
            3 -> "residential"
            4 -> "service"
            else -> "other"
        }

        private fun buildingClass(raw: Int) = when (raw) {
            1 -> "landmark"
            2 -> "parking"
            else -> "generic"
        }
    }
}
