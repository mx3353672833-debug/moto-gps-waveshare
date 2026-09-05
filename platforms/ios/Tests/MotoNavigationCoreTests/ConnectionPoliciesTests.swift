import Foundation
import XCTest
@testable import MotoNavigationCore

final class ConnectionPoliciesTests: XCTestCase {
    func testTrafficRefreshRequiresTheSameCompleteRouteBaseline() {
        let current = route(
            id: "route-a",
            distanceM: 1_000,
            points: [point(116.40, 39.90), point(116.41, 39.91)]
        )
        let sameGeometryNewProviderID = route(
            id: "route-b",
            distanceM: 1_001,
            points: current.polyline
        )
        let routeFromCurrentPosition = route(
            id: "route-a",
            distanceM: 600,
            points: [point(116.405, 39.905), point(116.41, 39.91)]
        )

        XCTAssertTrue(
            TrafficRefreshPolicy.sharesCompleteRouteBaseline(
                current: current,
                refreshed: sameGeometryNewProviderID
            )
        )
        XCTAssertFalse(
            TrafficRefreshPolicy.sharesCompleteRouteBaseline(
                current: current,
                refreshed: routeFromCurrentPosition
            )
        )
        XCTAssertEqual(
            TrafficRefreshPolicy.remainingDurationSeconds(
                refreshedCompleteDurationS: 400,
                remainingDistanceM: 250,
                completeDistanceM: 1_000
            ),
            100
        )
    }

    func testHandshakeRequiresTwoValidatedDeviceReadyMessages() throws {
        var gate = BLEHandshakeGate(localMaximumFrameSize: 185)
        try gate.begin(sessionID: 0x1234)

        let ready = deviceReady(sessionID: 0x1234, maximumFrameSize: 160)
        XCTAssertEqual(
            try gate.acceptDeviceReady(ready),
            .sendPhoneReady(maximumFrameSize: 160)
        )
        XCTAssertEqual(gate.stage, .awaitingFinalDeviceReady)
        XCTAssertEqual(
            try gate.acceptDeviceReady(ready),
            .protocolReady(maximumFrameSize: 160)
        )
        XCTAssertEqual(gate.stage, .ready)
        XCTAssertEqual(gate.negotiatedHeartbeatIntervalMs, 1_000)
        XCTAssertEqual(gate.deviceLivenessTimeoutMs, 5_000)
        XCTAssertNoThrow(try gate.validateProtocolSession(0x1234))
        XCTAssertThrowsError(try gate.validateProtocolSession(0x5678)) {
            XCTAssertEqual($0 as? BLEHandshakeValidationError, .invalidSession)
        }
    }

    func testHandshakeRejectsWrongSessionAndMissingCapabilities() throws {
        var gate = BLEHandshakeGate(localMaximumFrameSize: 185)
        try gate.begin(sessionID: 7)

        XCTAssertThrowsError(try gate.acceptDeviceReady(deviceReady(sessionID: 8))) {
            XCTAssertEqual($0 as? BLEHandshakeValidationError, .invalidSession)
        }

        let missingTraffic = BLEDeviceHandshakeStatus(
            role: 2,
            state: 1,
            minimumVersion: 1,
            maximumVersion: 1,
            capabilities: BLEHandshakeGate.requiredDeviceCapabilities & ~(1 << 2),
            sessionID: 7,
            maximumFrameSize: 160,
            heartbeatIntervalMs: 1_000
        )
        XCTAssertThrowsError(try gate.acceptDeviceReady(missingTraffic)) {
            XCTAssertEqual(
                $0 as? BLEHandshakeValidationError,
                .missingCapabilities(1 << 2)
            )
        }
    }

    func testFinalDeviceReadyMustRepeatTheNegotiatedParameters() throws {
        var gate = BLEHandshakeGate(localMaximumFrameSize: 185)
        try gate.begin(sessionID: 7)

        let first = BLEDeviceHandshakeStatus(
            role: 2,
            state: 1,
            minimumVersion: 1,
            maximumVersion: 2,
            capabilities: BLEHandshakeGate.requiredDeviceCapabilities,
            sessionID: 7,
            maximumFrameSize: 160,
            heartbeatIntervalMs: 1_000
        )
        XCTAssertEqual(
            try gate.acceptDeviceReady(first),
            .sendPhoneReady(maximumFrameSize: 160)
        )

        let changedVersionRange = BLEDeviceHandshakeStatus(
            role: 2,
            state: 1,
            minimumVersion: 1,
            maximumVersion: 3,
            capabilities: first.capabilities,
            sessionID: first.sessionID,
            maximumFrameSize: first.maximumFrameSize,
            heartbeatIntervalMs: first.heartbeatIntervalMs
        )
        XCTAssertThrowsError(try gate.acceptDeviceReady(changedVersionRange)) {
            XCTAssertEqual(
                $0 as? BLEHandshakeValidationError,
                .inconsistentNegotiation
            )
        }
    }

    func testLivenessTimeoutTracksAValidSlowHeartbeat() throws {
        var gate = BLEHandshakeGate(localMaximumFrameSize: 185)
        try gate.begin(sessionID: 7)
        let slowReady = deviceReady(
            sessionID: 7,
            heartbeatIntervalMs: 6_000
        )
        _ = try gate.acceptDeviceReady(slowReady)
        _ = try gate.acceptDeviceReady(slowReady)
        XCTAssertEqual(gate.deviceLivenessTimeoutMs, 18_000)
    }

    private func deviceReady(
        sessionID: UInt32,
        maximumFrameSize: UInt16 = 160,
        heartbeatIntervalMs: UInt16 = 1_000
    ) -> BLEDeviceHandshakeStatus {
        BLEDeviceHandshakeStatus(
            role: 2,
            state: 1,
            minimumVersion: 1,
            maximumVersion: 1,
            capabilities: BLEHandshakeGate.requiredDeviceCapabilities,
            sessionID: sessionID,
            maximumFrameSize: maximumFrameSize,
            heartbeatIntervalMs: heartbeatIntervalMs
        )
    }

    private func point(_ longitude: Double, _ latitude: Double) -> GCJ02Point {
        GCJ02Point(longitudeDeg: longitude, latitudeDeg: latitude)
    }

    private func route(
        id: String,
        distanceM: Double,
        points: [GCJ02Point]
    ) -> RoutePlan {
        RoutePlan(
            routeID: id,
            provider: "amap",
            generatedAtMs: 1,
            totalDistanceM: distanceM,
            totalDurationS: 400,
            polyline: points,
            maneuvers: [],
            traffic: []
        )
    }
}
