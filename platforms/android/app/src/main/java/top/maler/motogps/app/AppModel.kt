package top.maler.motogps.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import top.maler.motogps.BuildConfig
import top.maler.motogps.ble.BleCommandDisposition
import top.maler.motogps.ble.BleDeviceSnapshot
import top.maler.motogps.ble.Esp32BleCentral
import top.maler.motogps.core.BleGoldenCheck
import top.maler.motogps.core.NavCoreSnapshot
import top.maler.motogps.gateway.GatewayClient
import top.maler.motogps.gateway.GatewayConfiguration
import top.maler.motogps.gateway.KeyValueStore
import top.maler.motogps.gateway.gatewayJson
import top.maler.motogps.location.AndroidNavigationLocationSource
import top.maler.motogps.location.SearchLocationBiasSource
import top.maler.motogps.location.SearchLocationStatus
import top.maler.motogps.map.SurroundingMapStore
import top.maler.motogps.media.MediaSessionController
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.model.PlaceSearchResult
import top.maler.motogps.model.RouteRequest
import top.maler.motogps.model.WGS84Point
import top.maler.motogps.nav.DemoNavigationLocationSource
import top.maler.motogps.nav.DemoNavigationRouteProvider
import top.maler.motogps.nav.DemoNavigationSession
import top.maler.motogps.nav.GatewayRouteProvider
import top.maler.motogps.nav.JinanDemoFixture
import top.maler.motogps.nav.PreviewSelectedRouteProvider
import top.maler.motogps.nav.RoutePreviewCandidate
import top.maler.motogps.nav.SharedNavigationRuntime
import top.maler.motogps.nav.formatDistance
import top.maler.motogps.nav.formatDuration
import top.maler.motogps.service.NavigationForegroundService

