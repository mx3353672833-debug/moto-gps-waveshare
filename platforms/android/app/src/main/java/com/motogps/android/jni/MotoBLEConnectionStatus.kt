package com.motogps.android.jni

data class MotoBLEConnectionStatus(
    val role: Int,
    val state: Int,
    val minimumVersion: Int,
    val maximumVersion: Int,
    val capabilities: Long,
    val sessionId: Long,
    val maxFrameSize: Int,
    val heartbeatIntervalMs: Int
)
