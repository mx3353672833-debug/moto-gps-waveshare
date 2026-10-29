package top.maler.motogps.core

/**
 * Raw JNI surface of libmoto_jni (app/src/main/cpp/moto_jni.cpp). Use
 * [NavCoreBridge] and [BleProtocolCodec] instead of calling this directly.
 * Strings travel as UTF-8 byte arrays; NavCore results are UTF-8 JSON.
 */
internal object MotoNative {
    init {
        System.loadLibrary("moto_jni")
    }

    // NavApp
    @JvmStatic external fun navCreate(): Long
    @JvmStatic external fun navDestroy(handle: Long)
    @JvmStatic external fun navSnapshot(handle: Long): ByteArray
    @JvmStatic external fun navReset(handle: Long): ByteArray
    @JvmStatic external fun navBegin(handle: Long, longitude: Double, latitude: Double): ByteArray
    @JvmStatic external fun navCancel(handle: Long): ByteArray
    @JvmStatic external fun navSetNetwork(handle: Long, name: ByteArray): ByteArray
    @JvmStatic external fun navSelectPage(handle: Long, name: ByteArray): ByteArray

    @JvmStatic external fun navPushFix(
        handle: Long,
        longitude: Double,
        latitude: Double,
        accuracyM: Double,
        speedMps: Double,
        headingDeg: Double,
        timestampMs: Long,
    ): ByteArray

    @JvmStatic external fun navAcceptRoute(
        handle: Long,
        requestId: Long,
        routeId: ByteArray,
        polylineLatLon: DoubleArray,
        maneuverIds: LongArray,
        maneuverTypes: Array<ByteArray>,
        maneuverOffsets: DoubleArray,
        maneuverRoads: Array<ByteArray>,
        maneuverInstructions: Array<ByteArray>,
        maneuverRoundaboutExits: IntArray,
        trafficStarts: DoubleArray,
        trafficEnds: DoubleArray,
        trafficLevels: Array<ByteArray>,
        totalDistanceM: Double,
        totalDurationS: Long,
        speedLimitKph: Int,
        generatedAtMs: Long,
        receivedAtMs: Long,
    ): ByteArray

    @JvmStatic external fun navRejectRoute(
        handle: Long,
        requestId: Long,
        retryable: Boolean,
        receivedAtMs: Long,
    ): ByteArray

    @JvmStatic external fun navAcceptTraffic(
        handle: Long,
        routeId: ByteArray,
        starts: DoubleArray,
        ends: DoubleArray,
        levels: Array<ByteArray>,
        remainingDurationS: Long,
        requestId: Long,
        observedAtMs: Long,
    ): ByteArray

    @JvmStatic external fun navRejectTraffic(handle: Long, requestId: Long, receivedAtMs: Long): ByteArray
    @JvmStatic external fun navTick(handle: Long, nowMs: Long): ByteArray

    // BLE v1 codec
    @JvmStatic external fun bleServiceUuid(): String
    @JvmStatic external fun blePhoneToDeviceUuid(): String
    @JvmStatic external fun bleDeviceToPhoneUuid(): String
    @JvmStatic external fun bleCreate(maximumFrameSize: Int): Long
    @JvmStatic external fun bleDestroy(handle: Long)
    @JvmStatic external fun bleSetMaximumFrameSize(handle: Long, maximumFrameSize: Int)
    @JvmStatic external fun bleMaximumFrameSize(handle: Long): Int
    @JvmStatic external fun bleResetInbound(handle: Long)
    @JvmStatic external fun bleLastEncodedSequence(handle: Long): Int
    @JvmStatic external fun bleLastError(handle: Long): String
    @JvmStatic external fun bleRouteToken(routeId: ByteArray): Long
    @JvmStatic external fun bleEncodePhoneStatus(handle: Long, ready: Boolean, sessionId: Long): Array<ByteArray>?
    @JvmStatic external fun bleEncodeHeartbeat(handle: Long, sessionId: Long, monotonicMs: Long): Array<ByteArray>?
    @JvmStatic external fun bleEncodeAck(handle: Long, sequence: Int, status: Int, commandId: Int): Array<ByteArray>?

    @JvmStatic external fun bleEncodeNavigationSnapshot(
        handle: Long,
        names: Array<ByteArray>,
        flags: BooleanArray,
        numbers: LongArray,
        roadName: ByteArray,
        instruction: ByteArray,
    ): Array<ByteArray>?

    @JvmStatic external fun bleEncodeNavigationPayload(
        handle: Long,
        names: Array<ByteArray>,
        flags: BooleanArray,
        numbers: LongArray,
        roadName: ByteArray,
        instruction: ByteArray,
    ): ByteArray?

    @JvmStatic external fun bleEncodeRouteGeometry(
        handle: Long,
        routeId: ByteArray,
        routeGeneration: Long,
        originLatitude: Double,
        originLongitude: Double,
        pointsLatLon: DoubleArray,
    ): Array<ByteArray>?

    @JvmStatic external fun bleEncodeRouteGeometryPayload(
        handle: Long,
        routeId: ByteArray,
        routeGeneration: Long,
        originLatitude: Double,
        originLongitude: Double,
        pointsLatLon: DoubleArray,
    ): ByteArray?

    @JvmStatic external fun bleEncodeMediaState(
        handle: Long,
        flags: BooleanArray,
        trackToken: Long,
        positionS: Int,
        durationS: Int,
        sourceName: ByteArray,
        trackTitle: ByteArray,
        artistName: ByteArray,
    ): Array<ByteArray>?

    @JvmStatic external fun bleEncodeMapScene(
        handle: Long,
        revision: Long,
        originLatE6: Int,
        originLonE6: Int,
        radiusM: Int,
        roadClasses: Array<ByteArray>,
        roadCounts: IntArray,
        roadPoints: IntArray,
        buildingClasses: Array<ByteArray>,
        buildingCounts: IntArray,
        buildingPoints: IntArray,
    ): Array<ByteArray>?

    @JvmStatic external fun bleEncodeMapScenePayload(
        handle: Long,
        revision: Long,
        originLatE6: Int,
        originLonE6: Int,
        radiusM: Int,
        roadClasses: Array<ByteArray>,
        roadCounts: IntArray,
        roadPoints: IntArray,
        buildingClasses: Array<ByteArray>,
        buildingCounts: IntArray,
        buildingPoints: IntArray,
    ): ByteArray?

    @JvmStatic external fun blePushFrame(handle: Long, frame: ByteArray, receivedAtMs: Long): LongArray?

    // Device role, for the protocol simulator in instrumented tests only.
    @JvmStatic external fun deviceEncodeStatus(
        handle: Long,
        state: Int,
        sessionId: Long,
        maximumFrameSize: Int,
        heartbeatIntervalMs: Int,
        capabilities: Long,
    ): Array<ByteArray>?

    @JvmStatic external fun deviceEncodeCommand(
        handle: Long,
        kind: Int,
        commandId: Int,
        page: Int,
        eventTimeMs: Long,
    ): Array<ByteArray>?
}

internal fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
