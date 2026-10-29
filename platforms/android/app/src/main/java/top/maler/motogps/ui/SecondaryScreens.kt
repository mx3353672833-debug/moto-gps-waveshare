package top.maler.motogps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.maler.motogps.app.AppModel
import top.maler.motogps.ble.BleDeviceSnapshot
import top.maler.motogps.location.SearchLocationStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        content = content,
    )
}

// ------------------------------------------------------------- round screen

class DeviceActions(
    val requestBluetooth: () -> Unit,
    val openBluetoothSettings: () -> Unit,
    val requestLocation: () -> Unit,
    val openNotificationAccess: () -> Unit,
)

@Composable
fun DeviceScreen(model: AppModel, actions: DeviceActions, onBack: () -> Unit) {
    val device = model.device
    val name = when (val c = device.connection) {
        is BleDeviceSnapshot.Connection.Connected -> c.name
        is BleDeviceSnapshot.Connection.Connecting -> c.name
        else -> "MOTO GPS"
    }
    val detail = when (val c = device.connection) {
        is BleDeviceSnapshot.Connection.Connected ->
            if (device.isReady) "圆屏已就绪，可以接收导航指引。" else "正在准备导航同步，请稍候。"
        is BleDeviceSnapshot.Connection.Failed ->
            if (c.message.contains("靠近") || c.message.contains("配对")) c.message else "${c.message}。请确认圆屏已开机并靠近手机。"
        BleDeviceSnapshot.Connection.BluetoothUnavailable -> "请开启手机蓝牙。"
        BleDeviceSnapshot.Connection.PermissionRequired -> "请允许 MOTO GPS 查找并连接附近的蓝牙设备。"
        BleDeviceSnapshot.Connection.Scanning -> "请将已开机的圆屏放在手机附近。"
        is BleDeviceSnapshot.Connection.Connecting -> c.detail ?: "请将已开机的圆屏放在手机附近。"
        BleDeviceSnapshot.Connection.Idle -> "连接你的圆屏，在车把上查看导航。"
    }
    val connectionActionTitle = when (device.connection) {
        is BleDeviceSnapshot.Connection.Connected -> "断开连接"
        BleDeviceSnapshot.Connection.Scanning -> "停止搜索"
        is BleDeviceSnapshot.Connection.Connecting -> "取消连接"
        is BleDeviceSnapshot.Connection.Failed -> "重新连接"
        else -> "连接圆屏"
    }
    DetailScaffold("我的圆屏", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("device-details-sheet"), contentPadding = padding) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            section("connection", footer = "圆屏保持开机并靠近手机。首次连接会弹出蓝牙配对请求，请点“配对”。连接成功后，当前导航会自动同步。") {
                StatusRow(
                    "连接状态",
                    Icons.Default.Bluetooth,
                    deviceStatusText(device),
                    if (device.isReady) MotoColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionDivider()
                when (device.connection) {
                    BleDeviceSnapshot.Connection.PermissionRequired ->
                        ActionRow("允许连接附近设备", Icons.Default.Bluetooth, onClick = actions.requestBluetooth)
                    BleDeviceSnapshot.Connection.BluetoothUnavailable ->
                        ActionRow("打开蓝牙设置", Icons.Outlined.Settings, onClick = actions.openBluetoothSettings)
                    else -> ActionRow(connectionActionTitle, Icons.Default.Bluetooth, showChevron = false, onClick = model::toggleDeviceConnection)
                }
            }
            section(
                "forget",
                footer = "换了一块圆屏或配对异常时使用。若反复配对失败，请在系统蓝牙设置里删除 MOTO GPS，并在圆屏上清除配对后重试。",
            ) {
                ActionRow("忘记这块圆屏并重新搜索", Icons.Outlined.LinkOff, showChevron = false, onClick = model::forgetDevice)
            }
            section("location", header = "定位") {
                StatusRow(
                    "搜索位置",
                    Icons.Default.MyLocation,
                    when (model.searchLocationStatus) {
                        SearchLocationStatus.AVAILABLE -> "已获取"
                        SearchLocationStatus.PREPARING -> "获取中"
                        SearchLocationStatus.PERMISSION_DENIED -> "未获授权"
                        SearchLocationStatus.UNAVAILABLE -> "暂不可用"
                    },
                )
                if (model.searchLocationStatus == SearchLocationStatus.PERMISSION_DENIED) {
                    SectionDivider()
                    ActionRow("开启定位", Icons.Outlined.Settings, onClick = actions.requestLocation)
                }
            }
            section(
                "music",
                header = "音乐控制",
                footer = "授权“通知使用权”后，圆屏可显示正在播放的歌曲，并控制上一首、播放/暂停、下一首。支持提供系统媒体会话的播放器，例如网易云音乐、QQ 音乐、Spotify。MOTO GPS 不读取通知内容。",
            ) {
                StatusRow(
                    "圆屏控制音乐",
                    Icons.Default.MusicNote,
                    if (model.hasMediaAccess) "已开启" else "未开启",
                    if (model.hasMediaAccess) MotoColors.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionDivider()
                ActionRow(if (model.hasMediaAccess) "管理通知使用权" else "开启音乐控制", Icons.Outlined.Settings, onClick = actions.openNotificationAccess)
            }
        }
    }
}

