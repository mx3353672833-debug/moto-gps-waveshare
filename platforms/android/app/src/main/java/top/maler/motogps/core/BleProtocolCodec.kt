package top.maler.motogps.core

import kotlin.math.roundToLong

class BleProtocolException(message: String) : Exception(message)

/** Display projection handed to the shared NavigationSnapshot encoder. */
data class BleNavigationSnapshotInput(
    val stateName: String = "idle",
    val networkName: String = "offline",
    val displayPageName: String = "navigation",
    val maneuverName: String = "unknown",
    val trafficName: String = "unknown",
    val hasDestination: Boolean = false,
    val hasFix: Boolean = false,
    val gnssStale: Boolean = false,
    val offRoute: Boolean = false,
    val routeRequestInFlight: Boolean = false,
    val trafficRequestInFlight: Boolean = false,
    val hasRouteView: Boolean = false,
    val routeToken: Long = 0,
    val routeGeneration: Long = 0,
    val maneuverId: Long = 0,
    val distanceToManeuverM: Long = 0,
    val remainingDistanceM: Long = 0,
    val remainingDurationS: Long = 0,
    val routeProgressM: Long = 0,
    val totalDistanceM: Long = 0,
    val speedDeciKph: Int = 0,
    val speedLimitKph: Int = 0,
    val headingCentiDegrees: Int = 0,
    val accuracyDecimeters: Int = 0,
    val crossTrackDecimeters: Int = 0,
    val roundaboutExit: Int = 0,
    val roadName: String = "",
    val instructionText: String = "",
) {
    companion object {
        /** Unit conversions identical to ESP32BLECentral.makeSnapshotInput on iOS. */
        fun from(state: NavCoreSnapshot, routeToken: Long): BleNavigationSnapshotInput {
            val heading = state.headingDeg
            val headingCdeg = if (heading.isFinite()) {
                val normalized = (heading % 360) + if (heading < 0) 360 else 0
                (normalized * 100).roundToLong().coerceIn(0, 65_535).toInt()
            } else {
                0
            }
            return BleNavigationSnapshotInput(
                stateName = state.stateName,
                networkName = state.networkName,
                displayPageName = state.displayPageName,
                maneuverName = state.maneuverTypeName,
                trafficName = state.trafficName,
                hasDestination = state.hasDestination,
                hasFix = state.hasUsableFix,
                gnssStale = state.gnssStale,
                offRoute = state.offRoute,
                routeRequestInFlight = state.routeRequestInFlight,
                trafficRequestInFlight = state.trafficRequestInFlight,
                hasRouteView = state.hasRouteView,
                routeToken = routeToken,
                routeGeneration = state.routeGeneration,
                maneuverId = state.maneuverId,
                distanceToManeuverM = clampedUInt32(state.distanceToManeuverM),
                remainingDistanceM = clampedUInt32(state.remainingDistanceM),
                remainingDurationS = state.remainingDurationS,
                routeProgressM = clampedUInt32(state.routeProgressM),
                totalDistanceM = clampedUInt32(state.totalDistanceM),
                speedDeciKph = clampedUInt16(state.speedMps * 36),
                speedLimitKph = state.speedLimitKph,
                headingCentiDegrees = headingCdeg,
                accuracyDecimeters = clampedUInt16(state.horizontalAccuracyM * 10),
                crossTrackDecimeters = clampedUInt16(state.crossTrackDistanceM * 10),
                roundaboutExit = state.roundaboutExit,
                roadName = state.roadName,
                instructionText = state.instructionText,
            )
        }

        private fun clampedUInt32(value: Double): Long =
            if (!value.isFinite()) 0 else value.coerceIn(0.0, 4_294_967_295.0).roundToLong()

        private fun clampedUInt16(value: Double): Int =
            if (!value.isFinite()) 0 else value.roundToLong().coerceIn(0, 65_535).toInt()
    }
}

data class BleMediaStateInput(
    val connected: Boolean,
    val playing: Boolean,
    val likeAvailable: Boolean,
    val liked: Boolean,
    val trackToken: Long,
    val positionSeconds: Int,
    val durationSeconds: Int,
    val sourceName: String,
    val trackTitle: String,
    val artistName: String,
)

