package top.maler.motogps.core

/**
 * Re-encodes the shared golden NavigationSnapshot and compares it with
 * shared/protocol/fixtures/ble-navigation-v1.golden.txt (bundled as an asset).
 * Mirrors BLEProtocolGoldenCheck on iOS.
 */
object BleGoldenCheck {
    const val ASSET_NAME = "ble-navigation-v1.golden.txt"

    /** The input documented next to navigation.payload in the fixture. */
    fun goldenNavigationInput() = BleNavigationSnapshotInput(
        stateName = "navigating",
        networkName = "online",
        displayPageName = "navigation",
        maneuverName = "right",
        trafficName = "slow",
        hasDestination = true,
        hasFix = true,
        hasRouteView = true,
        routeToken = BleProtocolCodec.routeToken("web-beijing-fixture"),
        routeGeneration = 0x01020304,
        maneuverId = 42,
        distanceToManeuverM = 376,
        remainingDistanceM = 12_345,
        remainingDurationS = 1_020,
        routeProgressM = 2_100,
        totalDistanceM = 14_445,
        speedDeciKph = 483,
        speedLimitKph = 50,
        headingCentiDegrees = 9_123,
        accuracyDecimeters = 38,
        crossTrackDecimeters = 125,
        roadName = "东长安街",
        instructionText = "前方路口右转",
    )

    fun vectors(fixture: String): Map<String, String> = fixture.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

    /** Throws [BleProtocolException] when the shared encoder output drifts from the fixture. */
    fun verify(codec: BleProtocolCodec, fixture: String) {
        val expected = vectors(fixture)["navigation.payload"]
            ?: throw BleProtocolException("BLE v1 黄金帧缺少 navigation.payload")
        val actual = codec.encodeNavigationPayload(goldenNavigationInput()).toHex()
        if (actual != expected) {
            throw BleProtocolException("BLE v1 编码不一致：expected $expected, actual $actual")
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
