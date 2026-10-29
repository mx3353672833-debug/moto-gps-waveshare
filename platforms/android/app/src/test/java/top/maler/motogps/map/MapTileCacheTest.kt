package top.maler.motogps.map

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MapTileCacheTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("moto-map").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun tileJson(tile: MapTileId, roads: Int = 1) = buildString {
        append("""{"schema_version":1,"coordinate_system":"GCJ-02","tile":{"z":${tile.z},"x":${tile.x},"y":${tile.y}},"roads":[""")
        append((0 until roads).joinToString(",") { """{"class":"primary","points_e6":[[36670000,117120000],[36671000,117121000]]}""" })
        append("""],"buildings":[],"source":{"provider":"test","licence":"ODbL-1.0","attribution_url":"","retrieved_at":""}}""")
    }.toByteArray()

    private val a = MapTileId(15, 27044, 12791)
    private val b = MapTileId(15, 27045, 12791)
    private val c = MapTileId(15, 27046, 12791)

    @Test
    fun fullTileAcceptsMoreThanBleCapacityAndRejectsWrongTile() {
        val document = MapTileDocument.decode(tileJson(a, roads = 40), a)
        assertEquals(40, document.roads.size)
        assertThrows(MapException::class.java) { MapTileDocument.decode(tileJson(a), b) }
        assertThrows(MapException::class.java) { MapTileDocument.decode("not json".toByteArray(), a) }
    }

    @Test
    fun realShanghaiTileFixtureDecodes() {
        val bytes = javaClass.classLoader!!.getResource("shanghai-online-map-tile.json")!!.readBytes()
        val document = MapTileDocument.decode(bytes, MapTileId(15, 27440, 13389))
        assertTrue(document.roads.isNotEmpty())
    }

    @Test
    fun concurrentConsumersShareOneTileRequest() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = MapTileCache(root, { tile -> calls.incrementAndGet(); gate.await(); tileJson(tile) })
        val requests = (0 until 3).map { async { cache.fetch(a, refresh = true) } }
        kotlinx.coroutines.delay(100)
        gate.complete(Unit)
        requests.awaitAll()
        assertEquals(1, calls.get())
        assertNotNull(cache.cached(a))
    }

    @Test
    fun incompletePackPersistsAndResumeOnlyDownloadsMissingTile() = runBlocking {
        val failing = HashSet(setOf(b))
        val loaded = ArrayList<MapTileId>()
        val cache = MapTileCache(root, { tile ->
            loaded += tile
            if (tile in failing) throw MapException("offline")
            tileJson(tile)
        })
        val id = cache.createPack("济南", "测试", listOf(a, b))
        cache.fetch(a, refresh = false)
        assertThrows(MapException::class.java) { runBlocking { cache.fetch(b, refresh = false) } }
        assertFalse(cache.packs().single().isComplete)

        failing.clear()
        loaded.clear()
        val reopened = MapTileCache(root, { tile -> loaded += tile; tileJson(tile) })
        for (tile in reopened.tiles(id)) reopened.fetch(tile, refresh = false)
        assertEquals(listOf(b), loaded)
        assertTrue(reopened.packs().single().isComplete)
    }

    @Test
    fun deletingOnePackKeepsSharedTilesAndTemporaryClearKeepsPinnedTiles() = runBlocking {
        val cache = MapTileCache(root, { tile -> tileJson(tile) })
        val first = cache.createPack("一", "", listOf(a, b))
        cache.createPack("二", "", listOf(b))
        listOf(a, b).forEach { cache.fetch(it, refresh = false) }
        cache.fetch(c, refresh = true)
        cache.deletePack(first)
        assertNull(cache.cached(a))
        assertNotNull(cache.cached(b))
        assertNotNull(cache.cached(c))
        cache.clearTemporaryCache()
        assertNull(cache.cached(c))
        assertNotNull(cache.cached(b))
    }

    @Test
    fun duplicateDownloadReusesPack() = runBlocking {
        val cache = MapTileCache(root, { tile -> tileJson(tile) })
        assertEquals(cache.createPack("济南", "", listOf(a, b)), cache.createPack("济南", "", listOf(b, a)))
        assertEquals(1, cache.packs().size)
    }

    @Test
    fun corruptManifestIsQuarantinedAndDoesNotDisableOnlineFetch() = runBlocking {
        File(root, "tiles").mkdirs()
        File(root, "packs.json").writeText("{broken")
        val cache = MapTileCache(root, { tile -> tileJson(tile) })
        assertTrue(cache.packs().isEmpty())
        assertNotNull(cache.takeRecoveryNotice())
        assertNotNull(cache.fetch(a, refresh = true))
        assertTrue(root.listFiles()!!.any { it.name.startsWith("packs.corrupt-") })
    }

    @Test
    fun corruptTileBecomesAMissingTile() = runBlocking {
        val cache = MapTileCache(root, { tile -> tileJson(tile) })
        cache.createPack("一", "", listOf(a))
        cache.fetch(a, refresh = false)
        File(root, "tiles/${a.filename}").writeText("garbage")
        assertNull(cache.cached(a))
        assertFalse(cache.packs().single().isComplete)
    }
}
