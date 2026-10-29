package top.maler.motogps.map

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.maler.motogps.nav.JinanDemoFixture

class OfflineMapSceneTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    private val sample = Json { ignoreUnknownKeys = true }
        .decodeFromString(OfflineMapSceneDocument.serializer(), resource("jinan_map_scene_sample.json"))

    @Test
    fun bundledOsmSceneDecodesAndCropsRealFeaturesWithinBleCapacity() {
        val window = InMemoryOfflineMapSceneIndex(sample).query(sample.viewOrigin, 500, revision = 7)
        assertEquals(7L, window.revision)
        assertEquals(500, window.radiusM)
        assertTrue(window.roads.isNotEmpty())
        assertTrue(window.roads.size <= 24 && window.roads.sumOf { it.points.size } <= 192)
        assertTrue(window.buildings.size <= 16 && window.buildings.sumOf { it.points.size } <= 128)
        assertTrue(window.roads.all { it.points.size >= 2 })
        assertTrue(window.buildings.all { it.points.size >= 3 && it.points.first() != it.points.last() })
    }

    @Test
    fun sceneOutsideFixtureCoverageIsHonestEmptyWindow() {
        val shanghai = OfflineMapPointE6(31_228_457, 121_478_223)
        val window = InMemoryOfflineMapSceneIndex(sample).query(shanghai, 500, revision = 1)
        assertTrue(window.roads.isEmpty() && window.buildings.isEmpty())
    }

    @Test
    fun capacitySelectionUsesVisualPriorityInsteadOfInputOrder() {
        val origin = OfflineMapPointE6(36_670_000, 117_120_000)
        fun road(roadClass: String, offsetE6: Int) = OfflineMapRoad(
            roadClass = roadClass,
            points = (0 until 10).map { OfflineMapPointE6(origin.latitudeE6 + offsetE6, origin.longitudeE6 - 1_000 + it * 200) },
        )
        val residential = (0 until 30).map { road("residential", it * 20) }
        val primary = road("primary", 2_000)
        val window = InMemoryOfflineMapSceneIndex(residential + primary, emptyList()).query(origin, 500, 1)
        assertEquals("primary", window.roads.first().roadClass)
        assertTrue(window.roads.sumOf { it.points.size } <= 192)
    }

    @Test
    fun sceneConvertsToBleInputPreservingPoints() {
        val window = InMemoryOfflineMapSceneIndex(sample).query(sample.viewOrigin, 500, revision = 3)
        val input = window.toBleInput()
        assertEquals(3L, input.sceneRevision)
        assertEquals(window.roads.size, input.roads.size)
        assertEquals(window.roads.first().points.first().latitudeE6, input.roads.first().points.first().latitudeE6)
    }

    @Test
    fun sqliteBlobDecodesLittleEndianPairs() {
        val bytes = byteArrayOf(0x10, 0x27, 0, 0, 0xF0.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(listOf(OfflineMapPointE6(10_000, -10_000)), SQLiteOfflineMapSceneIndex.decodePoints(bytes))
        assertEquals(null, SQLiteOfflineMapSceneIndex.decodePoints(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun demoFixtureProducesTheCheckedInFallbackRoute() {
        val fixture = JinanDemoFixture.parse(resource(JinanDemoFixture.ASSET_NAME))
        val route = fixture.fallbackRoute(rerouted = false, generatedAtMs = 1)
        assertEquals("demo-jinan-big-data-d-to-inspur-v2", route.routeId)
        assertEquals(35, route.polyline.size)
        assertEquals(5, route.maneuvers.size)
        assertTrue(route.totalDistanceM > 500)
        assertEquals(0.0, route.maneuvers.first().routeOffsetM, 0.0)
        assertTrue(route.maneuvers.zipWithNext().all { (a, b) -> a.routeOffsetM <= b.routeOffsetM })
        assertEquals("demo-jinan-big-data-d-to-inspur-v2-rerouted", fixture.fallbackRoute(true, 1).routeId)
    }
}
