import Foundation
import MotoNavigationCore

private struct DemoDrivePath: Sendable {
    let points: [WGS84Point]
    let cumulativeDistancesM: [Double]

    init?(points: [WGS84Point]) {
        guard points.count >= 2 else { return nil }
        self.points = points
        var cumulative = Array(repeating: 0.0, count: points.count)
        for index in 1 ..< points.count {
            cumulative[index] = cumulative[index - 1] + Self.distance(
                from: points[index - 1],
                to: points[index]
            )
        }
        guard let distance = cumulative.last, distance > 1 else { return nil }
        cumulativeDistancesM = cumulative
    }

    var distanceM: Double { cumulativeDistancesM.last ?? 0 }

    func point(at offsetM: Double) -> WGS84Point {
        let offset = max(0, min(offsetM, distanceM))
        guard let upperIndex = cumulativeDistancesM.firstIndex(where: { $0 > offset }) else {
            return points[points.count - 1]
        }
        guard upperIndex > 0 else { return points[0] }
        let lowerIndex = upperIndex - 1
        let segmentDistance = cumulativeDistancesM[upperIndex] -
            cumulativeDistancesM[lowerIndex]
        let fraction = segmentDistance > 0
            ? (offset - cumulativeDistancesM[lowerIndex]) / segmentDistance
            : 0
        let start = points[lowerIndex]
        let end = points[upperIndex]
        return WGS84Point(
            longitudeDeg: start.longitudeDeg +
                (end.longitudeDeg - start.longitudeDeg) * fraction,
            latitudeDeg: start.latitudeDeg +
                (end.latitudeDeg - start.latitudeDeg) * fraction
        )
    }

    private static func distance(from start: WGS84Point, to end: WGS84Point) -> Double {
        let earthRadiusM = 6_371_000.0
        let latitude = (start.latitudeDeg + end.latitudeDeg) * 0.5 * .pi / 180
        let northM = (end.latitudeDeg - start.latitudeDeg) * .pi / 180 * earthRadiusM
        let eastM = (end.longitudeDeg - start.longitudeDeg) * .pi / 180 *
            cos(latitude) * earthRadiusM
        return hypot(eastM, northM)
    }
}

/// Couples the demo route provider to the simulated GNSS source. The source
/// emits only the initial D-building fix until the provider has returned a
/// route, then follows that exact returned polyline by travelled metres.
@MainActor
final class DemoNavigationSession {
    fileprivate private(set) var drivePath: DemoDrivePath?
    fileprivate private(set) var routeRevision = 0

    func install(route: RoutePlan) {
        let points: [WGS84Point]
        if route.provider == "openstreetmap-demo-fallback" {
            points = JinanDemoFixture.routeWGS84
        } else {
            points = route.polyline.map(ChinaCoordinateTransform.gcj02ToWGS84)
        }
        guard let path = DemoDrivePath(points: points) else { return }
        drivePath = path
        routeRevision &+= 1
    }
}

@MainActor
final class DemoNavigationLocationSource: NavigationLocationSource {
    private static let frameRateHz = 25.0
    private static let frameIntervalSeconds = 1.0 / frameRateHz
    private static let speedMps = 12.0
    private let session: DemoNavigationSession
    private var task: Task<Void, Never>?

    init(session: DemoNavigationSession) {
        self.session = session
    }

