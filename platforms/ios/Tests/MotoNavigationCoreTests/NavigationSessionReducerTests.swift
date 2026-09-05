import Foundation
import XCTest
@testable import MotoNavigationCore

final class NavigationSessionReducerTests: XCTestCase {
    private let destination = WGS84Point(longitudeDeg: 116.410886, latitudeDeg: 39.920150)
    private let firstFix = NavigationFix(
        coordinate: WGS84Point(longitudeDeg: 116.397389, latitudeDeg: 39.908722),
        horizontalAccuracyM: 5,
        speedMps: 8,
        courseDeg: 32,
        timestamp: Date(timeIntervalSince1970: 1_700_000_000)
    )

    func testStartWaitsForAFixThenRequestsExactlyOneRoute() throws {
        var reducer = NavigationSessionReducer()

        XCTAssertEqual(reducer.reduce(.start(destination: destination)), [.startLocation])
        XCTAssertEqual(reducer.state.phase, .acquiringLocation)

        let effects = reducer.reduce(.location(firstFix))
        let effect = try XCTUnwrap(effects.first)
        guard case let .requestRoute(routeRequest) = effect else {
            return XCTFail("Expected a route request")
        }

        XCTAssertEqual(routeRequest.origin, firstFix.coordinate)
        XCTAssertEqual(routeRequest.destination, destination)
        XCTAssertEqual(routeRequest.protocolVersion, 1)
        XCTAssertEqual(reducer.state.phase, .planningRoute)
        XCTAssertTrue(reducer.reduce(.location(firstFix)).isEmpty)
    }

    func testOnlyTheActiveResponseMayStartNavigation() throws {
        var reducer = NavigationSessionReducer()
        _ = reducer.reduce(.start(destination: destination))
        let effects = reducer.reduce(.location(firstFix))
        guard case let .requestRoute(request) = try XCTUnwrap(effects.first) else {
            return XCTFail("Expected a route request")
        }

        let plan = samplePlan()
        _ = reducer.reduce(.routeLoaded(RouteEnvelope(requestID: request.requestID + 1, route: plan)))
        XCTAssertEqual(reducer.state.phase, .planningRoute)
        XCTAssertNil(reducer.state.route)

        _ = reducer.reduce(.routeLoaded(RouteEnvelope(requestID: request.requestID, route: plan)))
        XCTAssertEqual(reducer.state.phase, .navigating)
        XCTAssertEqual(reducer.state.route?.routeID, plan.routeID)
    }

    func testStopClearsRuntimeStateAndStopsLocation() {
        var reducer = NavigationSessionReducer()
        _ = reducer.reduce(.start(destination: destination))
        let effects = reducer.reduce(.stop)

        XCTAssertEqual(effects, [.stopLocation])
        XCTAssertEqual(reducer.state, NavigationSessionState())
    }

    private func samplePlan() -> RoutePlan {
        RoutePlan(
            routeID: "route-test",
            provider: "fixture",
            generatedAtMs: 1_700_000_000_000,
            totalDistanceM: 1_200,
            totalDurationS: 300,
            polyline: [
                GCJ02Point(longitudeDeg: 116.4, latitudeDeg: 39.9),
                GCJ02Point(longitudeDeg: 116.41, latitudeDeg: 39.92),
            ],
            maneuvers: [],
            traffic: []
        )
    }
}
