import Foundation
import MotoNavigationCore

private struct CheckFailure: Error, CustomStringConvertible {
    let description: String
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw CheckFailure(description: message) }
}

@main
enum CoreChecks {
    static func main() throws {
        let destination = WGS84Point(longitudeDeg: 116.410886, latitudeDeg: 39.920150)
        let fix = NavigationFix(
            coordinate: WGS84Point(longitudeDeg: 116.397389, latitudeDeg: 39.908722),
            horizontalAccuracyM: 5,
            speedMps: 8,
            courseDeg: 32,
            timestamp: Date(timeIntervalSince1970: 1_700_000_000)
        )
        var reducer = NavigationSessionReducer()

        try require(
            reducer.reduce(.start(destination: destination)) == [.startLocation],
            "start must request the location source"
        )
        let routeEffects = reducer.reduce(.location(fix))
        guard case let .requestRoute(request) = routeEffects.first else {
            throw CheckFailure(description: "first fix must request a route")
        }
        try require(request.protocolVersion == 1, "route request version must remain v1")
        try require(request.origin == fix.coordinate, "route request must use the current WGS84 fix")

        let encoded = try JSONEncoder().encode(request)
        let json = try JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        try require(json?["request_id"] as? Int == Int(request.requestID), "request_id coding drifted")
        try require(json?["route_mode"] as? String == "driving", "route_mode coding drifted")

        let route = RoutePlan(
            routeID: "check-route",
            provider: "fixture",
            generatedAtMs: 1_700_000_000_000,
            totalDistanceM: 2_150,
            totalDurationS: 420,
            polyline: [
                GCJ02Point(longitudeDeg: 116.4, latitudeDeg: 39.9),
                GCJ02Point(longitudeDeg: 116.41, latitudeDeg: 39.92),
            ],
            maneuvers: [],
            traffic: []
        )
        _ = reducer.reduce(.routeLoaded(RouteEnvelope(requestID: request.requestID + 1, route: route)))
        try require(reducer.state.phase == .planningRoute, "stale response must be ignored")
        _ = reducer.reduce(.routeLoaded(RouteEnvelope(requestID: request.requestID, route: route)))
        try require(reducer.state.phase == .navigating, "active response must start navigation")

        let refreshedSameBaseline = RoutePlan(
            routeID: "provider-may-rotate-the-id",
            provider: "amap",
            generatedAtMs: route.generatedAtMs + 60_000,
            totalDistanceM: route.totalDistanceM + 1,
            totalDurationS: 400,
            polyline: route.polyline,
            maneuvers: [],
            traffic: []
        )
        try require(
            TrafficRefreshPolicy.sharesCompleteRouteBaseline(
                current: route,
                refreshed: refreshedSameBaseline
            ),
            "traffic refresh must accept the same complete route geometry"
        )
        let refreshedFromCurrentPosition = RoutePlan(
            routeID: route.routeID,
            provider: "amap",
            generatedAtMs: route.generatedAtMs + 60_000,
            totalDistanceM: 900,
            totalDurationS: 180,
            polyline: [
                GCJ02Point(longitudeDeg: 116.405, latitudeDeg: 39.91),
                GCJ02Point(longitudeDeg: 116.41, latitudeDeg: 39.92),
            ],
            maneuvers: [],
            traffic: []
        )
        try require(
            !TrafficRefreshPolicy.sharesCompleteRouteBaseline(
                current: route,
                refreshed: refreshedFromCurrentPosition
            ),
            "traffic offsets from a current-position route must be rejected"
        )
        try require(
            TrafficRefreshPolicy.remainingDurationSeconds(
                refreshedCompleteDurationS: 400,
                remainingDistanceM: 537.5,
                completeDistanceM: 2_150
            ) == 100,
            "traffic refresh ETA must be scaled to remaining route progress"
        )

        var handshake = BLEHandshakeGate(localMaximumFrameSize: 185)
        try handshake.begin(sessionID: 0x1234)
        let deviceReady = BLEDeviceHandshakeStatus(
            role: 2,
            state: 1,
            minimumVersion: 1,
            maximumVersion: 1,
            capabilities: BLEHandshakeGate.requiredDeviceCapabilities,
            sessionID: 0x1234,
            maximumFrameSize: 160,
            heartbeatIntervalMs: 1_000
        )
        let firstHandshakeTransition = try handshake.acceptDeviceReady(deviceReady)
        try require(
            firstHandshakeTransition == .sendPhoneReady(maximumFrameSize: 160),
            "first Device Ready must release Phone Ready"
        )
        let secondHandshakeTransition = try handshake.acceptDeviceReady(deviceReady)
        try require(
            secondHandshakeTransition == .protocolReady(maximumFrameSize: 160),
            "second validated Device Ready must open the protocol gate"
        )
        try require(
            handshake.negotiatedHeartbeatIntervalMs == 1_000,
            "device heartbeat interval must survive negotiation"
        )
        try require(
            handshake.deviceLivenessTimeoutMs == 5_000,
            "device liveness watchdog must derive from the negotiated interval"
        )
        try handshake.validateProtocolSession(0x1234)
        do {
            try handshake.validateProtocolSession(0x5678)
            throw CheckFailure(description: "wrong-session heartbeat must fail")
        } catch BLEHandshakeValidationError.invalidSession {
            // Expected.
        }
        var changedNegotiationHandshake = BLEHandshakeGate(localMaximumFrameSize: 185)
        try changedNegotiationHandshake.begin(sessionID: 0x1234)
        _ = try changedNegotiationHandshake.acceptDeviceReady(deviceReady)
        let changedVersionRange = BLEDeviceHandshakeStatus(
            role: deviceReady.role,
            state: deviceReady.state,
            minimumVersion: deviceReady.minimumVersion,
            maximumVersion: 2,
            capabilities: deviceReady.capabilities,
            sessionID: deviceReady.sessionID,
            maximumFrameSize: deviceReady.maximumFrameSize,
            heartbeatIntervalMs: deviceReady.heartbeatIntervalMs
        )
        do {
            _ = try changedNegotiationHandshake.acceptDeviceReady(changedVersionRange)
            throw CheckFailure(description: "final Device Ready must not change negotiation")
        } catch BLEHandshakeValidationError.inconsistentNegotiation {
            // Expected.
        }
        var wrongSessionHandshake = BLEHandshakeGate(localMaximumFrameSize: 185)
        try wrongSessionHandshake.begin(sessionID: 7)
        do {
            _ = try wrongSessionHandshake.acceptDeviceReady(deviceReady)
            throw CheckFailure(description: "a mismatched handshake session must fail")
        } catch BLEHandshakeValidationError.invalidSession {
            // Expected.
        }

        print("MotoNavigationCoreChecks: all checks passed")
    }
}
