package top.maler.motogps.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePoliciesTest {
    private fun deviceReady(
        sessionId: Long = 42,
        capabilities: Long = 0xFF,
        frame: Int = 185,
        heartbeat: Int = 1_000,
    ) = BleDeviceHandshakeStatus(
        role = 2, state = 1, minimumVersion = 1, maximumVersion = 1,
        capabilities = capabilities, sessionId = sessionId, maximumFrameSize = frame, heartbeatIntervalMs = heartbeat,
    )

    @Test
    fun handshakeRequiresTwoValidatedDeviceReadyMessages() {
        val gate = BleHandshakeGate(localMaximumFrameSize = 244)
        gate.begin(42)
        assertEquals(BleHandshakeTransition.SendPhoneReady(185), gate.acceptDeviceReady(deviceReady()))
        assertEquals(BleHandshakeStage.AWAITING_FINAL_DEVICE_READY, gate.stage)
        assertEquals(BleHandshakeTransition.ProtocolReady(185), gate.acceptDeviceReady(deviceReady()))
        assertEquals(BleHandshakeStage.READY, gate.stage)
        gate.validateProtocolSession(42)
        assertThrows(BleHandshakeException::class.java) { gate.validateProtocolSession(7) }
    }

    @Test
    fun handshakeRejectsWrongSessionAndMissingCapabilities() {
        val gate = BleHandshakeGate(244)
        gate.begin(42)
        assertThrows(BleHandshakeException::class.java) { gate.acceptDeviceReady(deviceReady(sessionId = 43)) }
        assertThrows(BleHandshakeException::class.java) { gate.acceptDeviceReady(deviceReady(capabilities = 0x01)) }
        assertThrows(BleHandshakeException::class.java) { gate.acceptDeviceReady(deviceReady(frame = 12)) }
        assertThrows(BleHandshakeException::class.java) { gate.acceptDeviceReady(deviceReady(heartbeat = 100)) }
        assertThrows(BleHandshakeException::class.java) { gate.acceptDeviceReady(deviceReady().copy(role = 1)) }
        assertThrows(BleHandshakeException::class.java) { gate.begin(0) }
    }

    @Test
    fun finalDeviceReadyMustRepeatTheNegotiatedParameters() {
        val gate = BleHandshakeGate(244)
        gate.begin(42)
        gate.acceptDeviceReady(deviceReady(frame = 185))
        assertThrows(BleHandshakeException::class.java) { gate.acceptDeviceReady(deviceReady(frame = 100)) }
    }

    @Test
    fun livenessTimeoutTracksAValidSlowHeartbeat() {
        val gate = BleHandshakeGate(244)
        gate.begin(42)
        gate.acceptDeviceReady(deviceReady(heartbeat = 2_500))
        assertEquals(7_500L, gate.deviceLivenessTimeoutMs)
        val fast = BleHandshakeGate(244).apply { begin(1); acceptDeviceReady(deviceReady(sessionId = 1)) }
        assertEquals(5_000L, fast.deviceLivenessTimeoutMs)
    }

    @Test
    fun heartbeatSendsOnlyElapsedSessionTimeAndResetsOnReconnect() {
        val clock = BleSessionHeartbeatClock()
        clock.begin(sessionId = 9, nowMs = 1_000_000)
        assertEquals(2_500L, clock.elapsedMs(9, 1_002_500))
        assertNull(clock.elapsedMs(10, 1_002_500))
        clock.reset()
        assertNull(clock.elapsedMs(9, 1_003_000))
        clock.begin(sessionId = 9, nowMs = 5_000)
        assertEquals(0L, clock.elapsedMs(9, 4_000))
    }

    @Test
    fun heartbeatElapsedTimeWrapsAtThirtyTwoBits() {
        val clock = BleSessionHeartbeatClock()
        clock.begin(1, 0)
        assertEquals(5L, clock.elapsedMs(1, 0x1_0000_0005L))
    }

    @Test
    fun pumpNeverRequestsBurstAndHonoursBackpressure() {
        assertEquals(BleWritePumpAction.Idle, BleWritePumpPolicy.action(0, true, 100, 0, 15))
        assertEquals(BleWritePumpAction.SendOne, BleWritePumpPolicy.action(3, true, 100, 0, 15))
        assertEquals(BleWritePumpAction.Wait(10), BleWritePumpPolicy.action(3, true, 105, 100, 15))
        assertEquals(BleWritePumpAction.Idle, BleWritePumpPolicy.action(3, false, 200, 100, 15))
        assertEquals(BleWritePumpAction.SendOne, BleWritePumpPolicy.action(3, true, 115, 100, 15))
    }

    @Test
    fun mapAckTimerWaitsForLastFragmentDuringBackpressure() {
        val delivery = BleMapSceneDelivery()
        assertTrue(delivery.shouldSend(revision = 1, queuedFrames = 0))
        delivery.queued(revision = 1, sequence = 10)
        delivery.expire(nowMs = 60_000)
        assertEquals(0, delivery.timeoutCount)
        delivery.lastFragmentWritten(nowMs = 1_000)
        delivery.expire(nowMs = 3_999)
        assertEquals(0, delivery.timeoutCount)
        delivery.expire(nowMs = 4_000)
        assertEquals(1, delivery.timeoutCount)
        assertNull(delivery.pending)
    }

    @Test
    fun repeatedNegativeMapAcksStopAtThreeAttempts() {
        val delivery = BleMapSceneDelivery()
        repeat(3) { attempt ->
            assertTrue(delivery.shouldSend(1, 0))
            delivery.queued(1, 20 + attempt)
            assertTrue(delivery.acknowledge(20 + attempt, status = 3))
        }
        assertEquals(3, delivery.timeoutCount)
        assertFalse(delivery.shouldSend(1, 0))
    }

    @Test
    fun olderMapAckDoesNotAcknowledgeNewerWindow() {
        val delivery = BleMapSceneDelivery()
        delivery.queued(revision = 2, sequence = 31)
        assertFalse(delivery.acknowledge(sequence = 30, status = 0))
        assertTrue(delivery.acknowledge(sequence = 31, status = 4))
        assertEquals(2L, delivery.acknowledgedRevision)
        assertFalse(delivery.shouldSend(2, 0))
        assertTrue(delivery.shouldSend(3, 0))
        assertFalse(delivery.shouldSend(3, queuedFrames = 33))
        delivery.queueWasDiscarded()
        assertTrue(delivery.shouldSend(2, 0))
    }

    @Test
    fun navigationBatchEncodesGeometryBeforeSnapshotAndQueueResetForcesGeometry() {
        val order = ArrayList<String>()
        val frames = BleOutboundBatch.encodeNavigation(
            geometryRequired = true,
            encodeGeometry = { order += "geometry"; listOf(byteArrayOf(1)) },
            encodeSnapshot = { order += "snapshot"; listOf(byteArrayOf(2)) },
        )
        assertEquals(listOf("geometry", "snapshot"), order)
        assertEquals(listOf(1.toByte(), 2.toByte()), frames.map { it[0] })
        assertFalse(BleOutboundBatch.requiresGeometry(hasGeometry = true, geometryChanged = false, pendingFrameCount = 10, snapshotFrameCount = 2))
        assertTrue(BleOutboundBatch.requiresGeometry(hasGeometry = true, geometryChanged = false, pendingFrameCount = 127, snapshotFrameCount = 2))
        assertFalse(BleOutboundBatch.requiresGeometry(hasGeometry = false, geometryChanged = true, pendingFrameCount = 0, snapshotFrameCount = 2))
    }
}
