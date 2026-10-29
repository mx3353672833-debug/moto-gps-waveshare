package top.maler.motogps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.maler.motogps.core.BleGoldenCheck
import top.maler.motogps.core.BleProtocolCodec
import top.maler.motogps.core.BleProtocolException
import top.maler.motogps.core.InboundMessage
import top.maler.motogps.core.hexToBytes
import top.maler.motogps.core.toHex
import top.maler.motogps.map.InMemoryOfflineMapSceneIndex
import top.maler.motogps.map.OfflineMapSceneDocument

/** Shared C++ wire contract exercised through the Android JNI bridge on a real ART runtime. */
@RunWith(AndroidJUnit4::class)
class NativeProtocolTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val fixture = assets.open(BleGoldenCheck.ASSET_NAME).bufferedReader().use { it.readText() }
    private val vectors = BleGoldenCheck.vectors(fixture)

    @Test
    fun uuidsMatchTheV1GattContract() {
        assertEquals("7e57a000-b50c-4b6a-9c57-40a54e8e1000", BleProtocolCodec.serviceUuid)
        assertEquals("7e57a001-b50c-4b6a-9c57-40a54e8e1000", BleProtocolCodec.phoneToDeviceUuid)
        assertEquals("7e57a002-b50c-4b6a-9c57-40a54e8e1000", BleProtocolCodec.deviceToPhoneUuid)
    }

    @Test
    fun navigationSnapshotMatchesGoldenBytes() {
        BleProtocolCodec(185).use { codec -> BleGoldenCheck.verify(codec, fixture) }
    }

    @Test
    fun routeGeometryMatchesGoldenBytes() {
        BleProtocolCodec(185).use { codec ->
            val payload = codec.encodeRouteGeometryPayload(
                routeId = "web-beijing-fixture",
                generation = 0x01020304,
                originLatitude = 39.922596,
                originLongitude = 116.416932,
                pointsLatLon = listOf(
                    listOf(39.922590, 116.416920),
                    listOf(39.922702, 116.417285),
                    listOf(39.922650, 116.417200),
                    listOf(39.923205, 116.418160),
                ),
            )
            assertEquals(vectors.getValue("geometry.payload"), payload.toHex())
        }
    }

    @Test
    fun goldenConnectionFrameDecodes() {
        BleProtocolCodec(185).use { codec ->
            val status = codec.pushFrame(vectors.getValue("connection.frame").hexToBytes(), 1) as InboundMessage.ConnectionStatus
            assertEquals(1, status.role)
            assertEquals(1, status.state)
            assertEquals(0x7FL, status.capabilities)
            assertEquals(0x12345678L, status.sessionId)
            assertEquals(185, status.maximumFrameSize)
            assertEquals(1_000, status.heartbeatIntervalMs)
        }
    }

    @Test
    fun goldenTwentyByteCommandFragmentsReassemble() {
        BleProtocolCodec(185).use { codec ->
            val frames = vectors.getValue("command.frames20").split(';').map { it.hexToBytes() }
            assertTrue(codec.pushFrame(frames[0], 1) is InboundMessage.Partial)
            val command = codec.pushFrame(frames[1], 2) as InboundMessage.DeviceCommand
            assertEquals(18, command.kind)
            assertEquals(0x1234, command.commandId)
            assertEquals(3, command.page)
            assertEquals(0x10203040L, command.eventTimeMs)
            assertTrue(command.ackRequested)
            assertTrue(codec.pushFrame(frames[0], 3) is InboundMessage.Duplicate)
        }
    }

    @Test
    fun corruptedFrameIsRejected() {
        BleProtocolCodec(185).use { codec ->
            val frame = vectors.getValue("connection.frame").hexToBytes()
            frame[frame.size - 3] = (frame[frame.size - 3].toInt() xor 0x01).toByte()
            assertThrows(BleProtocolException::class.java) { codec.pushFrame(frame, 1) }
        }
    }

    @Test
    fun smallestMtuFragmentsEveryMessage() {
        BleProtocolCodec(20).use { codec ->
            val frames = codec.encodePhoneStarting(sessionId = 0xCAFE)
            assertTrue(frames.size > 1)
            assertTrue(frames.all { it.size <= 20 })
        }
    }

    @Test
    fun croppedJinanSceneEncodesInto182ByteFrames() {
        val json = assets.open("jinan_map_scene_sample.json").bufferedReader().use { it.readText() }
        val document = Json { ignoreUnknownKeys = true }.decodeFromString(OfflineMapSceneDocument.serializer(), json)
        val window = InMemoryOfflineMapSceneIndex(document).query(document.viewOrigin, 500, 1)
        BleProtocolCodec(182).use { codec ->
            val payload = codec.encodeMapScenePayload(window.toBleInput())
            assertTrue(payload.size in 32..4_096)
            val frames = codec.encodeMapScene(window.toBleInput())
            assertTrue(frames.isNotEmpty() && frames.all { it.size <= 182 })
        }
    }

    @Test
    fun routeTokenHashesUtf8Bytes() {
        assertTrue(BleProtocolCodec.routeToken("demo-jinan-big-data-d-to-inspur-v2") != 0L)
        assertEquals(BleProtocolCodec.routeToken("经十路"), BleProtocolCodec.routeToken("经十路"))
        assertTrue(BleProtocolCodec.routeToken("a") != BleProtocolCodec.routeToken("b"))
    }
}