// ------------------------------------------------------------- gateway

@Composable
fun GatewaySettingsScreen(model: AppModel, onBack: () -> Unit) {
    var address by remember { mutableStateOf(if (model.isGatewayConfigured) model.gatewayBaseUrl.orEmpty() else "") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun save() {
        checkJob?.cancel()
        checking = false
        scope.launch {
            try {
                model.saveGatewayAddress(address)
                onBack()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = error.message
            }
        }
    }

    DetailScaffold(
        "网关设置",
        onBack = { if (!model.isUpdatingGateway) onBack() },
        actions = {
            TextButton(
                onClick = ::save,
                enabled = !model.isNavigationActive && !model.isUpdatingGateway && address.isNotBlank(),
                modifier = Modifier.testTag("gateway-save-button"),
            ) { Text("保存") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = padding) {
            section("address", header = "网关地址", footer = "填写完整的 HTTPS 根地址。例如 https://nav.example.com/moto-gps/api/，不要填高德 Key。") {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        checkJob?.cancel()
                        checking = false
                        message = null
                    },
                    placeholder = { Text("https://你的网关地址/") },
                    enabled = !model.isUpdatingGateway,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("gateway-address-field"),
                )
            }
            section("check", footer = "连接测试只检查网关状态；真实搜索、导航和地图还需要相应服务可用。") {
                ActionRow(
                    "测试连接",
                    Icons.Outlined.Language,
                    enabled = !checking && !model.isUpdatingGateway && address.isNotBlank(),
                    showChevron = false,
                    trailing = { if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    onClick = {
                        checkJob?.cancel()
                        message = null
                        checking = true
                        checkJob = scope.launch {
                            message = try {
                                model.checkGateway(address)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                error.message
                            }
                            checking = false
                        }
                    },
                )
                message?.let {
                    SectionDivider()
                    BodyText(it, Modifier.testTag("gateway-status-message"))
                }
            }
            section("notes") {
                BodyText("保存后立即生效，无需重新安装。更换地址会清空当前搜索并暂停地图下载，已下载的地图仍会保留。")
                SectionDivider()
                BodyText("地点、路线和定位请求将发送到这个地址，请使用你信任的网关。")
            }
            if (model.isNavigationActive) {
                section("navigation-active") { BodyText("请先结束导航，再更换网关地址。") }
            }
        }
    }
}

// ------------------------------------------------------------- data use

@Composable
fun DataUseScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    DetailScaffold("隐私与数据", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            section("location", header = "位置与路线") {
                BodyText("地点搜索、规划和重新规划路线时，搜索内容、起终点及所需位置会发送给配置的导航服务。使用高德服务时，网关会再将所需信息发送给高德。在线周边地图请求也会透露所查看的区域。")
                SectionDivider()
                BodyText("导航期间，定位用于更新路线和圆屏指引；锁屏或切换应用后，通知栏会显示“骑行导航”并继续导航。结束导航会停止导航定位。可在系统设置中调整权限。")
            }
            section("bluetooth", header = "蓝牙与音乐") {
                BodyText("连接圆屏后，导航、速度及周边地图通过加密的蓝牙连接同步。开启“通知使用权”后，正在播放的曲名、歌手和播放状态也会同步到圆屏，圆屏可控制上一首、播放或暂停、下一首。")
                SectionDivider()
                BodyText("音乐信息不发送到 MOTO GPS 的导航服务器，也不读取通知内容。不开启音乐控制不影响地点搜索和导航。")
            }
            section("storage", header = "存储与清理") {
                BodyText("最近地点、已连接圆屏的蓝牙地址和下载地图保存在手机上。首页可清空最近搜索；地图与离线下载页面可删除下载包、清理临时缓存。卸载 App 会移除它的本地数据。")
                SectionDivider()
                BodyText("服务器访问日志可能保留 IP、请求时间、搜索词、位置参数及地图区域。记录范围、轮转和保留期限由部署方的实际配置及隐私政策说明；这不等于数据只在手机上保存。")
            }
            section("third-party", header = "第三方服务", footer = "手机上的路线全览由 App 本地绘制；圆屏周边道路与建筑使用 OpenStreetMap / Protomaps 数据。") {
                ActionRow("高德隐私政策", onClick = { uriHandler.openUri("https://lbs.amap.com/home/privacy/") })
                SectionDivider()
                ActionRow("OpenStreetMap 数据与许可", onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") })
                SectionDivider()
                ActionRow("Protomaps 地图数据", onClick = { uriHandler.openUri("https://protomaps.com") })
            }
        }
    }
}

@Composable
fun DeleteIcon() = Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", tint = MotoColors.Red)

