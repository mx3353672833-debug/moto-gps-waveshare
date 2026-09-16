import Foundation
import XCTest
@testable import MOTO_GPS

final class BLEWritePumpPolicyTests: XCTestCase {
    func testHeartbeatSendsOnlyElapsedSessionTimeAndResetsOnReconnect() {
        var clock = BLESessionHeartbeatClock()
        XCTAssertNil(clock.elapsedMs(sessionID: 1, nowMs: 9_000_000))
        clock.begin(sessionID: 1, nowMs: 9_000_000)
        XCTAssertEqual(clock.elapsedMs(sessionID: 1, nowMs: 9_000_000), 0)
        XCTAssertEqual(clock.elapsedMs(sessionID: 1, nowMs: 9_001_500), 1_500)
        clock.reset()
        XCTAssertNil(clock.elapsedMs(sessionID: 1, nowMs: 9_002_000))
        clock.begin(sessionID: 2, nowMs: 10_000_000)
        XCTAssertNil(clock.elapsedMs(sessionID: 1, nowMs: 10_001_000))
        XCTAssertEqual(clock.elapsedMs(sessionID: 2, nowMs: 10_001_000), 1_000)
    }

    func testHeartbeatElapsedTimePreservesProtocolWrapWithoutExposingUptime() {
        var clock = BLESessionHeartbeatClock()
        let uptime: UInt64 = 80_000_000_000
        clock.begin(sessionID: 7, nowMs: uptime)
        XCTAssertEqual(clock.elapsedMs(sessionID: 7, nowMs: uptime - 1), 0)
        XCTAssertEqual(clock.elapsedMs(sessionID: 7, nowMs: uptime + UInt64(UInt32.max)), UInt32.max)
        XCTAssertEqual(clock.elapsedMs(sessionID: 7, nowMs: uptime + UInt64(UInt32.max) + 1_001), 1_000)
    }

    func testMissingDisconnectCallbackTriggersOneRecovery() throws {
        var teardown = BLETransportTeardown()
        let generation = try XCTUnwrap(teardown.begin())
        XCTAssertTrue(teardown.isWaiting)
        XCTAssertNil(teardown.begin(), "Concurrent failures must share the deadline")
        XCTAssertTrue(teardown.consumeTimeout(generation: generation))
        XCTAssertFalse(teardown.isWaiting)
        XCTAssertFalse(teardown.consumeTimeout(generation: generation))
    }

    func testDisconnectCallbackCancelsFallbackAndAllowsAnotherConnection() throws {
        var teardown = BLETransportTeardown()
        let completed = try XCTUnwrap(teardown.begin())
        teardown.cancel()
        XCTAssertFalse(teardown.isWaiting)
        XCTAssertFalse(teardown.consumeTimeout(generation: completed))
        XCTAssertNotNil(teardown.begin())
    }

    func testLateTimeoutCannotCancelNewConnectionRecovery() throws {
        var teardown = BLETransportTeardown()
        let old = try XCTUnwrap(teardown.begin())
        teardown.cancel()
        let current = try XCTUnwrap(teardown.begin())
        XCTAssertNotEqual(old, current)
        XCTAssertFalse(teardown.consumeTimeout(generation: old))
        XCTAssertTrue(teardown.isWaiting)
        XCTAssertTrue(teardown.consumeTimeout(generation: current))
    }

    func testUserDisconnectInvalidatesAlreadyQueuedRecovery() throws {
        var teardown = BLETransportTeardown()
        let generation = try XCTUnwrap(teardown.begin())
        teardown.cancel()
        teardown.cancel()
        XCTAssertFalse(teardown.consumeTimeout(generation: generation))
        XCTAssertFalse(teardown.isWaiting)
    }

