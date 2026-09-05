import Combine
import Foundation
import MotoNavigationCore

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var navigation = MotoNavCoreBridge().snapshot
    @Published private(set) var device = BLEDeviceSnapshot()
    @Published private(set) var navigationFailure: String?
    @Published private(set) var isNavigationActive = false
    @Published private(set) var isDemoActive = false

    @Published var destinationQuery = ""
    @Published private(set) var placeResults: [PlaceSearchResult] = []
    @Published private(set) var selectedPlace: PlaceSearchResult?
    @Published private(set) var recentPlaces: [PlaceSearchResult] = []
    @Published private(set) var isSearchingPlaces = false
    @Published private(set) var placeSearchFailure: String?
    @Published private(set) var searchLocationStatus: SearchLocationBiasStatus = .preparing
    @Published private(set) var routePreviewCandidates: [RoutePreviewCandidate] = []
    @Published private(set) var selectedRoutePreviewID: String?
    @Published private(set) var routePreviewOrigin: WGS84Point?
    @Published private(set) var isPlanningRoutePreview = false
    @Published private(set) var routePreviewFailure: String?

    private let bluetooth = ESP32BLECentral()
    private let liveLocation = CoreLocationNavigationSource()
    private let searchLocation = SearchLocationBiasSource()
    private let liveRouteProvider: AmapGatewayRouteProvider
    private let placeProvider: AmapGatewayPlaceProvider
    private let mediaController = AppleMusicRemoteController()
    private let offlineMapCoordinator: OfflineMapSceneCoordinator?
    private var runtime: SharedNavigationRuntime?
    private var placeSearchTask: Task<Void, Never>?
    private var routePreviewTask: Task<Void, Never>?
    private var routePreviewRequestID: UInt32 = 1
    private var routePreviewGeneration: UInt64 = 0

    private static let recentPlacesKey = "MotoGPS.RecentPlaces.v1" // gitleaks:allow — UserDefaults key name, not a credential.

    init(gatewayBaseURL: URL = AppConfiguration.gatewayBaseURL) {
        liveRouteProvider = AmapGatewayRouteProvider(baseURL: gatewayBaseURL)
        placeProvider = AmapGatewayPlaceProvider(baseURL: gatewayBaseURL)
        offlineMapCoordinator = try? OfflineMapSceneCoordinator()
        recentPlaces = Self.loadRecentPlaces()

        bluetooth.onSnapshotChange = { [weak self] snapshot in
            self?.device = snapshot
        }
        bluetooth.onDeviceCommand = { [weak self] command in
            guard let self else { return .failed }
            switch command.kind {
            case 0:
                guard let runtime = self.runtime else { return .invalidState }
                return runtime.selectDisplayPage(rawValue: command.page)
                    ? .accepted
                    : .unsupported
            case 16 ... 19:
                return self.mediaController.handleDeviceCommand(kind: command.kind)
            default:
                return .unsupported
            }
        }
        mediaController.onStateChange = { [weak self] state in
            self?.bluetooth.sendMediaState(state)
        }
        searchLocation.onLocationChange = { [weak self] point in
            guard let self else { return }
            let query = self.destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            if point != nil, self.selectedPlace == nil, query.count >= 2 {
                // A fresh one-shot fix may arrive after a nationwide request
                // has already started. Replace it with a location-biased search
                // without requesting location again and creating a callback loop.
                self.schedulePlaceSearch(query: query, delay: .zero)
            }
            if point != nil, self.selectedPlace != nil,
               self.isPlanningRoutePreview, self.routePreviewTask == nil
            {
                self.beginRoutePreviewRequestIfPossible()
            }
        }
        searchLocation.onStatusChange = { [weak self] status in
            guard let self else { return }
            self.searchLocationStatus = status
            guard self.isPlanningRoutePreview,
                  self.routePreviewTask == nil,
                  self.searchLocation.latestPoint == nil
            else { return }
            if status == .permissionDenied {
                self.failRoutePreview("需要当前位置才能规划路线，请在系统设置中允许定位")
            } else if status == .unavailable {
                self.failRoutePreview("暂时无法获取当前位置，请到开阔位置后重试")
            }
        }

        searchLocation.prepare()
        mediaController.start()
        bluetooth.connect()

        #if DEBUG
        // Command-line-only hook for a repeatable phone-to-round-screen smoke
        // test. Normal App launches and the visible demo control are unchanged.
        if ProcessInfo.processInfo.arguments.contains("--moto-demo-on-launch") {
            Task { @MainActor [weak self] in
                self?.startDemoNavigation()
            }
        }
        #endif
    }

    deinit {
        placeSearchTask?.cancel()
        routePreviewTask?.cancel()
    }

    var deviceReady: Bool {
        if case .connected = device.connection {
            return device.negotiatedProtocol == "V1"
        }
        return false
    }

    /// Navigation can start before BLE is ready. The central retains the newest
    /// snapshot and synchronizes it when the round display reconnects.
    var canStartNavigation: Bool {
        selectedPlace != nil && selectedRoutePreview != nil && !isPlanningRoutePreview
    }

    var selectedRoutePreview: RoutePreviewCandidate? {
        guard let selectedRoutePreviewID else { return nil }
        return routePreviewCandidates.first { $0.id == selectedRoutePreviewID }
    }

    var hasRoutePreview: Bool {
        !routePreviewCandidates.isEmpty
    }

    var searchBiasAvailable: Bool {
        searchLocationStatus == .available
    }

    var phaseTitle: String {
        if navigationFailure != nil { return "导航需要处理" }
        switch navigation.stateName {
        case "acquiring": return isDemoActive ? "演示即将开始" : "正在获取位置"
        case "planning": return "正在规划路线"
        case "navigating": return isDemoActive ? "正在演示导航" : "导航已发送到圆屏"
        case "rerouting": return "偏航，正在重新规划"
        case "arrived": return isDemoActive ? "演示完成" : "已经到达"
        default: return "准备出发"
        }
    }

    var phaseDetail: String {
        if let navigationFailure { return navigationFailure }
        if isDemoActive, navigation.stateName == "navigating" {
            return deviceReady
                ? "真实济南路网正在同步 · 道路 © OpenStreetMap contributors"
                : "真实济南路网运行中 · 道路 © OpenStreetMap contributors"
        }
        switch navigation.stateName {
        case "acquiring": return "请保持精确定位开启"
        case "planning": return "正在读取高德实时路线与路况"
        case "navigating": return deviceReady ? "手机可以锁屏并放入口袋" : "手机继续导航，圆屏连接后自动同步"
        case "rerouting": return "新路线生成后会自动同步到圆屏"
        case "arrived": return "本次导航已经完成"
        default: return "选择终点后，路线会通过蓝牙发送到圆屏"
        }
    }

    var remainingDistanceText: String {
        formatDistance(navigation.remainingDistanceM)
    }

    var remainingDurationText: String {
        formatDuration(Int(navigation.remainingDurationS))
    }

    var primaryActionTitle: String {
        if isNavigationActive { return "结束导航" }
        if isPlanningRoutePreview { return "正在规划路线" }
        if selectedPlace == nil { return "请先选择终点" }
        if selectedRoutePreview == nil { return "重新规划路线" }
        return "开始导航"
    }

    var activeDestinationName: String {
        isDemoActive ? "MOTO GPS 演示路线" : (selectedPlace?.name ?? "目的地")
    }

    var searchScopeText: String {
        switch searchLocationStatus {
        case .available:
            return "已按当前位置优先排序"
        case .preparing:
            return "正在获取当前位置；暂按全国搜索"
        case .permissionDenied:
            return "未获定位权限；暂按全国搜索"
        case .unavailable:
            return "当前位置暂不可用；暂按全国搜索"
        }
    }

    func destinationQueryDidChange() {
        guard !isNavigationActive else { return }
        navigationFailure = nil
        let query = destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if selectedPlace?.name == query {
            // Selecting a POI writes its canonical name back into the field.
            // That programmatic change must not immediately launch another
            // search and temporarily disable the Start Navigation action.
            placeSearchTask?.cancel()
            placeResults = []
            isSearchingPlaces = false
            return
        }
        if selectedPlace?.name != query {
            selectedPlace = nil
            clearRoutePreviewState()
        }
        schedulePlaceSearch(query: query, delay: .milliseconds(480))
    }

    func submitDestinationSearch() {
        guard !isNavigationActive else { return }
        searchLocation.refresh()
        let query = destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        schedulePlaceSearch(query: query, delay: .zero)
    }

    func selectPlace(_ place: PlaceSearchResult) {
        placeSearchTask?.cancel()
        isSearchingPlaces = false
        placeSearchFailure = nil
        navigationFailure = nil
        selectedPlace = place
        destinationQuery = place.name
        placeResults = []
        remember(place)
        planRoutePreview()
    }

    func clearDestination() {
        guard !isNavigationActive else { return }
        placeSearchTask?.cancel()
        destinationQuery = ""
        selectedPlace = nil
        placeResults = []
        isSearchingPlaces = false
        placeSearchFailure = nil
        navigationFailure = nil
        clearRoutePreviewState()
    }

    func clearRecentPlaces() {
        recentPlaces = []
        UserDefaults.standard.removeObject(forKey: Self.recentPlacesKey)
    }

    func toggleNavigation() {
        if isNavigationActive {
            stopNavigation()
        } else if selectedRoutePreview == nil {
            planRoutePreview()
        } else {
            startNavigation()
        }
    }

    func startNavigation() {
        guard let selectedPlace else {
            navigationFailure = "请先从搜索结果中选择终点"
            return
        }
        guard let selectedRoutePreview else {
            navigationFailure = "请先完成路线规划并选择一条路线"
            return
        }
        guard let routePreviewOrigin else {
            navigationFailure = "路线起点已失效，请重新规划路线"
            planRoutePreview()
            return
        }

        routePreviewTask?.cancel()
        routePreviewTask = nil
        offlineMapCoordinator?.reset()
        runtime?.stop()
        let runtime = SharedNavigationRuntime(
            locationSource: liveLocation,
            routeProvider: PreviewSelectedRouteProvider(
                selectedRoute: selectedRoutePreview.route,
                selectedRouteOrigin: routePreviewOrigin,
                liveProvider: liveRouteProvider
            )
        )
        bind(runtime)
        self.runtime = runtime
        isDemoActive = false
        navigationFailure = nil
        let started = runtime.start(
            destination: selectedPlace.location,
            destinationPOIID: selectedPlace.id.isEmpty ? nil : selectedPlace.id
        )
        isNavigationActive = started
        if !started {
            self.runtime = nil
        }
    }

    func startDemoNavigation() {
        guard !isNavigationActive else { return }
        offlineMapCoordinator?.reset()
        runtime?.stop()
        let demoSession = DemoNavigationSession()
        let runtime = SharedNavigationRuntime(
            locationSource: DemoNavigationLocationSource(session: demoSession),
            routeProvider: DemoNavigationRouteProvider(
                liveProvider: liveRouteProvider,
                session: demoSession
            )
        )
        bind(runtime)
        self.runtime = runtime
        navigationFailure = nil
        isDemoActive = true
        let started = runtime.start(
            destination: JinanDemoFixture.requestedDestinationWGS84,
            destinationPOIID: JinanDemoFixture.destinationPOIID
        )
        isNavigationActive = started
        if !started {
            isDemoActive = false
            self.runtime = nil
        }
    }

    func stopNavigation() {
        runtime?.stop()
        runtime = nil
        isNavigationActive = false
        isDemoActive = false
        navigationFailure = nil
        offlineMapCoordinator?.reset()
    }

    func selectRoutePreview(_ id: String) {
        guard routePreviewCandidates.contains(where: { $0.id == id }) else { return }
        selectedRoutePreviewID = id
        navigationFailure = nil
    }

    func planRoutePreview() {
        guard !isNavigationActive, selectedPlace != nil else { return }
        routePreviewGeneration &+= 1
        routePreviewTask?.cancel()
        routePreviewTask = nil
        routePreviewCandidates = []
        selectedRoutePreviewID = nil
        routePreviewOrigin = nil
        routePreviewFailure = nil
        navigationFailure = nil
        isPlanningRoutePreview = true

        if searchLocationStatus == .permissionDenied {
            failRoutePreview("需要当前位置才能规划路线，请在系统设置中允许定位")
            return
        }
        if searchLocation.refreshForRoutePlanning() {
            // Always wait for this new one-shot fix. Reusing the location that
            // biased an earlier POI search can build a route from home after
            // the rider has already moved elsewhere.
            return
        }
        beginRoutePreviewRequestIfPossible()
    }

    func toggleDeviceConnection() {
        bluetooth.toggleConnection()
    }

    private func bind(_ runtime: SharedNavigationRuntime) {
        runtime.onSnapshot = { [weak self] snapshot in
            self?.navigation = snapshot
            if snapshot.stateName == "navigating" || snapshot.stateName == "arrived" {
                self?.navigationFailure = nil
            }
            self?.bluetooth.sendNavigationSnapshot(snapshot)
            if snapshot.hasRouteView,
               let scene = self?.offlineMapCoordinator?.sceneIfNeeded(
                   latitudeDeg: snapshot.routeViewOriginLatitudeDeg,
                   longitudeDeg: snapshot.routeViewOriginLongitudeDeg
               ) {
                self?.bluetooth.sendMapScene(scene)
            }
        }
        runtime.onFailure = { [weak self] message in
            self?.navigationFailure = message
        }
    }

    private func beginRoutePreviewRequestIfPossible() {
        guard routePreviewTask == nil,
              isPlanningRoutePreview,
              let place = selectedPlace,
              let origin = searchLocation.latestPoint
        else { return }

        let placeIdentity = Self.placeIdentity(place)
        routePreviewRequestID &+= 1
        if routePreviewRequestID == 0 { routePreviewRequestID = 1 }
        let request = RouteRequest(
            requestID: routePreviewRequestID,
            origin: origin,
            destination: place.location,
            destinationPOIID: place.id.isEmpty ? nil : place.id
        )
        let routeProvider = liveRouteProvider
        let generation = routePreviewGeneration
        routePreviewTask = Task { @MainActor [weak self, routeProvider] in
            defer {
                if self?.routePreviewGeneration == generation {
                    self?.routePreviewTask = nil
                }
            }
            do {
                let routes = try await routeProvider.routeOptions(for: request)
                try Task.checkCancellation()
                guard let self,
                      let currentPlace = self.selectedPlace,
                      Self.placeIdentity(currentPlace) == placeIdentity
                else { return }
                let candidates = routes.enumerated().map {
                    RoutePreviewCandidate(ordinal: $0.offset, route: $0.element)
                }
                guard let first = candidates.first else {
                    self.failRoutePreview("高德没有返回可用路线，请稍后重试")
                    return
                }
                self.routePreviewCandidates = candidates
                self.selectedRoutePreviewID = first.id
                self.routePreviewOrigin = origin
                self.routePreviewFailure = nil
                self.isPlanningRoutePreview = false
            } catch is CancellationError {
                return
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.failRoutePreview("路线规划失败，请检查网络后重试")
            }
        }
    }

    private func failRoutePreview(_ message: String) {
        routePreviewTask?.cancel()
        routePreviewTask = nil
        routePreviewCandidates = []
        selectedRoutePreviewID = nil
        routePreviewOrigin = nil
        isPlanningRoutePreview = false
        routePreviewFailure = message
    }

    private func clearRoutePreviewState() {
        routePreviewGeneration &+= 1
        routePreviewTask?.cancel()
        routePreviewTask = nil
        routePreviewCandidates = []
        selectedRoutePreviewID = nil
        routePreviewOrigin = nil
        isPlanningRoutePreview = false
        routePreviewFailure = nil
    }

    private func schedulePlaceSearch(query: String, delay: Duration) {
        placeSearchTask?.cancel()
        placeSearchFailure = nil

        guard query.count >= 2 else {
            placeResults = []
            isSearchingPlaces = false
            return
        }
        guard selectedPlace?.name != query else {
            placeResults = []
            isSearchingPlaces = false
            return
        }

        // Never leave a previous query tappable while the next request is in
        // flight; selecting stale rows can start navigation to the wrong POI.
        placeResults = []
        isSearchingPlaces = true
        placeSearchTask = Task { @MainActor [weak self] in
            do {
                if delay != .zero {
                    try await Task.sleep(for: delay)
                }
                guard let self else { return }
                let places = try await self.placeProvider.search(
                    keywords: query,
                    near: self.searchLocation.latestPoint
                )
                try Task.checkCancellation()
                guard self.destinationQuery.trimmingCharacters(in: .whitespacesAndNewlines) == query else {
                    return
                }
                self.placeResults = Self.deduplicated(places)
                self.isSearchingPlaces = false
                self.placeSearchFailure = self.placeResults.isEmpty
                    ? "没有找到，试试输入更完整的地点名"
                    : nil
            } catch is CancellationError {
                return
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.placeResults = []
                self.isSearchingPlaces = false
                self.placeSearchFailure = "地点搜索失败，请检查网络后重试"
            }
        }
    }

    private func remember(_ place: PlaceSearchResult) {
        let key = Self.placeIdentity(place)
        recentPlaces.removeAll { Self.placeIdentity($0) == key }
        // Distance belongs to one location fix, not to the place itself. Keep
        // the exact destination but do not show a stale distance after the
        // rider moves or relaunches the app.
        recentPlaces.insert(
            PlaceSearchResult(
                id: place.id,
                name: place.name,
                address: place.address,
                city: place.city,
                district: place.district,
                displayArea: place.displayArea,
                location: place.location,
                distanceM: nil
            ),
            at: 0
        )
        if recentPlaces.count > 8 {
            recentPlaces.removeLast(recentPlaces.count - 8)
        }
        if let data = try? JSONEncoder().encode(recentPlaces) {
            UserDefaults.standard.set(data, forKey: Self.recentPlacesKey)
        }
    }

    private static func loadRecentPlaces() -> [PlaceSearchResult] {
        guard let data = UserDefaults.standard.data(forKey: recentPlacesKey),
              let places = try? JSONDecoder().decode([PlaceSearchResult].self, from: data)
        else { return [] }
        return Array(deduplicated(places).prefix(8))
    }

    private static func deduplicated(_ places: [PlaceSearchResult]) -> [PlaceSearchResult] {
        var seen = Set<String>()
        return places.filter { seen.insert(placeIdentity($0)).inserted }
    }

    private static func placeIdentity(_ place: PlaceSearchResult) -> String {
        if !place.id.isEmpty { return "id:\(place.id)" }
        return "geo:\(place.name)|\(place.location.longitudeDeg)|\(place.location.latitudeDeg)"
    }

    private func formatDistance(_ meters: Double) -> String {
        guard meters.isFinite, meters > 0 else { return "--" }
        return meters >= 1_000
            ? String(format: "%.1f km", meters / 1_000)
            : "\(Int(meters.rounded())) m"
    }

    private func formatDuration(_ seconds: Int) -> String {
        guard seconds > 0 else { return "--" }
        let minutes = max(1, Int(round(Double(seconds) / 60)))
        return minutes >= 60 ? "\(minutes / 60)时\(minutes % 60)分" : "\(minutes)分钟"
    }
}
