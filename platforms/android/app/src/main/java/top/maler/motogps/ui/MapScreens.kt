package top.maler.motogps.ui

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.maler.motogps.app.AppModel
import top.maler.motogps.gateway.MapCity
import top.maler.motogps.map.MapTileId
import top.maler.motogps.map.MapTilePlanner
import top.maler.motogps.map.SurroundingMapStore

private sealed interface MapPage {
    data object Overview : MapPage
    data object CitySearch : MapPage
    data class CityDownload(val city: MapCity) : MapPage
}

@Composable
fun MapDownloadsScreen(model: AppModel, onBack: () -> Unit) {
    var page by remember { mutableStateOf<MapPage>(MapPage.Overview) }
    androidx.activity.compose.BackHandler(enabled = page != MapPage.Overview) {
        page = if (page is MapPage.CityDownload) MapPage.CitySearch else MapPage.Overview
    }
    when (val current = page) {
        MapPage.Overview -> MapOverview(model, onBack, openCitySearch = { page = MapPage.CitySearch })
        MapPage.CitySearch -> CitySearchPage(model, onBack = { page = MapPage.Overview }) { page = MapPage.CityDownload(it) }
        is MapPage.CityDownload -> CityDownloadPage(model.surroundingMap, current.city) { page = MapPage.CitySearch }
    }
}

