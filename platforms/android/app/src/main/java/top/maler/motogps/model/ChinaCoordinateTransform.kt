package top.maler.motogps.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02 boundary helpers for the phone-side map and tile planner. Route
 * requests stay WGS84 (the gateway converts); route geometry and round-screen
 * map data stay GCJ-02. Only drawing and Web Mercator tile addressing convert.
 * Mirrors ChinaCoordinateTransform in the iOS demo support code.
 */
object ChinaCoordinateTransform {
    private const val EARTH_AXIS_M = 6_378_245.0
    private const val ECCENTRICITY_SQUARED = 0.006693421622965943

    fun gcj02ToWgs84(point: GCJ02Point): WGS84Point {
        var minimumLongitude = point.longitudeDeg - 0.02
        var maximumLongitude = point.longitudeDeg + 0.02
        var minimumLatitude = point.latitudeDeg - 0.02
        var maximumLatitude = point.latitudeDeg + 0.02
        var candidate = WGS84Point(longitudeDeg = point.longitudeDeg, latitudeDeg = point.latitudeDeg)
        repeat(32) {
            candidate = WGS84Point(
                longitudeDeg = (minimumLongitude + maximumLongitude) * 0.5,
                latitudeDeg = (minimumLatitude + maximumLatitude) * 0.5,
            )
            val projected = wgs84ToGcj02(candidate)
            if (projected.longitudeDeg < point.longitudeDeg) {
                minimumLongitude = candidate.longitudeDeg
            } else {
                maximumLongitude = candidate.longitudeDeg
            }
            if (projected.latitudeDeg < point.latitudeDeg) {
                minimumLatitude = candidate.latitudeDeg
            } else {
                maximumLatitude = candidate.latitudeDeg
            }
        }
        return candidate
    }

    fun wgs84ToGcj02(point: WGS84Point): GCJ02Point {
        if (point.longitudeDeg < 72.004 || point.longitudeDeg > 137.8347 ||
            point.latitudeDeg < 0.8293 || point.latitudeDeg > 55.8271
        ) {
            return GCJ02Point(longitudeDeg = point.longitudeDeg, latitudeDeg = point.latitudeDeg)
        }
        val longitudeOffset = point.longitudeDeg - 105
        val latitudeOffset = point.latitudeDeg - 35
        var latitudeDelta = transformLatitude(longitudeOffset, latitudeOffset)
        var longitudeDelta = transformLongitude(longitudeOffset, latitudeOffset)
        val radianLatitude = point.latitudeDeg / 180 * PI
        var magic = sin(radianLatitude)
        magic = 1 - ECCENTRICITY_SQUARED * magic * magic
        val squareRootMagic = sqrt(magic)
        latitudeDelta = latitudeDelta * 180 /
            ((EARTH_AXIS_M * (1 - ECCENTRICITY_SQUARED) / (magic * squareRootMagic)) * PI)
        longitudeDelta = longitudeDelta * 180 /
            ((EARTH_AXIS_M / squareRootMagic) * cos(radianLatitude) * PI)
        return GCJ02Point(
            longitudeDeg = point.longitudeDeg + longitudeDelta,
            latitudeDeg = point.latitudeDeg + latitudeDelta,
        )
    }

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        result += (20 * sin(y * PI) + 40 * sin(y / 3 * PI)) * 2 / 3
        result += (160 * sin(y / 12 * PI) + 320 * sin(y * PI / 30)) * 2 / 3
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        result += (20 * sin(x * PI) + 40 * sin(x / 3 * PI)) * 2 / 3
        result += (150 * sin(x / 12 * PI) + 300 * sin(x / 30 * PI)) * 2 / 3
        return result
    }
}
