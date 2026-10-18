package com.motogps.android.jni

class MotoBLEProtocolCodec {
    init {
        System.loadLibrary("motogps_jni")
    }

    external fun nativeInit(maximumFrameSize: Int): Long
    external fun nativeDestroy(ptr: Long)
    external fun nativeSetMaximumFrameSize(ptr: Long, maximumFrameSize: Int)
    external fun nativeResetInboundState(ptr: Long)
    external fun nativeEncodePhoneStarting(ptr: Long, sessionID: Long): Array<ByteArray>
    external fun nativeEncodePhoneReady(ptr: Long, sessionID: Long): Array<ByteArray>
    external fun nativeEncodeHeartbeat(ptr: Long, sessionID: Long, monotonicMs: Long): Array<ByteArray>
    external fun nativeEncodeAck(ptr: Long, sequence: Int, status: Int, commandID: Int): Array<ByteArray>
    external fun nativeEncodeNavigationSnapshot(ptr: Long, snapshot: MotoBLENavigationSnapshot): Array<ByteArray>
    external fun nativeEncodeNavigationPayloadForGoldenCheck(ptr: Long, snapshot: MotoBLENavigationSnapshot): ByteArray?
    external fun nativeEncodeMediaState(ptr: Long, state: Any): Array<ByteArray>
    external fun nativePushDeviceFrame(ptr: Long, frame: ByteArray, receivedAtMs: Long): MotoBLEInboundMessage
    external fun nativeGetLastEncodedSequence(ptr: Long): Int
}
