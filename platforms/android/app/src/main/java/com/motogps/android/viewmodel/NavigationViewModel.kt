package com.motogps.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEMapRoadPolyline
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLEProtocolCodec
import com.motogps.android.jni.MotoBLERouteGeometry
import com.motogps.android.map.HybridMapRepository
import com.motogps.android.map.SavedMapInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PlaceSearchResult(
    val name: String,
    val address: String,
    val displayArea: String,
    val latitude: Double,
    val longitude: Double
)

data class RouteOption(
    val id: String,
    val name: String,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val steps: List<String>,
    val rawRouteJson: JSONObject
)

data class RegionPreset(
    val id: String,
    val name: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
)

val ITALIAN_REGIONS = listOf(
    RegionPreset("abruzzo", "Abruzzo", 41.68, 13.01, 42.90, 14.79),
    RegionPreset("basilicata", "Basilicata", 39.89, 15.34, 41.14, 16.87),
    RegionPreset("calabria", "Calabria", 37.91, 15.62, 40.15, 17.21),
    RegionPreset("campania", "Campania", 39.99, 13.75, 41.51, 15.81),
    RegionPreset("emilia_romagna", "Emilia-Romagna", 43.73, 9.20, 45.14, 12.76),
    RegionPreset("friuli_venezia_giulia", "Friuli-Venezia Giulia", 45.58, 12.33, 46.65, 13.92),
    RegionPreset("lazio", "Lazio", 41.18, 11.45, 42.84, 14.03),
    RegionPreset("liguria", "Liguria", 43.77, 7.49, 44.68, 10.08),
    RegionPreset("lombardia", "Lombardia", 44.68, 8.49, 46.64, 11.43),
    RegionPreset("marche", "Marche", 42.69, 12.19, 43.97, 13.92),
    RegionPreset("molise", "Molise", 41.36, 13.94, 42.08, 15.16),
    RegionPreset("piemonte", "Piemonte", 44.06, 6.62, 46.46, 9.21),
    RegionPreset("puglia", "Puglia", 39.79, 14.93, 42.23, 18.52),
    RegionPreset("sardegna", "Sardegna", 38.86, 8.13, 41.31, 9.83),
    RegionPreset("sicilia", "Sicilia", 36.64, 12.42, 38.82, 15.66),
    RegionPreset("toscana", "Toscana", 42.23, 9.68, 44.47, 12.37),
    RegionPreset("trentino_alto_adige", "Trentino-Alto Adige", 45.67, 10.38, 47.09, 12.48),
    RegionPreset("umbria", "Umbria", 42.36, 11.89, 43.62, 13.26),
    RegionPreset("valle_daosta", "Valle d'Aosta", 45.46, 6.79, 45.99, 7.95),
    RegionPreset("veneto", "Veneto", 44.79, 10.62, 46.68, 13.10)
)