    func testMapAckTimerWaitsForLastFragmentDuringBackpressure() {
        var delivery = BLEMapSceneDelivery()
        delivery.queued(revision: 4, sequence: 40)
        delivery.expire(nowMs: 60_000)
        XCTAssertEqual(delivery.timeoutCount, 0)
        XCTAssertFalse(delivery.shouldSend(revision: 4, queuedFrames: 0))
        delivery.lastFragmentWritten(nowMs: 60_000)
        delivery.expire(nowMs: 62_999)
        XCTAssertEqual(delivery.timeoutCount, 0)
        delivery.expire(nowMs: 63_000)
        XCTAssertEqual(delivery.timeoutCount, 1)
        XCTAssertTrue(delivery.shouldSend(revision: 4, queuedFrames: 0))
    }

    func testRepeatedNegativeMapAcksStopAtThreeAttempts() {
        var delivery = BLEMapSceneDelivery()
        for sequence in UInt16(1)...3 {
            XCTAssertTrue(delivery.shouldSend(revision: 1, queuedFrames: 0))
            delivery.sent(revision: 1, sequence: sequence, nowMs: 1_000)
            XCTAssertTrue(delivery.acknowledge(sequence: sequence, status: 3))
        }
        XCTAssertFalse(delivery.shouldSend(revision: 1, queuedFrames: 0))
        delivery.queueWasDiscarded()
        XCTAssertFalse(delivery.shouldSend(revision: 1, queuedFrames: 0))
    }

    func testMapDeliveryRetriesLostFragmentsAndQueueResetWithoutMovement() {
        var delivery = BLEMapSceneDelivery()
        XCTAssertTrue(delivery.shouldSend(revision: 1, queuedFrames: 0))
        delivery.sent(revision: 1, sequence: 7, nowMs: 1_000)
        XCTAssertFalse(delivery.shouldSend(revision: 1, queuedFrames: 0))
        XCTAssertFalse(delivery.acknowledge(sequence: 6, status: 0))
        delivery.expire(nowMs: 3_999)
        XCTAssertFalse(delivery.shouldSend(revision: 1, queuedFrames: 0))
        delivery.expire(nowMs: 4_000)
        XCTAssertEqual(delivery.timeoutCount, 1)
        XCTAssertTrue(delivery.shouldSend(revision: 1, queuedFrames: 0))
        delivery.sent(revision: 1, sequence: 20, nowMs: 4_000)
        XCTAssertTrue(delivery.acknowledge(sequence: 20, status: 4))
        XCTAssertFalse(delivery.shouldSend(revision: 1, queuedFrames: 0))
        XCTAssertEqual(delivery.timeoutCount, 0)
        delivery.queueWasDiscarded()
        XCTAssertTrue(delivery.shouldSend(revision: 1, queuedFrames: 0))
        XCTAssertFalse(delivery.shouldSend(revision: 1, queuedFrames: 33))
    }

    func testOlderMapAckDoesNotAcknowledgeNewerWindowAndCodecDecodesAck() throws {
        var delivery = BLEMapSceneDelivery()
        let codec = MotoBLEProtocolCodec(maximumFrameSize: 182)
        let frames = try codec.encodeAck(forSequence: 23, status: 0, commandID: 0)
        XCTAssertNotEqual(codec.lastEncodedSequence, 0)
        let inbound = try codec.pushDeviceFrame(try XCTUnwrap(frames.first), receivedAtMs: 1_000)
        let ack = try XCTUnwrap(inbound.acknowledgement)
        XCTAssertEqual(ack.acknowledgedSequence, 23)
        delivery.sent(revision: 8, sequence: 23, nowMs: 1_000)
        XCTAssertTrue(delivery.acknowledge(sequence: ack.acknowledgedSequence, status: ack.status))
        XCTAssertTrue(delivery.shouldSend(revision: 9, queuedFrames: 0))
        delivery.sent(revision: 9, sequence: 30, nowMs: 2_000)
        XCTAssertFalse(delivery.acknowledge(sequence: 23, status: 0))
        XCTAssertFalse(delivery.shouldSend(revision: 9, queuedFrames: 0))
        XCTAssertTrue(delivery.acknowledge(sequence: 30, status: 2))
        XCTAssertTrue(delivery.shouldSend(revision: 9, queuedFrames: 0))
    }

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
