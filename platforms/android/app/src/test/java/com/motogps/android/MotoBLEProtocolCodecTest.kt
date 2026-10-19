package com.motogps.android

import com.motogps.android.jni.MotoBLEProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MotoBLEProtocolCodecTest {

    private lateinit var codec: MotoBLEProtocolCodec
    private var handle: Long = 0L

    @Before
    fun setUp() {
        codec = MotoBLEProtocolCodec()
        handle = codec.nativeInit(247)
    }

    @Test
    fun testPhoneStartingFrameEncoding() {
        val sessionId = 12345678L
        val frames = codec.nativeEncodePhoneStarting(handle, sessionId)

        assertEquals(1, frames.size)
        val frame = frames[0]

        // Frame header checks
        assertEquals(0xB7.toByte(), frame[0]) // MAGIC
        assertEquals(0x01.toByte(), frame[1]) // PROTOCOL_VERSION
        assertEquals(0x01.toByte(), frame[2]) // MSG_CONNECTION_STATUS
        // Flags: START (1) | END (2) | URGENT (8) = 11 (0x0B)
        assertEquals(0x0B.toByte(), frame[3])

        // Sequence number = 1 in Little Endian uint16
        assertEquals(0x01.toByte(), frame[4])
        assertEquals(0x00.toByte(), frame[5])

        // CRC check
        val crcOffset = frame.size - 2
        val expectedCrc = (frame[crcOffset].toInt() and 0xFF) or ((frame[crcOffset + 1].toInt() and 0xFF) shl 8)
        val actualCrc = MotoBLEProtocolCodec.crc16CcittFalse(frame, crcOffset)
        assertEquals(expectedCrc, actualCrc)

        // Payload checks
        // byte 10: Payload Revision (1)
        assertEquals(0x01.toByte(), frame[10])
        // byte 11: Role Phone (1)
        assertEquals(0x01.toByte(), frame[11])
        // byte 12: State Starting (0)
        assertEquals(0x00.toByte(), frame[12])
    }

    @Test
    fun testPhoneReadyFrameEncoding() {
        val sessionId = 87654321L
        val frames = codec.nativeEncodePhoneReady(handle, sessionId)

        assertEquals(1, frames.size)
        val frame = frames[0]

        assertEquals(0xB7.toByte(), frame[0]) // MAGIC
        assertEquals(0x01.toByte(), frame[1]) // PROTOCOL_VERSION
        assertEquals(0x01.toByte(), frame[2]) // MSG_CONNECTION_STATUS
        assertEquals(0x0B.toByte(), frame[3]) // Flags: START | END | URGENT

        // byte 10: Payload Revision (1)
        assertEquals(0x01.toByte(), frame[10])
        // byte 11: Role Phone (1)
        assertEquals(0x01.toByte(), frame[11])
        // byte 12: State Ready (1)
        assertEquals(0x01.toByte(), frame[12])
    }

    @Test
    fun testHeartbeatFrameEncoding() {
        val sessionId = 1000L
        val monotonicMs = 5000L
        val frames = codec.nativeEncodeHeartbeat(handle, sessionId, monotonicMs)

        assertEquals(1, frames.size)
        val frame = frames[0]

        assertEquals(0xB7.toByte(), frame[0]) // MAGIC
        assertEquals(0x01.toByte(), frame[1]) // PROTOCOL_VERSION
        assertEquals(0x02.toByte(), frame[2]) // MSG_HEARTBEAT
        assertEquals(0x03.toByte(), frame[3]) // Flags: START | END (3)

        val crcOffset = frame.size - 2
        val expectedCrc = (frame[crcOffset].toInt() and 0xFF) or ((frame[crcOffset + 1].toInt() and 0xFF) shl 8)
        val actualCrc = MotoBLEProtocolCodec.crc16CcittFalse(frame, crcOffset)
        assertEquals(expectedCrc, actualCrc)
    }

    @Test
    fun testPushDeviceFrameReassembly() {
        // Synthesize an inbound DeviceReady connection status message
        // Payload: rev(1), role=2(Device), state=1(Ready), minVer=1, maxVer=1, capabilities=0, sessionId=100, maxFrameSize=247, heartbeatInterval=1000
        val payload = byteArrayOf(
            1, // payload rev
            2, // role Device
            1, // state Ready
            1, // minVer
            1, // maxVer
            0, 0, 0, 0, // capabilities uint32
            100, 0, 0, 0, // sessionId uint32
            247.toByte(), 0, // maxFrameSize uint16
            0xE8.toByte(), 0x03 // heartbeatInterval 1000 uint16
        )

        val header = byteArrayOf(
            0xB7.toByte(), // magic
            1, // version
            1, // MSG_CONNECTION_STATUS
            3, // flags: START | END
            1, 0, // seq=1
            0, 0, // offset=0
            payload.size.toByte(), 0 // len
        )

        val totalLen = header.size + payload.size + 2
        val frame = ByteArray(totalLen)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(payload, 0, frame, header.size, payload.size)

        val crc = MotoBLEProtocolCodec.crc16CcittFalse(frame, totalLen - 2)
        frame[totalLen - 2] = (crc and 0xFF).toByte()
        frame[totalLen - 1] = ((crc shr 8) and 0xFF).toByte()

        val inbound = codec.nativePushDeviceFrame(handle, frame, System.currentTimeMillis())

        assertTrue(inbound.complete)
        assertNotNull(inbound.connStatus)
        assertEquals(2, inbound.connStatus!!.role) // Device
        assertEquals(1, inbound.connStatus!!.state) // Ready
        assertEquals(100L, inbound.connStatus!!.sessionId)
    }
}