class NavigationViewModel(
    private val bleManager: MotoBLEManager,
    private val mapRepository: HybridMapRepository? = null
) : ViewModel() {
    private val _connectionState = MutableStateFlow("Non connesso")
    val connectionState: StateFlow<String> = _connectionState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _searchResults = MutableStateFlow<List<PlaceSearchResult>>(emptyList())
    val searchResults: StateFlow<List<PlaceSearchResult>> = _searchResults

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _routePreview = MutableStateFlow<PlaceSearchResult?>(null)
    val routePreview: StateFlow<PlaceSearchResult?> = _routePreview

    private val _routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
    val routeOptions: StateFlow<List<RouteOption>> = _routeOptions

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating

    private val _downloadStatus = MutableStateFlow<String?>(null)
    val downloadStatus: StateFlow<String?> = _downloadStatus

    private val _isDownloadingMap = MutableStateFlow(false)
    val isDownloadingMap: StateFlow<Boolean> = _isDownloadingMap

    private val _savedMaps = MutableStateFlow<List<SavedMapInfo>>(emptyList())
    val savedMaps: StateFlow<List<SavedMapInfo>> = _savedMaps

    private val _currentDevicePage = MutableStateFlow(0)
    val currentDevicePage: StateFlow<Int> = _currentDevicePage

    init {
        bleManager.onDeviceCommandReceived = { cmd ->
            if (cmd.kind == 0) { // PageSelected
                _currentDevicePage.value = cmd.page
            }
        }
    }

    private var downloadJob: Job? = null
    private var navigationJob: Job? = null
    private var currentSnapshot: MotoBLENavigationSnapshot? = null
    private var currentGeometry: MotoBLERouteGeometry? = null

    private var mapSceneRevision = 1L
    private var lastMapSceneLat = 0.0
    private var lastMapSceneLon = 0.0
    private var lastMapSceneTime = 0L
    private val rawRoutePoints = mutableListOf<GeoPointE6>()

    private fun maybeSendMapScene(latitude: Double, longitude: Double, force: Boolean = false) {
        if (!_isNavigating.value) return

        val now = System.currentTimeMillis()
        val distMoved = if (lastMapSceneLat == 0.0 && lastMapSceneLon == 0.0) 999.0
        else calculateDistanceMeters(lastMapSceneLat, lastMapSceneLon, latitude, longitude)

        if (!force && distMoved < 100.0) return

        lastMapSceneLat = latitude
        lastMapSceneLon = longitude
        lastMapSceneTime = now

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (mapSceneRevision == 0L) mapSceneRevision = 1L
                val scene = mapRepository?.fetchMapScene(latitude, longitude, mapSceneRevision++)
                    ?: run {
                        val (gLat, gLon) = mapRepository?.wgs84ToGcj02(latitude, longitude) ?: Pair(latitude, longitude)
                        val originE6 = GeoPointE6((gLat * 1e6).toInt(), (gLon * 1e6).toInt())
                        MotoBLEMapScene(
                            coordinateSystem = 2,
                            sceneRevision = mapSceneRevision++,
                            viewOrigin = originE6,
                            radiusMeters = 500,
                            roads = emptyList(),
                            buildings = emptyList()
                        )
                    }

                bleManager.sendMapScene(scene)
            } catch (e: Exception) {
                Log.e("MapDownload", "Errore download/generazione mappa", e)
                _errorMessage.value = "Errore mappa: ${e.message}"
            }
        }
    }

    fun searchDestination(query: String, gatewayUrl: String) {
        if (query.isBlank() || gatewayUrl.isBlank()) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _routePreview.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    performSearch(query, gatewayUrl)
                }
                _searchResults.value = results
                if (results.isEmpty()) {
                    _errorMessage.value = "Nessun risultato trovato per '$query'"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Errore durante la ricerca: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun previewRoute(place: PlaceSearchResult, gatewayUrl: String) {
        _routePreview.value = place
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val options = withContext(Dispatchers.IO) {
                    fetchRouteOptions(place, gatewayUrl)
                }
                _routeOptions.value = options
            } catch (e: Exception) {
                _errorMessage.value = "Impossibile recuperare opzioni percorso: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectRouteOption(index: Int) {
        if (index in _routeOptions.value.indices) {
            _selectedRouteIndex.value = index
        }
    }

    fun clearRoutePreview() {
        _routePreview.value = null
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0
    }

    fun startNavigation(place: PlaceSearchResult, gatewayUrl: String) {
        if (gatewayUrl.isBlank()) return

        _currentDevicePage.value = 0
        activeDestination = place
        activeGatewayUrl = gatewayUrl
        _isNavigating.value = true

        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val (snapshot, geometry) = withContext(Dispatchers.IO) {
                    performRoutingWithGeometry(place, gatewayUrl)
                }

                currentSnapshot = snapshot
                currentGeometry = geometry

                if (geometry != null) {
                    bleManager.sendRouteGeometry(geometry)
                }
                bleManager.sendNavigationSnapshot(snapshot)

                val (curLat, curLon) = currentLocation.value
                maybeSendMapScene(curLat, curLon, force = true)

                _isLoading.value = false

                var loopCount = 0
                while (_isNavigating.value) {
                    delay(800)
                    if (!_isNavigating.value) break
                    loopCount++

                    val (cLat, cLon) = currentLocation.value
                    val nearbySegs = mapRepository?.getNearbyRoadSegments(cLat, cLon, 300.0) ?: emptyList()
                    val detectedSpeedLimit = findNearestRoadSpeedLimit(nearbySegs, cLat, cLon, 50.0)

                    val snapBase = currentSnapshot?.copy() ?: snapshot.copy()
                    val pageName = when (_currentDevicePage.value) {
                        1 -> "speedometer"
                        2 -> "heading"
                        3 -> "music"
                        else -> "navigation"
                    }
                    val activeSnap = snapBase.copy(
                        displayPageName = pageName,
                        speedLimitKPH = detectedSpeedLimit
                    )

                    bleManager.sendNavigationSnapshot(activeSnap)

                    if (loopCount % 4 == 0) {
                        val activeGeom = currentGeometry ?: geometry
                        if (activeGeom != null) {
                            bleManager.sendRouteGeometry(activeGeom)
                        }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Errore durante la navigazione: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        navigationJob?.cancel()
        navigationJob = null
        activeDestination = null
        currentSnapshot = null
        currentGeometry = null
        lastMapSceneLat = 0.0
        lastMapSceneLon = 0.0
        lastMapSceneTime = 0L
        rawRoutePoints.clear()

        val stopSnapshot = MotoBLENavigationSnapshot(
            stateName = "idle",
            networkName = "online",
            displayPageName = "idle",
            maneuverName = "none",
            trafficName = "none",
            hasDestination = false,
            hasFix = false,
            gnssStale = false,
            offRoute = false,
            routeRequestInFlight = false,
            trafficRequestInFlight = false,
            routeToken = 0L,
            routeGeneration = 0L,
            maneuverID = 0L,
            distanceToManeuverM = 0L,
            remainingDistanceM = 0L,
            remainingDurationS = 0L,
            routeProgressM = 0L,
            totalDistanceM = 0L,
            speedDeciKPH = 0,
            speedLimitKPH = 0,
            headingCentiDegrees = 0,
            accuracyDecimeters = 0,
            crossTrackDecimeters = 0,
            roundaboutExit = 0,
            instructionText = ""
        )
        bleManager.sendNavigationSnapshot(stopSnapshot)

        _routePreview.value = null
        _routeOptions.value = emptyList()
        _selectedRouteIndex.value = 0
    }

    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(41.9028, 12.4964))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation

    private var hasFirstFix = false
    private var activeDestination: PlaceSearchResult? = null
    private var activeGatewayUrl: String = ""
    private var lastRecalculationTime: Long = 0

    fun updateDeviceLocation(latitude: Double, longitude: Double) {
        hasFirstFix = true
        _currentLocation.value = Pair(latitude, longitude)

        if (_isNavigating.value && activeDestination != null && activeGatewayUrl.isNotBlank()) {
            checkOffRouteAndRecalculate(latitude, longitude)
            maybeSendMapScene(latitude, longitude, force = false)
        }
    }

    fun findNearestRoadSpeedLimit(segments: List<com.motogps.android.map.RoadSegment>, lat: Double, lon: Double, maxDistMeters: Double = 50.0): Int {
        var minDistance = maxDistMeters
        var matchedSpeed = 0

        for (seg in segments) {
            if (seg.points.isEmpty()) continue
            for (i in 0 until seg.points.size - 1) {
                val p1 = seg.points[i]
                val p2 = seg.points[i + 1]
                val dist = pointToSegmentDistanceMeters(
                    lat, lon,
                    p1.latitudeE6 / 1e6, p1.longitudeE6 / 1e6,
                    p2.latitudeE6 / 1e6, p2.longitudeE6 / 1e6
                )
                if (dist < minDistance) {
                    minDistance = dist
                    matchedSpeed = seg.maxSpeedKph
                }
            }
        }

        return matchedSpeed
    }

    private fun pointToSegmentDistanceMeters(
        px: Double, py: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double
    ): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) {
            return calculateDistanceMeters(px, py, x1, y1)
        }

        var t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
        t = t.coerceIn(0.0, 1.0)

        val projX = x1 + t * dx
        val projY = y1 + t * dy

        return calculateDistanceMeters(px, py, projX, projY)
    }

    private fun checkOffRouteAndRecalculate(latitude: Double, longitude: Double) {
        val dest = activeDestination ?: return
        val now = System.currentTimeMillis()
        if (now - lastRecalculationTime < 10000) return

        val crossTrackMeters = calculateDistanceMeters(latitude, longitude, dest.latitude, dest.longitude)
        if (crossTrackMeters > 35) {
            lastRecalculationTime = now
            viewModelScope.launch {
                try {
                    val (snapshot, geometry) = withContext(Dispatchers.IO) {
                        performRoutingWithGeometry(dest, activeGatewayUrl)
                    }
                    currentSnapshot = snapshot
                    currentGeometry = geometry
                    if (geometry != null) {
                        bleManager.sendRouteGeometry(geometry)
                    }
                    bleManager.sendNavigationSnapshot(snapshot)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun computeFnv1aRouteToken(routeId: String): Long {
        var hash = 2166136261L
        for (char in routeId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (char.toInt() and 0xFF).toLong()
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return if (hash == 0L) 1L else hash
    }

    private fun fetchRouteOptions(place: PlaceSearchResult, gatewayBaseUrl: String): List<RouteOption> {
        val baseUrl = if (gatewayBaseUrl.endsWith("/")) gatewayBaseUrl else "$gatewayBaseUrl/"
        val endpoint = "${baseUrl}v1/route-options"

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true

        val (originLat, originLon) = currentLocation.value

        val jsonPayload = JSONObject().apply {
            put("protocol_version", 1)
            put("request_id", System.currentTimeMillis().rem(Int.MAX_VALUE).toInt())
            put("route_mode", "driving")

            val originObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", originLon)
                put("latitude_deg", originLat)
            }
            put("origin", originObj)

            val destObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", place.longitude)
                put("latitude_deg", place.latitude)
            }
            put("destination", destObj)
            put("is_reroute", false)
        }

        connection.outputStream.use { os ->
            val input = jsonPayload.toString().toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                val routesArray = jsonObject.optJSONArray("routes")
                val results = mutableListOf<RouteOption>()

                if (routesArray != null && routesArray.length() > 0) {
                    for (i in 0 until routesArray.length()) {
                        val routeObj = routesArray.getJSONObject(i)
                        val summary = routeObj.optJSONObject("summary")
                        val dist = summary?.optLong("total_distance_meters", 0L) ?: routeObj.optLong("total_distance_m", routeObj.optLong("totalDistanceM", 0L))
                        val dur = summary?.optLong("estimated_duration_seconds", 0L) ?: routeObj.optLong("estimated_duration_s", routeObj.optLong("totalDurationS", 0L))

                        val stepsList = mutableListOf<String>()
                        val stepsArr = routeObj.optJSONArray("steps") ?: routeObj.optJSONArray("maneuvers")
                        if (stepsArr != null) {
                            for (j in 0 until stepsArr.length()) {
                                val stepObj = stepsArr.getJSONObject(j)
                                val instruction = stepObj.optString("instruction", stepObj.optString("name", ""))
                                if (instruction.isNotBlank()) {
                                    stepsList.add(instruction)
                                }
                            }
                        }

                        val title = if (i == 0) "Percorso consigliato" else "Percorso alternativo $i"
                        results.add(RouteOption("route_$i", title, dist, dur, stepsList, routeObj))
                    }
                } else if (jsonObject.has("route")) {
                    val routeObj = jsonObject.getJSONObject("route")
                    val dist = routeObj.optLong("total_distance_m", routeObj.optLong("totalDistanceM", 0L))
                    val dur = routeObj.optLong("estimated_duration_s", routeObj.optLong("totalDurationS", 0L))
                    results.add(RouteOption("route_0", "Percorso consigliato", dist, dur, emptyList(), routeObj))
                }
                return results
            } else {
                throw Exception("HTTP Error $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun performRoutingWithGeometry(place: PlaceSearchResult, gatewayBaseUrl: String): Pair<MotoBLENavigationSnapshot, MotoBLERouteGeometry?> {
        val baseUrl = if (gatewayBaseUrl.endsWith("/")) gatewayBaseUrl else "$gatewayBaseUrl/"
        val endpoint = "${baseUrl}v1/routes"

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true

        val (originLat, originLon) = currentLocation.value

        val jsonPayload = JSONObject().apply {
            put("protocol_version", 1)
            put("request_id", System.currentTimeMillis().rem(Int.MAX_VALUE).toInt())
            put("route_mode", "driving")

            val originObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", originLon)
                put("latitude_deg", originLat)
            }
            put("origin", originObj)

            val destObj = JSONObject().apply {
                put("coordinate_system", "WGS84")
                put("longitude_deg", place.longitude)
                put("latitude_deg", place.latitude)
            }
            put("destination", destObj)
            put("is_reroute", false)
        }

        connection.outputStream.use { os ->
            val input = jsonPayload.toString().toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                val protocolVersion = jsonObject.optInt("protocol_version", 1)
                if (protocolVersion != 1) {
                    throw Exception("Versione del protocollo non valida: $protocolVersion")
                }

                val routeObj = jsonObject.getJSONObject("route")
                val totalDistanceM = routeObj.optLong("total_distance_m", routeObj.optDouble("totalDistanceM", 0.0).toLong())
                val totalDurationS = routeObj.optLong("estimated_duration_s", routeObj.optInt("totalDurationS", 0).toLong())
                val routeID = routeObj.optString("route_id", routeObj.optString("routeID", "route_${System.currentTimeMillis()}"))

                var maneuverName = "continue"
                var instruction = "Segui il percorso"
                val maneuversArray = routeObj.optJSONArray("maneuvers")
                if (maneuversArray != null && maneuversArray.length() > 0) {
                    val firstManeuver = maneuversArray.getJSONObject(0)
                    maneuverName = firstManeuver.optString("type", "continue")
                    instruction = firstManeuver.optString("instruction", "Segui il percorso")
                }

                val routeToken = computeFnv1aRouteToken(routeID)

                // Extract geometry points (up to 24)
                val pointsE6 = mutableListOf<GeoPointE6>()
                val geomArray = routeObj.optJSONArray("geometry_gcj02")
                    ?: routeObj.optJSONArray("polyline")
                    ?: routeObj.optJSONArray("coordinates")

                if (geomArray != null) {
                    for (i in 0 until geomArray.length()) {
                        val item = geomArray.optJSONObject(i)
                        if (item != null) {
                            val lat = item.optDouble("latitude_deg", item.optDouble("latitude", Double.NaN))
                            val lon = item.optDouble("longitude_deg", item.optDouble("longitude", Double.NaN))
                            if (!lat.isNaN() && !lon.isNaN()) {
                                pointsE6.add(GeoPointE6((lat * 1e6).toInt(), (lon * 1e6).toInt()))
                            }
                        } else {
                            val coordArr = geomArray.optJSONArray(i)
                            if (coordArr != null && coordArr.length() >= 2) {
                                val lon = coordArr.optDouble(0, Double.NaN)
                                val lat = coordArr.optDouble(1, Double.NaN)
                                if (!lat.isNaN() && !lon.isNaN()) {
                                    pointsE6.add(GeoPointE6((lat * 1e6).toInt(), (lon * 1e6).toInt()))
                                }
                            }
                        }
                    }
                }

                if (pointsE6.isEmpty()) {
                    pointsE6.add(GeoPointE6((originLat * 1e6).toInt(), (originLon * 1e6).toInt()))
                    pointsE6.add(GeoPointE6((place.latitude * 1e6).toInt(), (place.longitude * 1e6).toInt()))
                }

                rawRoutePoints.clear()
                rawRoutePoints.addAll(pointsE6)

                val boundedPoints = pointsE6.take(24)
                val viewOrigin = boundedPoints.first()

                val routeGeometry = MotoBLERouteGeometry(
                    coordinateSystem = 2,
                    routeToken = routeToken,
                    routeGeneration = 1L,
                    chunkIndex = 0,
                    chunkCount = 1,
                    firstPointIndex = 0,
                    totalPointCount = boundedPoints.size,
                    viewOrigin = viewOrigin,
                    points = boundedPoints
                )

                val snapshot = MotoBLENavigationSnapshot(
                    stateName = "navigating",
                    networkName = "online",
                    displayPageName = "navigation",
                    maneuverName = maneuverName,
                    trafficName = "free_flow",
                    hasDestination = true,
                    hasFix = true,
                    gnssStale = false,
                    offRoute = false,
                    routeRequestInFlight = false,
                    trafficRequestInFlight = false,
                    routeToken = routeToken,
                    routeGeneration = 1L,
                    maneuverID = 1L,
                    distanceToManeuverM = totalDistanceM,
                    remainingDistanceM = totalDistanceM,
                    remainingDurationS = totalDurationS,
                    routeProgressM = 0L,
                    totalDistanceM = totalDistanceM,
                    speedDeciKPH = 0,
                    speedLimitKPH = 0,
                    headingCentiDegrees = 0,
                    accuracyDecimeters = 50,
                    crossTrackDecimeters = 0,
                    roundaboutExit = 0,
                    instructionText = instruction
                )

                return Pair(snapshot, routeGeometry)
            } else {
                throw Exception("HTTP Error $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun performSearch(query: String, gatewayBaseUrl: String): List<PlaceSearchResult> {
        val baseUrl = if (gatewayBaseUrl.endsWith("/")) gatewayBaseUrl else "$gatewayBaseUrl/"
        val (originLat, originLon) = currentLocation.value
        val endpoint = "${baseUrl}v1/places?keywords=${URLEncoder.encode(query, "UTF-8")}&longitude_deg=$originLon&latitude_deg=$originLat"

        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                val protocolVersion = jsonObject.optInt("protocol_version", 1)
                if (protocolVersion != 1) {
                    throw Exception("Versione del protocollo non valida: $protocolVersion")
                }

                val placesArray = jsonObject.optJSONArray("places") ?: return emptyList()
                val results = mutableListOf<PlaceSearchResult>()

                for (i in 0 until placesArray.length()) {
                    val placeObj = placesArray.getJSONObject(i)
                    val name = placeObj.optString("name", "")
                    val address = placeObj.optString("address", "")
                    val displayArea = placeObj.optString("display_area", "")

                    val locationObj = placeObj.optJSONObject("location")
                    val lat = locationObj?.optDouble("latitude_deg", 0.0) ?: 0.0
                    val lon = locationObj?.optDouble("longitude_deg", 0.0) ?: 0.0

                    results.add(PlaceSearchResult(name, address, displayArea, lat, lon))
                }
                return results
            } else {
                throw Exception("HTTP Error $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun setConnectionState(state: String) {
        _connectionState.value = state
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun refreshSavedMaps() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mapRepository?.listSavedMaps() ?: emptyList()
            _savedMaps.value = list
        }
    }

    fun deleteSavedMap(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mapRepository?.deleteSavedMap(fileName)
            refreshSavedMaps()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _isDownloadingMap.value = false
        _downloadStatus.value = "Download annullato dall'utente"
    }

    fun startMapDownload(context: android.content.Context) {
        downloadCurrentAreaMap(context)
    }

    fun downloadCurrentAreaMap(context: android.content.Context, radiusKm: Double = 10.0) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _isDownloadingMap.value = true
                    _downloadStatus.value = "Avvio download area attuale..."
                }

                val appContext = context.applicationContext
                val mapsDir = java.io.File(appContext.getExternalFilesDir(null), "maps")
                if (!mapsDir.exists()) mapsDir.mkdirs()

                val (curLat, curLon) = currentLocation.value
                val delta = radiusKm / 111.0 // ~0.09 deg for 10km
                val minLat = curLat - delta
                val minLon = curLon - delta
                val maxLat = curLat + delta
                val maxLon = curLon + delta

                val query = String.format(
                    java.util.Locale.US,
                    "[out:json][timeout:25];way(%f,%f,%f,%f)[highway~\"motorway|trunk|primary|secondary|tertiary|residential\"];out geom qt;",
                    minLat, minLon, maxLat, maxLon
                )

                val jsonResponse = mapRepository?.downloadOverpassQueryWithMirrors(query)
                if (jsonResponse != null) {
                    val repo = mapRepository ?: HybridMapRepository(appContext)
                    val segments = repo.parseGeoJsonOrJson(jsonResponse)
                    val targetFile = java.io.File(mapsDir, "area_corrente_10km.motomap")
                    if (targetFile.exists()) targetFile.delete()
                    repo.appendSegmentsToMotoMap(targetFile, segments)

                    val fileSizeKb = targetFile.length() / 1024
                    withContext(Dispatchers.Main) {
                        _downloadStatus.value = "Mappa area attuale salvata ($fileSizeKb KB)"
                        _isDownloadingMap.value = false
                        refreshSavedMaps()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _downloadStatus.value = "Errore download dati Overpass per area attuale"
                        _isDownloadingMap.value = false
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e("NavigationVM", "Errore durante download area attuale", t)
                withContext(Dispatchers.Main) {
                    _downloadStatus.value = "Errore: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    _isDownloadingMap.value = false
                }
            }
        }
    }

    fun downloadRouteCorridorMap(context: android.content.Context, radiusKm: Double = 10.0) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _isDownloadingMap.value = true
                    _downloadStatus.value = "Preparazione corridoio percorso..."
                }

                val appContext = context.applicationContext
                val mapsDir = java.io.File(appContext.getExternalFilesDir(null), "maps")
                if (!mapsDir.exists()) mapsDir.mkdirs()

                val corridorWaypoints = mutableListOf<Pair<Double, Double>>()
                val (curLat, curLon) = currentLocation.value
                corridorWaypoints.add(Pair(curLat, curLon))

                if (rawRoutePoints.isNotEmpty()) {
                    for (pt in rawRoutePoints) {
                        corridorWaypoints.add(Pair(pt.latitudeE6 / 1e6, pt.longitudeE6 / 1e6))
                    }
                } else {
                    val dest = _routePreview.value
                    if (dest != null) {
                        corridorWaypoints.add(Pair(dest.latitude, dest.longitude))
                    }
                }

                // Sample waypoints every ~10 km
                val sampledPoints = mutableListOf<Pair<Double, Double>>()
                sampledPoints.add(corridorWaypoints.first())
                var lastSampled = corridorWaypoints.first()

                for (i in 1 until corridorWaypoints.size) {
                    val pt = corridorWaypoints[i]
                    val dist = calculateDistanceMeters(lastSampled.first, lastSampled.second, pt.first, pt.second)
                    if (dist >= 10000.0 || i == corridorWaypoints.size - 1) {
                        sampledPoints.add(pt)
                        lastSampled = pt
                    }
                }

                val destNameSanitized = (_routePreview.value?.name ?: "attivo").replace("[^a-zA-Z0-9]".toRegex(), "_")
                val targetFile = java.io.File(mapsDir, "percorso_${destNameSanitized}.motomap")
                if (targetFile.exists()) targetFile.delete()

                val repo = mapRepository ?: HybridMapRepository(appContext)
                val delta = radiusKm / 111.0 // ~0.09 deg for 10km radius

                for (idx in sampledPoints.indices) {
                    val pt = sampledPoints[idx]
                    withContext(Dispatchers.Main) {
                        _downloadStatus.value = "Download corridoio percorso: tratto ${idx + 1} di ${sampledPoints.size}..."
                    }

                    val minLat = pt.first - delta
                    val minLon = pt.second - delta
                    val maxLat = pt.first + delta
                    val maxLon = pt.second + delta

                    val query = String.format(
                        java.util.Locale.US,
                        "[out:json][timeout:25];way(%f,%f,%f,%f)[highway~\"motorway|trunk|primary|secondary|tertiary|residential\"];out geom qt;",
                        minLat, minLon, maxLat, maxLon
                    )

                    val responseJson = repo.downloadOverpassQueryWithMirrors(query)
                    if (responseJson != null) {
                        val segments = repo.parseGeoJsonOrJson(responseJson)
                        repo.appendSegmentsToMotoMap(targetFile, segments)
                    }
                }

                // If Overpass yielded no segments or failed, fallback to rawRoutePoints
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    if (rawRoutePoints.isNotEmpty()) {
                        val fallbackSegments = listOf(com.motogps.android.map.RoadSegment(roadClass = 1, points = rawRoutePoints, maxSpeedKph = 90))
                        repo.appendSegmentsToMotoMap(targetFile, fallbackSegments)
                    }
                }

                val fileSizeKb = targetFile.length() / 1024
                withContext(Dispatchers.Main) {
                    _downloadStatus.value = "Corridoio percorso salvato ($fileSizeKb KB)"
                    _isDownloadingMap.value = false
                    refreshSavedMaps()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e("NavigationVM", "Errore durante download corridoio percorso", t)
                withContext(Dispatchers.Main) {
                    _downloadStatus.value = "Errore: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    _isDownloadingMap.value = false
                }
            }
        }
    }

    fun downloadRegionMap(context: android.content.Context, region: RegionPreset) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _isDownloadingMap.value = true
                    _downloadStatus.value = "Download ${region.name}: Inizializzazione..."
                }

                val appContext = context.applicationContext
                val mapsDir = java.io.File(appContext.getExternalFilesDir(null), "maps")
                if (!mapsDir.exists()) mapsDir.mkdirs()

                val targetFile = java.io.File(mapsDir, "${region.id}.motomap")
                if (targetFile.exists()) targetFile.delete()

                val repo = mapRepository ?: HybridMapRepository(appContext)
                val stepDeg = 0.35

                val tiles = mutableListOf<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
                var currentLat = region.minLat
                while (currentLat < region.maxLat) {
                    var currentLon = region.minLon
                    while (currentLon < region.maxLon) {
                        val tileMinLat = currentLat
                        val tileMinLon = currentLon
                        val tileMaxLat = minOf(currentLat + stepDeg, region.maxLat)
                        val tileMaxLon = minOf(currentLon + stepDeg, region.maxLon)
                        tiles.add(Pair(Pair(tileMinLat, tileMinLon), Pair(tileMaxLat, tileMaxLon)))
                        currentLon += stepDeg
                    }
                    currentLat += stepDeg
                }

                for (i in tiles.indices) {
                    val tile = tiles[i]
                    withContext(Dispatchers.Main) {
                        _downloadStatus.value = "Download ${region.name}: quadrante ${i + 1} di ${tiles.size}..."
                    }

                    val query = String.format(
                        java.util.Locale.US,
                        "[out:json][timeout:25];way(%f,%f,%f,%f)[highway~\"motorway|trunk|primary|secondary|tertiary\"];out geom qt;",
                        tile.first.first, tile.first.second, tile.second.first, tile.second.second
                    )

                    val responseJson = repo.downloadOverpassQueryWithMirrors(query)
                    if (responseJson != null) {
                        val segments = repo.parseGeoJsonOrJson(responseJson)
                        repo.appendSegmentsToMotoMap(targetFile, segments)
                    }
                }

                val fileSizeKb = targetFile.length() / 1024
                withContext(Dispatchers.Main) {
                    _downloadStatus.value = "Mappa ${region.name} salvata ($fileSizeKb KB)"
                    _isDownloadingMap.value = false
                    refreshSavedMaps()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e("NavigationVM", "Errore durante download regione ${region.name}", t)
                withContext(Dispatchers.Main) {
                    _downloadStatus.value = "Errore: ${t.localizedMessage ?: t.javaClass.simpleName}"
                    _isDownloadingMap.value = false
                }
            }
        }
    }
}

class NavigationViewModelFactory(
    private val bleManager: MotoBLEManager,
    private val mapRepository: HybridMapRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NavigationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NavigationViewModel(bleManager, mapRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