private class PreferencesStore(context: Context) : KeyValueStore {
    private val preferences = context.getSharedPreferences("moto_gps", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = preferences.getString(key, null)
    override fun putString(key: String, value: String) {
        preferences.edit { putString(key, value) }
    }
    fun remove(key: String) = preferences.edit { remove(key) }
}

/**
 * Application-wide state: one destination → route → ride flow plus the round
 * screen link. It outlives activities so a ride continues with the screen off;
 * the foreground service only keeps the process alive. Port of AppModel.swift.
 * Main thread only.
 */
class AppModel(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val preferences = PreferencesStore(context)

    var navigation by mutableStateOf(NavCoreSnapshot())
        private set
    var device by mutableStateOf(BleDeviceSnapshot())
        private set
    var navigationFailure by mutableStateOf<String?>(null)
        private set
    var isNavigationActive by mutableStateOf(false)
        private set
    var isDemoActive by mutableStateOf(false)
        private set

    var destinationQuery by mutableStateOf("")
        private set
    var placeResults by mutableStateOf<List<PlaceSearchResult>>(emptyList())
        private set
    var selectedPlace by mutableStateOf<PlaceSearchResult?>(null)
        private set
    var recentPlaces by mutableStateOf<List<PlaceSearchResult>>(emptyList())
        private set
    var isSearchingPlaces by mutableStateOf(false)
        private set
    var placeSearchFailure by mutableStateOf<String?>(null)
        private set
    var searchLocationStatus by mutableStateOf(SearchLocationStatus.PREPARING)
        private set
    var routePreviewCandidates by mutableStateOf<List<RoutePreviewCandidate>>(emptyList())
        private set
    var selectedRoutePreviewId by mutableStateOf<String?>(null)
        private set
    var routePreviewOrigin by mutableStateOf<WGS84Point?>(null)
        private set
    var isPlanningRoutePreview by mutableStateOf(false)
        private set
    var routePreviewFailure by mutableStateOf<String?>(null)
        private set
    var gatewayBaseUrl by mutableStateOf<String?>(null)
        private set
    var isUpdatingGateway by mutableStateOf(false)
        private set
    var hasMediaAccess by mutableStateOf(false)
        private set

    private val demoFixture: JinanDemoFixture by lazy {
        JinanDemoFixture.parse(context.assets.open(JinanDemoFixture.ASSET_NAME).bufferedReader().use { it.readText() })
    }
    private val goldenFixture: String? by lazy {
        runCatching { context.assets.open(BleGoldenCheck.ASSET_NAME).bufferedReader().use { it.readText() } }.getOrNull()
    }

    val bluetooth = Esp32BleCentral(context, scope, goldenFixture = if (BuildConfig.DEBUG) ({ goldenFixture }) else null)
    private val liveLocation = AndroidNavigationLocationSource(context)
    private val searchLocation = SearchLocationBiasSource(context)
    private val media = MediaSessionController(context)
    private var gatewayClient: GatewayClient? = null
    val surroundingMap: SurroundingMapStore
    private var runtime: SharedNavigationRuntime? = null
    private var placeSearchJob: Job? = null
    private var routePreviewJob: Job? = null
    private var routePreviewRequestId = 1L
    private var routePreviewGeneration = 0L
    private var started = false

    init {
        gatewayBaseUrl = GatewayConfiguration.resolvedUrl(preferences, BuildConfig.BUNDLED_GATEWAY_BASE_URL)
        gatewayClient = gatewayBaseUrl?.let(::GatewayClient)
        surroundingMap = SurroundingMapStore(context, scope, gatewayBaseUrl)
        recentPlaces = loadRecentPlaces()

        surroundingMap.onScene = { scene -> bluetooth.sendMapScene(scene.toBleInput()) }
        bluetooth.onSnapshotChange = { snapshot -> device = snapshot }
        bluetooth.onDeviceCommand = { command ->
            when (command.kind) {
                0 -> runtime?.let { if (it.selectDisplayPage(command.page)) BleCommandDisposition.ACCEPTED else BleCommandDisposition.UNSUPPORTED }
                    ?: BleCommandDisposition.INVALID_STATE
                in 16..19 -> media.handleDeviceCommand(command.kind)
                else -> BleCommandDisposition.UNSUPPORTED
            }
        }
        media.onStateChange = { state -> bluetooth.sendMediaState(state) }
        searchLocation.onLocationChange = { point ->
            val query = destinationQuery.trim()
            if (point != null && selectedPlace == null && query.length >= 2) {
                // A fresh fix may arrive after a nationwide request started;
                // replace it with a location-biased search.
                schedulePlaceSearch(query, delayMs = 0)
            }
            if (point != null && selectedPlace != null && isPlanningRoutePreview && routePreviewJob == null) {
                beginRoutePreviewRequestIfPossible()
            }
        }
        searchLocation.onStatusChange = { status ->
            searchLocationStatus = status
            if (isPlanningRoutePreview && routePreviewJob == null && searchLocation.latestPoint == null) {
                if (status == SearchLocationStatus.PERMISSION_DENIED) {
                    failRoutePreview("需要当前位置才能规划路线，请在系统设置中允许定位")
                } else if (status == SearchLocationStatus.UNAVAILABLE) {
                    failRoutePreview("暂时无法获取当前位置，请到开阔位置后重试")
                }
            }
        }
    }

    /** Begins location, media and round-screen work once the UI is first shown. */
    fun start() {
        if (started) return
        started = true
        searchLocation.prepare()
        media.start()
        hasMediaAccess = media.hasAccess
        bluetooth.connect()
    }

    /** Re-evaluates permissions after the user returns from a dialog or system settings. */
    fun onPermissionsChanged() {
        if (!started) return
        if (searchLocationStatus != SearchLocationStatus.AVAILABLE) searchLocation.prepare()
        media.refreshSessions()
        hasMediaAccess = media.hasAccess
        when (device.connection) {
            BleDeviceSnapshot.Connection.PermissionRequired,
            BleDeviceSnapshot.Connection.BluetoothUnavailable,
            -> bluetooth.connect()
            else -> Unit
        }
    }

    // ------------------------------------------------------------- derived state

    val isGatewayConfigured: Boolean get() = GatewayConfiguration.isValid(gatewayBaseUrl)
    val deviceReady: Boolean get() = device.isReady

    /** Navigation can start before BLE is ready; the link resynchronizes on reconnect. */
    val canStartNavigation: Boolean
        get() = selectedPlace != null && selectedRoutePreview != null && !isPlanningRoutePreview

    val selectedRoutePreview: RoutePreviewCandidate?
        get() = routePreviewCandidates.firstOrNull { it.id == selectedRoutePreviewId }

    val mapDownloadRoute: List<GCJ02Point>
        get() = if (isNavigationActive) runtime?.activeRoutePolyline.orEmpty() else selectedRoutePreview?.route?.polyline.orEmpty()

    val hasRoutePreview: Boolean get() = routePreviewCandidates.isNotEmpty()

    val remainingDistanceText: String get() = formatDistance(navigation.remainingDistanceM)
    val remainingDurationText: String get() = formatDuration(navigation.remainingDurationS)

    val primaryActionTitle: String
        get() = when {
            isNavigationActive -> "结束导航"
            isPlanningRoutePreview -> "正在规划路线"
            selectedPlace == null -> "请先选择终点"
            selectedRoutePreview == null -> "重新规划路线"
            else -> "开始导航"
        }

    val activeDestinationName: String
        get() = if (isDemoActive) "MOTO GPS 演示路线" else selectedPlace?.name ?: "目的地"

    val phaseDetail: String
        get() {
            navigationFailure?.let { return it }
            if (isDemoActive && navigation.stateName == "navigating") {
                return if (deviceReady) "真实济南路网正在同步 · ${JinanDemoFixture.ROAD_ATTRIBUTION}"
                else "真实济南路网运行中 · ${JinanDemoFixture.ROAD_ATTRIBUTION}"
            }
            return when (navigation.stateName) {
                "acquiring" -> "请保持精确定位开启"
                "planning" -> "正在读取高德实时路线与路况"
                "navigating" -> if (deviceReady) "手机可以锁屏并放入口袋" else "手机继续导航，圆屏连接后自动同步"
                "rerouting" -> "新路线生成后会自动同步到圆屏"
                "arrived" -> "本次导航已经完成"
                else -> "选择终点后，路线会通过蓝牙发送到圆屏"
            }
        }

    val searchScopeText: String
        get() = when (searchLocationStatus) {
            SearchLocationStatus.AVAILABLE -> "已按当前位置优先排序"
            SearchLocationStatus.PREPARING -> "正在获取当前位置；暂按全国搜索"
            SearchLocationStatus.PERMISSION_DENIED -> "未获定位权限；暂按全国搜索"
            SearchLocationStatus.UNAVAILABLE -> "当前位置暂不可用；暂按全国搜索"
        }

    // ------------------------------------------------------------- gateway

    suspend fun saveGatewayAddress(address: String) {
        if (isNavigationActive || isUpdatingGateway) throw IllegalStateException("请先结束导航，再更换网关地址。")
        val url = GatewayConfiguration.normalizedUrl(address)
        isUpdatingGateway = true
        try {
            clearDestination()
            surroundingMap.changeGateway(url)
            gatewayClient = GatewayClient(url)
            gatewayBaseUrl = url
            GatewayConfiguration.save(url, preferences)
        } finally {
            isUpdatingGateway = false
        }
    }

    suspend fun checkGateway(address: String): String {
        val url = GatewayConfiguration.normalizedUrl(address)
        return try {
            val health = GatewayClient(url).health()
            if (health.status != "ok") throw IllegalStateException()
            if (health.readyForLiveNavigation) "网关已连接，已启用实时导航配置。" else "网关已连接，但尚未启用实时导航。请检查服务端配置。"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            "连接失败，请检查地址、HTTPS 证书和网关是否已启动。"
        }
    }

    fun gatewayClientOrNull(): GatewayClient? = gatewayClient

    // ------------------------------------------------------------- destination search

    fun updateDestinationQuery(value: String) {
        if (isNavigationActive || isUpdatingGateway) return
        destinationQuery = value
        navigationFailure = null
        val query = value.trim()
        if (selectedPlace?.name == query) {
            placeSearchJob?.cancel()
            placeResults = emptyList()
            isSearchingPlaces = false
            return
        }
        if (selectedPlace?.name != query) {
            selectedPlace = null
            clearRoutePreviewState()
        }
        schedulePlaceSearch(query, delayMs = 480)
    }

    fun submitDestinationSearch() {
        if (isNavigationActive || isUpdatingGateway) return
        searchLocation.refresh()
        schedulePlaceSearch(destinationQuery.trim(), delayMs = 0)
    }

    fun selectPlace(place: PlaceSearchResult) {
        if (isUpdatingGateway) return
        placeSearchJob?.cancel()
        isSearchingPlaces = false
        placeSearchFailure = null
        navigationFailure = null
        selectedPlace = place
        destinationQuery = place.name
        placeResults = emptyList()
        remember(place)
        planRoutePreview()
    }

    fun clearDestination() {
        if (isNavigationActive) return
        placeSearchJob?.cancel()
        destinationQuery = ""
        selectedPlace = null
        placeResults = emptyList()
        isSearchingPlaces = false
        placeSearchFailure = null
        navigationFailure = null
        clearRoutePreviewState()
    }

    fun clearRecentPlaces() {
        recentPlaces = emptyList()
        preferences.remove(RECENT_PLACES_KEY)
    }

    private fun schedulePlaceSearch(query: String, delayMs: Long) {
        placeSearchJob?.cancel()
        placeSearchFailure = null
        val client = gatewayClient
        if (isUpdatingGateway || client == null || !isGatewayConfigured) {
            placeResults = emptyList()
            isSearchingPlaces = false
            if (query.length >= 2) placeSearchFailure = "请先在网关设置中填写服务地址"
            return
        }
        if (query.length < 2 || selectedPlace?.name == query) {
            placeResults = emptyList()
            isSearchingPlaces = false
            return
        }
        // Never leave a previous query tappable while the next is in flight;
        // selecting a stale row could start navigation to the wrong POI.
        placeResults = emptyList()
        isSearchingPlaces = true
        placeSearchJob = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val places = client.searchPlaces(query, searchLocation.latestPoint)
                if (destinationQuery.trim() != query) return@launch
                placeResults = deduplicated(places)
                isSearchingPlaces = false
                placeSearchFailure = if (placeResults.isEmpty()) "没有找到，试试输入更完整的地点名" else null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                placeResults = emptyList()
                isSearchingPlaces = false
                placeSearchFailure = "地点搜索失败，请检查网络后重试"
            }
        }
    }

    // ------------------------------------------------------------- route preview

    fun selectRoutePreview(id: String) {
        if (routePreviewCandidates.none { it.id == id }) return
        selectedRoutePreviewId = id
        navigationFailure = null
    }

    fun planRoutePreview() {
        if (isNavigationActive || isUpdatingGateway || selectedPlace == null) return
        if (!isGatewayConfigured) {
            failRoutePreview("请先在网关设置中填写服务地址")
            return
        }
        routePreviewGeneration += 1
        routePreviewJob?.cancel()
        routePreviewJob = null
        routePreviewCandidates = emptyList()
        selectedRoutePreviewId = null
        routePreviewOrigin = null
        routePreviewFailure = null
        navigationFailure = null
        isPlanningRoutePreview = true

        if (searchLocationStatus == SearchLocationStatus.PERMISSION_DENIED) {
            failRoutePreview("需要当前位置才能规划路线，请在系统设置中允许定位")
            return
        }
        // Always wait for a new fix: reusing the search-bias location can build
        // a route from home after the rider has already moved elsewhere.
        if (searchLocation.refreshForRoutePlanning()) return
        beginRoutePreviewRequestIfPossible()
    }

    private fun beginRoutePreviewRequestIfPossible() {
        val place = selectedPlace ?: return
        val origin = searchLocation.latestPoint ?: return
        val client = gatewayClient ?: return
        if (routePreviewJob != null || !isPlanningRoutePreview) return
        val placeIdentity = placeIdentity(place)
        routePreviewRequestId = (routePreviewRequestId + 1) and 0xFFFF_FFFFL
        if (routePreviewRequestId == 0L) routePreviewRequestId = 1
        val request = RouteRequest(
            requestId = routePreviewRequestId,
            origin = origin,
            destination = place.location,
            destinationPoiId = place.id.ifEmpty { null },
        )
        val generation = routePreviewGeneration
        routePreviewJob = scope.launch {
            try {
                val routes = client.routeOptions(request)
                val current = selectedPlace
                if (current == null || placeIdentity(current) != placeIdentity) return@launch
                val candidates = routes.mapIndexed { index, route -> RoutePreviewCandidate(index, route) }
                val first = candidates.firstOrNull()
                if (first == null) {
                    failRoutePreview("高德没有返回可用路线，请稍后重试")
                    return@launch
                }
                routePreviewCandidates = candidates
                selectedRoutePreviewId = first.id
                routePreviewOrigin = origin
                routePreviewFailure = null
                isPlanningRoutePreview = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failRoutePreview("路线规划失败，请检查网络后重试")
            } finally {
                if (routePreviewGeneration == generation) routePreviewJob = null
            }
        }
    }

    private fun failRoutePreview(message: String) {
        routePreviewJob?.cancel()
        routePreviewJob = null
        routePreviewCandidates = emptyList()
        selectedRoutePreviewId = null
        routePreviewOrigin = null
        isPlanningRoutePreview = false
        routePreviewFailure = message
    }

    private fun clearRoutePreviewState() {
        routePreviewGeneration += 1
        routePreviewJob?.cancel()
        routePreviewJob = null
        routePreviewCandidates = emptyList()
        selectedRoutePreviewId = null
        routePreviewOrigin = null
        isPlanningRoutePreview = false
        routePreviewFailure = null
    }

    // ------------------------------------------------------------- navigation

    fun toggleNavigation() {
        when {
            isNavigationActive -> stopNavigation()
            selectedRoutePreview == null -> planRoutePreview()
            else -> startNavigation()
        }
    }

    fun startNavigation() {
        val client = gatewayClient
        if (isUpdatingGateway || client == null || !isGatewayConfigured) {
            navigationFailure = "请先在网关设置中填写服务地址"
            return
        }
        val place = selectedPlace ?: run {
            navigationFailure = "请先从搜索结果中选择终点"
            return
        }
        val preview = selectedRoutePreview ?: run {
            navigationFailure = "请先完成路线规划并选择一条路线"
            return
        }
        val origin = routePreviewOrigin ?: run {
            navigationFailure = "路线起点已失效，请重新规划路线"
            planRoutePreview()
            return
        }
        routePreviewJob?.cancel()
        routePreviewJob = null
        surroundingMap.reset()
        closeRuntime()
        val runtime = SharedNavigationRuntime(
            scope = scope,
            locationSource = liveLocation,
            routeProvider = PreviewSelectedRouteProvider(preview.route, origin, GatewayRouteProvider(client)),
        )
        bind(runtime)
        this.runtime = runtime
        isDemoActive = false
        navigationFailure = null
        val started = runtime.start(place.location, place.id.ifEmpty { null })
        isNavigationActive = started
        if (started) {
            NavigationForegroundService.start(context)
        } else {
            closeRuntime()
        }
    }

    fun startDemoNavigation() {
        if (isNavigationActive || isUpdatingGateway) return
        surroundingMap.reset()
        closeRuntime()
        val session = DemoNavigationSession(demoFixture)
        val runtime = SharedNavigationRuntime(
            scope = scope,
            locationSource = DemoNavigationLocationSource(scope, session, demoFixture),
            routeProvider = DemoNavigationRouteProvider(gatewayClient?.let(::GatewayRouteProvider), session, demoFixture),
        )
        bind(runtime)
        this.runtime = runtime
        navigationFailure = null
        isDemoActive = true
        val started = runtime.start(demoFixture.requestedDestinationWgs84, demoFixture.destinationPoiId)
        isNavigationActive = started
        if (started) {
            NavigationForegroundService.start(context)
        } else {
            isDemoActive = false
            closeRuntime()
        }
    }

    fun stopNavigation() {
        closeRuntime()
        isNavigationActive = false
        isDemoActive = false
        navigationFailure = null
        surroundingMap.reset()
        NavigationForegroundService.stop(context)
        // Ending navigation returns to destination search, like the web shell.
        clearDestination()
    }

    fun toggleDeviceConnection() = bluetooth.toggleConnection()

    fun forgetDevice() {
        bluetooth.disconnect()
        bluetooth.forgetKnownDevice()
        bluetooth.connect()
    }

    private fun closeRuntime() {
        runtime?.close()
        runtime = null
    }

    private fun bind(runtime: SharedNavigationRuntime) {
        runtime.onSnapshot = { snapshot ->
            if (this.runtime === runtime || this.runtime == null) {
                navigation = snapshot
                if (snapshot.stateName == "navigating" || snapshot.stateName == "arrived") navigationFailure = null
                bluetooth.sendNavigationSnapshot(snapshot)
                if (snapshot.hasRouteView) {
                    surroundingMap.update(snapshot.routeViewOriginLatitudeDeg, snapshot.routeViewOriginLongitudeDeg)
                }
            }
        }
        runtime.onFailure = { message -> if (this.runtime === runtime) navigationFailure = message }
    }

    // ------------------------------------------------------------- recent places

    private fun remember(place: PlaceSearchResult) {
        val key = placeIdentity(place)
        // Distance belongs to one fix, not to the place itself.
        val updated = listOf(place.copy(distanceM = null)) + recentPlaces.filter { placeIdentity(it) != key }
        recentPlaces = updated.take(8)
        preferences.putString(RECENT_PLACES_KEY, gatewayJson.encodeToString(ListSerializer(PlaceSearchResult.serializer()), recentPlaces))
    }

    private fun loadRecentPlaces(): List<PlaceSearchResult> = runCatching {
        val raw = preferences.getString(RECENT_PLACES_KEY) ?: return emptyList()
        deduplicated(gatewayJson.decodeFromString(ListSerializer(PlaceSearchResult.serializer()), raw)).take(8)
    }.getOrDefault(emptyList())

    companion object {
        private const val RECENT_PLACES_KEY = "MotoGPS.RecentPlaces.v1"

        fun deduplicated(places: List<PlaceSearchResult>): List<PlaceSearchResult> {
            val seen = HashSet<String>()
            return places.filter { seen.add(placeIdentity(it)) }
        }

        fun placeIdentity(place: PlaceSearchResult): String =
            if (place.id.isNotEmpty()) "id:${place.id}" else "geo:${place.name}|${place.location.longitudeDeg}|${place.location.latitudeDeg}"
    }
}
