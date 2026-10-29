package top.maler.motogps.map

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import top.maler.motogps.gateway.GatewayClient

/**
 * Chooses the 500 m window around the rider for the round screen: online tiles
 * first, then cached tiles, downloaded packs and the bundled Jinan map. Also
 * owns city / route-corridor downloads. Port of SurroundingMapStore.swift.
 * Public methods are called on the main thread.
 */
class SurroundingMapStore(
    private val context: Context,
    private val scope: CoroutineScope,
    gatewayBaseUrl: String?,
) {
    var statusText by mutableStateOf("在线地图优先，离线地图备用")
        private set
    var isDownloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableDoubleStateOf(0.0)
        private set
    var downloadMessage by mutableStateOf<String?>(null)
        private set
    var packs by mutableStateOf<List<MapDownloadPack>>(emptyList())
        private set

    var onScene: ((OfflineMapSceneWindow) -> Unit)? = null

    private val cache = MapTileCache(File(context.filesDir, "SurroundingMaps"), loaderFor(gatewayBaseUrl))
    private var fallback: OfflineMapSceneQuerying? = null
    private var sceneJob: Job? = null
    private var downloadJob: Job? = null
    private var generation = 0L
    private var downloadGeneration = 0L
    private var revision = 0L
    private var lastOrigin: OfflineMapPointE6? = null
    private var retryAfterMs: Long? = null
    private var activeDownloadId: String? = null

    init {
        scope.launch {
            fallback = withContext(Dispatchers.IO) { loadBundledFallback() }
            reloadPacks()
        }
    }

    /** Called with the NavCore route-view origin (GCJ-02) whenever it is available. */
    fun update(latitudeDeg: Double, longitudeDeg: Double) {
        if (!latitudeDeg.isFinite() || !longitudeDeg.isFinite() || latitudeDeg !in -85.0..85.0 || longitudeDeg !in -180.0..180.0) {
            return
        }
        val origin = OfflineMapPointE6((latitudeDeg * 1_000_000).roundToInt(), (longitudeDeg * 1_000_000).roundToInt())
        val previous = lastOrigin
        if (previous != null && InMemoryOfflineMapSceneIndex.distanceBetween(previous, origin) < 100 &&
            (retryAfterMs?.let { System.currentTimeMillis() < it } ?: true)
        ) {
            return
        }
        val tiles = try {
            MapTilePlanner.aroundGcj02(latitudeDeg, longitudeDeg, 500.0).toSet().toList().also {
                if (it.isEmpty() || it.size > 64 || !it.all(MapTileId::isValid)) throw MapException("地图区域无效")
            }
        } catch (_: Exception) {
            statusText = "此位置暂时没有周边地图"
            return
        }
        lastOrigin = origin
        retryAfterMs = null
        generation += 1
        val current = generation
        sceneJob?.cancel()
        sceneJob = scope.launch {
            val documents = HashMap<MapTileId, MapTileDocument>()
            val needsRefresh = ArrayList<MapTileId>()
            for (tile in tiles) {
                runCatching { cache.cached(tile) }.getOrNull()?.let { documents[tile] = it }
                if (documents[tile] == null || !cache.isFresh(tile)) needsRefresh += tile
            }
            if (generation != current) return@launch
            emit(documents.values.toList(), origin, includeFallback = documents.size != tiles.size)
            statusText = if (documents.size == tiles.size) "正在更新 · 已缓存周边地图" else "正在加载在线周边地图"
            var failed = false
            // A small viewport touches 4–9 tiles. Bounded concurrency keeps
            // navigation and user-initiated downloads responsive.
            for (batch in needsRefresh.chunked(4)) {
                ensureActive()
                if (generation != current) return@launch
                val results = coroutineScope {
                    batch.map { tile ->
                        async {
                            tile to try {
                                cache.fetch(tile, refresh = true)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                }
                for ((tile, document) in results) {
                    if (document != null) documents[tile] = document else failed = true
                }
            }
            if (generation != current) return@launch
            val complete = documents.size == tiles.size
            val hasOfflineGeometry = emit(documents.values.toList(), origin, includeFallback = !complete)
            if (!failed && complete) {
                statusText = if (needsRefresh.isEmpty()) "周边地图已就绪 · 使用缓存" else "在线周边地图"
                retryAfterMs = System.currentTimeMillis() + 24L * 60 * 60 * 1_000
            } else {
                retryAfterMs = System.currentTimeMillis() + 30_000
                statusText = when {
                    complete -> "离线地图 · 当前区域已缓存"
                    hasOfflineGeometry -> "离线地图 · 当前区域仅部分覆盖"
                    else -> "在线地图不可用 · 此处无离线地图"
                }
            }
            reloadPacks()
        }
    }

    suspend fun changeGateway(baseUrl: String) {
        reset()
        cancelDownload()
        cache.replaceLoader(loaderFor(baseUrl))
    }

    fun reset() {
        generation += 1
        sceneJob?.cancel()
        sceneJob = null
        lastOrigin = null
        retryAfterMs = null
        statusText = "在线地图优先，离线地图备用"
    }

    fun download(name: String, detail: String, tiles: List<MapTileId>) =
        startDownload { cache.createPack(name, detail, tiles) }

    fun resume(pack: MapDownloadPack) = startDownload { pack.id }

    private fun startDownload(resolveId: suspend () -> String) {
        cancelDownload()
        downloadGeneration += 1
        val current = downloadGeneration
        isDownloading = true
        downloadProgress = 0.0
        downloadMessage = "正在准备下载…"
        downloadJob = scope.launch {
            try {
                val id = resolveId()
                if (downloadGeneration != current) return@launch
                activeDownloadId = id
                val tiles = cache.tiles(id)
                var verifiedBytes = 0L
                reloadPacks()
                tiles.forEachIndexed { index, tile ->
                    ensureActive()
                    cache.fetch(tile, refresh = false)
                    ensureActive()
                    if (downloadGeneration != current) return@launch
                    downloadProgress = (index + 1).toDouble() / tiles.size
                    verifiedBytes += cache.byteCount(tile)
                    downloadMessage = "已下载 ${Formatter.formatShortFileSize(context, verifiedBytes)} · ${(downloadProgress * 100).toInt()}%"
                }
                downloadMessage = "下载完成，可离线使用"
            } catch (error: CancellationException) {
                if (downloadGeneration == current) downloadMessage = "下载已暂停，可继续下载"
            } catch (error: Exception) {
                if (downloadGeneration == current) downloadMessage = "下载已暂停：${error.message}；已完成部分已保留"
            }
            if (downloadGeneration != current) return@launch
            isDownloading = false
            activeDownloadId = null
            reloadPacks()
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadGeneration += 1
        isDownloading = false
        activeDownloadId = null
        if (downloadMessage != null) downloadMessage = "下载已暂停，可继续下载"
        scope.launch { reloadPacks() }
    }

    fun delete(pack: MapDownloadPack) {
        if (activeDownloadId == pack.id) cancelDownload()
        scope.launch {
            try {
                cache.deletePack(pack.id)
                reloadPacks()
            } catch (error: Exception) {
                downloadMessage = error.message
            }
        }
    }

    fun clearTemporaryCache() {
        scope.launch {
            try {
                cache.clearTemporaryCache()
                downloadMessage = "临时缓存已清除，已下载地图保留"
            } catch (error: Exception) {
                downloadMessage = error.message
            }
        }
    }

    private suspend fun reloadPacks() {
        try {
            packs = cache.packs()
            cache.takeRecoveryNotice()?.let { downloadMessage = it }
        } catch (error: Exception) {
            downloadMessage = "无法读取离线地图：${error.message}"
        }
    }

    private data class ShapeKey(val id: Long?, val kind: String, val points: List<OfflineMapPointE6>)

    private fun emit(documents: List<MapTileDocument>, origin: OfflineMapPointE6, includeFallback: Boolean): Boolean {
        val roads = ArrayList<OfflineMapRoad>()
        val buildings = ArrayList<OfflineMapBuilding>()
        val roadKeys = HashSet<ShapeKey>()
        val buildingKeys = HashSet<ShapeKey>()
        val backup = if (includeFallback) fallback?.query(origin, 500, 0) else null
        for (road in documents.flatMap { it.roads } + backup?.roads.orEmpty()) {
            if (roadKeys.add(ShapeKey(road.osmWayId, road.roadClass, road.points))) roads += road
        }
        for (building in documents.flatMap { it.buildings } + backup?.buildings.orEmpty()) {
            if (buildingKeys.add(ShapeKey(building.osmWayId, building.buildingClass, building.points))) buildings += building
        }
        revision = (revision + 1) and 0xFFFF_FFFFL
        if (revision == 0L) revision = 1
        val window = InMemoryOfflineMapSceneIndex(roads, buildings).query(origin, 500, revision)
        onScene?.invoke(window)
        return window.roads.isNotEmpty() || window.buildings.isNotEmpty()
    }

    /** The bundled city map is copied out of the APK once, then opened read-only. */
    private fun loadBundledFallback(): OfflineMapSceneQuerying? {
        val directory = File(context.filesDir, "OfflineMaps").apply { mkdirs() }
        val database = File(directory, BUNDLED_DATABASE)
        try {
            val assetLength = context.assets.openFd(BUNDLED_DATABASE).use { it.length }
            if (!database.exists() || database.length() != assetLength) copyAsset(BUNDLED_DATABASE, database)
        } catch (_: Exception) {
            // Compressed assets have no file descriptor; compare by copying once.
            if (!database.exists()) runCatching { copyAsset(BUNDLED_DATABASE, database) }
        }
        if (database.exists()) {
            try {
                return SQLiteOfflineMapSceneIndex(database)
            } catch (error: Exception) {
                Log.w(TAG, "invalid bundled map: ${error.message}")
            }
        }
        return try {
            val json = context.assets.open(BUNDLED_SAMPLE).bufferedReader().use { it.readText() }
            InMemoryOfflineMapSceneIndex(sceneJson.decodeFromString(OfflineMapSceneDocument.serializer(), json))
        } catch (_: Exception) {
            null
        }
    }

    private fun copyAsset(name: String, target: File) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        context.assets.open(name).use { input -> temporary.outputStream().use { input.copyTo(it) } }
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    companion object {
        private const val TAG = "MotoMap"
        private val sceneJson = Json { ignoreUnknownKeys = true }
        const val BUNDLED_DATABASE = "jinan-v1.sqlite"
        const val BUNDLED_SAMPLE = "jinan_map_scene_sample.json"

        private fun loaderFor(baseUrl: String?): suspend (MapTileId) -> ByteArray = { tile ->
            val url = baseUrl ?: throw MapException("请先在网关设置中填写服务地址")
            GatewayClient(url).mapTile(tile.z, tile.x, tile.y, MapTileDocument.MAXIMUM_BYTES)
        }
    }
}
