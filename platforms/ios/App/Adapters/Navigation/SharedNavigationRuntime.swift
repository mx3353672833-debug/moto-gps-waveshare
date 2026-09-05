import Foundation
import MotoNavigationCore

/// Executes platform effects around the authoritative shared C++ NavApp/NavCore.
/// It contains no route matching, maneuver progression, reroute or traffic policy.
@MainActor
final class SharedNavigationRuntime {
    var onSnapshot: ((MotoNavCoreSnapshot) -> Void)?
    var onFailure: ((String) -> Void)?

    private let bridge = MotoNavCoreBridge()
    private let locationSource: any NavigationLocationSource
    private let routeProvider: any NavigationRouteProviding
    private var networkTasks: [UInt32: Task<Void, Never>] = [:]
    private var tickTask: Task<Void, Never>?
    private var activeRoute: ActiveRoute?
    private var destinationPOIID: String?

    private struct ActiveRoute {
        let plan: RoutePlan
        let origin: WGS84Point
        let destination: WGS84Point
        let destinationPOIID: String?
    }

    init(
        locationSource: any NavigationLocationSource,
        routeProvider: any NavigationRouteProviding
    ) {
        self.locationSource = locationSource
        self.routeProvider = routeProvider
    }

    @discardableResult
    func start(destination: WGS84Point, destinationPOIID: String? = nil) -> Bool {
        stop()
        self.destinationPOIID = destinationPOIID
        publish(bridge.reset())
        publish(bridge.setNetworkStateName("online"))
        publish(
            bridge.beginNavigation(
                toLongitude: destination.longitudeDeg,
                latitude: destination.latitudeDeg
            )
        )

        do {
            try locationSource.start(
                onFix: { [weak self] fix in self?.accept(fix) },
                onFailure: { [weak self] message in self?.onFailure?(message) }
            )
        } catch {
            onFailure?(error.localizedDescription)
            self.destinationPOIID = nil
            publish(bridge.cancelNavigation())
            return false
        }

        tickTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                self.publish(self.bridge.tick(atMs: Self.nowMs()))
            }
        }
        return true
    }

    func stop() {
        locationSource.stop()
        tickTask?.cancel()
        tickTask = nil
        networkTasks.values.forEach { $0.cancel() }
        networkTasks.removeAll()
        activeRoute = nil
        destinationPOIID = nil
        publish(bridge.cancelNavigation())
    }

    /// Commits an ESP32 page selection through the same shared NavCore that
    /// produces subsequent BLE snapshots, so the phone cannot switch it back.
    @discardableResult
    func selectDisplayPage(rawValue: UInt8) -> Bool {
        let name: String
        switch rawValue {
        case 0: name = "navigation"
        case 1: name = "speed"
        case 2: name = "compass"
        case 3: name = "music"
        default: return false
        }
        publish(bridge.selectDisplayPageName(name))
        return bridge.snapshot.displayPageName == name
    }

    private func accept(_ fix: NavigationFix) {
        let commands = bridge.pushFixLongitude(
            fix.coordinate.longitudeDeg,
            latitude: fix.coordinate.latitudeDeg,
            accuracyM: fix.horizontalAccuracyM,
            speedMPS: fix.speedMps ?? 0,
            headingDeg: fix.courseDeg ?? 0,
            timestampMs: UInt64(max(0, fix.timestamp.timeIntervalSince1970 * 1_000))
        )
        publish(commands)
    }

    private func publish(_ commands: [MotoNavCoreCommand]) {
        onSnapshot?(bridge.snapshot)
        execute(commands)
    }

    private func execute(_ commands: [MotoNavCoreCommand]) {
        for command in commands {
            guard networkTasks[command.requestID] == nil else { continue }
            switch command.typeName {
            case "request_route":
                let request = RouteRequest(
                    requestID: command.requestID,
                    origin: WGS84Point(
                        longitudeDeg: command.originLongitudeDeg,
                        latitudeDeg: command.originLatitudeDeg
                    ),
                    destination: WGS84Point(
                        longitudeDeg: command.destinationLongitudeDeg,
                        latitudeDeg: command.destinationLatitudeDeg
                    ),
                    isReroute: command.reroute,
                    previousRouteID: bridge.snapshot.routeID.isEmpty
                        ? nil
                        : bridge.snapshot.routeID,
                    destinationPOIID: destinationPOIID
                )
                runRouteRequest(request)

            case "request_traffic":
                // A traffic segment offset is measured from the beginning of
                // the complete route. Refresh with that route's original
                // endpoints, never with the rider's current position.
                guard let activeRoute,
                      activeRoute.plan.routeID == command.routeID
                else {
                    publish(
                        bridge.rejectTrafficRequestID(
                            command.requestID,
                            receivedAtMs: Self.nowMs()
                        )
                    )
                    continue
                }
                let request = RouteRequest(
                    requestID: command.requestID,
                    origin: activeRoute.origin,
                    destination: activeRoute.destination,
                    previousRouteID: activeRoute.plan.routeID,
                    destinationPOIID: activeRoute.destinationPOIID
                )
                runTrafficRequest(request, baseline: activeRoute)

            default:
                continue
            }
        }
    }

    private func runRouteRequest(_ request: RouteRequest) {
        networkTasks[request.requestID] = Task { @MainActor [weak self, routeProvider] in
            defer { self?.networkTasks[request.requestID] = nil }
            do {
                let envelope = try await routeProvider.route(for: request)
                guard !Task.isCancelled, envelope.requestID == request.requestID else { return }
                guard let self else { return }
                let previousGeneration = self.bridge.snapshot.routeGeneration
                let commands = self.bridge.acceptRoute(
                    Self.bridgeRoute(envelope.route),
                    requestID: request.requestID,
                    receivedAtMs: Self.nowMs()
                )
                if self.bridge.snapshot.routeGeneration != previousGeneration,
                   self.bridge.snapshot.routeID == envelope.route.routeID
                {
                    self.activeRoute = ActiveRoute(
                        plan: envelope.route,
                        origin: request.origin,
                        destination: request.destination,
                        destinationPOIID: request.destinationPOIID
                    )
                }
                self.publish(commands)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self?.publish(
                    self?.bridge.rejectRouteRequestID(
                        request.requestID,
                        retryable: true,
                        receivedAtMs: Self.nowMs()
                    ) ?? []
                )
                self?.onFailure?(error.localizedDescription)
            }
        }
    }

    private func runTrafficRequest(_ request: RouteRequest, baseline: ActiveRoute) {
        networkTasks[request.requestID] = Task { @MainActor [weak self, routeProvider] in
            defer { self?.networkTasks[request.requestID] = nil }
            do {
                let envelope = try await routeProvider.route(for: request)
                guard !Task.isCancelled,
                      envelope.requestID == request.requestID,
                      let self
                else { return }

                // AMap can choose a different route during a refresh. Its
                // traffic offsets are unsafe for the currently displayed route
                // unless the complete GCJ-02 polyline still matches exactly.
                guard self.activeRoute?.plan.routeID == baseline.plan.routeID,
                      TrafficRefreshPolicy.sharesCompleteRouteBaseline(
                          current: baseline.plan,
                          refreshed: envelope.route
                      )
                else {
                    self.publish(
                        self.bridge.rejectTrafficRequestID(
                            request.requestID,
                            receivedAtMs: Self.nowMs()
                        )
                    )
                    return
                }

                let snapshot = self.bridge.snapshot
                let remainingDurationS = TrafficRefreshPolicy.remainingDurationSeconds(
                    refreshedCompleteDurationS: envelope.route.totalDurationS,
                    remainingDistanceM: snapshot.remainingDistanceM,
                    completeDistanceM: snapshot.totalDistanceM
                )
                self.publish(
                    self.bridge.acceptTraffic(
                        forRouteID: baseline.plan.routeID,
                        segments: Self.bridgeTraffic(envelope.route.traffic),
                        remainingDurationS: remainingDurationS,
                        requestID: request.requestID,
                        observedAtMs: Self.nowMs()
                    )
                )
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self?.publish(
                    self?.bridge.rejectTrafficRequestID(
                        request.requestID,
                        receivedAtMs: Self.nowMs()
                    ) ?? []
                )
            }
        }
    }

    private static func bridgeRoute(_ route: RoutePlan) -> MotoNavRouteValue {
        let value = MotoNavRouteValue()
        value.routeID = route.routeID
        value.totalDistanceM = route.totalDistanceM
        value.totalDurationS = UInt32(clamping: route.totalDurationS)
        value.generatedAtMs = route.generatedAtMs
        value.polyline = route.polyline.map { point in
            let result = MotoNavPointValue()
            result.longitudeDeg = point.longitudeDeg
            result.latitudeDeg = point.latitudeDeg
            return result
        }
        value.maneuvers = route.maneuvers.map { maneuver in
            let result = MotoNavManeuverValue()
            result.identifier = maneuver.id
            result.typeName = maneuver.type.rawValue
            result.routeOffsetM = maneuver.routeOffsetM
            result.roadName = maneuver.roadName
            result.instructionText = maneuver.instruction
            result.roundaboutExit = UInt8(clamping: maneuver.roundaboutExit)
            return result
        }
        value.traffic = bridgeTraffic(route.traffic)
        return value
    }

    private static func bridgeTraffic(_ traffic: [TrafficSegment]) -> [MotoNavTrafficValue] {
        traffic.map { segment in
            let result = MotoNavTrafficValue()
            result.startOffsetM = segment.startOffsetM
            result.endOffsetM = segment.endOffsetM
            result.levelName = segment.level.rawValue
            return result
        }
    }

    private static func nowMs() -> UInt64 {
        UInt64(Date().timeIntervalSince1970 * 1_000)
    }
}
