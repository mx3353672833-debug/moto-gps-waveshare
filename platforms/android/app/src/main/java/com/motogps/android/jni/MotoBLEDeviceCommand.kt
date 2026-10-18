package com.motogps.android.jni

data class MotoBLEDeviceCommand(
    val sequence: Int,
    val ackReq: Boolean,
    val kind: Int,
    val commandId: Int,
    val page: Int,
    val x: Int,
    val y: Int,
    val eventTimeMs: Long
)
