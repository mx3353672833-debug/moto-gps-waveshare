import Foundation
import XCTest
@testable import MOTO_GPS

final class BLEWritePumpPolicyTests: XCTestCase {
    func testPumpNeverRequestsBurstAndHonoursBackpressure() {
        XCTAssertEqual(
            BLEWritePumpPolicy.action(
                pendingFrameCount: 20,
                canSend: true,
                nowMs: 1_000,
                lastSendMs: 0,
                pacingMs: 15
            ),
            .sendOne
        )
        XCTAssertEqual(
            BLEWritePumpPolicy.action(
                pendingFrameCount: 19,
                canSend: true,
                nowMs: 1_010,
                lastSendMs: 1_000,
                pacingMs: 15
            ),
            .wait(milliseconds: 5)
        )
        XCTAssertEqual(
            BLEWritePumpPolicy.action(
                pendingFrameCount: 19,
                canSend: false,
                nowMs: 1_015,
                lastSendMs: 1_000,
                pacingMs: 15
            ),
            .idle
        )
        XCTAssertEqual(
            BLEWritePumpPolicy.action(
                pendingFrameCount: 19,
                canSend: true,
                nowMs: 1_015,
                lastSendMs: 1_000,
                pacingMs: 15
            ),
            .sendOne
        )
    }

    func testNavigationBatchEncodesGeometryBeforeSnapshotWithIncreasingSequence() throws {
        let codec = MotoBLEProtocolCodec(maximumFrameSize: 182)
        let routedState = makeRoutedState()
        let snapshot = MotoBLENavigationSnapshotInput()
        snapshot.stateName = "navigating"
        snapshot.networkName = "online"
        snapshot.displayPageName = "navigation"
        snapshot.hasDestination = true
        snapshot.hasFix = true
        snapshot.hasRouteView = true
        snapshot.routeToken = codec.routeToken(forRouteID: routedState.routeID)
        snapshot.routeGeneration = routedState.routeGeneration

        let frames = try BLEOutboundBatch.encodeNavigation(
            geometryRequired: true,
            encodeGeometry: { try codec.encodeRouteGeometry(from: routedState) },
            encodeSnapshot: { try codec.encodeNavigationSnapshot(snapshot) }
        )

        let parsed = try frames.map(parseFrameHeader)
        let firstSnapshotIndex = try XCTUnwrap(
            parsed.firstIndex(where: { $0.type == 0x10 })
        )
        XCTAssertGreaterThan(firstSnapshotIndex, 0)
        XCTAssertTrue(parsed[..<firstSnapshotIndex].allSatisfy { $0.type == 0x11 })
        XCTAssertTrue(parsed[firstSnapshotIndex...].allSatisfy { $0.type == 0x10 })

        let geometryHeader = parsed[0]
        let snapshotHeader = parsed[firstSnapshotIndex]
        XCTAssertTrue(geometryHeader.isStart)
        XCTAssertTrue(snapshotHeader.isStart)
        XCTAssertEqual(
            snapshotHeader.sequence,
            geometryHeader.sequence == .max ? 1 : geometryHeader.sequence + 1
        )
    }

    func testQueueResetForcesUnchangedGeometryIntoReplacementBatch() {
        XCTAssertTrue(BLEOutboundBatch.requiresGeometry(
            hasGeometry: true,
            geometryChanged: false,
            pendingFrameCount: 127,
            snapshotFrameCount: 2
        ))
        XCTAssertFalse(BLEOutboundBatch.requiresGeometry(
            hasGeometry: true,
            geometryChanged: false,
            pendingFrameCount: 126,
            snapshotFrameCount: 2
        ))
        XCTAssertFalse(BLEOutboundBatch.requiresGeometry(
            hasGeometry: false,
            geometryChanged: true,
            pendingFrameCount: 127,
            snapshotFrameCount: 2
        ))
    }

    private func makeRoutedState() -> MotoNavCoreSnapshot {
        let state = MotoNavCoreSnapshot()
        let points = [
            makePoint(longitude: 117.127_950, latitude: 36.675_100),
            makePoint(longitude: 117.128_400, latitude: 36.675_350),
            makePoint(longitude: 117.129_050, latitude: 36.675_600),
        ]
        // MotoNavCoreSnapshot is immutable to production Swift, while its
        // Objective-C bridge has private setters used to construct snapshots.
        // KVC lets this protocol-order test provide a minimal real geometry
        // input without duplicating the RouteGeometry wire encoder.
        state.setValue(true, forKey: "hasRouteView")
        state.setValue("sequence-order-route", forKey: "routeID")
        state.setValue(NSNumber(value: 7), forKey: "routeGeneration")
        state.setValue(117.127_950, forKey: "routeViewOriginLongitudeDeg")
        state.setValue(36.675_100, forKey: "routeViewOriginLatitudeDeg")
        state.setValue(points, forKey: "routeViewPoints")
        return state
    }

    private func makePoint(longitude: Double, latitude: Double) -> MotoNavPointValue {
        let point = MotoNavPointValue()
        point.longitudeDeg = longitude
        point.latitudeDeg = latitude
        return point
    }

    private func parseFrameHeader(_ frame: Data) throws -> (
        type: UInt8,
        sequence: UInt16,
        isStart: Bool
    ) {
        guard frame.count >= 6 else {
            throw FrameParseError.tooShort
        }
        XCTAssertEqual(frame[0], 0xB7)
        XCTAssertEqual(frame[1], 0x01)
        return (
            type: frame[2],
            sequence: UInt16(frame[4]) | (UInt16(frame[5]) << 8),
            isStart: frame[3] & 0x01 != 0
        )
    }

    private enum FrameParseError: Error {
        case tooShort
    }
}