@Composable
private fun MapOverview(model: AppModel, onBack: () -> Unit, openCitySearch: () -> Unit) {
    val store = model.surroundingMap
    val route = model.mapDownloadRoute
    val destinationName = if (model.isDemoActive) "演示路线" else model.selectedPlace?.name
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var planningRoute by remember { mutableStateOf(false) }
    var planningError by remember { mutableStateOf<String?>(null) }

    DetailScaffold("地图", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("map-downloads-sheet"), contentPadding = padding) {
            section("auto", footer = "骑行时自动加载圆屏周边的道路和建筑。网络不可用时，使用已下载的地图。") {
                ActionRow("自动加载周边地图", Icons.Outlined.Language, subtitle = store.statusText, showChevron = false)
            }
            section("download", footer = "跨市骑行可先规划路线，只保存沿途约一公里范围的底图。离线底图不包含实时路况，规划新路线仍需要网络。") {
                ActionRow(
                    "下载城市地图",
                    Icons.Outlined.LocationCity,
                    enabled = model.isGatewayConfigured,
                    subtitle = if (model.isGatewayConfigured) null else "需要先设置导航网关",
                    onClick = openCitySearch,
                )
                if (route.size >= 2) {
                    SectionDivider()
                    ActionRow(
                        if (planningRoute) "正在计算下载范围…" else "下载这条路线周边",
                        Icons.AutoMirrored.Outlined.AltRoute,
                        subtitle = destinationName ?: "当前选择的路线",
                        enabled = !store.isDownloading && !planningRoute,
                        showChevron = false,
                        onClick = {
                            planningRoute = true
                            planningError = null
                            scope.launch {
                                try {
                                    val tiles = withContext(Dispatchers.Default) { MapTilePlanner.corridorGcj02(route) }
                                    store.download("前往${destinationName ?: "目的地"}", "所选路线及沿途约一公里", tiles)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    planningError = error.message
                                }
                                planningRoute = false
                            }
                        },
                    )
                }
            }
            downloadStatus(store)
            planningError?.let { section("planning-error") { BodyText(it) } }
            section("saved", header = "已保存") {
                if (store.packs.isEmpty()) {
                    BodyText("还没有下载城市或路线地图")
                    SectionDivider()
                }
                store.packs.forEach { pack ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(pack.name, style = MaterialTheme.typography.titleSmall)
                                Icon(
                                    if (pack.isComplete) Icons.Default.CheckCircle else Icons.Default.PauseCircle,
                                    contentDescription = if (pack.isComplete) "已下载" else "未完成",
                                    tint = if (pack.isComplete) MotoColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(pack.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${Formatter.formatShortFileSize(context, pack.byteCount)} · ${if (pack.isComplete) "已下载" else "尚未下载完成"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!pack.isComplete && !store.isDownloading) {
                                TextButton(onClick = { store.resume(pack) }) { Text("继续下载") }
                            }
                        }
                        IconButton(onClick = { store.delete(pack) }, enabled = !store.isDownloading) { DeleteIcon() }
                    }
                    SectionDivider()
                }
                ActionRow("济南基础地图 · 随 App 提供", Icons.Outlined.Storage, iconTint = MaterialTheme.colorScheme.onSurfaceVariant, showChevron = false)
            }
            section("cache", footer = "保留手动下载的地图。自动缓存最多 128 MB，手动下载最多 512 MB；城市与路线共用的数据只保存一份。下载中断后，可在 App 内继续。") {
                ActionRow("清理自动缓存", Icons.Outlined.CleaningServices, enabled = !store.isDownloading, showChevron = false, onClick = store::clearTemporaryCache)
            }
            section("attribution", footer = "建筑和道路的完整程度取决于当地地图数据。") {
                ActionRow("© OpenStreetMap contributors", onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") })
                SectionDivider()
                ActionRow("Protomaps 地图数据", onClick = { uriHandler.openUri("https://protomaps.com") })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.downloadStatus(store: SurroundingMapStore) {
    if (store.isDownloading) {
        section("download-progress") {
            LinearProgressIndicator(
                progress = { store.downloadProgress.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            BodyText(store.downloadMessage ?: "正在下载…")
            SectionDivider()
            ActionRow("暂停下载", showChevron = false, onClick = store::cancelDownload)
        }
    } else {
        store.downloadMessage?.let { message -> section("download-message") { BodyText(message) } }
    }
}

@Composable
private fun CitySearchPage(model: AppModel, onBack: () -> Unit, openCity: (MapCity) -> Unit) {
    var query by remember { mutableStateOf("") }
    var cities by remember { mutableStateOf<List<MapCity>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(query, attempt) {
        cities = emptyList()
        error = null
        val keywords = query.trim()
        val client = model.gatewayClientOrNull()
        if (keywords.length !in 2..80 || client == null) {
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        try {
            cities = client.searchCities(keywords)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = "暂时无法搜索城市，请检查网络后重试。"
        }
        searching = false
    }

    DetailScaffold("下载城市地图", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = padding) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索城市或区县") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                searching -> item(key = "searching") {
                    Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在搜索城市…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                error != null -> section("error") {
                    BodyText(error.orEmpty())
                    SectionDivider()
                    ActionRow("重试", showChevron = false, onClick = { attempt += 1 })
                }
                cities.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        Icons.Outlined.LocationCity,
                        if (query.trim().length >= 2) "未找到城市" else "添加常用城市",
                        "输入城市或区县名称，例如“济南”“上海”“历下区”。",
                    )
                }
                else -> section("cities") {
                    cities.forEachIndexed { index, city ->
                        if (index > 0) SectionDivider()
                        ActionRow(city.name, subtitle = city.detail, onClick = { openCity(city) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CityDownloadPage(store: SurroundingMapStore, city: MapCity, onBack: () -> Unit) {
    var tiles by remember { mutableStateOf<List<MapTileId>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(city) {
        try {
            tiles = withContext(Dispatchers.Default) { MapTilePlanner.boundsWgs84(city.boundsWgs84) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message
        }
    }
    val boxColor = MaterialTheme.colorScheme.primary
    DetailScaffold(city.name, onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            section("bounds", footer = "框内的道路和建筑将保存在手机。范围覆盖所选城市或区县，边缘可能包括部分相邻地区。") {
                Canvas(Modifier.fillMaxWidth().height(180.dp).padding(24.dp)) {
                    val b = city.boundsWgs84
                    val aspect = ((b[2] - b[0]) * kotlin.math.cos(Math.toRadians((b[1] + b[3]) / 2)) / (b[3] - b[1])).toFloat()
                    val height = minOf(size.height, size.width / aspect.coerceAtLeast(0.01f))
                    val width = height * aspect
                    val topLeft = Offset((size.width - width) / 2, (size.height - height) / 2)
                    drawRect(boxColor.copy(alpha = 0.1f), topLeft, Size(width, height))
                    drawRect(boxColor, topLeft, Size(width, height), style = Stroke(2.dp.toPx()))
                }
                SectionDivider()
                StatusRow("下载区域", Icons.Outlined.LocationCity, city.detail)
                if (tiles.isNotEmpty()) {
                    SectionDivider()
                    StatusRow("地图分块", Icons.Outlined.Storage, "${tiles.size} 块")
                }
            }
            section("start", footer = "建议连接 Wi-Fi 下载，实际大小会随当地道路和建筑数量变化。范围过大时，可搜索具体区县。") {
                error?.let {
                    BodyText(it)
                    SectionDivider()
                }
                ActionRow(
                    "下载地图",
                    enabled = tiles.isNotEmpty() && !store.isDownloading,
                    showChevron = false,
                    onClick = { store.download(city.name, city.detail, tiles) },
                )
            }
            downloadStatus(store)
        }
    }
}
