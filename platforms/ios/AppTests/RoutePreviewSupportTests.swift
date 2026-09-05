import Foundation
import MotoNavigationCore
import XCTest
@testable import MOTO_GPS

final class RoutePreviewSupportTests: XCTestCase {
    func testCandidatePresentsDistanceDurationAndCongestion() {
        let candidate = RoutePreviewCandidate(
            ordinal: 0,
            route: route(
                id: "selected",
                distanceM: 2_450,
                durationS: 780,
                traffic: [
                    TrafficSegment(startOffsetM: 0, endOffsetM: 1_900, level: .freeFlow),
                    TrafficSegment(startOffsetM: 1_900, endOffsetM: 2_450, level: .congested),
                ]
            )
        )

        XCTAssertEqual(candidate.title, "推荐路线")
        XCTAssertEqual(candidate.distanceText, "2.5 km")
        XCTAssertEqual(candidate.durationText, "13 分钟")
        XCTAssertEqual(candidate.trafficSummary, "拥堵较多")
    }

    func testSelectedRouteIsSuppliedOnlyForInitialRequest() async throws {
        let selected = route(id: "selected")
        let live = StubRouteProvider(route: route(id: "live"))
        let provider = PreviewSelectedRouteProvider(
            selectedRoute: selected,
            selectedRouteOrigin: WGS84Point(
                longitudeDeg: 117.12,
                latitudeDeg: 36.67
            ),
            liveProvider: live
        )

        let initial = try await provider.route(for: request(id: 11))
        XCTAssertEqual(initial.requestID, 11)
        XCTAssertEqual(initial.route.routeID, "selected")
        XCTAssertEqual(live.callCount, 0)

        let refresh = try await provider.route(
            for: request(id: 12, previousRouteID: "selected")
        )
        XCTAssertEqual(refresh.route.routeID, "live")
        XCTAssertEqual(live.callCount, 1)
    }

    func testSelectedRouteIsRejectedWhenLiveOriginMovedAwayFromPreview() async throws {
        let selected = route(id: "selected")
        let live = StubRouteProvider(route: route(id: "live"))
        let provider = PreviewSelectedRouteProvider(
            selectedRoute: selected,
            selectedRouteOrigin: WGS84Point(
                longitudeDeg: 117.12,
                latitudeDeg: 36.67
            ),
            liveProvider: live
        )

        let movedOrigin = WGS84Point(longitudeDeg: 117.125, latitudeDeg: 36.67)
        let initial = try await provider.route(
            for: request(id: 21, origin: movedOrigin)
        )

        XCTAssertEqual(initial.route.routeID, "live")
        XCTAssertEqual(live.callCount, 1)
    }

    private func request(
        id: UInt32,
        origin: WGS84Point = WGS84Point(
            longitudeDeg: 117.12,
            latitudeDeg: 36.67
        ),
        previousRouteID: String? = nil
    ) -> RouteRequest {
        RouteRequest(
            requestID: id,
            origin: origin,
            destination: WGS84Point(longitudeDeg: 117.13, latitudeDeg: 36.66),
            previousRouteID: previousRouteID
        )
    }

    private func route(
        id: String,
        distanceM: Double = 1_500,
        durationS: Int = 360,
        traffic: [TrafficSegment] = []
    ) -> RoutePlan {
        RoutePlan(
            routeID: id,
            provider: "test",
            generatedAtMs: 1,
            totalDistanceM: distanceM,
            totalDurationS: durationS,
            polyline: [
                GCJ02Point(longitudeDeg: 117.12, latitudeDeg: 36.67),
                GCJ02Point(longitudeDeg: 117.13, latitudeDeg: 36.66),
            ],
            maneuvers: [],
            traffic: traffic
        )
    }
}

private final class StubRouteProvider: NavigationRouteProviding, @unchecked Sendable {
    private let routeValue: RoutePlan
    private let lock = NSLock()
    private var calls = 0

    init(route: RoutePlan) {
        routeValue = route
    }

    var callCount: Int { lock.withLock { calls } }

    func route(for request: RouteRequest) async throws -> RouteEnvelope {
        lock.withLock { calls += 1 }
        return RouteEnvelope(requestID: request.requestID, route: routeValue)
    }
}
