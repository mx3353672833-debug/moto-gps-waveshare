package top.maler.motogps.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.maler.motogps.core.BleMapSceneInput
import top.maler.motogps.core.BleMediaStateInput
import top.maler.motogps.core.BleProtocolCodec
import top.maler.motogps.core.InboundMessage
import top.maler.motogps.core.NavCoreSnapshot

data class BleDeviceSnapshot(
    val connection: Connection = Connection.Idle,
    val negotiatedProtocol: String = "--",
    val lastCommandId: Int? = null,
) {
    sealed interface Connection {
        data object Idle : Connection
        data object PermissionRequired : Connection
        data object BluetoothUnavailable : Connection
        data object Scanning : Connection
        data class Connecting(val name: String, val detail: String? = null) : Connection
        data class Connected(val name: String) : Connection
        data class Failed(val message: String) : Connection
    }

    val isReady: Boolean get() = connection is Connection.Connected && negotiatedProtocol == "V1"
}

fun Context.hasBluetoothPermissions(): Boolean {
    val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}

/**
 * Android GATT transport for the MOTO GPS round screen: scan by the v1 service
 * UUID, connect, negotiate MTU, bond (LE Secure Connections, Just Works),
 * discover, subscribe to TX, then hand the link to [BleProtocolEngine]. All
 * Bluetooth callbacks are posted to the main thread and checked against the
 * current BluetoothGatt so a late callback from a closed link is ignored.
 */
