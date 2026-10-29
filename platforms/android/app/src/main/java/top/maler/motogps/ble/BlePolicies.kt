package top.maler.motogps.ble

/** Device command dispositions, encoded as the v1 Ack status byte. */
enum class BleCommandDisposition(val wireValue: Int) {
    ACCEPTED(0),
    UNSUPPORTED(1),
    INVALID_STATE(2),
    FAILED(3),
    DUPLICATE(4),
}

data class BleDeviceHandshakeStatus(
    val role: Int,
    val state: Int,
    val minimumVersion: Int,
    val maximumVersion: Int,
    val capabilities: Long,
    val sessionId: Long,
    val maximumFrameSize: Int,
    val heartbeatIntervalMs: Int,
)

enum class BleHandshakeStage { IDLE, AWAITING_INITIAL_DEVICE_READY, AWAITING_FINAL_DEVICE_READY, READY }

sealed interface BleHandshakeTransition {
    data class SendPhoneReady(val maximumFrameSize: Int) : BleHandshakeTransition
    data class ProtocolReady(val maximumFrameSize: Int) : BleHandshakeTransition
}

class BleHandshakeException(message: String) : Exception(message)

/**
 * The two-step v1 Ready exchange. Port of BLEHandshakeGate in the shared Swift
 * package: version range, capability intersection, frame size and heartbeat
 * must be valid and identical in both Device Ready messages.
 */
class BleHandshakeGate(localMaximumFrameSize: Int) {
    private val localMaximumFrameSize = localMaximumFrameSize.coerceIn(MINIMUM_FRAME_SIZE, MAXIMUM_FRAME_SIZE)

    var stage = BleHandshakeStage.IDLE
        private set
    var sessionId = 0L
        private set
    var negotiatedMaximumFrameSize = 0
        private set
    var negotiatedHeartbeatIntervalMs = 0
        private set

    private var initial: BleDeviceHandshakeStatus? = null

    val deviceLivenessTimeoutMs: Long get() = maxOf(5_000L, negotiatedHeartbeatIntervalMs * 3L)

    fun begin(sessionId: Long) {
        if (sessionId == 0L) throw BleHandshakeException("BLE 握手会话编号无效")
        this.sessionId = sessionId
        negotiatedMaximumFrameSize = 0
        negotiatedHeartbeatIntervalMs = 0
        initial = null
        stage = BleHandshakeStage.AWAITING_INITIAL_DEVICE_READY
    }

    fun acceptDeviceReady(status: BleDeviceHandshakeStatus): BleHandshakeTransition {
        validate(status)
        val negotiated = minOf(localMaximumFrameSize, status.maximumFrameSize)
        return when (stage) {
            BleHandshakeStage.AWAITING_INITIAL_DEVICE_READY -> {
                initial = status
                negotiatedMaximumFrameSize = negotiated
                negotiatedHeartbeatIntervalMs = status.heartbeatIntervalMs
                stage = BleHandshakeStage.AWAITING_FINAL_DEVICE_READY
                BleHandshakeTransition.SendPhoneReady(negotiated)
            }

            BleHandshakeStage.AWAITING_FINAL_DEVICE_READY -> {
                val first = initial
                if (first == null ||
                    status.minimumVersion != first.minimumVersion ||
                    status.maximumVersion != first.maximumVersion ||
                    status.capabilities != first.capabilities ||
                    status.maximumFrameSize != first.maximumFrameSize ||
                    status.heartbeatIntervalMs != first.heartbeatIntervalMs ||
                    negotiated != negotiatedMaximumFrameSize
                ) {
                    throw BleHandshakeException("设备两次 Ready 的协商参数不一致")
                }
                stage = BleHandshakeStage.READY
                BleHandshakeTransition.ProtocolReady(negotiated)
            }

            BleHandshakeStage.IDLE, BleHandshakeStage.READY -> throw BleHandshakeException("BLE 握手消息顺序无效")
        }
    }

    /** Session-bearing messages are valid only after the Ready exchange. */
    fun validateProtocolSession(candidateSessionId: Long) {
        if (stage != BleHandshakeStage.READY) throw BleHandshakeException("BLE 握手消息顺序无效")
        if (candidateSessionId == 0L || candidateSessionId != sessionId) throw BleHandshakeException("BLE 握手会话编号无效")
    }