data class BleMapPoint(val latitudeE6: Int, val longitudeE6: Int)

data class BleMapShape(val className: String, val points: List<BleMapPoint>)

data class BleMapSceneInput(
    val sceneRevision: Long,
    val originLatitudeE6: Int,
    val originLongitudeE6: Int,
    val radiusM: Int,
    val roads: List<BleMapShape>,
    val buildings: List<BleMapShape>,
)

sealed interface InboundMessage {
    val sequence: Int
    val ackRequested: Boolean

    /** A valid fragment of a message still being reassembled. */
    data class Partial(override val sequence: Int, override val ackRequested: Boolean) : InboundMessage

    /** A completed sequence seen again; application effects must not repeat. */
    data class Duplicate(override val sequence: Int, override val ackRequested: Boolean) : InboundMessage

    data class ConnectionStatus(
        override val sequence: Int,
        override val ackRequested: Boolean,
        val role: Int,
        val state: Int,
        val minimumVersion: Int,
        val maximumVersion: Int,
        val capabilities: Long,
        val sessionId: Long,
        val maximumFrameSize: Int,
        val heartbeatIntervalMs: Int,
    ) : InboundMessage

    data class Heartbeat(
        override val sequence: Int,
        override val ackRequested: Boolean,
        val sessionId: Long,
        val monotonicMs: Long,
    ) : InboundMessage

    data class Acknowledgement(
        override val sequence: Int,
        override val ackRequested: Boolean,
        val acknowledgedSequence: Int,
        val status: Int,
        val commandId: Int,
    ) : InboundMessage

    data class DeviceCommand(
        override val sequence: Int,
        override val ackRequested: Boolean,
        val kind: Int,
        val commandId: Int,
        val page: Int,
        val x: Int,
        val y: Int,
        val eventTimeMs: Long,
    ) : InboundMessage

    /** Phone-to-device messages; only produced by the protocol simulator. */
    data class PhoneMessage(
        override val sequence: Int,
        override val ackRequested: Boolean,
        val type: Int,
        val fields: List<Long>,
    ) : InboundMessage

    data class Other(override val sequence: Int, override val ackRequested: Boolean) : InboundMessage

    companion object {
        internal fun parse(values: LongArray): InboundMessage {
            val sequence = values[1].toInt()
            val ack = values[2] != 0L
            fun field(index: Int) = values.getOrElse(index) { 0L }
            return when (values[0].toInt()) {
                0 -> Partial(sequence, ack)
                1 -> Duplicate(sequence, ack)
                2 -> ConnectionStatus(
                    sequence, ack,
                    role = field(3).toInt(),
                    state = field(4).toInt(),
                    minimumVersion = field(5).toInt(),
                    maximumVersion = field(6).toInt(),
                    capabilities = field(7),
                    sessionId = field(8),
                    maximumFrameSize = field(9).toInt(),
                    heartbeatIntervalMs = field(10).toInt(),
                )
                3 -> Heartbeat(sequence, ack, sessionId = field(3), monotonicMs = field(4))
                4 -> Acknowledgement(
                    sequence, ack,
                    acknowledgedSequence = field(3).toInt(),
                    status = field(4).toInt(),
                    commandId = field(5).toInt(),
                )
                5 -> DeviceCommand(
                    sequence, ack,
                    kind = field(3).toInt(),
                    commandId = field(4).toInt(),
                    page = field(5).toInt(),
                    x = field(6).toInt(),
                    y = field(7).toInt(),
                    eventTimeMs = field(8),
                )
                in 7..10 -> PhoneMessage(sequence, ack, values[0].toInt(), values.drop(3))
                else -> Other(sequence, ack)
            }
        }
    }
}

/**
 * Thin Kotlin wrapper over shared/ble_protocol. No wire constants or binary
 * payload layouts are reimplemented on the Kotlin side. Each encode call
 * allocates the next logical-message sequence number, so encode in send order.
 */
class BleProtocolCodec(maximumFrameSize: Int) : AutoCloseable {
    private var handle: Long = MotoNative.bleCreate(maximumFrameSize)

    val lastEncodedSequence: Int get() = MotoNative.bleLastEncodedSequence(liveHandle())

