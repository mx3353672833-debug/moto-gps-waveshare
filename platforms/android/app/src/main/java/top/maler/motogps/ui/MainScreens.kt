package top.maler.motogps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.maler.motogps.app.AppModel
import top.maler.motogps.ble.BleDeviceSnapshot
import top.maler.motogps.location.SearchLocationStatus
import top.maler.motogps.model.PlaceSearchResult
import top.maler.motogps.nav.JinanDemoFixture
import top.maler.motogps.nav.RoutePreviewCandidate
import top.maler.motogps.nav.TrafficSummary
import top.maler.motogps.nav.formatDistance

class ScreenActions(
    val openDevice: () -> Unit,
    val openMaps: () -> Unit,
    val openGateway: () -> Unit,
    val openDataUse: () -> Unit,
    val openAppSettings: () -> Unit,
    val requestLocation: () -> Unit,
)

// ------------------------------------------------------------- home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, actions: ScreenActions, searchFocus: FocusRequester) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("出发", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = actions.openGateway, modifier = Modifier.testTag("gateway-settings-toolbar")) {
                        Icon(Icons.Default.Settings, contentDescription = "网关设置")
                    }
                },
                actions = { DeviceToolbarButton(model, actions.openDevice) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            if (!model.isGatewayConfigured) {
                section("gateway-setup", footer = "填写你部署的网关地址后，就可以搜索地点和规划路线。") {
                    ActionRow("设置导航网关", Icons.Outlined.Language, onClick = actions.openGateway)
                }
            }
            item(key = "search") {
                SearchField(
                    query = model.destinationQuery,
                    onQueryChange = model::updateDestinationQuery,
                    onSearch = {
                        model.submitDestinationSearch()
                        focusManager.clearFocus()
                    },
                    onClear = {
                        model.clearDestination()
                        searchFocus.requestFocus()
                    },
                    focusRequester = searchFocus,
                )
            }
            if (model.destinationQuery.isNotEmpty()) {
                section(
                    "results",
                    header = "搜索结果",
                    footer = model.searchScopeText,
                ) {
                    when {
                        model.isSearchingPlaces -> Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在搜索…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        model.placeResults.isNotEmpty() -> model.placeResults.forEachIndexed { index, place ->
                            if (index > 0) SectionDivider()
                            PlaceRow(place, recent = false, modifier = Modifier.testTag("place-result-$index")) {
                                focusManager.clearFocus()
                                model.selectPlace(place)
                            }
                        }

                        else -> EmptyState(
                            Icons.Default.Search,
                            if (model.placeSearchFailure == null) "输入地点名称" else "暂无结果",
                            model.placeSearchFailure ?: "至少输入两个字，例如“奥体中心”。",
                        ) {
                            if (model.placeSearchFailure != null) {
                                TextButton(onClick = model::submitDestinationSearch) { Text("重新搜索") }
                            }
                        }
                    }
                }
                if (model.searchLocationStatus == SearchLocationStatus.PERMISSION_DENIED) {
                    item(key = "location-permission") {
                        TextButton(onClick = actions.requestLocation, modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text("开启定位，按当前位置排序")
                        }
                    }
                }
            } else {
                if (model.recentPlaces.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(Icons.Outlined.Map, "选择目的地", "搜索地点或地址，选好路线就出发。")
                    }
                } else {
                    section(
                        "recent",
                        header = "最近搜索",
                        headerAction = { TextButton(onClick = model::clearRecentPlaces) { Text("清空") } },
                    ) {
                        model.recentPlaces.forEachIndexed { index, place ->
                            if (index > 0) SectionDivider()
                            PlaceRow(place, recent = true, modifier = Modifier.testTag("recent-place-$index")) {
                                focusManager.clearFocus()
                                model.selectPlace(place)
                            }
                        }
                    }
                }
                section("tools", footer = "连接圆屏后，导航指引会自动同步。") {
                    DeviceSummaryRow(model, actions.openDevice)
                    SectionDivider()
                    ActionRow(
                        "地图与离线下载",
                        Icons.Outlined.Map,
                        subtitle = "自动加载周边，也能提前保存城市和沿途地图",
                        onClick = actions.openMaps,
                    )
                    SectionDivider()
                    ActionRow(
                        "演示导航",
                        Icons.Outlined.PlayCircle,
                        subtitle = "先体验一次导航流程",
                        showChevron = false,
                        onClick = {
                            focusManager.clearFocus()
                            model.startDemoNavigation()
                        },
                    )
                }
                section("settings") {
                    ActionRow("网关设置", Icons.Outlined.Language, onClick = actions.openGateway)
                    SectionDivider()
                    ActionRow("隐私与数据", Icons.Outlined.PrivacyTip, onClick = actions.openDataUse)
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("搜索地点或地址") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "清空搜索") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester)
            .testTag("destination-search-field"),
    )
}

