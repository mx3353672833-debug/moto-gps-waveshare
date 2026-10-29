package top.maler.motogps.map

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maler.motogps.model.ChinaCoordinateTransform
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.model.WGS84Point

class MapTilePlannerTest {
    private val jinanToQingdao = listOf(
        ChinaCoordinateTransform.wgs84ToGcj02(WGS84Point(longitudeDeg = 117.01, latitudeDeg = 36.65)),
        ChinaCoordinateTransform.wgs84ToGcj02(WGS84Point(longitudeDeg = 120.38, latitudeDeg = 36.07)),
    )

    private fun webMercatorTile(longitude: Double, latitude: Double): MapTileId {
        val count = 1 shl 15
        val x = floor((longitude + 180) / 360 * count).toInt()
        val y = floor((1 - asinh(tan(latitude * PI / 180)) / PI) / 2 * count).toInt()
        return MapTileId(15, x, y)
    }

    @Test
    fun knownGcj02FixUsesTheSameTilesAsItsWgs84Location() {
        // Pairs verified through the backend coordinate transform; z/x/y match
        // the real Protomaps samples used by the iOS tests.
        val pairs = listOf(
            Triple(GCJ02Point(117.1261301, 36.6704414), WGS84Point(longitudeDeg = 117.12, latitudeDeg = 36.67), MapTileId(15, 27044, 12791)),
            Triple(GCJ02Point(121.4782231, 31.2284577), WGS84Point(longitudeDeg = 121.4737, latitudeDeg = 31.2304), MapTileId(15, 27440, 13389)),
        )
        for ((gcj, wgs, tile) in pairs) {
            val online = MapTilePlanner.aroundGcj02(gcj.latitudeDeg, gcj.longitudeDeg, 0.0)
            val city = MapTilePlanner.boundsWgs84(
                listOf(wgs.longitudeDeg - 0.000001, wgs.latitudeDeg - 0.000001, wgs.longitudeDeg + 0.000001, wgs.latitudeDeg + 0.000001),
            )
            assertEquals(listOf(tile), online)
            assertEquals(online.toSet(), city.toSet())
        }
    }

    @Test
    fun sparseIntercityRouteIncludesIntermediateEightHundredMetreWindows() {
        // Only two vertices over ~300 km: visiting vertices alone would miss
        // all intermediate checkpoints.
        val tiles = MapTilePlanner.corridorGcj02(jinanToQingdao).toSet()
        val start = WGS84Point(longitudeDeg = 117.01, latitudeDeg = 36.65)
        val finish = WGS84Point(longitudeDeg = 120.38, latitudeDeg = 36.07)
        for (fraction in listOf(0.0, 0.123, 0.25, 0.501, 0.75, 0.937, 1.0)) {
            val longitude = start.longitudeDeg + (finish.longitudeDeg - start.longitudeDeg) * fraction
            val latitude = start.latitudeDeg + (finish.latitudeDeg - start.latitudeDeg) * fraction
            val north = 800.0 / 111_195
            val east = north / cos(latitude * PI / 180)
            for ((dx, dy) in listOf(0.0 to 0.0, east to 0.0, -east to 0.0, 0.0 to north, 0.0 to -north)) {
                assertTrue("fraction $fraction offset $dx,$dy", webMercatorTile(longitude + dx, latitude + dy) in tiles)
            }
        }
    }

    @Test
    fun intercityDownloadDoesNotFillTheRectangleBetweenCities() {
        val corridor = MapTilePlanner.corridorGcj02(jinanToQingdao)
        val rectangle = MapTilePlanner.boundsWgs84(listOf(117.01, 36.07, 120.38, 36.65))
        assertTrue(corridor.size < rectangle.size / 5)
        assertEquals(corridor.size, corridor.toSet().size)
        assertFalse(webMercatorTile(120.35, 36.62) in corridor)
        assertFalse(webMercatorTile(117.04, 36.10) in corridor)
    }

    @Test
    fun gcj02RoundTripStaysWithinCentimetres() {
        val wgs = WGS84Point(longitudeDeg = 117.12, latitudeDeg = 36.67)
        val back = ChinaCoordinateTransform.gcj02ToWgs84(ChinaCoordinateTransform.wgs84ToGcj02(wgs))
        assertEquals(wgs.longitudeDeg, back.longitudeDeg, 1e-7)
        assertEquals(wgs.latitudeDeg, back.latitudeDeg, 1e-7)
    }
}