    private fun validate(status: BleDeviceHandshakeStatus) {
        if (status.role != 2) throw BleHandshakeException("BLE 握手端角色无效（${status.role}）")
        if (status.state != 1) throw BleHandshakeException("设备没有进入 Ready 状态（${status.state}）")
        if (status.minimumVersion > PROTOCOL_VERSION || status.maximumVersion < PROTOCOL_VERSION) {
            throw BleHandshakeException("设备协议版本不兼容（${status.minimumVersion}-${status.maximumVersion}）")
        }
        if (status.sessionId != sessionId) throw BleHandshakeException("BLE 握手会话编号无效")
        val missing = REQUIRED_DEVICE_CAPABILITIES and status.capabilities.inv()
        if (missing != 0L) throw BleHandshakeException("设备缺少必要能力（0x%08X）".format(missing))
        if (status.maximumFrameSize !in MINIMUM_FRAME_SIZE..MAXIMUM_FRAME_SIZE) {
            throw BleHandshakeException("设备报告的 BLE 帧大小无效（${status.maximumFrameSize}）")
        }
        if (status.heartbeatIntervalMs < 250) {
            throw BleHandshakeException("设备报告的心跳间隔无效（${status.heartbeatIntervalMs} ms）")
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MINIMUM_FRAME_SIZE = 13
        const val MAXIMUM_FRAME_SIZE = 512
        const val CAPABILITY_MAP_SCENE = 1L shl 7

        /** navigation, route geometry, traffic, touch commands, command ACK. */
        const val REQUIRED_DEVICE_CAPABILITIES: Long = (1L shl 0) or (1L shl 1) or (1L shl 2) or (1L shl 4) or (1L shl 6)
    }
}

sealed interface BleWritePumpAction {
    data object Idle : BleWritePumpAction
    data class Wait(val milliseconds: Long) : BleWritePumpAction
    data object SendOne : BleWritePumpAction
}

/**
 * Paces one frame per tick. A GATT write callback only reflects the phone-side
 * controller buffer, not how quickly the terminal drains its RX queue.
 */
object BleWritePumpPolicy {
    fun action(pendingFrameCount: Int, canSend: Boolean, nowMs: Long, lastSendMs: Long, pacingMs: Long): BleWritePumpAction {
        if (pendingFrameCount <= 0) return BleWritePumpAction.Idle
        val nextAllowed = lastSendMs + pacingMs
        if (lastSendMs != 0L && nowMs < nextAllowed) return BleWritePumpAction.Wait(nextAllowed - nowMs)
        return if (canSend) BleWritePumpAction.SendOne else BleWritePumpAction.Idle
    }
}

/** Only elapsed time between app events may leave the phone, never system uptime. */
class BleSessionHeartbeatClock {
    private var sessionId = 0L
    private var startedAtMs = 0L
    private var active = false

    fun begin(sessionId: Long, nowMs: Long) {
        this.sessionId = sessionId
        startedAtMs = nowMs
        active = true
    }

    fun reset() {
        active = false
    }

    fun elapsedMs(sessionId: Long, nowMs: Long): Long? {
        if (!active || this.sessionId != sessionId) return null
        val elapsed = if (nowMs >= startedAtMs) nowMs - startedAtMs else 0L
        return elapsed and 0xFFFF_FFFFL
    }
}

object BleOutboundBatch {
    const val QUEUE_CAPACITY = 128

    /** Encode in send order: each call allocates the next logical-message sequence. */
    inline fun encodeNavigation(
        geometryRequired: Boolean,
        encodeGeometry: () -> List<ByteArray>,
        encodeSnapshot: () -> List<ByteArray>,
    ): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        if (geometryRequired) frames += encodeGeometry()
        frames += encodeSnapshot()
        return frames
    }

    fun requiresGeometry(
        hasGeometry: Boolean,
        geometryChanged: Boolean,
        pendingFrameCount: Int,
        snapshotFrameCount: Int,
        queueCapacity: Int = QUEUE_CAPACITY,
    ): Boolean = hasGeometry && (geometryChanged || pendingFrameCount + snapshotFrameCount > queueCapacity)
}

/**
 * A queued map is not delivered until the terminal acknowledges its complete
 * logical message. Retries are freshly encoded to preserve wire sequence order.
 */
class BleMapSceneDelivery {
    data class Pending(val revision: Long, val sequence: Int, val sentAtMs: Long?)

    var acknowledgedRevision: Long? = null
        private set
    var pending: Pending? = null
        private set
    var timeoutCount = 0
        private set

    fun shouldSend(revision: Long, queuedFrames: Int): Boolean =
        pending == null && acknowledgedRevision != revision && queuedFrames <= 32 && timeoutCount < 3

    fun queued(revision: Long, sequence: Int) {
        pending = Pending(revision, sequence, null)
    }

    fun lastFragmentWritten(nowMs: Long) {
        val current = pending ?: return
        pending = current.copy(sentAtMs = nowMs)
    }

    fun acknowledge(sequence: Int, status: Int): Boolean {
        val current = pending ?: return false
        if (current.sequence != sequence) return false
        pending = null
        if (status == 0 || status == 4) {
            acknowledgedRevision = current.revision
            timeoutCount = 0
        } else {
            timeoutCount += 1
        }
        return true
    }

    fun expire(nowMs: Long) {
        val current = pending ?: return
        val sentAt = current.sentAtMs ?: return
        if (nowMs >= sentAt && nowMs - sentAt >= 3_000) {
            pending = null
            timeoutCount += 1
        }
    }

    fun queueWasDiscarded() {
        pending = null
        acknowledgedRevision = null
    }
}
