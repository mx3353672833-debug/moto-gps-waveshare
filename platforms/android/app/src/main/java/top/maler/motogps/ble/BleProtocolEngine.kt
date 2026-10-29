package top.maler.motogps.ble

import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.maler.motogps.core.BleGoldenCheck
import top.maler.motogps.core.BleMapSceneInput
import top.maler.motogps.core.BleMediaStateInput
import top.maler.motogps.core.BleNavigationSnapshotInput
import top.maler.motogps.core.BleProtocolCodec
import top.maler.motogps.core.InboundMessage
import top.maler.motogps.core.NavCoreSnapshot

/** One GATT value to write on the PhoneToDevice characteristic. */
fun interface BleFrameSink {
    /** Returns false when the platform refused the write (busy); the engine retries. */
    fun write(frame: ByteArray, withResponse: Boolean): Boolean
}

/**
 * The phone side of the v1 protocol over one encrypted, subscribed link: full
 * handshake, serialized and paced writes, heartbeat/liveness, ACK handling,
 * de-duplicated device commands, 5 Hz snapshot coalescing and acknowledged
 * map scenes. GATT plumbing lives in [Esp32BleCentral]. Port of the protocol
 * half of ESP32BLECentral.swift. Main thread only.
 */
class BleProtocolEngine(
    private val scope: CoroutineScope,
    private val goldenFixture: (() -> String?)? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Called once the second Device Ready confirms the session. */
    var onProtocolReady: (() -> Unit)? = null

    /** The link cannot recover in place; the transport must tear down and reconnect. */
    var onTransportError: ((String) -> Unit)? = null

    var onDeviceCommand: ((InboundMessage.DeviceCommand) -> BleCommandDisposition)? = null
    var onLastCommandId: ((Int) -> Unit)? = null

    var protocolReady = false
        private set

    private data class Outbound(val frame: ByteArray, val withResponse: Boolean)

    private data class RouteGeometrySignature(
        val routeId: String,
        val generation: Long,
        val originLatitudeE6: Long,
        val originLongitudeE6: Long,
        val pointCoordinatesE6: List<Long>,
    )

    private data class MapScene(val revision: Long, val input: BleMapSceneInput)

    private var sink: BleFrameSink? = null
    private var supportsWithResponse = false
    private var codec: BleProtocolCodec? = null
    private var handshake: BleHandshakeGate? = null
    private var sessionId = 0L
    private var peerCapabilities = 0L
    private val heartbeatClock = BleSessionHeartbeatClock()
    private var lastValidDeviceFrameAtMs = 0L
    private var deviceHeartbeatTimeoutMs = 5_000L

    private val outbound = ArrayDeque<Outbound>()
    private var writeInFlight = false
    private var busyRetries = 0
    private var lastWriteWithoutResponseAtMs = 0L
    private var writePumpJob: Job? = null

    private var handshakeJob: Job? = null
    private var heartbeatJob: Job? = null
    private var navigationTransmitJob: Job? = null
    private var lastNavigationTransmitAtMs = 0L
    private var lastRouteGeometrySignature: RouteGeometrySignature? = null

    private var pendingNavigationState: NavCoreSnapshot? = null
    private var pendingMediaState: BleMediaStateInput? = null
    private var pendingMapScene: MapScene? = null
    private var mapSceneDelivery = BleMapSceneDelivery()
    private var mapSceneFinalFrame: ByteArray? = null

    private val commandIdBySequence = HashMap<Int, Int>()
    private val commandStatusById = HashMap<Int, BleCommandDisposition>()
    private val receivedCommandOrder = ArrayDeque<Int>()

    /** Starts a fresh v1 session on an encrypted link whose TX notifications are enabled. */
    fun attach(sink: BleFrameSink, localMaximumFrameSize: Int, supportsWithResponse: Boolean) {
        detach()
        val frameSize = localMaximumFrameSize.coerceIn(BleHandshakeGate.MINIMUM_FRAME_SIZE, BleHandshakeGate.MAXIMUM_FRAME_SIZE)
        this.sink = sink
        this.supportsWithResponse = supportsWithResponse
        val codec = BleProtocolCodec(frameSize)
        this.codec = codec
        sessionId = makeSessionId()
        heartbeatClock.begin(sessionId, clock())
        trace("starting v1 handshake; session=$sessionId frame=$frameSize")
        try {
            goldenFixture?.invoke()?.let { BleGoldenCheck.verify(codec, it) }
            val gate = BleHandshakeGate(frameSize)
            gate.begin(sessionId)
            handshake = gate
            sendHandshakeFrame(BleHandshakeStage.AWAITING_INITIAL_DEVICE_READY)
            scheduleHandshakeTimeout(BleHandshakeStage.AWAITING_INITIAL_DEVICE_READY)
        } catch (error: Exception) {
            fail(error.message ?: "BLE v1 握手失败")
        }
    }

    /** Drops all per-link protocol state. Retained display state is resent next session. */
    fun detach() {
        handshakeJob?.cancel(); handshakeJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        navigationTransmitJob?.cancel(); navigationTransmitJob = null
        writePumpJob?.cancel(); writePumpJob = null
        heartbeatClock.reset()
        lastNavigationTransmitAtMs = 0
        lastWriteWithoutResponseAtMs = 0
        lastRouteGeometrySignature = null
        mapSceneDelivery = BleMapSceneDelivery()
        mapSceneFinalFrame = null
        sink = null
        codec?.close()
        codec = null
        handshake = null
        protocolReady = false
        peerCapabilities = 0
        lastValidDeviceFrameAtMs = 0
        deviceHeartbeatTimeoutMs = 5_000
        outbound.clear()
        writeInFlight = false
        busyRetries = 0
        commandIdBySequence.clear()
        commandStatusById.clear()
        receivedCommandOrder.clear()
    }

    fun sendNavigationSnapshot(state: NavCoreSnapshot) {
        pendingNavigationState = state
        scheduleNavigationTransmit()
    }

    /** Retained while disconnected and resent as soon as a session is ready. */
    fun sendMediaState(state: BleMediaStateInput) {
        pendingMediaState = state
        flushPendingMediaState()
    }

    /** Sparse, low-frequency replacement; only the newest window is kept. */
    fun sendMapScene(scene: BleMapSceneInput) {
        pendingMapScene = MapScene(scene.sceneRevision, scene)
        flushPendingMapScene()
    }

    fun onWriteComplete(success: Boolean) {
        writeInFlight = false
        if (!success) {
            fail("圆屏写入失败，正在重新连接")
            return
        }
        flushWrites()
    }

    fun onNotification(frame: ByteArray) {
        val codec = codec ?: return
        if (frame.isEmpty()) return
        val inbound = try {
            codec.pushFrame(frame, clock())
        } catch (error: Exception) {
            fail(error.message ?: "BLE v1 数据无效")
            return
        }
        lastValidDeviceFrameAtMs = clock()

        when (inbound) {
            is InboundMessage.Duplicate -> {
                if (inbound.ackRequested) {
                    val commandId = commandIdBySequence[inbound.sequence] ?: return
                    val original = commandStatusById[commandId] ?: return
                    acknowledge(inbound.sequence, commandId, original)
                }
            }

            is InboundMessage.Partial -> Unit

            is InboundMessage.ConnectionStatus -> {
                // A previous session's final Ready can still be in flight. It
                // must not poison the new reassembler baseline or the link.
                if (inbound.sessionId != sessionId) {
                    trace("ignored stale Device status for session=${inbound.sessionId}")
                    codec.resetInboundState()
                    return
                }
                if (!protocolReady) acceptDeviceReady(inbound)
            }

            is InboundMessage.Heartbeat -> {
                val gate = handshake
                if (!protocolReady || gate == null) {
                    codec.resetInboundState()
                    return
                }
                try {
                    gate.validateProtocolSession(inbound.sessionId)
                } catch (error: Exception) {
                    fail(error.message ?: "BLE 握手会话编号无效")
                }
            }

            is InboundMessage.Acknowledgement -> {
                if (!protocolReady) {
                    codec.resetInboundState()
                    return
                }
                if (mapSceneDelivery.acknowledge(inbound.acknowledgedSequence, inbound.status)) {
                    trace("map acknowledged sequence=${inbound.acknowledgedSequence} status=${inbound.status}")
                    if (mapSceneDelivery.timeoutCount >= 3) {
                        fail("周边地图连续接收失败，正在重新连接")
                        return
                    }
                    flushPendingMapScene()
                }
            }

            is InboundMessage.DeviceCommand -> {
                if (!protocolReady) {
                    codec.resetInboundState()
                    return
                }
                onLastCommandId?.invoke(inbound.commandId)
                val status = if (commandStatusById.containsKey(inbound.commandId)) {
                    commandIdBySequence[inbound.sequence] = inbound.commandId
                    BleCommandDisposition.DUPLICATE
                } else {
                    val result = onDeviceCommand?.invoke(inbound) ?: BleCommandDisposition.UNSUPPORTED
                    remember(inbound.commandId, inbound.sequence, result)
                    result
                }
                if (inbound.ackRequested) acknowledge(inbound.sequence, inbound.commandId, status)
            }

            is InboundMessage.PhoneMessage, is InboundMessage.Other -> Unit
        }
    }

    // ---------------------------------------------------------------- handshake

    private fun acceptDeviceReady(value: InboundMessage.ConnectionStatus) {
        val gate = handshake
        val codec = codec
        if (gate == null || codec == null) {
            fail("设备在握手开始前发送了状态")
            return
        }
        val status = BleDeviceHandshakeStatus(
            role = value.role,
            state = value.state,
            minimumVersion = value.minimumVersion,
            maximumVersion = value.maximumVersion,
            capabilities = value.capabilities,
            sessionId = value.sessionId,
            maximumFrameSize = value.maximumFrameSize,
            heartbeatIntervalMs = value.heartbeatIntervalMs,
        )
        try {
            when (val transition = gate.acceptDeviceReady(status)) {
                is BleHandshakeTransition.SendPhoneReady -> {
                    trace("received initial Device Ready; negotiated frame=${transition.maximumFrameSize}")
                    codec.maximumFrameSize = transition.maximumFrameSize
                    sendHandshakeFrame(BleHandshakeStage.AWAITING_FINAL_DEVICE_READY)
                    scheduleHandshakeTimeout(BleHandshakeStage.AWAITING_FINAL_DEVICE_READY)
                }

                is BleHandshakeTransition.ProtocolReady -> {
                    trace("received final Device Ready; protocol ready frame=${transition.maximumFrameSize}")
                    codec.maximumFrameSize = transition.maximumFrameSize
                    handshakeJob?.cancel()
                    handshakeJob = null
                    protocolReady = true
                    peerCapabilities = value.capabilities
                    deviceHeartbeatTimeoutMs = gate.deviceLivenessTimeoutMs
                    lastValidDeviceFrameAtMs = clock()
                    startHeartbeat()
                    onProtocolReady?.invoke()
                    pendingNavigationState?.let { sendNavigationSnapshot(it) }
                    flushPendingMediaState()
                    flushPendingMapScene()
                }
            }
        } catch (error: Exception) {
            fail(error.message ?: "BLE v1 握手失败")
        }
    }

    private fun sendHandshakeFrame(stage: BleHandshakeStage) {
        val codec = codec ?: return
        val frames = when (stage) {
            BleHandshakeStage.AWAITING_INITIAL_DEVICE_READY -> codec.encodePhoneStarting(sessionId)
            BleHandshakeStage.AWAITING_FINAL_DEVICE_READY -> codec.encodePhoneReady(sessionId)
            BleHandshakeStage.IDLE, BleHandshakeStage.READY -> return
        }
        // ConnectionStatus is one short frame; prefer an acknowledged GATT
        // write so a busy no-response buffer cannot strand the handshake.
        send(frames, withResponse = supportsWithResponse && frames.size == 1)
    }

    private fun scheduleHandshakeTimeout(expectedStage: BleHandshakeStage) {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            // Notifications may be briefly delayed while encryption settles.
            // The ESP32 answers Starting and Ready idempotently, so resend first.
            repeat(5) { attempt ->
                delay(1_000)
                if (handshake?.stage != expectedStage || protocolReady) return@launch
                trace("retrying handshake stage=$expectedStage attempt=${attempt + 1}")
                try {
                    sendHandshakeFrame(expectedStage)
                } catch (error: Exception) {
                    fail(error.message ?: "BLE v1 握手失败")
                    return@launch
                }
            }
            delay(1_000)
            if (handshake?.stage == expectedStage && !protocolReady) fail("BLE v1 握手超时，正在重新连接")
        }
    }

    // ---------------------------------------------------------------- outbound

    /** Returns true when the queue had to be reset to bound memory. */
    private fun send(frames: List<ByteArray>, withResponse: Boolean = false): Boolean {
        if (sink == null) return false
        var reset = false
        if (outbound.size + frames.size > BleOutboundBatch.QUEUE_CAPACITY) {
            // Keep the newest complete state instead of growing without bound.
            outbound.clear()
            lastRouteGeometrySignature = null
            mapSceneDelivery.queueWasDiscarded()
            mapSceneFinalFrame = null
            reset = true
        }
        frames.forEach { outbound.addLast(Outbound(it, withResponse)) }
        flushWrites()
        return reset
    }

    private fun flushWrites() {
        val sink = sink ?: return
        if (writeInFlight || writePumpJob != null) return
        val head = outbound.firstOrNull() ?: return
        val now = clock()
        if (!head.withResponse) {
            when (val action = BleWritePumpPolicy.action(outbound.size, true, now, lastWriteWithoutResponseAtMs, PACING_MS)) {
                BleWritePumpAction.Idle -> return
                is BleWritePumpAction.Wait -> {
                    scheduleWritePump(action.milliseconds)
                    return
                }
                BleWritePumpAction.SendOne -> Unit
            }
        }
        outbound.removeFirst()
        writeInFlight = true
        if (!sink.write(head.frame, head.withResponse)) {
            writeInFlight = false
            outbound.addFirst(head)
            busyRetries += 1
            if (busyRetries > 100) {
                fail("圆屏蓝牙发送持续繁忙，正在重新连接")
                return
            }
            scheduleWritePump(BUSY_RETRY_MS)
            return
        }
        busyRetries = 0
        if (!head.withResponse) lastWriteWithoutResponseAtMs = now
        markMapFragmentWritten(head.frame)
    }

    private fun scheduleWritePump(delayMs: Long) {
        if (writePumpJob != null) return
        writePumpJob = scope.launch {
            delay(delayMs)
            writePumpJob = null
            flushWrites()
        }
    }

    private fun markMapFragmentWritten(frame: ByteArray) {
        if (frame !== mapSceneFinalFrame) return
        mapSceneFinalFrame = null
        mapSceneDelivery.lastFragmentWritten(clock())
    }

    private fun scheduleNavigationTransmit() {
        if (!protocolReady || codec == null || pendingNavigationState == null || navigationTransmitJob != null) return
        val now = clock()
        val nextAllowed = lastNavigationTransmitAtMs + NAVIGATION_TRANSMIT_INTERVAL_MS
        if (lastNavigationTransmitAtMs == 0L || now >= nextAllowed) {
            flushPendingNavigationState()
            return
        }
        navigationTransmitJob = scope.launch {
            delay(nextAllowed - now)
            navigationTransmitJob = null
            flushPendingNavigationState()
        }
    }

    private fun flushPendingNavigationState() {
        val codec = codec ?: return
        val state = pendingNavigationState ?: return
        if (!protocolReady) return
        pendingNavigationState = null
        val signature = routeGeometrySignature(state)
        try {
            // NavigationSnapshot is at most 211 bytes in v1: at most two values
            // of the smallest negotiated MTU worth checking before encoding.
            val geometryRequired = BleOutboundBatch.requiresGeometry(
                hasGeometry = signature != null,
                geometryChanged = signature != lastRouteGeometrySignature,
                pendingFrameCount = outbound.size,
                snapshotFrameCount = 2,
            )
            val frames = BleOutboundBatch.encodeNavigation(
                geometryRequired = geometryRequired,
                encodeGeometry = { codec.encodeRouteGeometry(state) },
                encodeSnapshot = {
                    codec.encodeNavigationSnapshot(
                        BleNavigationSnapshotInput.from(state, BleProtocolCodec.routeToken(state.routeId)),
                    )
                },
            )
            val didReset = send(frames)
            lastRouteGeometrySignature = if (signature == null || geometryRequired || !didReset) signature else null
            lastNavigationTransmitAtMs = clock()
            flushPendingMapScene()
        } catch (error: Exception) {
            if (pendingNavigationState == null) pendingNavigationState = state
            fail(error.message ?: "导航数据编码失败")
            return
        }
        if (pendingNavigationState != null) scheduleNavigationTransmit()
    }

    private fun flushPendingMediaState() {
        val codec = codec ?: return
        val state = pendingMediaState ?: return
        if (!protocolReady) return
        try {
            send(codec.encodeMediaState(state))
        } catch (error: Exception) {
            fail(error.message ?: "音乐数据编码失败")
        }
    }

    private fun flushPendingMapScene() {
        val codec = codec ?: return
        val scene = pendingMapScene ?: return
        if (!protocolReady || peerCapabilities and BleHandshakeGate.CAPABILITY_MAP_SCENE == 0L) return
        if (!mapSceneDelivery.shouldSend(scene.revision, outbound.size)) return
        try {
            val frames = codec.encodeMapScene(scene.input)
            mapSceneDelivery.queued(scene.revision, codec.lastEncodedSequence)
            mapSceneFinalFrame = frames.lastOrNull()
            send(frames)
            trace("map queued revision=${scene.revision} roads=${scene.input.roads.size} buildings=${scene.input.buildings.size} frames=${frames.size}")
        } catch (error: Exception) {
            fail(error.message ?: "周边地图编码失败")
        }
    }

    // ---------------------------------------------------------------- liveness

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val codec = codec ?: return@launch
                if (!protocolReady) return@launch
                val now = clock()
                val elapsed = heartbeatClock.elapsedMs(sessionId, now) ?: return@launch
                if (lastValidDeviceFrameAtMs > 0 && now > lastValidDeviceFrameAtMs + deviceHeartbeatTimeoutMs) {
                    fail("设备心跳超时，正在重连")
                    return@launch
                }
                try {
                    mapSceneDelivery.expire(now)
                    if (mapSceneDelivery.timeoutCount >= 3) {
                        fail("周边地图传输未确认，正在重新连接")
                        return@launch
                    }
                    send(codec.encodeHeartbeat(sessionId, elapsed))
                    flushPendingMapScene()
                } catch (error: Exception) {
                    fail(error.message ?: "心跳编码失败")
                    return@launch
                }
            }
        }
    }

    private fun remember(commandId: Int, sequence: Int, status: BleCommandDisposition) {
        commandIdBySequence[sequence] = commandId
        commandStatusById[commandId] = status
        receivedCommandOrder.addLast(commandId)
        while (receivedCommandOrder.size > 64) {
            val stale = receivedCommandOrder.removeFirst()
            commandStatusById.remove(stale)
            commandIdBySequence.entries.removeAll { it.value == stale }
        }
    }

    private fun acknowledge(sequence: Int, commandId: Int, status: BleCommandDisposition) {
        val codec = codec ?: return
        try {
            send(codec.encodeAck(sequence, status.wireValue, commandId))
        } catch (error: Exception) {
            fail(error.message ?: "ACK 编码失败")
        }
    }

    private fun fail(message: String) {
        trace("transport recovery: $message")
        detach()
        onTransportError?.invoke(message)
    }

    private fun trace(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "MotoBLE"

        /** Frozen v1 ceiling of 5 Hz; the terminal interpolates locally at 40 Hz. */
        const val NAVIGATION_TRANSMIT_INTERVAL_MS = 200L
        const val PACING_MS = 15L
        private const val BUSY_RETRY_MS = 20L

        fun makeSessionId(): Long = Random.nextLong(1L, 0x1_0000_0000L)

        private fun coordinateE6(value: Double): Long = if (value.isFinite()) (value * 1_000_000).roundToLong() else 0L

        private fun routeGeometrySignature(state: NavCoreSnapshot): RouteGeometrySignature? {
            if (!state.hasRouteView || state.routeId.isEmpty() || state.routeViewPoints.isEmpty()) return null
            return RouteGeometrySignature(
                routeId = state.routeId,
                generation = state.routeGeneration,
                originLatitudeE6 = coordinateE6(state.routeViewOriginLatitudeDeg),
                originLongitudeE6 = coordinateE6(state.routeViewOriginLongitudeDeg),
                pointCoordinatesE6 = state.routeViewPoints.flatMap { pair -> pair.map(::coordinateE6) },
            )
        }
    }
}
