package com.motogps.android.jni

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

data class GeoPointE6(
    val latitudeE6: Int,
    val longitudeE6: Int
)

data class MotoBLERouteGeometry(
    val coordinateSystem: Int = 2, // GCJ-02
    val routeToken: Long,
    val routeGeneration: Long,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 1,
    val firstPointIndex: Int = 0,
    val totalPointCount: Int,
    val viewOrigin: GeoPointE6,
    val points: List<GeoPointE6>
)

data class MotoBLEMapRoadPolyline(
    val roadClass: Int = 5, // 0 motorway, 1 primary, 2 secondary, 3 residential, 4 service, 5 other
    val points: List<GeoPointE6>
)

data class MotoBLEMapBuildingFootprint(
    val buildingClass: Int = 0, // 0 generic, 1 landmark, 2 parking
    val points: List<GeoPointE6>
)

data class MotoBLEMapScene(
    val coordinateSystem: Int = 2, // GCJ-02
    val sceneRevision: Long,
    val viewOrigin: GeoPointE6,
    val radiusMeters: Int = 500,
    val roads: List<MotoBLEMapRoadPolyline>,
    val buildings: List<MotoBLEMapBuildingFootprint> = emptyList()
)

class MotoBLEProtocolCodec {

    companion object {
        const val FRAME_MAGIC: Byte = 0xB7.toByte()
        const val PROTOCOL_VERSION: Byte = 1
        const val PAYLOAD_REVISION: Byte = 1
        const val FRAME_HEADER_SIZE = 10
        const val FRAME_CRC_SIZE = 2
        const val FRAME_OVERHEAD = FRAME_HEADER_SIZE + FRAME_CRC_SIZE // 12
        const val MAX_BLE_ATTRIBUTE_VALUE_SIZE = 512

        // Frame flags
        const val FRAME_START = 1
        const val FRAME_END = 2
        const val ACK_REQUESTED = 4
        const val URGENT = 8

        // Message types
        const val MSG_CONNECTION_STATUS: Byte = 0x01
        const val MSG_HEARTBEAT: Byte = 0x02
        const val MSG_ACK: Byte = 0x03
        const val MSG_NAVIGATION_SNAPSHOT: Byte = 0x10
        const val MSG_ROUTE_GEOMETRY: Byte = 0x11
        const val MSG_TRAFFIC_DEVIATION: Byte = 0x12
        const val MSG_MEDIA_STATE: Byte = 0x13
        const val MSG_MAP_SCENE: Byte = 0x14
        const val MSG_DEVICE_COMMAND: Byte = 0x20

        private val idGenerator = AtomicLong(1L)
        private val instances = ConcurrentHashMap<Long, CodecStorage>()

        fun crc16CcittFalse(data: ByteArray, length: Int): Int {
            var crc = 0xFFFF
            for (i in 0 until length) {
                val b = data[i].toInt() and 0xFF
                crc = crc xor (b shl 8)
                for (bit in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) {
                        ((crc shl 1) xor 0x1021) and 0xFFFF
                    } else {
                        (crc shl 1) and 0xFFFF
                    }
                }
            }
            return crc and 0xFFFF
        }
    }

    private class CodecStorage(var maxFrameSize: Int) {
        var sequence: Int = 1
        var lastEncodedSequence: Int = 0
        var activeSequence: Int = 0
        var activeMessageLength: Int = 0
        var activeType: Byte = 0
        val reassemblyBuffer = mutableListOf<Byte>()

        init {
            maxFrameSize = maxFrameSize.coerceIn(FRAME_OVERHEAD + 1, MAX_BLE_ATTRIBUTE_VALUE_SIZE)
        }

        fun nextSequence(): Int {
            val seq = sequence
            sequence++
            if (sequence == 0 || sequence > 0xFFFF) {
                sequence = 1
            }
            return seq
        }

        fun resetInbound() {
            activeSequence = 0
            activeMessageLength = 0
            activeType = 0
            reassemblyBuffer.clear()
        }
    }

    fun nativeInit(maximumFrameSize: Int): Long {
        val id = idGenerator.getAndIncrement()
        instances[id] = CodecStorage(maximumFrameSize)
        return id
    }

    fun nativeDestroy(ptr: Long) {
        instances.remove(ptr)
    }

    fun nativeSetMaximumFrameSize(ptr: Long, maximumFrameSize: Int) {
        instances[ptr]?.let { storage ->
            storage.maxFrameSize = maximumFrameSize.coerceIn(FRAME_OVERHEAD + 1, MAX_BLE_ATTRIBUTE_VALUE_SIZE)
        }
    }

    fun nativeResetInboundState(ptr: Long) {
        instances[ptr]?.resetInbound()
    }

    fun nativeEncodePhoneStarting(ptr: Long, sessionID: Long): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val payload = buildConnectionStatusPayload(
            role = 1, // Phone
            state = 0, // Starting
            minVersion = 1,
            maxVersion = 1,
            capabilities = 0xFFL, // All capabilities (Navigation, RouteGeom, Traffic, Media, TouchCmds, MusicCmds, CommandAck, MapScene)
            sessionId = sessionID,
            maxFrameSize = storage.maxFrameSize,
            heartbeatIntervalMs = 1000
        )
        return fragmentMessage(storage, MSG_CONNECTION_STATUS, payload, ackRequested = false, urgent = true)
    }

    fun nativeEncodePhoneReady(ptr: Long, sessionID: Long): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val payload = buildConnectionStatusPayload(
            role = 1, // Phone
            state = 1, // Ready
            minVersion = 1,
            maxVersion = 1,
            capabilities = 0xFFL, // All capabilities
            sessionId = sessionID,
            maxFrameSize = storage.maxFrameSize,
            heartbeatIntervalMs = 1000
        )
        return fragmentMessage(storage, MSG_CONNECTION_STATUS, payload, ackRequested = false, urgent = true)
    }

    fun nativeEncodeHeartbeat(ptr: Long, sessionID: Long, monotonicMs: Long): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val writer = Writer()
        writer.writeU8(PAYLOAD_REVISION.toInt())
        writer.writeU32(sessionID)
        writer.writeU32(monotonicMs)
        writer.writeU16(0) // status_flags
        val payload = writer.toByteArray()
        return fragmentMessage(storage, MSG_HEARTBEAT, payload, ackRequested = false, urgent = false)
    }

    fun nativeEncodeAck(ptr: Long, sequence: Int, status: Int, commandID: Int): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val writer = Writer()
        writer.writeU8(PAYLOAD_REVISION.toInt())
        writer.writeU16(sequence)
        writer.writeU8(status)
        writer.writeU16(commandID)
        val payload = writer.toByteArray()
        return fragmentMessage(storage, MSG_ACK, payload, ackRequested = true, urgent = true)
    }

    fun nativeEncodeNavigationSnapshot(ptr: Long, snapshot: MotoBLENavigationSnapshot): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val payload = buildNavigationSnapshotPayload(snapshot) ?: return emptyArray()
        return fragmentMessage(storage, MSG_NAVIGATION_SNAPSHOT, payload, ackRequested = false, urgent = false)
    }

    fun nativeEncodeRouteGeometry(ptr: Long, geometry: MotoBLERouteGeometry): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val payload = buildRouteGeometryPayload(geometry) ?: return emptyArray()
        return fragmentMessage(storage, MSG_ROUTE_GEOMETRY, payload, ackRequested = false, urgent = false)
    }

    fun nativeEncodeMapScene(ptr: Long, scene: MotoBLEMapScene): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val payload = buildMapScenePayload(scene) ?: return emptyArray()
        return fragmentMessage(storage, MSG_MAP_SCENE, payload, ackRequested = true, urgent = false)
    }

    fun nativeEncodeNavigationPayloadForGoldenCheck(ptr: Long, snapshot: MotoBLENavigationSnapshot): ByteArray? {
        return buildNavigationSnapshotPayload(snapshot)
    }

    fun nativeEncodeGeometryPayloadForGoldenCheck(ptr: Long, geometry: MotoBLERouteGeometry): ByteArray? {
        return buildRouteGeometryPayload(geometry)
    }

    fun nativeEncodeMapScenePayloadForGoldenCheck(ptr: Long, scene: MotoBLEMapScene): ByteArray? {
        return buildMapScenePayload(scene)
    }

    fun nativeEncodeMediaState(ptr: Long, state: Any): Array<ByteArray> {
        val storage = instances[ptr] ?: return emptyArray()
        val writer = Writer()
        writer.writeU8(PAYLOAD_REVISION.toInt())
        writer.writeU8(0) // flags
        writer.writeU32(0L) // track_token
        writer.writeU16(0) // position_s
        writer.writeU16(0) // duration_s
        writer.writeStringU8("") // source
        writer.writeStringU8("") // title
        writer.writeStringU8("") // artist
        val payload = writer.toByteArray()
        return fragmentMessage(storage, MSG_MEDIA_STATE, payload, ackRequested = false, urgent = false)
    }

    fun nativePushDeviceFrame(ptr: Long, frame: ByteArray, receivedAtMs: Long): MotoBLEInboundMessage {
        val storage = instances[ptr] ?: return MotoBLEInboundMessage(0, false, false, false, null, null, null, null)

        if (frame.size < FRAME_OVERHEAD) {
            return MotoBLEInboundMessage(0, false, false, false, null, null, null, null)
        }
        if (frame[0] != FRAME_MAGIC) {
            return MotoBLEInboundMessage(0, false, false, false, null, null, null, null)
        }

        val crcOffset = frame.size - 2
        val expectedCrc = (frame[crcOffset].toInt() and 0xFF) or ((frame[crcOffset + 1].toInt() and 0xFF) shl 8)
        val actualCrc = crc16CcittFalse(frame, crcOffset)
        if (expectedCrc != actualCrc) {
            return MotoBLEInboundMessage(0, false, false, false, null, null, null, null)
        }

        val msgType = frame[2]
        val flags = frame[3].toInt() and 0xFF
        val sequence = (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8)
        val fragmentOffset = (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
        val messageLength = (frame[8].toInt() and 0xFF) or ((frame[9].toInt() and 0xFF) shl 8)

        val starts = (flags and FRAME_START) != 0
        val ends = (flags and FRAME_END) != 0

        if (starts) {
            storage.resetInbound()
            storage.activeSequence = sequence
            storage.activeMessageLength = messageLength
            storage.activeType = msgType
        }

        val payloadSize = frame.size - FRAME_OVERHEAD
        for (i in 0 until payloadSize) {
            storage.reassemblyBuffer.add(frame[10 + i])
        }

        var connStatus: MotoBLEConnectionStatus? = null
        var heartbeat: MotoBLEHeartbeat? = null
        var ack: MotoBLEAcknowledgement? = null
        var command: MotoBLEDeviceCommand? = null
        var isComplete = false

        if (ends && storage.reassemblyBuffer.size == storage.activeMessageLength) {
            isComplete = true
            val payload = storage.reassemblyBuffer.toByteArray()
            val reader = Reader(payload)

            if (reader.remaining() > 0) {
                val rev = reader.readU8()
                if (rev == PAYLOAD_REVISION.toInt()) {
                    when (msgType) {
                        MSG_CONNECTION_STATUS -> {
                            if (reader.remaining() >= 16) {
                                val role = reader.readU8()
                                val state = reader.readU8()
                                val minVer = reader.readU8()
                                val maxVer = reader.readU8()
                                val capabilities = reader.readU32()
                                val sessionId = reader.readU32()
                                val maxFrameSize = reader.readU16()
                                val hbInterval = reader.readU16()
                                connStatus = MotoBLEConnectionStatus(
                                    role, state, minVer, maxVer, capabilities, sessionId, maxFrameSize, hbInterval
                                )
                            }
                        }
                        MSG_DEVICE_COMMAND -> {
                            if (reader.remaining() >= 12) {
                                val kind = reader.readU8()
                                val cmdId = reader.readU16()
                                val page = reader.readU8()
                                val x = reader.readU16()
                                val y = reader.readU16()
                                val eventTimeMs = reader.readU32()
                                command = MotoBLEDeviceCommand(
                                    sequence, false, kind, cmdId, page, x, y, eventTimeMs
                                )
                            }
                        }
                    }
                }
            }
            storage.resetInbound()
        }

        return MotoBLEInboundMessage(
            sequence,
            ackReq = false,
            complete = isComplete,
            duplicate = false,
            connStatus = connStatus,
            heartbeat = heartbeat,
            ack = ack,
            command = command
        )
    }

    fun nativeGetLastEncodedSequence(ptr: Long): Int {
        return instances[ptr]?.lastEncodedSequence ?: 0
    }

    private fun buildConnectionStatusPayload(
        role: Int,
        state: Int,
        minVersion: Int,
        maxVersion: Int,
        capabilities: Long,
        sessionId: Long,
        maxFrameSize: Int,
        heartbeatIntervalMs: Int
    ): ByteArray {
        val writer = Writer()
        writer.writeU8(PAYLOAD_REVISION.toInt())
        writer.writeU8(role)
        writer.writeU8(state)
        writer.writeU8(minVersion)
        writer.writeU8(maxVersion)
        writer.writeU32(capabilities)
        writer.writeU32(sessionId)
        writer.writeU16(maxFrameSize)
        writer.writeU16(heartbeatIntervalMs)
        return writer.toByteArray()
    }

    private fun buildNavigationSnapshotPayload(snapshot: MotoBLENavigationSnapshot): ByteArray? {
        val state = when (snapshot.stateName) {
            "acquiring" -> 1
            "planning" -> 2
            "navigating" -> 3
            "rerouting" -> 4
            "arrived" -> 5
            else -> 0
        }
        val network = when (snapshot.networkName) {
            "offline" -> 0
            "connecting" -> 1
            else -> 2
        }
        val displayPage = when (snapshot.displayPageName) {
            "speedometer", "speed" -> 1
            "heading", "compass" -> 2
            "music" -> 3
            else -> 0
        }
        val maneuver = when (snapshot.maneuverName) {
            "turn_left" -> 3
            "turn_right" -> 7
            "keep_left" -> 2
            "keep_right" -> 6
            "u_turn" -> 5
            "roundabout" -> 10
            "arrive" -> 12
            else -> 1
        }
        val traffic = when (snapshot.trafficName) {
            "clear" -> 1
            "slow" -> 2
            "congested" -> 3
            else -> 0
        }

        var flags = 0
        if (snapshot.hasDestination) flags = flags or 1
        if (snapshot.hasFix) flags = flags or 2
        if (snapshot.gnssStale) flags = flags or 4
        if (snapshot.offRoute) flags = flags or 8
        if (snapshot.instructionText.isNotBlank()) flags = flags or 16
        if (snapshot.routeRequestInFlight) flags = flags or 32
        if (snapshot.trafficRequestInFlight) flags = flags or 64
        if (snapshot.hasDestination && snapshot.routeToken != 0L) flags = flags or 128

        var roadName = ""
        var instruction = snapshot.instructionText
        if (snapshot.instructionText.contains("\n")) {
            val parts = snapshot.instructionText.split("\n", limit = 2)
            roadName = parts[0]
            instruction = parts[1]
        }

        val writer = Writer()
        writer.writeU8(PAYLOAD_REVISION.toInt())
        writer.writeU8(state)
        writer.writeU8(network)
        writer.writeU8(displayPage)
        writer.writeU8(maneuver)
        writer.writeU8(traffic)
        writer.writeU16(flags)
        writer.writeU32(snapshot.routeToken)
        writer.writeU32(snapshot.routeGeneration)
        writer.writeU32(snapshot.maneuverID)
        writer.writeU32(snapshot.distanceToManeuverM)
        writer.writeU32(snapshot.remainingDistanceM)
        writer.writeU32(snapshot.remainingDurationS)
        writer.writeU32(snapshot.routeProgressM)
        writer.writeU32(snapshot.totalDistanceM)
        writer.writeU16(snapshot.speedDeciKPH)
        writer.writeU16(snapshot.speedLimitKPH)
        writer.writeU16(snapshot.headingCentiDegrees)
        writer.writeU16(snapshot.accuracyDecimeters)
        writer.writeU16(snapshot.crossTrackDecimeters)
        writer.writeU8(snapshot.roundaboutExit)
        writer.writeStringU8(roadName)
        writer.writeStringU8(instruction)
        return writer.toByteArray()
    }

    private fun buildRouteGeometryPayload(geometry: MotoBLERouteGeometry): ByteArray? {
        if (geometry.points.isEmpty()) return null

        val writer = Writer()
        writer.writeU8(PAYLOAD_REVISION.toInt())
        writer.writeU8(geometry.coordinateSystem)
        writer.writeU32(geometry.routeToken)
        writer.writeU32(geometry.routeGeneration)
        writer.writeU16(geometry.chunkIndex)
        writer.writeU16(geometry.chunkCount)
        writer.writeU16(geometry.firstPointIndex)
        writer.writeU16(geometry.totalPointCount)
        writer.writeS32(geometry.viewOrigin.latitudeE6)
        writer.writeS32(geometry.viewOrigin.longitudeE6)
        writer.writeU8(geometry.points.size)

        val first = geometry.points.first()
        writer.writeS32(first.latitudeE6)
        writer.writeS32(first.longitudeE6)

        var prev = first
        for (i in 1 until geometry.points.size) {
            val pt = geometry.points[i]
            val dLat = pt.latitudeE6 - prev.latitudeE6
            val dLon = pt.longitudeE6 - prev.longitudeE6
            writer.writeSignedVarint(dLat)
            writer.writeSignedVarint(dLon)
            prev = pt
        }

        return writer.toByteArray()
    }

    private fun buildMapScenePayload(scene: MotoBLEMapScene): ByteArray? {
        if (scene.roads.size > 24 || scene.buildings.size > 16) return null

        var totalRoadPoints = 0
        for (road in scene.roads) {
            if (road.points.size < 2) return null
            totalRoadPoints += road.points.size
        }
        if (totalRoadPoints > 192) return null

        var totalBuildingPoints = 0
        for (building in scene.buildings) {
            if (building.points.size < 3) return null
            totalBuildingPoints += building.points.size
        }
        if (totalBuildingPoints > 128) return null

        val writer = Writer()
        writer.writeU8(PAYLOAD_REVISION.toInt())
        writer.writeU8(scene.coordinateSystem)
        writer.writeU32(scene.sceneRevision)
        writer.writeS32(scene.viewOrigin.latitudeE6)
        writer.writeS32(scene.viewOrigin.longitudeE6)
        writer.writeU16(scene.radiusMeters)
        writer.writeU8(scene.roads.size)
        writer.writeU8(scene.buildings.size)

        for (road in scene.roads) {
            writer.writeU8(road.roadClass)
            writer.writeU8(road.points.size)
            for (pt in road.points) {
                val dLat = pt.latitudeE6 - scene.viewOrigin.latitudeE6
                val dLon = pt.longitudeE6 - scene.viewOrigin.longitudeE6
                writer.writeSignedVarint(dLat)
                writer.writeSignedVarint(dLon)
            }
        }

        for (building in scene.buildings) {
            writer.writeU8(building.buildingClass)
            writer.writeU8(building.points.size)
            for (pt in building.points) {
                val dLat = pt.latitudeE6 - scene.viewOrigin.latitudeE6
                val dLon = pt.longitudeE6 - scene.viewOrigin.longitudeE6
                writer.writeSignedVarint(dLat)
                writer.writeSignedVarint(dLon)
            }
        }

        return writer.toByteArray()
    }

    private fun fragmentMessage(
        storage: CodecStorage,
        type: Byte,
        payload: ByteArray,
        ackRequested: Boolean,
        urgent: Boolean
    ): Array<ByteArray> {
        var baseFlags = 0
        if (ackRequested) baseFlags = baseFlags or ACK_REQUESTED
        if (urgent) baseFlags = baseFlags or URGENT

        val maxFrameSize = storage.maxFrameSize
        val fragmentCapacity = maxFrameSize - FRAME_OVERHEAD
        val seq = storage.nextSequence()
        storage.lastEncodedSequence = seq

        val frames = mutableListOf<ByteArray>()
        var offset = 0

        if (payload.isEmpty()) {
            val flags = baseFlags or FRAME_START or FRAME_END
            frames.add(encodeFrame(type, flags.toByte(), seq, 0, 0, ByteArray(0)))
        } else {
            while (offset < payload.size) {
                val count = min(fragmentCapacity, payload.size - offset)
                var flags = baseFlags
                if (offset == 0) flags = flags or FRAME_START
                if (offset + count == payload.size) flags = flags or FRAME_END

                val slice = payload.copyOfRange(offset, offset + count)
                frames.add(encodeFrame(type, flags.toByte(), seq, offset, payload.size, slice))
                offset += count
            }
        }

        return frames.toTypedArray()
    }

    private fun encodeFrame(
        type: Byte,
        flags: Byte,
        sequence: Int,
        fragmentOffset: Int,
        messageLength: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = FRAME_OVERHEAD + payload.size
        val buf = ByteArray(totalLen)
        buf[0] = FRAME_MAGIC
        buf[1] = PROTOCOL_VERSION
        buf[2] = type
        buf[3] = flags
        buf[4] = (sequence and 0xFF).toByte()
        buf[5] = ((sequence shr 8) and 0xFF).toByte()
        buf[6] = (fragmentOffset and 0xFF).toByte()
        buf[7] = ((fragmentOffset shr 8) and 0xFF).toByte()
        buf[8] = (messageLength and 0xFF).toByte()
        buf[9] = ((messageLength shr 8) and 0xFF).toByte()

        System.arraycopy(payload, 0, buf, FRAME_HEADER_SIZE, payload.size)

        val crc = crc16CcittFalse(buf, totalLen - 2)
        buf[totalLen - 2] = (crc and 0xFF).toByte()
        buf[totalLen - 1] = ((crc shr 8) and 0xFF).toByte()

        return buf
    }

    private class Writer {
        private val list = mutableListOf<Byte>()

        fun writeU8(v: Int) {
            list.add((v and 0xFF).toByte())
        }

        fun writeU16(v: Int) {
            writeU8(v and 0xFF)
            writeU8((v shr 8) and 0xFF)
        }

        fun writeU32(v: Long) {
            writeU16((v and 0xFFFF).toInt())
            writeU16(((v shr 16) and 0xFFFF).toInt())
        }

        fun writeS32(v: Int) {
            writeU32(v.toLong() and 0xFFFFFFFFL)
        }

        fun writeSignedVarint(value: Int) {
            val wide = value.toLong()
            var encoded = if (wide >= 0) {
                (wide * 2) and 0xFFFFFFFFL
            } else {
                ((-wide * 2) - 1) and 0xFFFFFFFFL
            }
            do {
                var b = (encoded and 0x7F).toInt()
                encoded = encoded ushr 7
                if (encoded != 0L) {
                    b = b or 0x80
                }
                writeU8(b)
            } while (encoded != 0L)
        }

        fun writeStringU8(str: String) {
            val bytes = str.toByteArray(StandardCharsets.UTF_8)
            val len = min(bytes.size, 255)
            writeU8(len)
            for (i in 0 until len) {
                list.add(bytes[i])
            }
        }

        fun toByteArray(): ByteArray = list.toByteArray()
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun remaining(): Int = bytes.size - pos

        fun readU8(): Int {
            if (pos >= bytes.size) return 0
            return bytes[pos++].toInt() and 0xFF
        }

        fun readU16(): Int {
            val low = readU8()
            val high = readU8()
            return low or (high shl 8)
        }

        fun readU32(): Long {
            val low = readU16().toLong()
            val high = readU16().toLong()
            return low or (high shl 16)
        }
    }
}