@Composable
private fun PlaceRow(place: PlaceSearchResult, recent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionRow(
        title = place.name,
        icon = if (recent) Icons.Outlined.History else Icons.Default.Place,
        iconTint = if (recent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        subtitle = placeSubtitle(place),
        onClick = onClick,
    )
}

fun placeSubtitle(place: PlaceSearchResult): String {
    val area = place.displayArea.ifEmpty { listOf(place.city, place.district).filter { it.isNotEmpty() }.joinToString(" · ") }
    val parts = listOf(area, place.address).filter { it.isNotEmpty() }.toMutableList()
    place.distanceM?.takeIf { it >= 0 }?.let { parts += "距你 ${formatDistance(it)}" }
    return if (parts.isEmpty()) "查看路线" else parts.joinToString(" · ")
}

// ------------------------------------------------------------- route preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePreviewScreen(model: AppModel, actions: ScreenActions, onChangeDestination: () -> Unit) {
    val place = model.selectedPlace ?: return
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("路线") },
                navigationIcon = {
                    IconButton(onClick = model::clearDestination) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onChangeDestination, modifier = Modifier.testTag("destination-change-button")) { Text("更换") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { NavigationActionBar(model) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            section("destination", header = "从我的位置出发") {
                ActionRow(place.name, Icons.Default.LocationOn, iconTint = MotoColors.Red, subtitle = placeSubtitle(place), showChevron = false)
            }
            when {
                model.isPlanningRoutePreview -> section("loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp).testTag("route-preview-loading"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Column {
                            Text("正在规划路线")
                            Text("获取当前位置与路况…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                model.hasRoutePreview -> {
                    section("map") {
                        RouteOverviewMap(
                            candidates = model.routePreviewCandidates,
                            selectedId = model.selectedRoutePreviewId,
                            origin = model.routePreviewOrigin,
                            destination = place.location,
                            description = "前往${place.name}的路线全览",
                            modifier = Modifier.fillMaxWidth().height(260.dp).testTag("route-preview-map"),
                        )
                    }
                    section("options", header = "选择路线", footer = "高德驾车路线 · 预计时间会随路况变化") {
                        model.routePreviewCandidates.forEachIndexed { index, candidate ->
                            if (index > 0) SectionDivider()
                            RouteOptionRow(candidate, selected = candidate.id == model.selectedRoutePreviewId) {
                                model.selectRoutePreview(candidate.id)
                            }
                        }
                    }
                    section("downloads") {
                        ActionRow("地图与离线下载", Icons.Outlined.Map, subtitle = "可保存这条路线沿途的地图", onClick = actions.openMaps)
                    }
                }

                model.routePreviewFailure != null -> section("failure") {
                    EmptyState(Icons.Default.Warning, "无法规划路线", model.routePreviewFailure.orEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (model.searchLocationStatus == SearchLocationStatus.PERMISSION_DENIED) {
                                TextButton(onClick = actions.requestLocation) { Text("开启定位") }
                            } else {
                                TextButton(onClick = model::planRoutePreview) { Text("重新规划") }
                            }
                        }
                    }
                }
            }
            model.navigationFailure?.let { failure ->
                section("navigation-failure") { FailureMessage(failure) }
            }
        }
    }
}