    func start(
        onFix: @escaping @MainActor (NavigationFix) -> Void,
        onFailure _: @escaping @MainActor (String) -> Void
    ) throws {
        stop()

        // This first real POI coordinate triggers the route request. Movement
        // deliberately waits for DemoNavigationRouteProvider to install the
        // returned geometry, preventing a hand-drawn path from diverging from
        // what the display is rendering.
        Self.emit(
            point: JinanDemoFixture.requestedOriginWGS84,
            heading: 0,
            speedMps: 0,
            onFix: onFix
        )

        task = Task { @MainActor [session] in
            var observedRevision = session.routeRevision
            var startedAt: TimeInterval?
            while !Task.isCancelled {
                guard let path = session.drivePath else {
                    try? await Task.sleep(for: .milliseconds(40))
                    continue
                }

                if startedAt == nil || observedRevision != session.routeRevision {
                    observedRevision = session.routeRevision
                    startedAt = ProcessInfo.processInfo.systemUptime
                }
                guard let startedAt else { continue }

                let elapsed = max(0, ProcessInfo.processInfo.systemUptime - startedAt)
                let routeOffsetM = min(path.distanceM, elapsed * Self.speedMps)
                let tangentHalfWindowM = 11.0
                let point = path.point(at: routeOffsetM)
                let heading = Self.bearing(
                    from: path.point(at: routeOffsetM - tangentHalfWindowM),
                    to: path.point(at: routeOffsetM + tangentHalfWindowM)
                )
                let isFinal = routeOffsetM >= path.distanceM
                Self.emit(
                    point: point,
                    heading: heading,
                    speedMps: isFinal ? 0 : Self.speedMps,
                    onFix: onFix
                )
                if isFinal {
                    // Shared NavCore confirms arrival using consecutive fixes.
                    try? await Task.sleep(for: .milliseconds(40))
                    if !Task.isCancelled {
                        Self.emit(
                            point: point,
                            heading: heading,
                            speedMps: 0,
                            onFix: onFix
                        )
                    }
                    return
                }

                let nextFrameIndex = floor(elapsed * Self.frameRateHz) + 1
                let nextDeadline = startedAt +
                    nextFrameIndex * Self.frameIntervalSeconds
                let delay = max(
                    0,
                    nextDeadline - ProcessInfo.processInfo.systemUptime
                )
                if delay > 0 {
                    try? await Task.sleep(for: .seconds(delay))
                } else {
                    await Task.yield()
                }
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    private static func emit(
        point: WGS84Point,
        heading: Double,
        speedMps: Double,
        onFix: @escaping @MainActor (NavigationFix) -> Void
    ) {
        onFix(
            NavigationFix(
                coordinate: point,
                horizontalAccuracyM: 3.5,
                speedMps: speedMps,
                courseDeg: heading,
                timestamp: Date()
            )
        )
    }

    private static func bearing(from start: WGS84Point, to end: WGS84Point) -> Double {
        let latitudeA = start.latitudeDeg * .pi / 180
        let latitudeB = end.latitudeDeg * .pi / 180
        let longitudeDelta = (end.longitudeDeg - start.longitudeDeg) * .pi / 180
        let y = sin(longitudeDelta) * cos(latitudeB)
        let x = cos(latitudeA) * sin(latitudeB) -
            sin(latitudeA) * cos(latitudeB) * cos(longitudeDelta)
        return (atan2(y, x) * 180 / .pi + 360)
            .truncatingRemainder(dividingBy: 360)
    }
}

/// Uses the existing AMap gateway for every demo route request. If the test
/// phone has no network, it falls back to the checked-in OSM/ODbL route so the
/// hardware demo remains usable without persisting an AMap response in source.
final class DemoNavigationRouteProvider: NavigationRouteProviding, @unchecked Sendable {
    private let liveProvider: AmapGatewayRouteProvider
    private let session: DemoNavigationSession

    init(liveProvider: AmapGatewayRouteProvider, session: DemoNavigationSession) {
        self.liveProvider = liveProvider
        self.session = session
    }

    func route(for request: RouteRequest) async throws -> RouteEnvelope {
        let shouldMoveAlongResult = request.previousRouteID == nil || request.isReroute
        do {
            let envelope = try await liveProvider.route(for: request)
            let route = Self.withStableDemoIdentity(
                envelope.route,
                rerouted: request.isReroute,
                provider: "amap-gateway-demo-live"
            )
            if shouldMoveAlongResult {
                await session.install(route: route)
            }
            return RouteEnvelope(requestID: request.requestID, route: route)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            let route = Self.fallbackRoute(
                rerouted: request.isReroute,
                generatedAtMs: UInt64(Date().timeIntervalSince1970 * 1_000)
            )
            if shouldMoveAlongResult {
                await session.install(route: route)
            }
            return RouteEnvelope(requestID: request.requestID, route: route)
        }
    }

    private static func withStableDemoIdentity(
        _ route: RoutePlan,
        rerouted: Bool,
        provider: String
    ) -> RoutePlan {
        RoutePlan(
            routeID: rerouted ? JinanDemoFixture.reroutedRouteID : JinanDemoFixture.routeID,
            provider: provider,
            coordinateSystem: route.coordinateSystem,
            generatedAtMs: route.generatedAtMs,
            totalDistanceM: route.totalDistanceM,
            totalDurationS: route.totalDurationS,
            polyline: route.polyline,
            maneuvers: route.maneuvers,
            traffic: route.traffic
        )
    }

    private static func fallbackRoute(rerouted: Bool, generatedAtMs: UInt64) -> RoutePlan {
        let cumulative = cumulativeDistances(points: JinanDemoFixture.routeGCJ02)
        let totalDistanceM = cumulative.last ?? 0
        let maneuvers = JinanDemoFixture.maneuvers.map { fixture in
            RouteManeuver(
                id: fixture.id,
                type: fixture.type,
                routeOffsetM: cumulative[fixture.routePointIndex],
                roadName: fixture.roadName,
                instruction: fixture.instruction
            )
        }
        return RoutePlan(
            routeID: rerouted ? JinanDemoFixture.reroutedRouteID : JinanDemoFixture.routeID,
            provider: "openstreetmap-demo-fallback",
            generatedAtMs: generatedAtMs,
            totalDistanceM: totalDistanceM,
            totalDurationS: JinanDemoFixture.fallbackDurationS,
            polyline: JinanDemoFixture.routeGCJ02,
            maneuvers: maneuvers,
            traffic: []
        )
    }

    private static func cumulativeDistances(points: [GCJ02Point]) -> [Double] {
        var output = Array(repeating: 0.0, count: points.count)
        guard points.count > 1 else { return output }
        for index in 1 ..< points.count {
            output[index] = output[index - 1] + distance(
                from: points[index - 1],
                to: points[index]
            )
        }
        return output
    }

    private static func distance(from start: GCJ02Point, to end: GCJ02Point) -> Double {
        let earthRadiusM = 6_371_000.0
        let latitude = (start.latitudeDeg + end.latitudeDeg) * 0.5 * .pi / 180
        let northM = (end.latitudeDeg - start.latitudeDeg) * .pi / 180 * earthRadiusM
        let eastM = (end.longitudeDeg - start.longitudeDeg) * .pi / 180 *
            cos(latitude) * earthRadiusM
        return hypot(eastM, northM)
    }
}

enum ChinaCoordinateTransform {
    private static let earthAxisM = 6_378_245.0
    private static let eccentricitySquared = 0.006693421622965943

    static func gcj02ToWGS84(_ point: GCJ02Point) -> WGS84Point {
        var minimumLongitude = point.longitudeDeg - 0.02
        var maximumLongitude = point.longitudeDeg + 0.02
        var minimumLatitude = point.latitudeDeg - 0.02
        var maximumLatitude = point.latitudeDeg + 0.02
        var candidate = WGS84Point(
            longitudeDeg: point.longitudeDeg,
            latitudeDeg: point.latitudeDeg
        )

        for _ in 0 ..< 32 {
            candidate = WGS84Point(
                longitudeDeg: (minimumLongitude + maximumLongitude) * 0.5,
                latitudeDeg: (minimumLatitude + maximumLatitude) * 0.5
            )
            let projected = wgs84ToGCJ02(candidate)
            if projected.longitudeDeg < point.longitudeDeg {
                minimumLongitude = candidate.longitudeDeg
            } else {
                maximumLongitude = candidate.longitudeDeg
            }
            if projected.latitudeDeg < point.latitudeDeg {
                minimumLatitude = candidate.latitudeDeg
            } else {
                maximumLatitude = candidate.latitudeDeg
            }
        }
        return candidate
    }

    private static func wgs84ToGCJ02(_ point: WGS84Point) -> GCJ02Point {
        if point.longitudeDeg < 72.004 || point.longitudeDeg > 137.8347 ||
            point.latitudeDeg < 0.8293 || point.latitudeDeg > 55.8271
        {
            return GCJ02Point(
                longitudeDeg: point.longitudeDeg,
                latitudeDeg: point.latitudeDeg
            )
        }

        let longitudeOffset = point.longitudeDeg - 105
        let latitudeOffset = point.latitudeDeg - 35
        var latitudeDelta = transformLatitude(longitudeOffset, latitudeOffset)
        var longitudeDelta = transformLongitude(longitudeOffset, latitudeOffset)
        let radianLatitude = point.latitudeDeg / 180 * .pi
        var magic = sin(radianLatitude)
        magic = 1 - eccentricitySquared * magic * magic
        let squareRootMagic = sqrt(magic)
        latitudeDelta = latitudeDelta * 180 /
            ((earthAxisM * (1 - eccentricitySquared) /
                (magic * squareRootMagic)) * .pi)
        longitudeDelta = longitudeDelta * 180 /
            ((earthAxisM / squareRootMagic) * cos(radianLatitude) * .pi)
        return GCJ02Point(
            longitudeDeg: point.longitudeDeg + longitudeDelta,
            latitudeDeg: point.latitudeDeg + latitudeDelta
        )
    }

    private static func transformLatitude(_ longitude: Double, _ latitude: Double) -> Double {
        var result = -100 + 2 * longitude + 3 * latitude +
            0.2 * latitude * latitude + 0.1 * longitude * latitude +
            0.2 * sqrt(abs(longitude))
        result += (20 * sin(6 * longitude * .pi) +
            20 * sin(2 * longitude * .pi)) * 2 / 3
        result += (20 * sin(latitude * .pi) +
            40 * sin(latitude / 3 * .pi)) * 2 / 3
        result += (160 * sin(latitude / 12 * .pi) +
            320 * sin(latitude * .pi / 30)) * 2 / 3
        return result
    }

    private static func transformLongitude(_ longitude: Double, _ latitude: Double) -> Double {
        var result = 300 + longitude + 2 * latitude +
            0.1 * longitude * longitude + 0.1 * longitude * latitude +
            0.1 * sqrt(abs(longitude))
        result += (20 * sin(6 * longitude * .pi) +
            20 * sin(2 * longitude * .pi)) * 2 / 3
        result += (20 * sin(longitude * .pi) +
            40 * sin(longitude / 3 * .pi)) * 2 / 3
        result += (150 * sin(longitude / 12 * .pi) +
            300 * sin(longitude / 30 * .pi)) * 2 / 3
        return result
    }
}
