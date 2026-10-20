package com.motogps.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLERouteGeometry
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

class NavigationViewModel(private val bleManager: MotoBLEManager) : ViewModel() {
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

    private var navigationJob: Job? = null

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

        activeDestination = place
        activeGatewayUrl = gatewayUrl
        isNavigating = true

        navigationJob?.cancel()
        navigationJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val (snapshot, geometry) = withContext(Dispatchers.IO) {
                    performRoutingWithGeometry(place, gatewayUrl)
                }

                if (geometry != null) {
                    bleManager.sendRouteGeometry(geometry)
                }
                bleManager.sendNavigationSnapshot(snapshot)

                while (isNavigating) {
                    delay(800)
                    if (isNavigating) {
                        bleManager.sendNavigationSnapshot(snapshot)
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
        isNavigating = false
        navigationJob?.cancel()
        navigationJob = null
        activeDestination = null
    }

    private val _currentLocation = MutableStateFlow<Pair<Double, Double>>(Pair(41.9028, 12.4964))
    val currentLocation: StateFlow<Pair<Double, Double>> = _currentLocation

    private var hasFirstFix = false
    private var activeDestination: PlaceSearchResult? = null
    private var activeGatewayUrl: String = ""
    private var isNavigating: Boolean = false
    private var lastRecalculationTime: Long = 0

    fun updateDeviceLocation(latitude: Double, longitude: Double) {
        hasFirstFix = true
        _currentLocation.value = Pair(latitude, longitude)

        if (isNavigating && activeDestination != null && activeGatewayUrl.isNotBlank()) {
            checkOffRouteAndRecalculate(latitude, longitude)
        }
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
}

class NavigationViewModelFactory(private val bleManager: MotoBLEManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NavigationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NavigationViewModel(bleManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