    var maximumFrameSize: Int
        get() = MotoNative.bleMaximumFrameSize(liveHandle())
        set(value) = MotoNative.bleSetMaximumFrameSize(liveHandle(), value)

    /** Drops device-to-phone fragment history; outbound sequences are preserved. */
    fun resetInboundState() = MotoNative.bleResetInbound(liveHandle())

    fun encodePhoneStarting(sessionId: Long) = frames(MotoNative.bleEncodePhoneStatus(liveHandle(), false, sessionId))

    fun encodePhoneReady(sessionId: Long) = frames(MotoNative.bleEncodePhoneStatus(liveHandle(), true, sessionId))

    fun encodeHeartbeat(sessionId: Long, monotonicMs: Long) =
        frames(MotoNative.bleEncodeHeartbeat(liveHandle(), sessionId, monotonicMs))

    fun encodeAck(sequence: Int, status: Int, commandId: Int) =
        frames(MotoNative.bleEncodeAck(liveHandle(), sequence, status, commandId))

    fun encodeNavigationSnapshot(input: BleNavigationSnapshotInput): List<ByteArray> {
        val (names, flags, numbers) = snapshotArguments(input)
        return frames(
            MotoNative.bleEncodeNavigationSnapshot(
                liveHandle(), names, flags, numbers, input.roadName.utf8(), input.instructionText.utf8(),
            ),
        )
    }

    fun encodeNavigationPayload(input: BleNavigationSnapshotInput): ByteArray {
        val (names, flags, numbers) = snapshotArguments(input)
        return MotoNative.bleEncodeNavigationPayload(
            liveHandle(), names, flags, numbers, input.roadName.utf8(), input.instructionText.utf8(),
        ) ?: throw BleProtocolException(MotoNative.bleLastError(liveHandle()))
    }

    /** Encodes the snapshot's local route window, or nothing when it has none. */
    fun encodeRouteGeometry(snapshot: NavCoreSnapshot): List<ByteArray> {
        if (!snapshot.hasRouteView || snapshot.routeViewPoints.isEmpty() || snapshot.routeId.isEmpty()) {
            return emptyList()
        }
        return frames(
            MotoNative.bleEncodeRouteGeometry(
                liveHandle(),
                snapshot.routeId.utf8(),
                snapshot.routeGeneration,
                snapshot.routeViewOriginLatitudeDeg,
                snapshot.routeViewOriginLongitudeDeg,
                flatten(snapshot.routeViewPoints),
            ),
        )
    }

    fun encodeRouteGeometryPayload(
        routeId: String,
        generation: Long,
        originLatitude: Double,
        originLongitude: Double,
        pointsLatLon: List<List<Double>>,
    ): ByteArray = MotoNative.bleEncodeRouteGeometryPayload(
        liveHandle(), routeId.utf8(), generation, originLatitude, originLongitude, flatten(pointsLatLon),
    ) ?: throw BleProtocolException(MotoNative.bleLastError(liveHandle()))

    fun encodeMediaState(input: BleMediaStateInput) = frames(
        MotoNative.bleEncodeMediaState(
            liveHandle(),
            booleanArrayOf(input.connected, input.playing, input.likeAvailable, input.liked),
            input.trackToken,
            input.positionSeconds,
            input.durationSeconds,
            input.sourceName.utf8(),
            input.trackTitle.utf8(),
            input.artistName.utf8(),
        ),
    )

    fun encodeMapScene(input: BleMapSceneInput): List<ByteArray> {
        val roads = shapeArguments(input.roads)
        val buildings = shapeArguments(input.buildings)
        return frames(
            MotoNative.bleEncodeMapScene(
                liveHandle(), input.sceneRevision, input.originLatitudeE6, input.originLongitudeE6, input.radiusM,
                roads.first, roads.second, roads.third, buildings.first, buildings.second, buildings.third,
            ),
        )
    }

    fun encodeMapScenePayload(input: BleMapSceneInput): ByteArray {
        val roads = shapeArguments(input.roads)
        val buildings = shapeArguments(input.buildings)
        return MotoNative.bleEncodeMapScenePayload(
            liveHandle(), input.sceneRevision, input.originLatitudeE6, input.originLongitudeE6, input.radiusM,
            roads.first, roads.second, roads.third, buildings.first, buildings.second, buildings.third,
        ) ?: throw BleProtocolException(MotoNative.bleLastError(liveHandle()))
    }

