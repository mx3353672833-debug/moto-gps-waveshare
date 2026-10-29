package top.maler.motogps.map

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class MapDownloadPack(
    val id: String,
    val name: String,
    val detail: String,
    val byteCount: Long,
    val tileCount: Int,
    val isComplete: Boolean,
)

/**
 * A single disk owner that serializes manifests, quota checks and shared tile
 * writes. Downloaded packs pin tiles; the automatic navigation cache only uses
 * unpinned space. Port of the MapTileCache actor in SurroundingMapStore.swift.
 */
class MapTileCache(
    private val root: File,
    private var loader: suspend (MapTileId) -> ByteArray,
    private val cacheLimit: Long = 128L * 1_024 * 1_024,
    private val cacheTileLimit: Int = 10_000,
    private val packLimit: Long = 512L * 1_024 * 1_024,
) {
    @Serializable
    private data class SavedPack(val id: String, val name: String, val detail: String, val tiles: List<MapTileId>)

    private data class CachedFile(val size: Long, val modifiedAtMs: Long)

    private val mutex = Mutex()
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var loaderGeneration = 0L
    private var initialized = false
    private var savedPacks: List<SavedPack> = emptyList()
    private val inFlight = HashMap<MapTileId, Deferred<MapTileDocument>>()
    private val files = HashMap<MapTileId, CachedFile>()
    private var pinnedTiles: Set<MapTileId> = emptySet()
    private var pinnedBytes = 0L
    private var temporaryBytes = 0L
    private var temporaryCount = 0
    private val writeEpochs = HashMap<MapTileId, Long>()
    private var nextBulkRequestAtMs = 0L
    private var recoveryNotice: String? = null

    private val tilesDirectory get() = File(root, "tiles")
    private val manifestFile get() = File(root, "packs.json")

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun initialize() {
        if (initialized) return
        tilesDirectory.mkdirs()
        if (manifestFile.exists()) {
            try {
                if (manifestFile.length() > 16L * 1_024 * 1_024) throw MapException("manifest too large")
                val packs = json.decodeFromString(ListSerializer(SavedPack.serializer()), manifestFile.readText())
                val valid = packs.size <= 256 && packs.map { it.id }.toSet().size == packs.size &&
                    packs.all { pack -> pack.tiles.isNotEmpty() && pack.tiles.all { it.isValid } } &&
                    packs.flatMap { it.tiles }.toSet().size <= 40_000
                if (!valid) throw MapException("invalid manifest")
                savedPacks = packs
            } catch (_: Exception) {
                // Keep the original manifest for recovery, while valid tiles
                // remain reusable and an unrelated download can still proceed.
                manifestFile.renameTo(File(root, "packs.corrupt-${UUID.randomUUID()}.json"))
                savedPacks = emptyList()
                recoveryNotice = "离线地图清单损坏，已保留原文件。请重新添加需要的下载区域。"
            }
        }
        tilesDirectory.listFiles()?.forEach { file ->
            val parts = file.nameWithoutExtension.split('-')
            if (file.extension != "json" || parts.size != 3) return@forEach
            val tile = MapTileId(parts[0].toIntOrNull() ?: return@forEach, parts[1].toIntOrNull() ?: return@forEach, parts[2].toIntOrNull() ?: return@forEach)
            if (tile.isValid) files[tile] = CachedFile(file.length(), file.lastModified())
        }
        refreshPinAccounting()
        initialized = true
        trimTemporaryCache(null)
    }

    private fun saveManifest(packs: List<SavedPack>) {
        val data = json.encodeToString(ListSerializer(SavedPack.serializer()), packs)
        if (packs.size > 256 || data.length > 16 * 1_024 * 1_024) throw MapException("下载区域过大，请缩小城市区域或路线范围")
        val temporary = File(root, "packs.json.tmp")
        temporary.writeText(data)
        if (!temporary.renameTo(manifestFile)) {
            manifestFile.delete()
            temporary.renameTo(manifestFile)
        }
        savedPacks = packs
        refreshPinAccounting()
    }

    private fun refreshPinAccounting() {
        pinnedTiles = savedPacks.flatMap { it.tiles }.toSet()
        pinnedBytes = 0
        temporaryBytes = 0
        temporaryCount = 0
        for ((tile, file) in files) {
            if (tile in pinnedTiles) {
                pinnedBytes += file.size
            } else {
                temporaryBytes += file.size
                temporaryCount += 1
            }
        }
    }

    private fun recordFile(tile: MapTileId, file: CachedFile?) {
        val delta = (file?.size ?: 0) - (files[tile]?.size ?: 0)
        if (tile in pinnedTiles) {
            pinnedBytes += delta
        } else {
            temporaryBytes += delta
            temporaryCount += (if (file == null) 0 else 1) - (if (files[tile] == null) 0 else 1)
        }
        if (file == null) files.remove(tile) else files[tile] = file
    }

    private fun cachedLocked(tile: MapTileId): MapTileDocument? {
        initialize()
        if (!tile.isValid) throw MapException("地图区域无效")
        val file = File(tilesDirectory, tile.filename)
        if (!file.exists()) {
            recordFile(tile, null)
            return null
        }
        return try {
            if (file.length() > MapTileDocument.MAXIMUM_BYTES) throw MapException("地图分块超过大小限制")
            MapTileDocument.decode(file.readBytes(), tile)
        } catch (_: Exception) {
            // A corrupt tile becomes a resumable missing tile, never a
            // completed pack or a source of malformed BLE geometry.
            file.delete()
            recordFile(tile, null)
            null
        }
    }

    suspend fun cached(tile: MapTileId): MapTileDocument? = locked { cachedLocked(tile) }

    suspend fun replaceLoader(newLoader: suspend (MapTileId) -> ByteArray) = locked {
        loaderGeneration += 1
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        loader = newLoader
        nextBulkRequestAtMs = 0
    }

    suspend fun fetch(tile: MapTileId, refresh: Boolean): MapTileDocument {
        val generation = mutex.withLock { loaderGeneration }
        var bulkDelayMs = 0L
        locked {
            initialize()
            if (!tile.isValid) throw MapException("地图区域无效")
        }
        if (!refresh) {
            cached(tile)?.let { return it }
            mutex.withLock { inFlight[tile] }?.let { return it.await() }
            mutex.withLock {
                val now = System.currentTimeMillis()
                bulkDelayMs = maxOf(0L, nextBulkRequestAtMs - now)
                nextBulkRequestAtMs = now + bulkDelayMs + 250
            }
            if (bulkDelayMs > 0) delay(bulkDelayMs)
            kotlin.coroutines.coroutineContext.ensureActive()
            if (mutex.withLock { loaderGeneration } != generation) throw kotlinx.coroutines.CancellationException("loader replaced")
            cached(tile)?.let { return it }
        }
        val request = mutex.withLock {
            inFlight[tile]?.let { return@withLock it }
            val epoch = writeEpochs[tile] ?: 0L
            val currentLoader = loader
            val deferred = loadScope.async {
                val data = currentLoader(tile)
                val document = MapTileDocument.decode(data, tile)
                locked {
                    if (loaderGeneration != generation || (writeEpochs[tile] ?: 0L) != epoch) {
                        throw kotlinx.coroutines.CancellationException("tile write superseded")
                    }
                    write(data, tile)
                }
                document
            }
            inFlight[tile] = deferred
            deferred.invokeOnCompletion {
                loadScope.launch {
                    mutex.withLock { if (inFlight[tile] === deferred) inFlight.remove(tile) }
                }
            }
            deferred
        }
        return request.await()
    }

    suspend fun isFresh(tile: MapTileId): Boolean = locked {
        // Refresh at most daily, including pinned tiles. Cache hits stay
        // labelled as cached data rather than proof of connectivity.
        val file = files[tile] ?: return@locked false
        System.currentTimeMillis() - file.modifiedAtMs < 24L * 60 * 60 * 1_000
    }

    suspend fun byteCount(tile: MapTileId): Long = locked { files[tile]?.size ?: 0L }

    suspend fun takeRecoveryNotice(): String? = locked {
        val notice = recoveryNotice
        recoveryNotice = null
        notice
    }

    private fun write(data: ByteArray, tile: MapTileId) {
        if (tile in pinnedTiles && pinnedBytes - (files[tile]?.size ?: 0) + data.size > packLimit) {
            throw MapException("离线地图已达到 512 MB 上限，请删除不用的区域")
        }
        val target = File(tilesDirectory, tile.filename)
        val temporary = File(tilesDirectory, "${tile.filename}.tmp")
        temporary.writeBytes(data)
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
        recordFile(tile, CachedFile(data.size.toLong(), System.currentTimeMillis()))
        trimTemporaryCache(null)
    }

    suspend fun createPack(name: String, detail: String, tiles: List<MapTileId>): String = locked {
        initialize()
        val unique = tiles.toSet().sortedBy { it.filename }
        if (unique.isEmpty() || !unique.all { it.isValid }) throw MapException("地图区域无效")
        val normalizedName = name.take(120)
        savedPacks.firstOrNull { it.name == normalizedName && it.tiles == unique }?.let { return@locked it.id }
        val allPinned = pinnedTiles + unique
        if (allPinned.size > 40_000) throw MapException("下载区域过大，请缩小城市区域或路线范围")
        val total = allPinned.sumOf { files[it]?.size ?: 0L }
        if (total > packLimit) throw MapException("离线地图已达到 512 MB 上限，请删除不用的区域")
        val pack = SavedPack(UUID.randomUUID().toString(), normalizedName, detail.take(400), unique)
        saveManifest(savedPacks + pack)
        pack.id
    }

    suspend fun tiles(id: String): List<MapTileId> = locked {
        initialize()
        savedPacks.firstOrNull { it.id == id }?.tiles ?: throw MapException("未找到这份离线地图")
    }

    suspend fun packs(): List<MapDownloadPack> = locked {
        initialize()
        savedPacks.map { pack ->
            var count = 0
            var bytes = 0L
            for (tile in pack.tiles) {
                val size = files[tile]?.size ?: 0L
                if (size > 0) {
                    count += 1
                    bytes += size
                }
            }
            val complete = count == pack.tiles.size
            MapDownloadPack(
                id = pack.id,
                name = pack.name,
                detail = if (complete) pack.detail else "${pack.detail} · 已下载 ${count * 100 / pack.tiles.size}%",
                byteCount = bytes,
                tileCount = pack.tiles.size,
                isComplete = complete,
            )
        }
    }

    suspend fun deletePack(id: String) = locked {
        initialize()
        val removed = savedPacks.firstOrNull { it.id == id }
        val remaining = savedPacks.filter { it.id != id }
        saveManifest(remaining)
        val retained = remaining.flatMap { it.tiles }.toSet()
        for (tile in removed?.tiles.orEmpty()) {
            if (tile in retained) continue
            writeEpochs[tile] = (writeEpochs[tile] ?: 0L) + 1
            File(tilesDirectory, tile.filename).delete()
            recordFile(tile, null)
        }
    }

    suspend fun clearTemporaryCache() = locked {
        initialize()
        for (tile in inFlight.keys) if (tile !in pinnedTiles) writeEpochs[tile] = (writeEpochs[tile] ?: 0L) + 1
        trimTemporaryCache(0)
    }

    private fun trimTemporaryCache(limit: Long?) {
        // Byte limits count JSON payload bytes; a separate count bound stops
        // tiny empty tiles from consuming excessive filesystem allocation.
        val byteLimit = limit ?: cacheLimit
        val countLimit = if (limit == 0L) 0 else cacheTileLimit
        if (temporaryBytes <= byteLimit && temporaryCount <= countLimit) return
        val entries = files.filterKeys { it !in pinnedTiles }.entries.sortedBy { it.value.modifiedAtMs }
        for ((tile, _) in entries) {
            if (temporaryBytes <= byteLimit && temporaryCount <= countLimit) break
            File(tilesDirectory, tile.filename).delete()
            recordFile(tile, null)
        }
    }
}
