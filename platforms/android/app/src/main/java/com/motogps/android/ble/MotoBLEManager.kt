package com.motogps.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.motogps.android.jni.MotoBLEMapScene
import com.motogps.android.jni.MotoBLENavigationSnapshot
import com.motogps.android.jni.MotoBLEProtocolCodec
import com.motogps.android.jni.MotoBLERouteGeometry
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class MotoBLEManager(private val context: Context) {
    val serviceUuid: UUID = UUID.fromString("7e57a000-b50c-4b6a-9c57-40a54e8e1000")
    val phoneToDeviceUuid: UUID = UUID.fromString("7e57a001-b50c-4b6a-9c57-40a54e8e1000")
    val deviceToPhoneUuid: UUID = UUID.fromString("7e57a002-b50c-4b6a-9c57-40a54e8e1000")
    val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var onConnectionStateChange: ((String) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    var onDeviceCommandReceived: ((com.motogps.android.jni.MotoBLEDeviceCommand) -> Unit)? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatScheduler: ScheduledExecutorService? = null

    private val bleCodec = MotoBLEProtocolCodec()
    private var codecPtr: Long = 0L

    private enum class HandshakeState {
        IDLE,
        STARTING_SENT,
        READY_SENT,
        COMPLETED
    }

    private var handshakeState = HandshakeState.IDLE
    private var currentSessionId: Long = 0L

    private var isScanning = false

    // Synchronized FIFO Write Queue for GATT Characteristic Writes
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInProgress = false

    // Asynchronous Operation Queue for GATT configuration steps
    private val operationQueue = mutableListOf<() -> Unit>()
    private var operationInProgress = false

    private var cccdRetryCount = 0
    private var cccdWritePending = false
    private val cccdTimeoutRunnable = Runnable {
        if (cccdWritePending) {
            Log.w("MotoBLEManager", "CCCD write timed out after 1000ms, retrying")
            retryCccdWrite()
        }
    }

    init {
        codecPtr = bleCodec.nativeInit(247)
    }

    fun getCurrentSessionId(): Long = currentSessionId

    fun sendNavigationSnapshot(snapshot: MotoBLENavigationSnapshot) {
        if (codecPtr != 0L) {
            val frames = bleCodec.nativeEncodeNavigationSnapshot(codecPtr, snapshot)
            enqueueSendFramesTask(frames)
        }
    }

    fun sendMapScene(scene: MotoBLEMapScene) {
        if (codecPtr != 0L) {
            val frames = bleCodec.nativeEncodeMapScene(codecPtr, scene)
            enqueueSendFramesTask(frames)
        }
    }

    fun sendRouteGeometry(geometry: MotoBLERouteGeometry) {
        if (codecPtr != 0L) {
            val frames = bleCodec.nativeEncodeRouteGeometry(codecPtr, geometry)
            enqueueSendFramesTask(frames)
        }
    }

    private fun enqueueOperation(operation: () -> Unit) {
        operationQueue.add(operation)
        if (!operationInProgress) {
            executeNextOperation()
        }
    }

    private fun executeNextOperation() {
        if (operationQueue.isNotEmpty()) {
            operationInProgress = true
            val operation = operationQueue.removeAt(0)
            operation()
        } else {
            operationInProgress = false
        }
    }

    private fun completeOperation() {
        operationInProgress = false
        executeNextOperation()
    }

    fun enqueueSendFramesTask(frames: Array<ByteArray>) {
        synchronized(writeQueue) {
            for (frame in frames) {
                writeQueue.addLast(frame)
            }
            if (!writeInProgress) {
                writeInProgress = true
                mainHandler.post { processNextWrite() }
            }
        }
    }

    private fun processNextWrite() {
        val frame: ByteArray?
        synchronized(writeQueue) {
            frame = if (writeQueue.isNotEmpty()) writeQueue.removeFirst() else null
            if (frame == null) {
                writeInProgress = false
                return
            }
        }

        val gatt = bluetoothGatt ?: run {
            synchronized(writeQueue) { writeInProgress = false }
            return
        }
        val characteristic = writeCharacteristic ?: run {
            synchronized(writeQueue) { writeInProgress = false }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, frame!!, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame!!
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = gatt.device
                    handshakeState = HandshakeState.IDLE
                    operationQueue.clear()
                    operationInProgress = false
                    synchronized(writeQueue) {
                        writeQueue.clear()
                        writeInProgress = false
                    }
                    mainHandler.postDelayed({
                        enqueueOperation {
                            gatt.discoverServices()
                        }
                    }, 300)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    stopHeartbeat()
                    mainHandler.removeCallbacks(cccdTimeoutRunnable)
                    cccdWritePending = false
                    cccdRetryCount = 0
                    handshakeState = HandshakeState.IDLE
                    mainHandler.post { onConnectionStateChange?.invoke("Non connesso") }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.post { onConnectionError?.invoke("Errore di connessione GATT ($status)") }
                    }
                    writeCharacteristic = null
                    readCharacteristic = null
                    connectedDevice = null
                    operationQueue.clear()
                    operationInProgress = false
                    synchronized(writeQueue) {
                        writeQueue.clear()
                        writeInProgress = false
                    }
                    try {
                        gatt.disconnect()
                    } catch (_: Exception) {}
                    try {
                        gatt.close()
                    } catch (_: Exception) {}
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUuid)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(phoneToDeviceUuid)
                    readCharacteristic = service.getCharacteristic(deviceToPhoneUuid)

                    mainHandler.postDelayed({
                        enqueueOperation {
                            gatt.requestMtu(185)
                        }
                    }, 300)
                } else {
                    mainHandler.post { onConnectionError?.invoke("Errore: Servizio non trovato") }
                }
            } else {
                mainHandler.post { onConnectionError?.invoke("Errore discovery servizi GATT ($status)") }
            }
            completeOperation()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (codecPtr != 0L) {
                    bleCodec.nativeSetMaximumFrameSize(codecPtr, mtu.coerceAtMost(185))
                }
            }
            enqueueOperation {
                mainHandler.postDelayed({
                    startCccdWrite(gatt)
                }, 300)
            }
            completeOperation()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == cccdUuid) {
                mainHandler.removeCallbacks(cccdTimeoutRunnable)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cccdWritePending = false
                    cccdRetryCount = 0
                    startHandshakeAndHeartbeat()
                } else {
                    retryCccdWrite()
                    return
                }
            }
            completeOperation()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            mainHandler.postDelayed({
                processNextWrite()
            }, 18)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleInboundFrame(data)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleInboundFrame(value)
        }
    }

    private fun doWriteDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val resCode = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            resCode == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun startHandshakeAndHeartbeat() {
        if (handshakeState == HandshakeState.IDLE) {
            currentSessionId = (System.currentTimeMillis() % 0x7FFFFFFF).coerceAtLeast(1)
            handshakeState = HandshakeState.STARTING_SENT
            if (codecPtr != 0L) {
                bleCodec.nativeResetInboundState(codecPtr)
                val frames = bleCodec.nativeEncodePhoneStarting(codecPtr, currentSessionId)
                enqueueSendFramesTask(frames)
            }
            startHeartbeat()
        }
    }

    private fun retryCccdWrite() {
        mainHandler.removeCallbacks(cccdTimeoutRunnable)
        if (cccdRetryCount < 3) {
            cccdRetryCount++
            mainHandler.postDelayed({
                val gatt = bluetoothGatt
                if (gatt != null && cccdWritePending) {
                    performCccdWrite(gatt)
                }
            }, 300)
        } else {
            Log.w("MotoBLEManager", "CCCD write reached 3 retries without callback; proceeding with handshake anyway")
            cccdWritePending = false
            startHandshakeAndHeartbeat()
            completeOperation()
        }
    }

    private fun performCccdWrite(gatt: BluetoothGatt) {
        val characteristic = readCharacteristic ?: run {
            cccdWritePending = false
            completeOperation()
            return
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(cccdUuid) ?: run {
            cccdWritePending = false
            completeOperation()
            return
        }

        val success = doWriteDescriptor(gatt, descriptor)
        if (!success) {
            Log.w("MotoBLEManager", "writeDescriptor returned false/error, retrying CCCD write")
            retryCccdWrite()
        } else {
            mainHandler.removeCallbacks(cccdTimeoutRunnable)
            mainHandler.postDelayed(cccdTimeoutRunnable, 1000)
        }
    }

    private fun startCccdWrite(gatt: BluetoothGatt) {
        cccdRetryCount = 0
        cccdWritePending = true
        performCccdWrite(gatt)
    }

    private fun handleInboundFrame(frame: ByteArray) {
        if (codecPtr == 0L) return
        val inboundMessage = bleCodec.nativePushDeviceFrame(codecPtr, frame, System.currentTimeMillis())
        if (inboundMessage.complete) {
            val status = inboundMessage.connStatus
            if (status != null && status.role == 2 /* Device */ && status.state == 1 /* Ready */) {
                if (handshakeState == HandshakeState.STARTING_SENT) {
                    handshakeState = HandshakeState.READY_SENT
                    val frames = bleCodec.nativeEncodePhoneReady(codecPtr, currentSessionId)
                    enqueueSendFramesTask(frames)
                } else if (handshakeState == HandshakeState.READY_SENT) {
                    handshakeState = HandshakeState.COMPLETED
                    mainHandler.post { onConnectionStateChange?.invoke("Connesso") }
                    startHeartbeat()
                }
            }

            val cmd = inboundMessage.command
            if (cmd != null) {
                if (codecPtr != 0L) {
                    val ackFrames = bleCodec.nativeEncodeAck(codecPtr, cmd.sequence, 0 /* Ok */, cmd.commandId)
                    enqueueSendFramesTask(ackFrames)
                }
                mainHandler.post { onDeviceCommandReceived?.invoke(cmd) }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                mainHandler.post { onConnectionStateChange?.invoke("Connessione in corso...") }

                stopScan()

                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            mainHandler.post {
                onConnectionStateChange?.invoke("Non connesso")
                onConnectionError?.invoke("Errore di scansione ($errorCode)")
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun stopScan() {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w("MotoBLEManager", "Error stopping BLE scan: ${e.message}")
        } finally {
            isScanning = false
        }
    }

    fun startScan() {
        stopScan()
        onConnectionStateChange?.invoke("Ricerca in corso...")
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            isScanning = false
            Log.e("MotoBLEManager", "Failed to start BLE scan: ${e.message}")
            onConnectionStateChange?.invoke("Non connesso")
            onConnectionError?.invoke("Impossibile avviare la scansione BLE")
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor()
        heartbeatScheduler?.scheduleAtFixedRate({
            try {
                if (codecPtr != 0L && currentSessionId != 0L) {
                    val frames = bleCodec.nativeEncodeHeartbeat(codecPtr, currentSessionId, System.currentTimeMillis() % Int.MAX_VALUE)
                    enqueueSendFramesTask(frames)
                }
            } catch (e: Exception) {
                Log.w("MotoBLEManager", "Heartbeat send failed: ${e.message}")
            }
        }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() {
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
    }

    fun disconnect() {
        stopHeartbeat()
        stopScan()
        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
            } catch (_: Exception) {}
            try {
                gatt.close()
            } catch (_: Exception) {}
        }
        bluetoothGatt = null
    }
}
