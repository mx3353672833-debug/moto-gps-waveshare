package com.motogps.android.map

import android.content.Context
import android.util.Log
import com.motogps.android.jni.GeoPointE6
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RoadSegment(
    val roadClass: Int = 5, // 0 motorway, 1 primary, 2 secondary, 3 residential, 4 service, 5 other
    val points: List<GeoPointE6>
)

class HybridMapRepository(
    private val mapsDir: File,
    private val cacheDir: File,
    private val userAgent: String = "MotoGpsWaveshare/1.0",
    private val maxCacheSizeBytes: Long = 1024L * 1024L * 1024L // 1 GB
) {
    constructor(context: Context) : this(
        mapsDir = File(context.getExternalFilesDir(null), "maps"),
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
            Log.e("HybridMapRepo", "Errore creazione directory mappe", e)
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
                Log.e("HybridMapRepo", "Errore scrittura file cache", e)
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

        for (file in files) {
            if (file.isFile && (file.extension == "json" || file.extension == "geojson")) {
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

    private suspend fun downloadVectorData(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val bboxSize = 0.02 // ~2km box around location
        val minLat = lat - bboxSize
        val minLon = lon - bboxSize
        val maxLat = lat + bboxSize
        val maxLon = lon + bboxSize

        val overpassUrl = "https://overpass-api.de/api/interpreter?data=" +
                "[out:json];way[highway]($minLat,$minLon,$maxLat,$maxLon);out%20geom;"

        try {
            val url = URL(overpassUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode in 200..299) {
                BufferedInputStream(conn.inputStream).use { bis ->
                    val buffer = ByteArray(8192)
                    val outBuilder = StringBuilder()
                    var bytesRead: Int
                    while (bis.read(buffer).also { bytesRead = it } != -1) {
                        outBuilder.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                    }
                    outBuilder.toString()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("HybridMapRepo", "Errore download dati vettoriali", e)
            null
        }
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
                                segments.add(RoadSegment(roadClass, points))
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
                        val geomType = geometry.optString("type")

                        if (geomType == "LineString" && geometry.has("coordinates")) {
                            val coords = geometry.getJSONArray("coordinates")
                            val points = parseLineStringCoordinates(coords)
                            if (points.size >= 2) {
                                segments.add(RoadSegment(roadClass, points))
                            }
                        } else if (geomType == "MultiLineString" && geometry.has("coordinates")) {
                            val multiCoords = geometry.getJSONArray("coordinates")
                            for (j in 0 until multiCoords.length()) {
                                val coords = multiCoords.getJSONArray(j)
                                val points = parseLineStringCoordinates(coords)
                                if (points.size >= 2) {
                                    segments.add(RoadSegment(roadClass, points))
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
                    val ptsArray = segObj.getJSONArray("points")
                    val points = mutableListOf<GeoPointE6>()
                    for (j in 0 until ptsArray.length()) {
                        val ptObj = ptsArray.getJSONObject(j)
                        val lat = ptObj.optDouble("latitudeE6", ptObj.optDouble("lat", 0.0) * 1e6).toInt()
                        val lon = ptObj.optDouble("longitudeE6", ptObj.optDouble("lon", 0.0) * 1e6).toInt()
                        points.add(GeoPointE6(lat, lon))
                    }
                    if (points.size >= 2) {
                        segments.add(RoadSegment(roadClass, points))
                    }
                }
            }
        } catch (_: Exception) {
        }

        return segments
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

                filteredSegments.add(RoadSegment(segment.roadClass, truncatedPoints))
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
