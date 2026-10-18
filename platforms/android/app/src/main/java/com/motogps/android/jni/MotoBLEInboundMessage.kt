package com.motogps.android.jni

data class MotoBLEInboundMessage(
    val sequence: Int,
    val ackReq: Boolean,
    val complete: Boolean,
    val duplicate: Boolean,
    val connStatus: MotoBLEConnectionStatus?,
    val heartbeat: MotoBLEHeartbeat?,
    val ack: MotoBLEAcknowledgement?,
    val command: MotoBLEDeviceCommand?
)