@Composable
private fun RouteOptionRow(candidate: RoutePreviewCandidate, selected: Boolean, onClick: () -> Unit) {
    val trafficColor = when (candidate.trafficSummary) {
        TrafficSummary.HEAVY -> MotoColors.Red
        TrafficSummary.PARTIAL -> MotoColors.Orange
        TrafficSummary.CLEAR -> MotoColors.Green
        TrafficSummary.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("route-option-${candidate.ordinal}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                candidate.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(candidate.durationText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(candidate.distanceText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = trafficColor, modifier = Modifier.size(16.dp))
                Text(candidate.trafficSummary.text, style = MaterialTheme.typography.bodyMedium, color = trafficColor)
            }
        }
        Icon(
            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (selected) "已选择" else null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun NavigationActionBar(model: AppModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 4.dp) {
        Button(
            onClick = model::toggleNavigation,
            enabled = model.isNavigationActive || (model.selectedPlace != null && !model.isPlanningRoutePreview),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (model.isNavigationActive) MotoColors.Red else MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(54.dp)
                .testTag("primary-navigation-action"),
        ) {
            if (model.isPlanningRoutePreview) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(if (model.isNavigationActive) Icons.Default.Stop else Icons.Default.Navigation, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(model.primaryActionTitle, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ------------------------------------------------------------- active ride

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveNavigationScreen(model: AppModel, actions: ScreenActions) {
    val nav = model.navigation
    val hasEstimate = nav.stateName in setOf("navigating", "rerouting", "arrived")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (model.isDemoActive) "演示导航" else "导航中") },
                actions = { DeviceToolbarButton(model, actions.openDevice) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { NavigationActionBar(model) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "hero") { RideHero(model) }
            section("destination") {
                ActionRow(
                    title = model.activeDestinationName,
                    icon = Icons.Default.Flag,
                    subtitle = if (model.isDemoActive) "演示目的地" else "目的地",
                    showChevron = false,
                )
                SectionDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    RideMetric("剩余时间", if (hasEstimate) model.remainingDurationText else "—", Modifier.weight(1f))
                    RideMetric("剩余路程", if (hasEstimate) model.remainingDistanceText else "—", Modifier.weight(1f))
                }
            }
            section("status", header = "导航状态") {
                DeviceSummaryRow(model, actions.openDevice)
                SectionDivider()
                StatusRow(
                    "手机定位",
                    Icons.Default.MyLocation,
                    when {
                        model.isDemoActive -> "演示位置"
                        nav.hasUsableFix -> "已获取"
                        else -> "获取中"
                    },
                    if (nav.hasUsableFix) MotoColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionDivider()
                StatusRow(
                    "路况",
                    Icons.Default.DirectionsCar,
                    when {
                        model.isDemoActive -> "演示中"
                        nav.trafficRequestInFlight -> "更新中"
                        nav.networkName == "online" -> "在线"
                        else -> "离线"
                    },
                )
                SectionDivider()
                StatusRow("周边地图", Icons.Outlined.Map, model.surroundingMap.statusText)
                SectionDivider()
                ActionRow("管理离线地图", Icons.Outlined.TravelExplore, onClick = actions.openMaps)
            }
            if (model.isDemoActive) {
                section("demo", footer = "道路数据 © OpenStreetMap contributors") {
                    ActionRow("演示中的位置与行驶过程为模拟数据。", Icons.Default.Info, showChevron = false)
                }
            }
        }
    }
}

@Composable
private fun RideHero(model: AppModel) {
    val nav = model.navigation
    val failure = model.navigationFailure
    val title = when {
        failure != null -> "导航需要处理"
        nav.stateName == "acquiring" -> "正在获取位置"
        nav.stateName == "planning" -> "正在规划路线"
        nav.stateName == "rerouting" -> "正在重新规划"
        nav.stateName == "arrived" -> "已到达目的地"
        nav.stateName == "navigating" -> if (model.isDemoActive) "正在演示" else "正在导航"
        else -> "准备出发"
    }
    val detail = when {
        failure != null -> failure
        nav.stateName == "navigating" && model.isDemoActive -> "预览行驶过程与圆屏上的导航指引。"
        nav.stateName == "navigating" -> if (model.deviceReady) "圆屏已连接，手机可锁屏收好。" else "圆屏连接后，导航指引会自动同步。"
        else -> model.phaseDetail
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (nav.stateName == "navigating" && nav.hasNextManeuver && failure == null) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ManeuverBadge(nav.maneuverTypeName)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(formatDistance(nav.distanceToManeuverM), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            nav.instructionText.ifEmpty { nav.roadName },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (nav.roadName.isNotEmpty() && nav.instructionText.isNotEmpty()) {
                            Text(nav.roadName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            Box(
                Modifier.size(76.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (nav.stateName in setOf("acquiring", "planning", "rerouting") && failure == null) {
                    CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
                } else {
                    Icon(
                        when {
                            failure != null -> Icons.Default.Warning
                            nav.stateName == "arrived" -> Icons.Default.Flag
                            else -> Icons.Default.Navigation
                        },
                        contentDescription = null,
                        tint = if (failure == null) MaterialTheme.colorScheme.primary else MotoColors.Orange,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (model.isDemoActive && nav.stateName == "navigating") {
            Text(JinanDemoFixture.ROAD_ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RideMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FailureMessage(message: String) {
    ActionRow(message, Icons.Default.Warning, iconTint = MotoColors.Orange, showChevron = false)
}

// ------------------------------------------------------------- device summary

@Composable
fun DeviceToolbarButton(model: AppModel, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("device-details-button")) {
        Box {
            Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, contentDescription = "我的圆屏，${deviceStatusText(model.device)}")
            if (model.deviceReady) {
                Box(Modifier.size(8.dp).background(MotoColors.Green, CircleShape).align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
fun DeviceSummaryRow(model: AppModel, onClick: () -> Unit) {
    ActionRow(
        title = "我的圆屏",
        icon = Icons.AutoMirrored.Outlined.BluetoothSearching,
        subtitle = deviceStatusText(model.device),
        trailing = {
            if (model.deviceReady) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MotoColors.Green, modifier = Modifier.padding(end = 4.dp))
            }
        },
        onClick = onClick,
    )
}

fun deviceStatusText(device: BleDeviceSnapshot): String = when (val connection = device.connection) {
    is BleDeviceSnapshot.Connection.Connected -> if (device.isReady) "已连接" else "正在准备"
    BleDeviceSnapshot.Connection.Scanning -> "正在寻找圆屏"
    is BleDeviceSnapshot.Connection.Connecting -> connection.detail ?: "正在连接"
    is BleDeviceSnapshot.Connection.Failed -> "连接未成功"
    BleDeviceSnapshot.Connection.BluetoothUnavailable -> "蓝牙不可用"
    BleDeviceSnapshot.Connection.PermissionRequired -> "需要蓝牙权限"
    BleDeviceSnapshot.Connection.Idle -> "未连接"
}
