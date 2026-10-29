package top.maler.motogps

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.maler.motogps.ble.BleCommandDisposition
import top.maler.motogps.ble.BleFrameSink
import top.maler.motogps.ble.BleProtocolEngine
import top.maler.motogps.core.BleGoldenCheck
import top.maler.motogps.core.BleMapPoint
import top.maler.motogps.core.BleMapSceneInput
import top.maler.motogps.core.BleMapShape
import top.maler.motogps.core.BleMediaStateInput
import top.maler.motogps.core.BleProtocolCodec
import top.maler.motogps.core.InboundMessage
import top.maler.motogps.core.NavCoreBridge
import top.maler.motogps.nav.JinanDemoFixture

/**
 * Runs the phone protocol engine against a simulated round screen that uses
 * the same shared C++ codec as the ESP32 firmware (device role). This covers
 * the full v1 handshake, paced writes with GATT completion callbacks,
 * snapshots, command ACK/de-duplication, map ACKs and liveness — everything
 * except the radio itself, which still needs a real phone and screen.
 */
@RunWith(AndroidJUnit4::class)
class BleProtocolEngineSimulationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** Device side of the link. Answers on the main thread like GATT callbacks would. */
    private inner class SimulatedScreen(private val engine: BleProtocolEngine, private val frameSize: Int = 185) {
        val codec = BleProtocolCodec(frameSize)
        val received = CopyOnWriteArrayList<InboundMessage>()
        var sessionId = 0L
        var answerHeartbeats = true
        var capabilities = 0xFFL

        val sink = BleFrameSink { frame, _ ->
            main.post {
                // GATT reports the write before the peer processes it.
                engine.onWriteComplete(true)
                receive(frame)
            }
            true
        }

        private fun receive(frame: ByteArray) {
            val message = codec.pushFrame(frame, android.os.SystemClock.elapsedRealtime())
            received += message
            when {
                message is InboundMessage.ConnectionStatus && message.role == 1 -> {
                    sessionId = message.sessionId
                    notify(codec.encodeDeviceStatus(state = 1, sessionId = sessionId, maximumFrameSize = frameSize, heartbeatIntervalMs = 1_000, capabilities = capabilities))
                }
                message is InboundMessage.Heartbeat && answerHeartbeats ->
                    notify(codec.encodeHeartbeat(sessionId, 1_000))
                message is InboundMessage.PhoneMessage && message.ackRequested ->
                    notify(codec.encodeAck(message.sequence, 0, 0))
            }
        }

        fun notify(frames: List<ByteArray>) = frames.forEach { frame -> main.post { engine.onNotification(frame) } }

        fun sendCommand(kind: Int, commandId: Int, page: Int): List<ByteArray> {
            val frames = codec.encodeDeviceCommand(kind, commandId, page, 1)
            notify(frames)
            return frames
        }

        inline fun <reified T : InboundMessage> count(predicate: (T) -> Boolean = { true }) =
            received.filterIsInstance<T>().count(predicate)
    }

    private val golden = InstrumentationRegistry.getInstrumentation().targetContext.assets
        .open(BleGoldenCheck.ASSET_NAME).bufferedReader().use { it.readText() }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(20) }
    }

    @Test
    fun handshakeSnapshotsCommandsMapAndHeartbeat() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = BleProtocolEngine(scope, goldenFixture = { golden })
            val screen = SimulatedScreen(engine)
            var ready = 0
            var failure: String? = null
            val commands = ArrayList<Int>()
            engine.onProtocolReady = { ready += 1 }
            engine.onTransportError = { failure = it }
            engine.onDeviceCommand = { command ->
                commands += command.kind
                BleCommandDisposition.ACCEPTED
            }

            engine.attach(screen.sink, localMaximumFrameSize = 244, supportsWithResponse = true)
            waitFor { engine.protocolReady }
            assertEquals(1, ready)
            assertEquals(null, failure)
            // Starting then Ready, both for the same random session.
            assertEquals(2, screen.count<InboundMessage.ConnectionStatus>())
            assertTrue(screen.sessionId in 1L..0xFFFF_FFFFL)

            // A real NavCore snapshot with a route window: geometry goes first, then the snapshot.
            val fixture = InstrumentationRegistry.getInstrumentation().targetContext.assets
                .open(JinanDemoFixture.ASSET_NAME).bufferedReader().use { JinanDemoFixture.parse(it.readText()) }
            NavCoreBridge().use { core ->
                core.setNetworkState("online")
                core.beginNavigation(fixture.requestedDestinationWgs84.longitudeDeg, fixture.requestedDestinationWgs84.latitudeDeg)
                val start = fixture.routeWgs84[1]
                val request = core.pushFix(start.longitudeDeg, start.latitudeDeg, 3.5, 5.0, 90.0, System.currentTimeMillis()).single()
                core.acceptRoute(fixture.fallbackRoute(false, System.currentTimeMillis()), request.requestId, System.currentTimeMillis())
                assertTrue(core.snapshot.hasRouteView)
                engine.sendNavigationSnapshot(core.snapshot)
            }
            waitFor { screen.count<InboundMessage.PhoneMessage> { it.type == 7 } >= 1 }
            val geometryIndex = screen.received.indexOfFirst { it is InboundMessage.PhoneMessage && it.type == 8 }
            val snapshotIndex = screen.received.indexOfFirst { it is InboundMessage.PhoneMessage && it.type == 7 }
            assertTrue("geometry must precede the snapshot", geometryIndex in 0 until snapshotIndex)

            // Media state is retained and sent.
            engine.sendMediaState(BleMediaStateInput(true, true, false, false, 9, 30, 200, "网易云音乐", "晴天", "周杰伦"))
            waitFor { screen.count<InboundMessage.PhoneMessage> { it.type == 9 } >= 1 }

            // Device command → handler once → ACK OK; the same sequence again is re-acknowledged only.
            val frames = screen.sendCommand(kind = 18, commandId = 77, page = 3)
            waitFor { screen.count<InboundMessage.Acknowledgement>() >= 1 }
            screen.notify(frames)
            waitFor { screen.count<InboundMessage.Acknowledgement>() >= 2 }
            assertEquals(listOf(18), commands)
            assertTrue(screen.received.filterIsInstance<InboundMessage.Acknowledgement>().all { it.status == 0 && it.commandId == 77 })

            // Map scene requests an application ACK, which the simulated screen sends.
            engine.sendMapScene(
                BleMapSceneInput(
                    sceneRevision = 5, originLatitudeE6 = 36_670_000, originLongitudeE6 = 117_120_000, radiusM = 500,
                    roads = listOf(BleMapShape("primary", listOf(BleMapPoint(36_670_000, 117_120_000), BleMapPoint(36_671_000, 117_121_000)))),
                    buildings = emptyList(),
                ),
            )
            waitFor { screen.count<InboundMessage.PhoneMessage> { it.type == 10 && it.ackRequested } >= 1 }

            // Heartbeats carry the session and keep the link alive.
            waitFor(3_000) { screen.count<InboundMessage.Heartbeat> { it.sessionId == screen.sessionId } >= 2 }
            assertEquals(null, failure)
            engine.detach()
        }
    }

    @Test
    fun silentScreenTriggersLivenessRecovery() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = BleProtocolEngine(scope)
            val screen = SimulatedScreen(engine)
            var failure: String? = null
            engine.onTransportError = { failure = it }
            engine.attach(screen.sink, 244, supportsWithResponse = true)
            waitFor { engine.protocolReady }
            screen.answerHeartbeats = false
            waitFor(9_000) { failure != null }
            assertNotNull(failure)
            assertTrue(failure!!, failure!!.contains("心跳"))
            assertTrue(!engine.protocolReady)
        }
    }

    @Test
    fun screenWithoutRequiredCapabilitiesIsRejected() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = BleProtocolEngine(scope)
            val screen = SimulatedScreen(engine).apply { capabilities = 0x01 }
            var failure: String? = null
            engine.onTransportError = { failure = it }
            engine.attach(screen.sink, 244, supportsWithResponse = true)
            waitFor { failure != null }
            assertTrue(failure!!, failure!!.contains("必要能力"))
        }
    }

    @Test
    fun smallMtuStillCompletesTheHandshake() = runBlocking {
        withContext(Dispatchers.Main) {
            val engine = BleProtocolEngine(scope)
            val screen = SimulatedScreen(engine, frameSize = 20)
            engine.attach(screen.sink, 20, supportsWithResponse = false)
            waitFor { engine.protocolReady }
            engine.detach()
        }
    }
}
