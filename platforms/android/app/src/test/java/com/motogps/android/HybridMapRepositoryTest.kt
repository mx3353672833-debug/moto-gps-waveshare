package com.motogps.android

import com.motogps.android.jni.GeoPointE6
import com.motogps.android.map.HybridMapRepository
import com.motogps.android.map.RoadSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HybridMapRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mapsDir: File
    private lateinit var cacheDir: File
    private lateinit var repository: HybridMapRepository

    @Before
    fun setUp() {
        mapsDir = tempFolder.newFolder("maps")
        cacheDir = tempFolder.newFolder("cache")
        repository = HybridMapRepository(
            mapsDir = mapsDir,
            cacheDir = cacheDir,
            maxCacheSizeBytes = 1000L // 1000 bytes max for easy LRU testing
        )
    }

    @Test
    fun testMotoMapLineByLineParsingAndBBoxFiltering() {
        val motomapFile = File(mapsDir, "lombardia.motomap")
        val segs = listOf(
            RoadSegment(
                roadClass = 1,
                points = listOf(
                    GeoPointE6(45464000, 9190000), // Milan area
                    GeoPointE6(45465000, 9191000)
                )
            ),
            RoadSegment(
                roadClass = 2,
                points = listOf(
                    GeoPointE6(41902800, 12496400), // Rome area (outside Milan)
                    GeoPointE6(41903000, 12497000)
                )
            )
        )

        repository.appendSegmentsToMotoMap(motomapFile, segs)

        // Query Milan location (45.464, 9.190) -> should only return the Milan segment
        val milanSegments = runBlocking { repository.getNearbyRoadSegments(45.464, 9.190, 600.0) }
        assertEquals(1, milanSegments.size)
        assertEquals(1, milanSegments[0].roadClass)
        assertEquals(45464000, milanSegments[0].points[0].latitudeE6)

        // Test file listing and deleting
        val savedMaps = repository.listSavedMaps()
        assertEquals(1, savedMaps.size)
        assertEquals("lombardia.motomap", savedMaps[0].fileName)

        val deleted = repository.deleteSavedMap("lombardia.motomap")
        assertTrue(deleted)
        assertEquals(0, repository.listSavedMaps().size)
    }

    @Test
    fun testLocalRegionalFilePriorityOverCacheAndNetwork() {
        // Create local regional file in mapsDir
        val regionalFile = File(mapsDir, "rome_region.json")
        val regionalGeoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": { "highway": "primary" },
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [12.4964, 41.9028],
                      [12.4970, 41.9030]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        regionalFile.writeText(regionalGeoJson)

        // Create cached file with different segment
        val cacheFile = File(cacheDir, "tile_419_124.json")
        val cachedGeoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": { "highway": "secondary" },
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [12.4980, 41.9040],
                      [12.4990, 41.9050]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        cacheFile.writeText(cachedGeoJson)

        val segments = runBlocking { repository.getNearbyRoadSegments(41.9028, 12.4964, 600.0) }

        // Regional file must take priority (0 network calls, ignores cache when regional covers)
        assertEquals(1, segments.size)
        assertEquals(1, segments[0].roadClass) // primary
        assertEquals(2, segments[0].points.size)
        assertEquals(41902800, segments[0].points[0].latitudeE6)
        assertEquals(12496400, segments[0].points[0].longitudeE6)
    }

    @Test
    fun testDynamicCacheFallbackWhenNoRegionalFile() {
        // Ensure no regional file in mapsDir
        assertTrue(mapsDir.listFiles()?.isEmpty() == true)

        // Put cached tile file in cacheDir
        val cacheFile = File(cacheDir, "tile_419_124.json")
        val cachedGeoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": { "highway": "motorway" },
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [12.4964, 41.9028],
                      [12.4975, 41.9035]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        cacheFile.writeText(cachedGeoJson)

        val segments = runBlocking { repository.getNearbyRoadSegments(41.9028, 12.4964, 600.0) }

        assertEquals(1, segments.size)
        assertEquals(0, segments[0].roadClass) // motorway
        assertEquals(2, segments[0].points.size)
    }

    @Test
    fun testLimitsEnforcedMax24SegmentsAnd192Nodes() {
        // Create GeoJSON with 30 segments each having 10 points (300 points total)
        val features = mutableListOf<String>()
        for (i in 0 until 30) {
            val coords = (0 until 10).joinToString(",") { idx ->
                val lat = 41.9028 + (i * 0.00001) + (idx * 0.000001)
                val lon = 12.4964 + (i * 0.00001) + (idx * 0.000001)
                "[$lon, $lat]"
            }
            features.add("""
                {
                  "type": "Feature",
                  "properties": { "highway": "residential" },
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [ $coords ]
                  }
                }
            """.trimIndent())
        }

        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [ ${features.joinToString(",")} ]
            }
        """.trimIndent()

        val regionalFile = File(mapsDir, "large_map.json")
        regionalFile.writeText(geoJson)

        val segments = runBlocking { repository.getNearbyRoadSegments(41.9028, 12.4964, 1000.0) }

        // Must enforce limits: max 24 segments and max 192 total nodes
        assertTrue("Segments count must be <= 24", segments.size <= 24)
        val totalNodes = segments.sumOf { it.points.size }
        assertTrue("Total nodes count must be <= 192", totalNodes <= 192)
    }

    @Test
    fun testLruCacheEvictionPolicyOnSizeThreshold() {
        val smallRepo = HybridMapRepository(
            mapsDir = mapsDir,
            cacheDir = cacheDir,
            maxCacheSizeBytes = 500L // 500 bytes threshold
        )

        val oldFile = File(cacheDir, "tile_old.json")
        oldFile.writeText("X".repeat(300))
        oldFile.setLastModified(System.currentTimeMillis() - 10000)

        val newFile = File(cacheDir, "tile_new.json")
        newFile.writeText("Y".repeat(300))
        newFile.setLastModified(System.currentTimeMillis())

        // Exceed limit (600 bytes > 500 bytes) and trigger cache limit enforcement
        smallRepo.enforceLruCacheLimit()

        // Old file should be evicted first due to LRU policy
        assertFalse("Old file should be deleted by LRU policy", oldFile.exists())
    }
}