@SuppressLint("MissingPermission")
class Esp32BleCentral(
    private val context: Context,
    private val scope: CoroutineScope,
    goldenFixture: (() -> String?)? = null,
) {
    var onSnapshotChange: ((BleDeviceSnapshot) -> Unit)? = null
    var onDeviceCommand: ((InboundMessage.DeviceCommand) -> BleCommandDisposition)? = null

    var snapshot = BleDeviceSnapshot()
        private set(value) {
            field = value
            onSnapshotChange?.invoke(value)
        }

    private enum class Phase { IDLE, SCANNING, CONNECTING, NEGOTIATING_MTU, BONDING, DISCOVERING, SUBSCRIBING, HANDSHAKING, READY }

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("moto_ble", Context.MODE_PRIVATE)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val engine = BleProtocolEngine(scope, goldenFixture)

    private val serviceUuid = UUID.fromString(BleProtocolCodec.serviceUuid)
    private val phoneToDeviceUuid = UUID.fromString(BleProtocolCodec.phoneToDeviceUuid)
    private val deviceToPhoneUuid = UUID.fromString(BleProtocolCodec.deviceToPhoneUuid)

    private var shouldMaintainConnection = false
    private var phase = Phase.IDLE
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rxWriteWithoutResponse = false
    private var localMaximumFrameSize = DEFAULT_FRAME_SIZE
    private var connectingFromKnownAddress = false
    private var subscribeAttempts = 0
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var stageTimeoutJob: Job? = null
    private var scanning = false
    private var receiversRegistered = false

    init {
        engine.onProtocolReady = {
            phase = Phase.READY
            reconnectAttempt = 0
            stageTimeoutJob?.cancel()
            snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Connected(deviceName()), negotiatedProtocol = "V1")
        }
        engine.onTransportError = { message -> recoverFromTransportError(message) }
        engine.onDeviceCommand = { command -> onDeviceCommand?.invoke(command) ?: BleCommandDisposition.UNSUPPORTED }
        engine.onLastCommandId = { id -> snapshot = snapshot.copy(lastCommandId = id) }
    }

    fun toggleConnection() {
        when (snapshot.connection) {
            is BleDeviceSnapshot.Connection.Connected,
            is BleDeviceSnapshot.Connection.Connecting,
            BleDeviceSnapshot.Connection.Scanning,
            -> disconnect()
            else -> connect()
        }
    }

    fun connect() {
        shouldMaintainConnection = true
        reconnectJob?.cancel()
        reconnectJob = null
        registerReceivers()
        if (!context.hasBluetoothPermissions()) {
            snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.PermissionRequired)
            return
        }
        if (adapter?.isEnabled != true) {
            snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.BluetoothUnavailable)
            return
        }
        connectKnownDeviceOrScan()
    }

    fun disconnect() {
        shouldMaintainConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        stageTimeoutJob?.cancel()
        stopScan()
        engine.detach()
        closeGatt()
        phase = Phase.IDLE
        snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.Idle)
    }

    /** Forget the remembered round screen so the next connection scans afresh. */
    fun forgetKnownDevice() {
        preferences.edit { remove(KNOWN_ADDRESS_KEY) }
    }

    fun sendNavigationSnapshot(state: NavCoreSnapshot) = engine.sendNavigationSnapshot(state)
    fun sendMediaState(state: BleMediaStateInput) = engine.sendMediaState(state)
    fun sendMapScene(scene: BleMapSceneInput) = engine.sendMapScene(scene)

    // ------------------------------------------------------------- discovery

    private fun connectKnownDeviceOrScan() {
        if (!shouldMaintainConnection || gatt != null) return
        val known = preferences.getString(KNOWN_ADDRESS_KEY, null)
        val adapter = adapter
        if (known != null && adapter != null && BluetoothAdapter.checkBluetoothAddress(known)) {
            attachAndConnect(adapter.getRemoteDevice(known), fromKnownAddress = true)
            return
        }
        startScan()
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) {
            snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.BluetoothUnavailable)
            return
        }
        if (scanning) return
        phase = Phase.SCANNING
        snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Scanning)
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (error: SecurityException) {
            snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.PermissionRequired)
            return
        }
        // Continuous scanning is throttled by Android and drains the battery;
        // pause and retry with the reconnect backoff instead.
        startStageTimeout(SCAN_WINDOW_MS) {
            stopScan()
            snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Failed("没有找到圆屏"))
            scheduleReconnect()
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Bluetooth turned off; the scanner is already gone.
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning || !shouldMaintainConnection || gatt != null) return@post
                attachAndConnect(result.device, fromKnownAddress = false)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                if (!shouldMaintainConnection) return@post
                snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Failed("蓝牙扫描失败（$errorCode）"))
                scheduleReconnect()
            }
        }
    }

    // ------------------------------------------------------------- connection

    private fun attachAndConnect(target: BluetoothDevice, fromKnownAddress: Boolean) {
        if (!shouldMaintainConnection) return
        stopScan()
        stageTimeoutJob?.cancel()
        device = target
        connectingFromKnownAddress = fromKnownAddress
        phase = Phase.CONNECTING
        snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Connecting(deviceName()))
        gatt = target.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        startStageTimeout(CONNECT_TIMEOUT_MS) {
            // A stale remembered address can stay pending forever and block
            // discovery of a replacement screen; forget it and scan.
            if (connectingFromKnownAddress) forgetKnownDevice()
            recoverFromTransportError("连接设备超时，正在重新扫描")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(source: BluetoothGatt, status: Int, newState: Int) {
            handler.post { handleConnectionState(source, status, newState) }
        }

        override fun onMtuChanged(source: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (source !== gatt || phase != Phase.NEGOTIATING_MTU) return@post
                localMaximumFrameSize = if (status == BluetoothGatt.GATT_SUCCESS) {
                    (mtu - 3).coerceIn(DEFAULT_FRAME_SIZE, BleHandshakeGate.MAXIMUM_FRAME_SIZE)
                } else {
                    DEFAULT_FRAME_SIZE
                }
                trace("ATT MTU=$mtu status=$status frame=$localMaximumFrameSize")
                ensureBonded()
            }
        }

        override fun onServicesDiscovered(source: BluetoothGatt, status: Int) {
            handler.post {
                if (source !== gatt || phase != Phase.DISCOVERING) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recoverFromTransportError("读取圆屏服务失败（$status）")
                    return@post
                }
                configureCharacteristics(source)
            }
        }

        override fun onDescriptorWrite(source: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (source !== gatt || phase != Phase.SUBSCRIBING || descriptor.uuid != CCCD_UUID) return@post
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> finishProtocolSetup()
                    GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> {
                        // The stack starts pairing on this error; wait for the
                        // bond and retry the encrypted CCCD write once.
                        if (subscribeAttempts < 2) {
                            phase = Phase.BONDING
                            ensureBonded()
                        } else {
                            recoverFromTransportError("圆屏要求加密连接，配对没有完成")
                        }
                    }
                    else -> recoverFromTransportError("设备通知订阅没有生效（$status）")
                }
            }
        }

        override fun onCharacteristicWrite(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (source !== gatt || characteristic.uuid != phoneToDeviceUuid) return@post
                engine.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val frame = value.copyOf()
            handler.post { deliverNotification(source, characteristic, frame) }
        }

        // Delivered only on Android 12 and lower.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val frame = characteristic.value?.copyOf() ?: return
            handler.post { deliverNotification(source, characteristic, frame) }
        }
    }

    private fun handleConnectionState(source: BluetoothGatt, status: Int, newState: Int) {
        if (source !== gatt) {
            // Late callback from a link we already replaced.
            source.close()
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            if (!shouldMaintainConnection) {
                closeGatt()
                return
            }
            stageTimeoutJob?.cancel()
            device?.let { preferences.edit { putString(KNOWN_ADDRESS_KEY, it.address) } }
            trace("physical link connected; device=${device?.address}")
            engine.detach()
            subscribeAttempts = 0
            // A physical connection is only the first stage; the UI keeps
            // showing Connecting until discovery, subscription and the full
            // v1 handshake have all completed.
            snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Connecting(deviceName()), negotiatedProtocol = "--")
            phase = Phase.NEGOTIATING_MTU
            startStageTimeout(SETUP_TIMEOUT_MS) { recoverFromTransportError("恢复圆屏服务超时，正在重新连接") }
            if (!source.requestMtu(REQUESTED_MTU)) {
                localMaximumFrameSize = DEFAULT_FRAME_SIZE
                ensureBonded()
            }
            return
        }
        trace("physical link disconnected; status=$status")
        engine.detach()
        closeGatt()
        stageTimeoutJob?.cancel()
        phase = Phase.IDLE
        if (!shouldMaintainConnection) {
            snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.Idle)
            return
        }
        snapshot = BleDeviceSnapshot(
            BleDeviceSnapshot.Connection.Failed(
                if (status == BluetoothGatt.GATT_SUCCESS) "设备连接已断开，等待重连" else "连接未成功（$status），正在重试",
            ),
        )
        scheduleReconnect()
    }

    private fun ensureBonded() {
        val target = device ?: return
        when (target.bondState) {
            BluetoothDevice.BOND_BONDED -> discoverServices()
            BluetoothDevice.BOND_BONDING -> waitForBond()
            else -> {
                trace("creating bond")
                if (!target.createBond()) {
                    // Some stacks refuse while busy; the encrypted CCCD write
                    // will trigger pairing instead.
                    discoverServices()
                    return
                }
                waitForBond()
            }
        }
    }

    private fun waitForBond() {
        phase = Phase.BONDING
        snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Connecting(deviceName(), "请在手机弹出的配对请求中点“配对”"))
        startStageTimeout(BOND_TIMEOUT_MS) {
            recoverFromTransportError("配对没有完成。请在系统蓝牙设置中删除 MOTO GPS 后重试")
        }
    }

    private fun onBondStateChanged(target: BluetoothDevice, state: Int, previous: Int) {
        if (target.address != device?.address || phase != Phase.BONDING) return
        when (state) {
            BluetoothDevice.BOND_BONDED -> {
                trace("bonded")
                snapshot = snapshot.copy(connection = BleDeviceSnapshot.Connection.Connecting(deviceName()))
                if (tx == null) discoverServices() else subscribe()
            }
            BluetoothDevice.BOND_NONE -> if (previous == BluetoothDevice.BOND_BONDING) {
                recoverFromTransportError("配对失败。请在系统蓝牙设置中删除 MOTO GPS，再重新连接")
            }
        }
    }

    private fun discoverServices() {
        val current = gatt ?: return
        phase = Phase.DISCOVERING
        startStageTimeout(SETUP_TIMEOUT_MS) { recoverFromTransportError("恢复圆屏服务超时，正在重新连接") }
        if (!current.discoverServices()) recoverFromTransportError("无法读取圆屏服务")
    }

    private fun configureCharacteristics(source: BluetoothGatt) {
        val service = source.getService(serviceUuid)
        if (service == null) {
            recoverFromTransportError("设备缺少 MOTO GPS 导航服务")
            return
        }
        val rxCharacteristic = service.getCharacteristic(phoneToDeviceUuid)
        val txCharacteristic = service.getCharacteristic(deviceToPhoneUuid)
        val rxProperties = rxCharacteristic?.properties ?: 0
        val writeWithoutResponse = rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val write = rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val notify = (txCharacteristic?.properties ?: 0) and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (rxCharacteristic == null || txCharacteristic == null || !(write || writeWithoutResponse) || !notify) {
            recoverFromTransportError("设备 BLE 特征或权限与 v1 不匹配")
            return
        }
        rx = rxCharacteristic
        tx = txCharacteristic
        rxWriteWithoutResponse = writeWithoutResponse
        rxSupportsWrite = write
        subscribe()
    }

    private var rxSupportsWrite = false

    private fun subscribe() {
        val current = gatt ?: return
        val characteristic = tx ?: return
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null || !current.setCharacteristicNotification(characteristic, true)) {
            recoverFromTransportError("设备通知订阅没有生效")
            return
        }
        phase = Phase.SUBSCRIBING
        subscribeAttempts += 1
        startStageTimeout(SETUP_TIMEOUT_MS) { recoverFromTransportError("设备通知订阅没有生效") }
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            current.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            current.writeDescriptor(descriptor)
        }
        if (!accepted) recoverFromTransportError("设备通知订阅没有生效")
    }

    private fun finishProtocolSetup() {
        val current = gatt ?: return
        val characteristic = rx ?: return
        phase = Phase.HANDSHAKING
        // The engine owns its own handshake timeout from here on.
        stageTimeoutJob?.cancel()
        val sink = BleFrameSink { frame, withResponse ->
            if (current !== gatt) return@BleFrameSink false
            val useResponse = withResponse || !rxWriteWithoutResponse
            val writeType = if (useResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                current.writeCharacteristic(characteristic, frame, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
        }
        engine.attach(sink, localMaximumFrameSize, supportsWithResponse = rxSupportsWrite)
    }

    private fun deliverNotification(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, frame: ByteArray) {
        if (source !== gatt || characteristic.uuid != deviceToPhoneUuid) return
        engine.onNotification(frame)
    }

    // ------------------------------------------------------------- recovery

    private fun recoverFromTransportError(message: String) {
        trace("transport recovery: $message")
        stopScan()
        reconnectJob?.cancel()
        reconnectJob = null
        stageTimeoutJob?.cancel()
        engine.detach()
        closeGatt()
        phase = Phase.IDLE
        // A late callback can arrive after a user-requested disconnect. It
        // must not replace Idle with Failed or restart the connection loop.
        if (!shouldMaintainConnection) {
            snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.Idle)
            return
        }
        snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.Failed(message))
        scheduleReconnect()
    }

    private fun closeGatt() {
        val current = gatt ?: return
        gatt = null
        rx = null
        tx = null
        try {
            current.disconnect()
        } finally {
            current.close()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldMaintainConnection) return
        reconnectJob?.cancel()
        val delaySeconds = RECONNECT_DELAYS_S[minOf(reconnectAttempt, RECONNECT_DELAYS_S.lastIndex)]
        reconnectAttempt = minOf(reconnectAttempt + 1, RECONNECT_DELAYS_S.lastIndex)
        reconnectJob = scope.launch {
            delay(delaySeconds * 1_000)
            reconnectJob = null
            if (!shouldMaintainConnection) return@launch
            if (!context.hasBluetoothPermissions()) {
                snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.PermissionRequired)
                return@launch
            }
            if (adapter?.isEnabled != true) {
                snapshot = BleDeviceSnapshot(BleDeviceSnapshot.Connection.BluetoothUnavailable)
                return@launch
            }
            connectKnownDeviceOrScan()
        }
    }

    private fun startStageTimeout(timeoutMs: Long, onTimeout: () -> Unit) {
        stageTimeoutJob?.cancel()
        stageTimeoutJob = scope.launch {
            delay(timeoutMs)
            stageTimeoutJob = null
            onTimeout()
        }
    }

    private fun deviceName(): String = try {
        device?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    } catch (_: SecurityException) {
        DEFAULT_NAME
    }

    // ------------------------------------------------------------- system state

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON && shouldMaintainConnection) {
                        reconnectAttempt = 0
                        connectKnownDeviceOrScan()
                    } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        stopScan()
                        reconnectJob?.cancel()
                        stageTimeoutJob?.cancel()
                        engine.detach()
                        closeGatt()
                        phase = Phase.IDLE
                        snapshot = BleDeviceSnapshot(
                            if (shouldMaintainConnection) BleDeviceSnapshot.Connection.BluetoothUnavailable else BleDeviceSnapshot.Connection.Idle,
                        )
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    onBondStateChanged(
                        target,
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR),
                        intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR),
                    )
                }
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // System broadcasts; the receiver does not need to be exported.
        ContextCompat.registerReceiver(context, systemReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
    }

    private fun trace(message: String) {
        Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "MotoBLE"
        private const val KNOWN_ADDRESS_KEY = "MotoGPS.KnownPeripheralAddress"
        private const val DEFAULT_NAME = "MOTO GPS"
        private const val REQUESTED_MTU = 517
        private const val DEFAULT_FRAME_SIZE = 20
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val SETUP_TIMEOUT_MS = 8_000L
        private const val BOND_TIMEOUT_MS = 45_000L
        private const val SCAN_WINDOW_MS = 20_000L
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 0x05
        private const val GATT_INSUFFICIENT_ENCRYPTION = 0x0F
        private val RECONNECT_DELAYS_S = longArrayOf(1, 2, 4, 8, 15, 30)
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