    /** Feeds one GATT value. Throws [BleProtocolException] for any rejected frame. */
    fun pushFrame(frame: ByteArray, receivedAtMs: Long): InboundMessage {
        val values = MotoNative.blePushFrame(liveHandle(), frame, receivedAtMs)
            ?: throw BleProtocolException(MotoNative.bleLastError(liveHandle()))
        return InboundMessage.parse(values)
    }

    // Device role (protocol simulator in instrumented tests).
    internal fun encodeDeviceStatus(
        state: Int,
        sessionId: Long,
        maximumFrameSize: Int,
        heartbeatIntervalMs: Int,
        capabilities: Long,
    ) = frames(
        MotoNative.deviceEncodeStatus(liveHandle(), state, sessionId, maximumFrameSize, heartbeatIntervalMs, capabilities),
    )

    internal fun encodeDeviceCommand(kind: Int, commandId: Int, page: Int, eventTimeMs: Long) =
        frames(MotoNative.deviceEncodeCommand(liveHandle(), kind, commandId, page, eventTimeMs))

    override fun close() {
        if (handle != 0L) {
            MotoNative.bleDestroy(handle)
            handle = 0L
        }
    }

    private fun liveHandle(): Long {
        check(handle != 0L) { "BleProtocolCodec is closed" }
        return handle
    }

    private fun frames(value: Array<ByteArray>?): List<ByteArray> =
        value?.toList() ?: throw BleProtocolException(MotoNative.bleLastError(liveHandle()))

    private fun snapshotArguments(input: BleNavigationSnapshotInput) = Triple(
        arrayOf(
            input.stateName.utf8(),
            input.networkName.utf8(),
            input.displayPageName.utf8(),
            input.maneuverName.utf8(),
            input.trafficName.utf8(),
        ),
        booleanArrayOf(
            input.hasDestination,
            input.hasFix,
            input.gnssStale,
            input.offRoute,
            input.routeRequestInFlight,
            input.trafficRequestInFlight,
            input.hasRouteView,
        ),
        longArrayOf(
            input.routeToken,
            input.routeGeneration,
            input.maneuverId,
            input.distanceToManeuverM,
            input.remainingDistanceM,
            input.remainingDurationS,
            input.routeProgressM,
            input.totalDistanceM,
            input.speedDeciKph.toLong(),
            input.speedLimitKph.toLong(),
            input.headingCentiDegrees.toLong(),
            input.accuracyDecimeters.toLong(),
            input.crossTrackDecimeters.toLong(),
            input.roundaboutExit.toLong(),
        ),
    )

    private fun shapeArguments(shapes: List<BleMapShape>): Triple<Array<ByteArray>, IntArray, IntArray> {
        val points = IntArray(shapes.sumOf { it.points.size } * 2)
        var cursor = 0
        for (shape in shapes) {
            for (point in shape.points) {
                points[cursor++] = point.latitudeE6
                points[cursor++] = point.longitudeE6
            }
        }
        return Triple(
            Array(shapes.size) { shapes[it].className.utf8() },
            IntArray(shapes.size) { shapes[it].points.size },
            points,
        )
    }

    companion object {
        val serviceUuid: String by lazy { MotoNative.bleServiceUuid() }
        val phoneToDeviceUuid: String by lazy { MotoNative.blePhoneToDeviceUuid() }
        val deviceToPhoneUuid: String by lazy { MotoNative.bleDeviceToPhoneUuid() }

        /** FNV-1a token of the exact UTF-8 route ID, computed by the shared codec. */
        fun routeToken(routeId: String): Long = MotoNative.bleRouteToken(routeId.utf8())

        private fun flatten(points: List<List<Double>>): DoubleArray {
            val values = DoubleArray(points.size * 2)
            points.forEachIndexed { index, pair ->
                values[index * 2] = pair.getOrElse(0) { 0.0 }
                values[index * 2 + 1] = pair.getOrElse(1) { 0.0 }
            }
            return values
        }
    }
}
