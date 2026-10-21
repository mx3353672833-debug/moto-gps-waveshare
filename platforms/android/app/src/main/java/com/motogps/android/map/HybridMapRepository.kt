package com.motogps.android.map

import android.content.Context
import android.util.Log
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapBuildingFootprint
import com.motogps.android.jni.MotoBLEMapRoadPolyline
import com.motogps.android.jni.MotoBLEMapScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RoadSegment(
    val roadClass: Int = 5, // 0 motorway, 1 primary, 2 secondary, 3 residential, 4 service, 5 other
    val points: List<GeoPointE6>,
    val maxSpeedKph: Int = 0
)

data class SavedMapInfo(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long
)

data class MapSceneData(
    val roads: List<MotoBLEMapRoadPolyline>,
    val buildings: List<MotoBLEMapBuildingFootprint> = emptyList()
)

class HybridMapRepository(
    private val mapsDir: File,
    private val cacheDir: File,
    private val userAgent: String = "MotoGpsWaveshare/1.0",
    private val maxCacheSizeBytes: Long = 1024L * 1024L * 1024L // 1 GB
) {
    constructor(context: Context) : this(
        mapsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "maps"),
        cacheDir = File(context.cacheDir, "vector_tiles")
    )

    init {
        ensureDirectories()
    }

    private fun ensureDirectories() {
        try {
            if (!mapsDir.exists()) {
                mapsDir.mkdirs()
            }
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
        } catch (e: Exception) {
            logE("HybridMapRepo", "Errore creazione directory mappe", e)
        }
    }

    private fun logE(tag: String, msg: String, t: Throwable? = null) {
        try {
            Log.e(tag, msg, t)
        } catch (_: Exception) {
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Exception) {
        }
    }

    suspend fun getNearbyRoadSegments(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double = 600.0
    ): List<RoadSegment> = withContext(Dispatchers.IO) {
        ensureDirectories()
        val rawSegments = loadRoadSegments(centerLat, centerLon, radiusMeters)
        filterAndTruncateSegments(rawSegments, centerLat, centerLon, radiusMeters)
    }

    suspend fun fetchMapScene(
        centerLat: Double,
        centerLon: Double,
        sceneRevision: Long
    ): MotoBLEMapScene = withContext(Dispatchers.IO) {
        ensureDirectories()
        val (gcjLat, gcjLon) = wgs84ToGcj02(centerLat, centerLon)
        val viewOrigin = GeoPointE6((gcjLat * 1e6).toInt(), (gcjLon * 1e6).toInt())

        val rawSceneData = loadMapSceneData(centerLat, centerLon, 600.0)
        val processedScene = processMapSceneData(rawSceneData, viewOrigin)

        MotoBLEMapScene(
            coordinateSystem = 2, // GCJ-02
            sceneRevision = sceneRevision,
            viewOrigin = viewOrigin,
            radiusMeters = 500,
            roads = processedScene.roads,
            buildings = processedScene.buildings
        )
    }

    private suspend fun loadMapSceneData(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): MapSceneData = withContext(Dispatchers.IO) {
        enforceLruCacheLimit()

        val regionalSegments = loadFromRegionalFiles(centerLat, centerLon, radiusMeters)
        if (regionalSegments != null && regionalSegments.isNotEmpty()) {
            val roads = regionalSegments.map { MotoBLEMapRoadPolyline(roadClass = it.roadClass, points = it.points) }
            return@withContext MapSceneData(roads = roads, buildings = emptyList())
        }

        val cacheKey = getCacheKey(centerLat, centerLon)
        val sanitizedKey = cacheKey.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val cachedFile = File(cacheDir, "${sanitizedKey}_scene.json")

        if (cachedFile.exists() && cachedFile.length() > 0) {
            cachedFile.setLastModified(System.currentTimeMillis())
            val cachedData = parseMapSceneJson(cachedFile.readText())
            if (cachedData.roads.isNotEmpty() || cachedData.buildings.isNotEmpty()) {
                return@withContext cachedData
            }
        }

        val downloadedJson = downloadMapSceneOverpassData(centerLat, centerLon)
        if (downloadedJson != null) {
            try {
                BufferedOutputStream(FileOutputStream(cachedFile)).use { bos ->
                    val bytes = downloadedJson.toByteArray(Charsets.UTF_8)
                    bos.write(bytes)
                    bos.flush()
                }
            } catch (e: Exception) {
                logE("HybridMapRepo", "Errore scrittura cache scene", e)
            }
            return@withContext parseMapSceneJson(downloadedJson)
        }

        MapSceneData(roads = emptyList(), buildings = emptyList())
    }

    private suspend fun downloadMapSceneOverpassData(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val bboxSize = 0.0055 // ~600m box
        val minLat = lat - bboxSize
        val minLon = lon - bboxSize
        val maxLat = lat + bboxSize
        val maxLon = lon + bboxSize

        val query = String.format(
            Locale.US,
            "[out:json][timeout:25];(way(%f,%f,%f,%f)[highway~\"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|residential|living_street|service|unclassified)$\"];way(%f,%f,%f,%f)[building];);out geom qt;",
            minLat, minLon, maxLat, maxLon, minLat, minLon, maxLat, maxLon
        )

        downloadOverpassQueryWithMirrors(query)
    }

    private fun parseMapSceneJson(jsonStr: String): MapSceneData {
        val roads = mutableListOf<MotoBLEMapRoadPolyline>()
        val buildings = mutableListOf<MotoBLEMapBuildingFootprint>()

        try {
            val root = JSONObject(jsonStr)

            if (root.has("elements")) {
                val elements = root.getJSONArray("elements")
                for (i in 0 until elements.length()) {
                    val elem = elements.getJSONObject(i)
                    if (elem.optString("type") == "way" && elem.has("tags") && elem.has("geometry")) {
                        val tags = elem.getJSONObject("tags")
                        val geom = elem.getJSONArray("geometry")
                        val rawPoints = mutableListOf<GeoPointE6>()

                        for (j in 0 until geom.length()) {
                            val pt = geom.getJSONObject(j)
                            val pLat = pt.getDouble("lat")
                            val pLon = pt.getDouble("lon")
                            val (gcjLat, gcjLon) = wgs84ToGcj02(pLat, pLon)
                            rawPoints.add(GeoPointE6((gcjLat * 1e6).toInt(), (gcjLon * 1e6).toInt()))
                        }

                        if (tags.has("highway")) {
                            val roadClass = mapHighwayTagToRoadClass(tags.getString("highway"))
                            if (rawPoints.size >= 2) {
                                roads.add(MotoBLEMapRoadPolyline(roadClass, rawPoints))
                            }
                        } else if (tags.has("building")) {
                            if (rawPoints.size >= 3) {
                                buildings.add(MotoBLEMapBuildingFootprint(0, rawPoints))
                            }
                        }
                    }
                }
            } else {
                // Fallback using parseGeoJsonOrJson for roads
                val segments = parseGeoJsonOrJson(jsonStr)
                for (seg in segments) {
                    val gcjPoints = seg.points.map { pt ->
                        val (gLat, gLon) = wgs84ToGcj02(pt.latitudeE6 / 1e6, pt.longitudeE6 / 1e6)
                        GeoPointE6((gLat * 1e6).toInt(), (gLon * 1e6).toInt())
                    }
                    if (gcjPoints.size >= 2) {
                        roads.add(MotoBLEMapRoadPolyline(seg.roadClass, gcjPoints))
                    }
                }
            }
        } catch (_: Exception) {
        }

        return MapSceneData(roads, buildings)
    }

    private fun processMapSceneData(
        data: MapSceneData,
        viewOrigin: GeoPointE6
    ): MapSceneData {
        val processedRoads = mutableListOf<MotoBLEMapRoadPolyline>()
        var totalRoadPoints = 0

        for (road in data.roads) {
            if (processedRoads.size >= 24) break

            // Ramer-Douglas-Peucker simplification
            val simplified = simplifyPointsRdp(road.points, 2.5)

            // Clip points to ±100,000 E6 of viewOrigin
            val clipped = simplified.filter { pt ->
                Math.abs(pt.latitudeE6 - viewOrigin.latitudeE6) <= 100000 &&
                        Math.abs(pt.longitudeE6 - viewOrigin.longitudeE6) <= 100000
            }

            if (clipped.size >= 2) {
                val availablePoints = 192 - totalRoadPoints
                if (availablePoints < 2) break

                val finalPoints = if (clipped.size > availablePoints) clipped.take(availablePoints) else clipped
                processedRoads.add(MotoBLEMapRoadPolyline(road.roadClass, finalPoints))
                totalRoadPoints += finalPoints.size
                if (totalRoadPoints >= 192) break
            }
        }

        val processedBuildings = mutableListOf<MotoBLEMapBuildingFootprint>()
        var totalBuildingPoints = 0

        for (bldg in data.buildings) {
            if (processedBuildings.size >= 16) break

            var pts = bldg.points.toMutableList()
            // Do not repeat first point at end of building ring
            if (pts.size > 1 && pts.first().latitudeE6 == pts.last().latitudeE6 && pts.first().longitudeE6 == pts.last().longitudeE6) {
                pts.removeAt(pts.size - 1)
            }

            val clipped = pts.filter { pt ->
                Math.abs(pt.latitudeE6 - viewOrigin.latitudeE6) <= 100000 &&
                        Math.abs(pt.longitudeE6 - viewOrigin.longitudeE6) <= 100000
            }

            if (clipped.size >= 3) {
                val availablePoints = 128 - totalBuildingPoints
                if (availablePoints < 3) break

                val finalPoints = if (clipped.size > availablePoints) clipped.take(availablePoints) else clipped
                if (finalPoints.size >= 3) {
                    processedBuildings.add(MotoBLEMapBuildingFootprint(bldg.buildingClass, finalPoints))
                    totalBuildingPoints += finalPoints.size
                    if (totalBuildingPoints >= 128) break
                }
            }
        }

        return MapSceneData(processedRoads, processedBuildings)
    }

    fun simplifyPointsRdp(points: List<GeoPointE6>, epsilonMeters: Double = 2.5): List<GeoPointE6> {
        if (points.size <= 2) return points

        val epsilonDeg = epsilonMeters / 111000.0
        var maxDist = 0.0
        var index = 0

        val p1 = points.first()
        val p2 = points.last()

        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], p1, p2)
            if (dist > maxDist) {
                maxDist = dist
                index = i
            }
        }

        return if (maxDist > epsilonDeg) {
            val rec1 = simplifyPointsRdp(points.subList(0, index + 1), epsilonMeters)
            val rec2 = simplifyPointsRdp(points.subList(index, points.size), epsilonMeters)
            rec1.dropLast(1) + rec2
        } else {
            listOf(p1, p2)
        }
    }

    private fun perpendicularDistance(pt: GeoPointE6, lineStart: GeoPointE6, lineEnd: GeoPointE6): Double {
        val x = pt.longitudeE6 / 1e6
        val y = pt.latitudeE6 / 1e6
        val x1 = lineStart.longitudeE6 / 1e6
        val y1 = lineStart.latitudeE6 / 1e6
        val x2 = lineEnd.longitudeE6 / 1e6
        val y2 = lineEnd.latitudeE6 / 1e6

        val dx = x2 - x1
        val dy = y2 - y1

        if (dx == 0.0 && dy == 0.0) {
            return Math.hypot(x - x1, y - y1)
        }

        val num = Math.abs(dy * x - dx * y + x2 * y1 - y2 * x1)
        val den = Math.hypot(dx, dy)
        return num / den
    }

    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return Pair(lat, lon)
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - 0.00669342162296594323 * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((6378245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (6378245.0 / sqrtMagic * Math.cos(radLat) * Math.PI)
        return Pair(lat + dLat, lon + dLon)
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean {
        if (lon < 72.004 || lon > 137.8347) return true
        if (lat < 0.8293 || lat > 55.8271) return true
        return false
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    private suspend fun loadRoadSegments(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): List<RoadSegment> = withContext(Dispatchers.IO) {
        enforceLruCacheLimit()

        // 1. Check local regional files with highest priority (0 network calls)
        val regionalSegments = loadFromRegionalFiles(centerLat, centerLon, radiusMeters)
        if (regionalSegments != null && regionalSegments.isNotEmpty()) {
            return@withContext regionalSegments
        }

        // 2. Fallback to dynamic cache or network download (150-200 km bounding box scope)
        val cacheKey = getCacheKey(centerLat, centerLon)
        val sanitizedKey = cacheKey.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val cachedFile = File(cacheDir, "$sanitizedKey.json")

        if (cachedFile.exists() && cachedFile.length() > 0) {
            cachedFile.setLastModified(System.currentTimeMillis()) // Update LRU access time
            val cachedSegments = parseGeoJsonOrJson(cachedFile.readText())
            if (cachedSegments.isNotEmpty()) {
                return@withContext cachedSegments
            }
        }

        // Download dynamic vector data if not in cache or regional file
        val downloadedJson = downloadVectorData(centerLat, centerLon)
        if (downloadedJson != null) {
            try {
                BufferedOutputStream(FileOutputStream(cachedFile)).use { bos ->
                    val bytes = downloadedJson.toByteArray(Charsets.UTF_8)
                    var offset = 0
                    val bufferSize = 8192
                    while (offset < bytes.size) {
                        val len = minOf(bufferSize, bytes.size - offset)
                        bos.write(bytes, offset, len)
                        offset += len
                    }
                    bos.flush()
                }
            } catch (e: Exception) {
                logE("HybridMapRepo", "Errore scrittura file cache", e)
            }
            return@withContext parseGeoJsonOrJson(downloadedJson)
        }

        emptyList()
    }

    internal fun loadFromRegionalFiles(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): List<RoadSegment>? {
        if (!mapsDir.exists() || !mapsDir.isDirectory) return null

        val files = mapsDir.listFiles() ?: return null
        val regionalSegments = mutableListOf<RoadSegment>()

        // 1. Process .motomap compact line-by-line files with spatial BBox filter
        val searchBBox = calculateBoundingBoxE6(centerLat, centerLon, radiusMeters)

        for (file in files) {
            if (!file.isFile) continue
            if (file.extension.equals("motomap", ignoreCase = true)) {
                try {
                    file.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val parsed = parseMotoMapLineWithBBoxFilter(line, searchBBox)
                            if (parsed != null) {
                                regionalSegments.add(parsed)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logE("HybridMapRepo", "Errore lettura file motomap: ${file.name}", e)
                }
            } else if (file.extension.equals("json", ignoreCase = true) || file.extension.equals("geojson", ignoreCase = true)) {
                try {
                    val content = file.readText()
                    val segments = parseGeoJsonOrJson(content)
                    if (segments.isNotEmpty()) {
                        regionalSegments.addAll(segments)
                    }
                } catch (_: Exception) {
                }
            }
        }

        val nearby = filterAndTruncateSegments(regionalSegments, centerLat, centerLon, radiusMeters)
        return if (nearby.isNotEmpty()) nearby else null
    }

    fun listSavedMaps(): List<SavedMapInfo> {
        if (!mapsDir.exists() || !mapsDir.isDirectory) return emptyList()

        val files = mapsDir.listFiles() ?: return emptyList()
        val result = mutableListOf<SavedMapInfo>()

        for (file in files) {
            if (file.isFile && (file.extension.equals("motomap", ignoreCase = true) ||
                        file.extension.equals("json", ignoreCase = true) ||
                        file.extension.equals("geojson", ignoreCase = true))) {
                val displayName = file.nameWithoutExtension.replace('_', ' ').uppercase(Locale.getDefault())
                result.add(
                    SavedMapInfo(
                        fileName = file.name,
                        displayName = displayName,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified()
                    )
                )
            }
        }
        return result.sortedByDescending { it.lastModified }
    }

    fun deleteSavedMap(fileName: String): Boolean {
        val file = File(mapsDir, fileName)
        return if (file.exists() && file.parentFile == mapsDir) {
            file.delete()
        } else false
    }

    fun appendSegmentsToMotoMap(file: File, segments: List<RoadSegment>) {
        if (segments.isEmpty()) return
        try {
            ensureDirectories()
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                for (seg in segments) {
                    if (seg.points.size < 2) continue
                    var minLatE6 = Int.MAX_VALUE
                    var minLonE6 = Int.MAX_VALUE
                    var maxLatE6 = Int.MIN_VALUE
                    var maxLonE6 = Int.MIN_VALUE

                    val ptsString = StringBuilder()
                    for (i in seg.points.indices) {
                        val pt = seg.points[i]
                        if (pt.latitudeE6 < minLatE6) minLatE6 = pt.latitudeE6
                        if (pt.longitudeE6 < minLonE6) minLonE6 = pt.longitudeE6
                        if (pt.latitudeE6 > maxLatE6) maxLatE6 = pt.latitudeE6
                        if (pt.longitudeE6 > maxLonE6) maxLonE6 = pt.longitudeE6

                        ptsString.append("${pt.latitudeE6},${pt.longitudeE6}")
                        if (i < seg.points.size - 1) {
                            ptsString.append(";")
                        }
                    }

                    // Format: roadClass,maxSpeedKph|minLatE6,minLonE6,maxLatE6,maxLonE6|lat1E6,lon1E6;lat2E6,lon2E6;...
                    val line = "${seg.roadClass},${seg.maxSpeedKph}|$minLatE6,$minLonE6,$maxLatE6,$maxLonE6|$ptsString\n"
                    writer.write(line)
                }
                writer.flush()
            }
        } catch (e: Exception) {
            logE("HybridMapRepo", "Errore scrittura file motomap", e)
        }
    }

    private data class BoundingBoxE6(
        val minLatE6: Int,
        val minLonE6: Int,
        val maxLatE6: Int,
        val maxLonE6: Int
    )

    private fun calculateBoundingBoxE6(centerLat: Double, centerLon: Double, radiusMeters: Double): BoundingBoxE6 {
        val degDeltaLat = radiusMeters / 111000.0
        val degDeltaLon = radiusMeters / (111000.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.1))

        val minLatE6 = ((centerLat - degDeltaLat) * 1e6).toInt()
        val maxLatE6 = ((centerLat + degDeltaLat) * 1e6).toInt()
        val minLonE6 = ((centerLon - degDeltaLon) * 1e6).toInt()
        val maxLonE6 = ((centerLon + degDeltaLon) * 1e6).toInt()

        return BoundingBoxE6(minLatE6, minLonE6, maxLatE6, maxLonE6)
    }

    private fun parseMotoMapLineWithBBoxFilter(line: String, searchBBox: BoundingBoxE6): RoadSegment? {
        if (line.isBlank() || !line.contains("|")) return null
        val parts = line.split("|")
        if (parts.size < 3) return null

        val roadClassParts = parts[0].trim().split(",")
        val roadClass = roadClassParts[0].toIntOrNull() ?: 5
        val parsedSpeed = if (roadClassParts.size >= 2) (roadClassParts[1].toIntOrNull() ?: 0) else 0
        val maxSpeedKph = if (parsedSpeed > 0) parsedSpeed else 0
        val bboxParts = parts[1].split(",")
        if (bboxParts.size < 4) return null

        val minLatE6 = bboxParts[0].trim().toIntOrNull() ?: return null
        val minLonE6 = bboxParts[1].trim().toIntOrNull() ?: return null
        val maxLatE6 = bboxParts[2].trim().toIntOrNull() ?: return null
        val maxLonE6 = bboxParts[3].trim().toIntOrNull() ?: return null

        // Check bounding box intersection
        if (maxLatE6 < searchBBox.minLatE6 || minLatE6 > searchBBox.maxLatE6 ||
            maxLonE6 < searchBBox.minLonE6 || minLonE6 > searchBBox.maxLonE6) {
            return null
        }

        val points = mutableListOf<GeoPointE6>()
        val coordPairs = parts[2].split(";")
        for (pair in coordPairs) {
            if (pair.isBlank()) continue
            val latLon = pair.split(",")
            if (latLon.size >= 2) {
                val lat = latLon[0].trim().toIntOrNull() ?: continue
                val lon = latLon[1].trim().toIntOrNull() ?: continue
                points.add(GeoPointE6(lat, lon))
            }
        }

        return if (points.size >= 2) RoadSegment(roadClass, points, maxSpeedKph) else null
    }

    private fun getCacheKey(lat: Double, lon: Double): String {
        // ~0.1 deg (~11 km grid tile key for cache)
        val latKey = (lat * 10).toInt()
        val lonKey = (lon * 10).toInt()
        return "tile_${latKey}_${lonKey}"
    }

    fun enforceLruCacheLimit() {
        if (!cacheDir.exists() || !cacheDir.isDirectory) return

        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }

        if (totalSize <= maxCacheSizeBytes) return

        // Sort files by last modified timestamp (oldest first for LRU)
        val sortedFiles = files.sortedBy { it.lastModified() }

        for (file in sortedFiles) {
            val length = file.length()
            if (file.delete()) {
                totalSize -= length
                if (totalSize <= maxCacheSizeBytes) break
            }
        }
    }

    suspend fun downloadOverpassQueryWithMirrors(query: String): String? = withContext(Dispatchers.IO) {
        val mirrors = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        for (mirror in mirrors) {
            val endpointUrl = "$mirror?data=$encodedQuery"
            try {
                val url = URL(endpointUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.connectTimeout = 12000
                conn.readTimeout = 12000

                if (conn.responseCode in 200..299) {
                    val rawStream: InputStream = conn.inputStream
                    val stream = if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                        GZIPInputStream(rawStream)
                    } else {
                        rawStream
                    }

                    return@withContext BufferedInputStream(stream).use { bis ->
                        val buffer = ByteArray(8192)
                        val outBuilder = StringBuilder()
                        var bytesRead: Int
                        while (bis.read(buffer).also { bytesRead = it } != -1) {
                            outBuilder.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                        }
                        outBuilder.toString()
                    }
                } else {
                    logW("HybridMapRepo", "Mirror $mirror ha risposto con codice ${conn.responseCode}")
                }
            } catch (e: Exception) {
                logW("HybridMapRepo", "Mirror $mirror fallito: ${e.message}")
            }
        }
        null
    }

    private suspend fun downloadVectorData(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val bboxSize = 0.02 // ~2km box around location
        val minLat = lat - bboxSize
        val minLon = lon - bboxSize
        val maxLat = lat + bboxSize
        val maxLon = lon + bboxSize

        val query = String.format(
            Locale.US,
            "[out:json][timeout:25];way(%f,%f,%f,%f)[highway~\"motorway|trunk|primary|secondary|tertiary|residential\"];out geom qt;",
            minLat, minLon, maxLat, maxLon
        )

        downloadOverpassQueryWithMirrors(query)
    }

    fun parseGeoJsonOrJson(jsonStr: String): List<RoadSegment> {
        val segments = mutableListOf<RoadSegment>()
        try {
            val root = JSONObject(jsonStr)

            // 1. Overpass API JSON format
            if (root.has("elements")) {
                val elements = root.getJSONArray("elements")
                for (i in 0 until elements.length()) {
                    val elem = elements.getJSONObject(i)
                    if (elem.optString("type") == "way" && elem.has("tags")) {
                        val tags = elem.getJSONObject("tags")
                        if (tags.has("highway")) {
                            val highwayTag = tags.getString("highway")
                            val roadClass = mapHighwayTagToRoadClass(highwayTag)
                            val points = mutableListOf<GeoPointE6>()

                            val speedTag = tags.optString("maxspeed", "")
                            val speedTypeTag = tags.optString("maxspeed:type", "")
                            val maxSpeedKph = parseSpeedTag(speedTag, speedTypeTag)

                            if (elem.has("geometry")) {
                                val geom = elem.getJSONArray("geometry")
                                for (j in 0 until geom.length()) {
                                    val pt = geom.getJSONObject(j)
                                    val pLat = pt.getDouble("lat")
                                    val pLon = pt.getDouble("lon")
                                    points.add(GeoPointE6((pLat * 1e6).toInt(), (pLon * 1e6).toInt()))
                                }
                            }
                            if (points.size >= 2) {
                                segments.add(RoadSegment(roadClass, points, maxSpeedKph))
                            }
                        }
                    }
                }
            }
            // 2. GeoJSON FeatureCollection format
            else if (root.optString("type") == "FeatureCollection" && root.has("features")) {
                val features = root.getJSONArray("features")
                for (i in 0 until features.length()) {
                    val feat = features.getJSONObject(i)
                    val properties = feat.optJSONObject("properties") ?: JSONObject()
                    val geometry = feat.optJSONObject("geometry") ?: continue

                    val highwayTag = properties.optString("highway", properties.optString("road_class", ""))
                    if (highwayTag.isNotBlank() || properties.has("highway") || properties.has("road_class")) {
                        val roadClass = mapHighwayTagToRoadClass(highwayTag)
                        val speedTag = properties.optString("maxspeed", "")
                        val speedTypeTag = properties.optString("maxspeed:type", "")
                        val maxSpeedKph = parseSpeedTag(speedTag, speedTypeTag)

                        val geomType = geometry.optString("type")

                        if (geomType == "LineString" && geometry.has("coordinates")) {
                            val coords = geometry.getJSONArray("coordinates")
                            val points = parseLineStringCoordinates(coords)
                            if (points.size >= 2) {
                                segments.add(RoadSegment(roadClass, points, maxSpeedKph))
                            }
                        } else if (geomType == "MultiLineString" && geometry.has("coordinates")) {
                            val multiCoords = geometry.getJSONArray("coordinates")
                            for (j in 0 until multiCoords.length()) {
                                val coords = multiCoords.getJSONArray(j)
                                val points = parseLineStringCoordinates(coords)
                                if (points.size >= 2) {
                                    segments.add(RoadSegment(roadClass, points, maxSpeedKph))
                                }
                            }
                        }
                    }
                }
            }
            // 3. Custom JSON format (array of segments)
            else if (root.has("segments")) {
                val segArray = root.getJSONArray("segments")
                for (i in 0 until segArray.length()) {
                    val segObj = segArray.getJSONObject(i)
                    val roadClass = segObj.optInt("roadClass", segObj.optInt("road_class", 5))
                    val maxSpeedKph = segObj.optInt("maxSpeedKph", segObj.optInt("maxspeed", 0))
                    val ptsArray = segObj.getJSONArray("points")
                    val points = mutableListOf<GeoPointE6>()
                    for (j in 0 until ptsArray.length()) {
                        val ptObj = ptsArray.getJSONObject(j)
                        val lat = ptObj.optDouble("latitudeE6", ptObj.optDouble("lat", 0.0) * 1e6).toInt()
                        val lon = ptObj.optDouble("longitudeE6", ptObj.optDouble("lon", 0.0) * 1e6).toInt()
                        points.add(GeoPointE6(lat, lon))
                    }
                    if (points.size >= 2) {
                        segments.add(RoadSegment(roadClass, points, maxSpeedKph))
                    }
                }
            }
        } catch (_: Exception) {
        }

        return segments
    }

    fun parseSpeedTag(maxspeedTag: String, maxspeedTypeTag: String = ""): Int {
        val speedStr = maxspeedTag.trim().lowercase()
        if (speedStr.isNotBlank()) {
            val digits = speedStr.takeWhile { it.isDigit() }
            val value = digits.toIntOrNull()
            if (value != null && value > 0) {
                if (speedStr.contains("mph")) {
                    return Math.round(value * 1.60934f)
                }
                return value
            }
            val parsedNamed = parseNamedSpeedType(speedStr)
            if (parsedNamed > 0) return parsedNamed
        }

        val typeStr = maxspeedTypeTag.trim().lowercase()
        if (typeStr.isNotBlank()) {
            val parsedType = parseNamedSpeedType(typeStr)
            if (parsedType > 0) return parsedType
        }

        return 0
    }

    private fun parseNamedSpeedType(typeStr: String): Int {
        return when {
            typeStr.contains("urban") || typeStr.contains("living_street") -> 50
            typeStr.contains("rural") || typeStr.contains("extraurban") -> 90
            typeStr.contains("trunk") -> 110
            typeStr.contains("motorway") || typeStr.contains("autostrada") -> 130
            typeStr.contains("walk") -> 10
            else -> 0
        }
    }

    private fun parseLineStringCoordinates(coords: JSONArray): List<GeoPointE6> {
        val points = mutableListOf<GeoPointE6>()
        for (i in 0 until coords.length()) {
            val coord = coords.getJSONArray(i)
            if (coord.length() >= 2) {
                val lon = coord.getDouble(0)
                val lat = coord.getDouble(1)
                points.add(GeoPointE6((lat * 1e6).toInt(), (lon * 1e6).toInt()))
            }
        }
        return points
    }

    private fun mapHighwayTagToRoadClass(tag: String): Int {
        return when (tag.lowercase()) {
            "motorway", "motorway_link", "trunk", "trunk_link" -> 0
            "primary", "primary_link" -> 1
            "secondary", "secondary_link" -> 2
            "tertiary", "tertiary_link", "residential" -> 3
            "service", "unclassified" -> 4
            else -> 5
        }
    }

    private fun filterAndTruncateSegments(
        segments: List<RoadSegment>,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): List<RoadSegment> {
        val filteredSegments = mutableListOf<RoadSegment>()
        var totalNodeCount = 0

        for (segment in segments) {
            if (filteredSegments.size >= 24) break

            // Filter points within radius
            val nearbyPoints = segment.points.filter { pt ->
                val pLat = pt.latitudeE6 / 1e6
                val pLon = pt.longitudeE6 / 1e6
                calculateDistanceMeters(centerLat, centerLon, pLat, pLon) <= radiusMeters
            }

            if (nearbyPoints.size >= 2) {
                val availableNodeBudget = 192 - totalNodeCount
                if (availableNodeBudget < 2) break

                val truncatedPoints = if (nearbyPoints.size > availableNodeBudget) {
                    nearbyPoints.take(availableNodeBudget)
                } else {
                    nearbyPoints
                }

                filteredSegments.add(RoadSegment(segment.roadClass, truncatedPoints, segment.maxSpeedKph))
                totalNodeCount += truncatedPoints.size

                if (totalNodeCount >= 192) break
            }
        }

        return filteredSegments
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
