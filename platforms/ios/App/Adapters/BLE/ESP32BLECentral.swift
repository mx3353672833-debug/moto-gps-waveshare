@preconcurrency import CoreBluetooth
import Foundation
import MotoNavigationCore

struct BLEDeviceSnapshot: Equatable {
    enum Connection: Equatable {
        case idle
        case bluetoothUnavailable
        case scanning
        case connecting(name: String)
        case connected(name: String)
        case failed(message: String)
    }

    var connection: Connection = .idle
    var negotiatedProtocol = "--"
    var lastCommandID: UInt16?
}

enum BLECommandDisposition: UInt8 {
    case accepted = 0
    case unsupported = 1
    case invalidState = 2
    case failed = 3
    case duplicate = 4
}

enum BLEWritePumpAction: Equatable {
    case idle
    case wait(milliseconds: UInt64)
    case sendOne
}

enum BLEWritePumpPolicy {
    static func action(
        pendingFrameCount: Int,
        canSend: Bool,
        nowMs: UInt64,
        lastSendMs: UInt64,
        pacingMs: UInt64
    ) -> BLEWritePumpAction {
        guard pendingFrameCount > 0 else { return .idle }
        let nextAllowed = lastSendMs &+ pacingMs
        if lastSendMs != 0, nowMs < nextAllowed {
            return .wait(milliseconds: nextAllowed - nowMs)
        }
        return canSend ? .sendOne : .idle
    }
}

enum BLEOutboundBatch {
    /// Invokes the stateful protocol codec in the same order the frames will
    /// be written. Encoding order matters because each call allocates the next
    /// logical-message sequence number.
    static func encodeNavigation(
        geometryRequired: Bool,
        encodeGeometry: () throws -> [Data],
        encodeSnapshot: () throws -> [Data]
    ) rethrows -> [Data] {
        var frames: [Data] = []
        if geometryRequired {
            frames.append(contentsOf: try encodeGeometry())
        }
        frames.append(contentsOf: try encodeSnapshot())
        return frames
    }

    static func requiresGeometry(
        hasGeometry: Bool,
        geometryChanged: Bool,
        pendingFrameCount: Int,
        snapshotFrameCount: Int,
        queueCapacity: Int = 128
    ) -> Bool {
        hasGeometry && (
            geometryChanged || pendingFrameCount + snapshotFrameCount > queueCapacity
        )
    }
}

@MainActor
final class ESP32BLECentral: NSObject {
    // UUIDs belong to the CoreBluetooth transport adapter; the byte format is
    // implemented only by shared/ble_protocol through MotoBLEProtocolBridge.
    private static let serviceUUID = CBUUID(string: MotoBLEProtocolCodec.serviceUUIDString())
    private static let phoneToDeviceUUID = CBUUID(string: MotoBLEProtocolCodec.phoneToDeviceUUIDString())
    private static let deviceToPhoneUUID = CBUUID(string: MotoBLEProtocolCodec.deviceToPhoneUUIDString())

    var onSnapshotChange: ((BLEDeviceSnapshot) -> Void)?
    var onDeviceCommand: ((MotoBLEDeviceCommand) -> BLECommandDisposition)?
    private(set) var snapshot = BLEDeviceSnapshot() {
        didSet { onSnapshotChange?(snapshot) }
    }

    private func trace(_ message: @autoclosure () -> String) {
        #if DEBUG
        print("[MotoBLE] \(message())")
        #endif
    }

    private static let restorationID = "org.example.motogps.central"
    private static let knownPeripheralKey = "MotoGPS.KnownPeripheralIdentifier"
    /// Keep the radio feed at the frozen v1 protocol ceiling of 5 Hz. The
    /// terminal interpolates heading/position locally at 40 Hz, so animation
    /// remains smooth without forcing a full LVGL redraw ten times per second.
    private static let navigationTransmitIntervalMs: UInt64 = 200
    /// CoreBluetooth's `canSendWriteWithoutResponse` only reflects the
    /// phone-side buffer, not how quickly the terminal application drains its
    /// RX queue. Pace application frames instead of bursting every fragment in
    /// one run-loop turn.
    private static let writeWithoutResponsePacingMs: UInt64 = 15
    private static let mapSceneCapability: UInt32 = 1 << 7

    private struct RouteGeometrySignature: Equatable {
        let routeID: String
        let generation: UInt32
        let originLongitudeE6: Int64
        let originLatitudeE6: Int64
        let pointCoordinatesE6: [Int64]
    }

    private lazy var central = CBCentralManager(
        delegate: self,
        queue: .main,
        options: [CBCentralManagerOptionRestoreIdentifierKey: Self.restorationID]
    )
    private var peripheral: CBPeripheral?
    private var phoneToDeviceCharacteristic: CBCharacteristic?
    private var deviceToPhoneCharacteristic: CBCharacteristic?
    private var codec: MotoBLEProtocolCodec?
    private var handshake: BLEHandshakeGate?
    private var pendingNavigationState: MotoNavCoreSnapshot?
    private var pendingMediaState: PhoneMediaState?
    private var pendingMapScene: OfflineMapSceneWindow?
    private var shouldMaintainConnection = false
    private var protocolReady = false
    private var peerCapabilities: UInt32 = 0
    private var transportTeardownInProgress = false
    private var reconnectAttempt = 0
    private var reconnectTask: Task<Void, Never>?
    private var connectionTimeoutTask: Task<Void, Never>?
    private var gattSetupTimeoutTask: Task<Void, Never>?
    private var handshakeTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var navigationTransmitTask: Task<Void, Never>?
    private var writePumpTask: Task<Void, Never>?
    private var lastNavigationTransmitAtMs: UInt64 = 0
    private var lastWriteWithoutResponseAtMs: UInt64 = 0
    private var lastRouteGeometrySignature: RouteGeometrySignature?
    private var lastValidDeviceFrameAtMs: UInt64 = 0
    private var deviceHeartbeatTimeoutMs: UInt64 = 5_000
    private var outboundFrames: [Data] = []
    private var writeWithResponseInFlight = false
    private var commandIDBySequence: [UInt16: UInt16] = [:]
    private var commandStatusByID: [UInt16: BLECommandDisposition] = [:]
    private var receivedCommandOrder: [UInt16] = []
    private var gattSetupInProgress = false
    private var sessionID: UInt32 = {
        var value = UInt32.random(in: 1 ... .max)
        if value == 0 { value = 1 }
        return value
    }()

    func toggleConnection() {
        switch snapshot.connection {
        case .connected, .connecting, .scanning:
            disconnect()
        case .idle, .bluetoothUnavailable, .failed:
            connect()
        }
    }

    func connect() {
        trace("connect requested; central=\(central.state.rawValue)")
        shouldMaintainConnection = true
        reconnectTask?.cancel()
        switch central.state {
        case .poweredOn:
            connectKnownPeripheralOrScan()
        case .unknown, .resetting:
            snapshot.connection = .scanning
        default:
            snapshot.connection = .bluetoothUnavailable
        }
    }

    func disconnect() {
        shouldMaintainConnection = false
        reconnectTask?.cancel()
        reconnectTask = nil
        transportTeardownInProgress = false
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        central.stopScan()
        if let peripheral { central.cancelPeripheralConnection(peripheral) }
        self.peripheral = nil
        clearProtocolState()
        snapshot = BLEDeviceSnapshot(connection: .idle)
    }

    func sendNavigationSnapshot(_ state: MotoNavCoreSnapshot) {
        pendingNavigationState = state
        scheduleNavigationTransmit()
    }

    /// Retains the newest Apple Music projection while disconnected and sends
    /// it as soon as the encrypted v1 session becomes ready.
    func sendMediaState(_ state: PhoneMediaState) {
        pendingMediaState = state
        flushPendingMediaState()
    }

    /// Map scenes are sparse, low-frequency replacements.  Retain only the
    /// newest local window while disconnected or while a prior write drains.
    func sendMapScene(_ scene: OfflineMapSceneWindow) {
        pendingMapScene = scene
        flushPendingMapScene()
    }

    private func startScan() {
        guard central.state == .poweredOn else { return }
        snapshot.connection = .scanning
        central.scanForPeripherals(
            withServices: [Self.serviceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
    }

    private func connectKnownPeripheralOrScan() {
        guard !transportTeardownInProgress else { return }
        if let peripheral {
            if peripheral.state == .connecting {
                if connectionTimeoutTask == nil {
                    scheduleConnectionTimeout(for: peripheral)
                }
                return
            }
            if peripheral.state == .connected {
                beginGattSetup(for: peripheral)
                return
            }
        }
        if let rawIdentifier = UserDefaults.standard.string(forKey: Self.knownPeripheralKey),
           let identifier = UUID(uuidString: rawIdentifier),
           let known = central.retrievePeripherals(withIdentifiers: [identifier]).first
        {
            attachAndConnect(known)
            return
        }
        if let connected = central.retrieveConnectedPeripherals(withServices: [Self.serviceUUID]).first {
            attachAndConnect(connected)
            return
        }
        startScan()
    }

    private func attachAndConnect(_ peripheral: CBPeripheral) {
        guard shouldMaintainConnection else { return }
        central.stopScan()
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        self.peripheral = peripheral
        peripheral.delegate = self
        snapshot.connection = .connecting(name: peripheral.name ?? "MOTO GPS")
        if peripheral.state == .connected {
            peripheral.discoverServices([Self.serviceUUID])
        } else {
            central.connect(peripheral)
            scheduleConnectionTimeout(for: peripheral)
        }
    }

    private func scheduleConnectionTimeout(for candidate: CBPeripheral) {
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = Task { @MainActor [weak self, weak candidate] in
            try? await Task.sleep(for: .seconds(12))
            guard !Task.isCancelled,
                  let self,
                  let candidate,
                  self.shouldMaintainConnection,
                  candidate === self.peripheral,
                  candidate.state != .connected
            else { return }

            // A stale retrievePeripherals result can otherwise remain pending
            // indefinitely and prevent discovery of a replacement device.
            UserDefaults.standard.removeObject(forKey: Self.knownPeripheralKey)
            self.recoverFromTransportError("连接设备超时，正在重新扫描")
        }
    }

    /// State restoration can return an already-connected CBPeripheral while
    /// CBCentralManager is still `.unknown`. Starting discovery from
    /// `willRestoreState` is unreliable: CoreBluetooth may never deliver the
    /// discovery callback, and the old code then remained in Connecting
    /// forever. This entry point is only called after the central is powered on
    /// and can also resume from CoreBluetooth's restored GATT cache.
    private func beginGattSetup(for candidate: CBPeripheral) {
        guard shouldMaintainConnection,
              central.state == .poweredOn,
              candidate === peripheral,
              candidate.state == .connected,
              codec == nil,
              !gattSetupInProgress
        else { return }

        gattSetupInProgress = true
        scheduleGattSetupTimeout(for: candidate)

        if let service = candidate.services?.first(where: { $0.uuid == Self.serviceUUID }) {
            if hasRequiredCharacteristics(service) {
                configureCharacteristics(from: service, on: candidate)
            } else {
                trace("using restored navigation service; rediscovering characteristics")
                candidate.discoverCharacteristics(
                    [Self.phoneToDeviceUUID, Self.deviceToPhoneUUID],
                    for: service
                )
            }
        } else {
            trace("discovering navigation service")
            candidate.discoverServices([Self.serviceUUID])
        }
    }

    private func scheduleGattSetupTimeout(for candidate: CBPeripheral) {
        gattSetupTimeoutTask?.cancel()
        gattSetupTimeoutTask = Task { @MainActor [weak self, weak candidate] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled,
                  let self,
                  let candidate,
                  candidate === self.peripheral,
                  self.shouldMaintainConnection,
                  self.codec == nil
            else { return }
            self.recoverFromTransportError("恢复圆屏服务超时，正在重新连接")
        }
    }

    private func hasRequiredCharacteristics(_ service: CBService) -> Bool {
        let characteristics = service.characteristics ?? []
        return characteristics.contains(where: { $0.uuid == Self.phoneToDeviceUUID }) &&
            characteristics.contains(where: { $0.uuid == Self.deviceToPhoneUUID })
    }

    private func configureCharacteristics(
        from service: CBService,
        on candidate: CBPeripheral
    ) {
        guard shouldMaintainConnection,
              candidate === peripheral,
              service.uuid == Self.serviceUUID
        else { return }

        let characteristics = service.characteristics ?? []
        guard let rx = characteristics.first(where: { $0.uuid == Self.phoneToDeviceUUID }),
              rx.properties.contains(.writeWithoutResponse) || rx.properties.contains(.write),
              let tx = characteristics.first(where: { $0.uuid == Self.deviceToPhoneUUID }),
              tx.properties.contains(.notify)
        else {
            recoverFromTransportError("设备 BLE 特征或权限与 v1 不匹配")
            return
        }

        phoneToDeviceCharacteristic = rx
        deviceToPhoneCharacteristic = tx
        trace("characteristics ready; rx=\(rx.properties.rawValue) tx=\(tx.properties.rawValue) notifying=\(tx.isNotifying)")

        if tx.isNotifying {
            finishProtocolSetup(for: candidate)
        } else {
            candidate.setNotifyValue(true, for: tx)
        }
    }

    private func finishProtocolSetup(for peripheral: CBPeripheral) {
        guard codec == nil,
              !protocolReady,
              let rx = phoneToDeviceCharacteristic,
              deviceToPhoneCharacteristic?.isNotifying == true
        else { return }
        gattSetupTimeoutTask?.cancel()
        gattSetupTimeoutTask = nil
        gattSetupInProgress = false
        let writeType: CBCharacteristicWriteType = rx.properties.contains(.writeWithoutResponse)
            ? .withoutResponse
            : .withResponse
        let maximum = peripheral.maximumWriteValueLength(for: writeType)
        let localMaximumFrameSize = max(
            BLEHandshakeGate.minimumFrameSize,
            min(BLEHandshakeGate.maximumFrameSize, maximum)
        )
        let codec = MotoBLEProtocolCodec(
            maximumFrameSize: UInt(localMaximumFrameSize)
        )
        self.codec = codec
        sessionID = Self.makeSessionID()
        trace("starting v1 handshake; session=\(sessionID) frame=\(localMaximumFrameSize) notify=\(deviceToPhoneCharacteristic?.isNotifying == true)")
        do {
            #if DEBUG
            try BLEProtocolGoldenCheck.verify(codec: codec)
            #endif
            var handshake = BLEHandshakeGate(
                localMaximumFrameSize: localMaximumFrameSize
            )
            try handshake.begin(sessionID: sessionID)
            self.handshake = handshake
            try sendHandshakeFrame(for: .awaitingInitialDeviceReady, codec: codec)
            scheduleHandshakeTimeout(for: .awaitingInitialDeviceReady)
        } catch {
            recoverFromTransportError(error.localizedDescription)
        }
    }

    private func acceptDeviceReady(_ value: MotoBLEConnectionStatus) {
        guard var handshake, let codec else {
            recoverFromTransportError("设备在握手开始前发送了状态")
            return
        }

        let status = BLEDeviceHandshakeStatus(
            role: value.role,
            state: value.state,
            minimumVersion: value.minimumVersion,
            maximumVersion: value.maximumVersion,
            capabilities: value.capabilities,
            sessionID: value.sessionID,
            maximumFrameSize: value.maximumFrameSize,
            heartbeatIntervalMs: value.heartbeatIntervalMs
        )

        do {
            let transition = try handshake.acceptDeviceReady(status)
            self.handshake = handshake
            switch transition {
            case let .sendPhoneReady(maximumFrameSize):
                trace("received initial Device Ready; negotiated frame=\(maximumFrameSize)")
                codec.setMaximumFrameSize(UInt(maximumFrameSize))
                try sendHandshakeFrame(for: .awaitingFinalDeviceReady, codec: codec)
                scheduleHandshakeTimeout(for: .awaitingFinalDeviceReady)

            case let .protocolReady(maximumFrameSize):
                trace("received final Device Ready; protocol ready frame=\(maximumFrameSize)")
                codec.setMaximumFrameSize(UInt(maximumFrameSize))
                handshakeTask?.cancel()
                handshakeTask = nil
                protocolReady = true
                peerCapabilities = value.capabilities
                // A physical connection is not healthy until service discovery,
                // subscription and the full application handshake all succeed.
                // Keeping the attempt count until here gives repeated GATT or
                // handshake failures a real exponential backoff.
                reconnectAttempt = 0
                deviceHeartbeatTimeoutMs = handshake.deviceLivenessTimeoutMs
                snapshot.connection = .connected(
                    name: peripheral?.name ?? "MOTO GPS"
                )
                snapshot.negotiatedProtocol = "V1"
                lastValidDeviceFrameAtMs = Self.monotonicMs()
                startHeartbeat()
                if let pendingNavigationState {
                    sendNavigationSnapshot(pendingNavigationState)
                }
                flushPendingMediaState()
                flushPendingMapScene()
            }
        } catch {
            recoverFromTransportError(error.localizedDescription)
        }
    }

    private func scheduleHandshakeTimeout(for expectedStage: BLEHandshakeStage) {
        handshakeTask?.cancel()
        handshakeTask = Task { @MainActor [weak self] in
            // Notification delivery and write-without-response may briefly be
            // delayed while iOS restores encryption/CCCD state. Re-send the
            // idempotent session frame before tearing down a healthy radio
            // link. The ESP32 answers both Starting and Ready repeatedly.
            for attempt in 1 ... 5 {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled,
                      let self,
                      self.handshake?.stage == expectedStage,
                      !self.protocolReady,
                      let codec = self.codec
                else { return }
                do {
                    self.trace("retrying handshake stage=\(expectedStage) attempt=\(attempt)")
                    try self.sendHandshakeFrame(for: expectedStage, codec: codec)
                } catch {
                    self.recoverFromTransportError(error.localizedDescription)
                    return
                }
            }
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled,
                  let self,
                  self.handshake?.stage == expectedStage,
                  !self.protocolReady
            else { return }
            self.recoverFromTransportError("BLE v1 握手超时，正在重新连接")
        }
    }

    private func sendHandshakeFrame(
        for stage: BLEHandshakeStage,
        codec: MotoBLEProtocolCodec
    ) throws {
        let frames: [Data]
        switch stage {
        case .awaitingInitialDeviceReady:
            frames = try codec.encodePhoneStarting(withSessionID: sessionID)
        case .awaitingFinalDeviceReady:
            frames = try codec.encodePhoneReady(withSessionID: sessionID)
        case .idle, .ready:
            return
        }

        // ConnectionStatus is a single short frame. Prefer an acknowledged
        // GATT write here: canSendWriteWithoutResponse can remain false during
        // CoreBluetooth restoration without issuing its readiness callback,
        // which previously left every handshake frame queued forever.
        if frames.count == 1,
           let peripheral,
           let characteristic = phoneToDeviceCharacteristic,
           characteristic.properties.contains(.write),
           !writeWithResponseInFlight
        {
            writeWithResponseInFlight = true
            peripheral.writeValue(frames[0], for: characteristic, type: .withResponse)
            trace("sent reliable handshake frame stage=\(stage) bytes=\(frames[0].count)")
            return
        }
        try send(frames)
    }

    private func clearProtocolState() {
        gattSetupTimeoutTask?.cancel()
        gattSetupTimeoutTask = nil
        handshakeTask?.cancel()
        handshakeTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        navigationTransmitTask?.cancel()
        navigationTransmitTask = nil
        writePumpTask?.cancel()
        writePumpTask = nil
        lastNavigationTransmitAtMs = 0
        lastWriteWithoutResponseAtMs = 0
        lastRouteGeometrySignature = nil
        phoneToDeviceCharacteristic = nil
        deviceToPhoneCharacteristic = nil
        codec = nil
        handshake = nil
        protocolReady = false
        peerCapabilities = 0
        lastValidDeviceFrameAtMs = 0
        deviceHeartbeatTimeoutMs = 5_000
        outboundFrames.removeAll()
        writeWithResponseInFlight = false
        commandIDBySequence.removeAll()
        commandStatusByID.removeAll()
        receivedCommandOrder.removeAll()
        gattSetupInProgress = false
        snapshot.negotiatedProtocol = "--"
        snapshot.lastCommandID = nil
    }

    /// GATT discovery, subscription and write failures cannot recover while the
    /// peripheral remains connected. Keep the failed object until CoreBluetooth
    /// confirms teardown, then didDisconnect/didFail enters the backoff path.
    /// This prevents the same CBPeripheral instance from being reattached while
    /// callbacks from its old physical connection are still queued.
    private func recoverFromTransportError(_ message: String) {
        guard !transportTeardownInProgress else { return }
        trace("transport recovery: \(message)")
        let failedPeripheral = peripheral
        central.stopScan()
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        clearProtocolState()

        // A late delegate callback can arrive after a user-requested disconnect.
        // It must not replace Idle with Failed or restart the connection loop.
        guard shouldMaintainConnection else {
            transportTeardownInProgress = false
            peripheral = nil
            snapshot.connection = .idle
            return
        }
        snapshot.connection = .failed(message: message)

        if let failedPeripheral,
           failedPeripheral.state != .disconnected
        {
            transportTeardownInProgress = true
            central.cancelPeripheralConnection(failedPeripheral)
            return
        }
        transportTeardownInProgress = false
        peripheral = nil
        scheduleReconnect()
    }

    @discardableResult
    private func send(_ frames: [Data]) throws -> Bool {
        guard phoneToDeviceCharacteristic != nil else { return false }
        var resetQueuedFrames = false
        if outboundFrames.count + frames.count > 128 {
            // Preserve the newest complete snapshot rather than growing without
            // bound while iOS waits for CoreBluetooth backpressure to clear.
            outboundFrames.removeAll(keepingCapacity: true)
            // A queued geometry frame may have been discarded. Force the next
            // coalesced navigation send to restore a complete route before we
            // resume snapshot-only updates.
            lastRouteGeometrySignature = nil
            resetQueuedFrames = true
        }
        outboundFrames.append(contentsOf: frames)
        flushWrites()
        return resetQueuedFrames
    }

    private func scheduleNavigationTransmit() {
        guard protocolReady,
              codec != nil,
              pendingNavigationState != nil,
              navigationTransmitTask == nil
        else { return }

        let now = Self.monotonicMs()
        let nextAllowed = lastNavigationTransmitAtMs &+
            Self.navigationTransmitIntervalMs
        if lastNavigationTransmitAtMs == 0 || now >= nextAllowed {
            flushPendingNavigationState()
            return
        }

        let delayMs = nextAllowed - now
        navigationTransmitTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(delayMs))
            guard !Task.isCancelled, let self else { return }
            self.navigationTransmitTask = nil
            self.flushPendingNavigationState()
        }
    }

    private func flushPendingNavigationState() {
        guard protocolReady,
              let codec,
              let state = pendingNavigationState
        else { return }

        // Take the newest immutable NavCore snapshot. Any update published
        // while encoding runs will become the next coalesced transmission.
        pendingNavigationState = nil
        let geometrySignature = Self.routeGeometrySignature(for: state)
        do {
            // NavigationSnapshot is at most 211 bytes in v1, hence at most two
            // 182-byte GATT values. Use that conservative upper bound before
            // touching the stateful codec so queue-reset recovery does not
            // require encoding the snapshot first and consuming its sequence.
            let geometryRequired = BLEOutboundBatch.requiresGeometry(
                hasGeometry: geometrySignature != nil,
                geometryChanged: geometrySignature != lastRouteGeometrySignature,
                pendingFrameCount: outboundFrames.count,
                snapshotFrameCount: 2
            )
            let frames = try BLEOutboundBatch.encodeNavigation(
                geometryRequired: geometryRequired,
                encodeGeometry: { try codec.encodeRouteGeometry(from: state) },
                encodeSnapshot: {
                    try codec.encodeNavigationSnapshot(
                        makeSnapshotInput(state, codec: codec)
                    )
                }
            )
            let didResetQueue = try send(frames)
            if geometrySignature == nil || geometryRequired || !didResetQueue {
                lastRouteGeometrySignature = geometrySignature
            } else {
                // Defensive fallback if batching rules change: never claim a
                // geometry survived a queue reset unless it was in this batch.
                lastRouteGeometrySignature = nil
            }
            lastNavigationTransmitAtMs = Self.monotonicMs()
        } catch {
            // Preserve the newest state for the next protocol-ready session.
            if pendingNavigationState == nil {
                pendingNavigationState = state
            }
            recoverFromTransportError(error.localizedDescription)
            return
        }

        if pendingNavigationState != nil {
            scheduleNavigationTransmit()
        }
    }

    private func flushPendingMediaState() {
        guard protocolReady,
              let codec,
              let state = pendingMediaState
        else { return }

        do {
            try send(codec.encodeMediaState(makeMediaStateInput(state)))
        } catch {
            // Keep the immutable latest state for the next healthy session.
            recoverFromTransportError(error.localizedDescription)
        }
    }

    private func flushPendingMapScene() {
        guard protocolReady,
              peerCapabilities & Self.mapSceneCapability != 0,
              let codec,
              let scene = pendingMapScene
        else { return }

        do {
            try send(codec.encodeMapScene(scene.makeBLEInput()))
            // Keep the latest complete scene so a later BLE reconnection can
            // restore the map even when the motorcycle has not moved 100 m.
        } catch {
            // Retain the newest window for a future protocol-ready session.
            recoverFromTransportError(error.localizedDescription)
        }
    }

    private static func routeGeometrySignature(
        for state: MotoNavCoreSnapshot
    ) -> RouteGeometrySignature? {
        guard state.hasRouteView,
              !state.routeID.isEmpty,
              !state.routeViewPoints.isEmpty
        else { return nil }

        var coordinates: [Int64] = []
        coordinates.reserveCapacity(state.routeViewPoints.count * 2)
        for point in state.routeViewPoints {
            coordinates.append(coordinateE6(point.longitudeDeg))
            coordinates.append(coordinateE6(point.latitudeDeg))
        }
        return RouteGeometrySignature(
            routeID: state.routeID,
            generation: state.routeGeneration,
            originLongitudeE6: coordinateE6(state.routeViewOriginLongitudeDeg),
            originLatitudeE6: coordinateE6(state.routeViewOriginLatitudeDeg),
            pointCoordinatesE6: coordinates
        )
    }

    private static func coordinateE6(_ value: Double) -> Int64 {
        guard value.isFinite else { return 0 }
        return Int64((value * 1_000_000).rounded())
    }

    private func flushWrites() {
        guard let peripheral,
              let characteristic = phoneToDeviceCharacteristic,
              !outboundFrames.isEmpty
        else { return }

        if characteristic.properties.contains(.writeWithoutResponse) {
            guard writePumpTask == nil else { return }
            let now = Self.monotonicMs()
            switch BLEWritePumpPolicy.action(
                pendingFrameCount: outboundFrames.count,
                canSend: peripheral.canSendWriteWithoutResponse,
                nowMs: now,
                lastSendMs: lastWriteWithoutResponseAtMs,
                pacingMs: Self.writeWithoutResponsePacingMs
            ) {
            case .idle:
                return
            case let .wait(milliseconds):
                scheduleWritePump(afterMs: milliseconds)
                return
            case .sendOne:
                // Exactly one frame per pump tick. `canSend...` does not expose
                // how quickly the terminal drains its application RX queue, so
                // a while loop can still overrun it when iOS reports space.
                peripheral.writeValue(
                    outboundFrames.removeFirst(),
                    for: characteristic,
                    type: .withoutResponse
                )
                lastWriteWithoutResponseAtMs = now
                if !outboundFrames.isEmpty {
                    scheduleWritePump(afterMs: Self.writeWithoutResponsePacingMs)
                }
            }
            return
        }

        guard characteristic.properties.contains(.write),
              !writeWithResponseInFlight
        else { return }
        writeWithResponseInFlight = true
        peripheral.writeValue(
            outboundFrames.removeFirst(),
            for: characteristic,
            type: .withResponse
        )
    }

    private func scheduleWritePump(afterMs delayMs: UInt64) {
        guard writePumpTask == nil else { return }
        writePumpTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(delayMs))
            guard !Task.isCancelled, let self else { return }
            self.writePumpTask = nil
            self.flushWrites()
        }
    }

    private func scheduleReconnect() {
        guard shouldMaintainConnection else { return }
        reconnectTask?.cancel()
        let delays: [UInt64] = [1, 2, 4, 8, 15, 30]
        let delay = delays[min(reconnectAttempt, delays.count - 1)]
        reconnectAttempt = min(reconnectAttempt + 1, delays.count - 1)
        reconnectTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled,
                  let self,
                  self.shouldMaintainConnection,
                  self.central.state == .poweredOn
            else { return }
            self.connectKnownPeripheralOrScan()
        }
    }

    private func startHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled,
                      let self,
                      self.protocolReady,
                      let codec = self.codec,
                      self.peripheral != nil
                else { return }

                let now = Self.monotonicMs()
                if self.lastValidDeviceFrameAtMs > 0,
                   now > self.lastValidDeviceFrameAtMs + self.deviceHeartbeatTimeoutMs
                {
                    self.recoverFromTransportError("设备心跳超时，正在重连")
                    return
                }

                do {
                    try self.send(
                        codec.encodeHeartbeat(
                            withSessionID: self.sessionID,
                            monotonicMs: UInt32(truncatingIfNeeded: now)
                        )
                    )
                } catch {
                    self.recoverFromTransportError(error.localizedDescription)
                    return
                }
            }
        }
    }

    private func remember(
        commandID: UInt16,
        sequence: UInt16,
        status: BLECommandDisposition
    ) {
        commandIDBySequence[sequence] = commandID
        commandStatusByID[commandID] = status
        receivedCommandOrder.append(commandID)
        while receivedCommandOrder.count > 64 {
            let stale = receivedCommandOrder.removeFirst()
            commandStatusByID.removeValue(forKey: stale)
            commandIDBySequence = commandIDBySequence.filter { $0.value != stale }
        }
    }

    private func acknowledge(
        sequence: UInt16,
        commandID: UInt16,
        status: BLECommandDisposition
    ) {
        guard let codec else { return }
        do {
            try send(
                codec.encodeAck(
                    forSequence: sequence,
                    status: status.rawValue,
                    commandID: commandID
                )
            )
        } catch {
            recoverFromTransportError(error.localizedDescription)
        }
    }

    private static func monotonicMs() -> UInt64 {
        UInt64(ProcessInfo.processInfo.systemUptime * 1_000)
    }

    private static func makeSessionID() -> UInt32 {
        var value = UInt32.random(in: 1 ... .max)
        if value == 0 { value = 1 }
        return value
    }

    private func makeSnapshotInput(
        _ state: MotoNavCoreSnapshot,
        codec: MotoBLEProtocolCodec
    ) -> MotoBLENavigationSnapshotInput {
        let input = MotoBLENavigationSnapshotInput()
        input.stateName = state.stateName
        input.networkName = state.networkName
        input.displayPageName = state.displayPageName
        input.hasDestination = state.hasDestination
        input.hasFix = state.hasUsableFix
        input.gnssStale = state.gnssStale
        input.offRoute = state.offRoute
        input.routeRequestInFlight = state.routeRequestInFlight
        input.trafficRequestInFlight = state.trafficRequestInFlight
        input.hasRouteView = state.hasRouteView
        input.routeToken = codec.routeToken(forRouteID: state.routeID)
        input.routeGeneration = state.routeGeneration
        input.maneuverID = state.maneuverID
        input.maneuverName = state.maneuverTypeName
        input.distanceToManeuverM = clampedUInt32(state.distanceToManeuverM)
        input.remainingDistanceM = clampedUInt32(state.remainingDistanceM)
        input.remainingDurationS = state.remainingDurationS
        input.routeProgressM = clampedUInt32(state.routeProgressM)
        input.totalDistanceM = clampedUInt32(state.totalDistanceM)
        input.speedDeciKPH = UInt16(clamping: Int((state.speedMPS * 36).rounded()))
        input.speedLimitKPH = state.speedLimitKPH
        if state.headingDeg.isFinite {
            let heading = state.headingDeg
            let normalized = heading.truncatingRemainder(dividingBy: 360) + (heading < 0 ? 360 : 0)
            input.headingCentiDegrees = UInt16(clamping: Int((normalized * 100).rounded()))
        }
        input.accuracyDecimeters = UInt16(clamping: Int((state.horizontalAccuracyM * 10).rounded()))
        input.crossTrackDecimeters = UInt16(clamping: Int((state.crossTrackDistanceM * 10).rounded()))
        input.roundaboutExit = state.roundaboutExit
        input.roadName = state.roadName
        input.instructionText = state.instructionText
        input.trafficName = state.trafficName
        return input
    }

    private func makeMediaStateInput(
        _ state: PhoneMediaState
    ) -> MotoBLEMediaStateInput {
        let input = MotoBLEMediaStateInput()
        input.connected = state.connected
        input.playing = state.playing
        input.likeAvailable = state.likeAvailable
        input.liked = state.liked
        input.trackToken = state.trackToken
        input.positionSeconds = state.positionSeconds
        input.durationSeconds = state.durationSeconds
        input.sourceName = state.sourceName
        input.trackTitle = state.trackTitle
        input.artistName = state.artistName
        return input
    }

    private func clampedUInt32(_ value: Double) -> UInt32 {
        UInt32(clamping: Int(max(0, min(value.rounded(), Double(UInt32.max)))))
    }
}

extension ESP32BLECentral: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        trace("central state changed to \(central.state.rawValue)")
        if central.state == .poweredOn, shouldMaintainConnection {
            connectKnownPeripheralOrScan()
        } else if central.state != .poweredOn {
            connectionTimeoutTask?.cancel()
            connectionTimeoutTask = nil
            if let peripheral, peripheral.state != .disconnected {
                central.cancelPeripheralConnection(peripheral)
            }
            transportTeardownInProgress = false
            peripheral = nil
            clearProtocolState()
            snapshot.connection = .bluetoothUnavailable
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        willRestoreState dict: [String: Any]
    ) {
        guard let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
              let peripheral = restored.first
        else { return }
        shouldMaintainConnection = true
        trace("restored peripheral \(peripheral.identifier); state=\(peripheral.state.rawValue) services=\(peripheral.services?.count ?? 0)")
        transportTeardownInProgress = false
        self.peripheral = peripheral
        peripheral.delegate = self
        snapshot.connection = .connecting(name: peripheral.name ?? "MOTO GPS")
        // Do not start GATT discovery while the restored central is still in
        // `.unknown`; centralManagerDidUpdateState will call
        // connectKnownPeripheralOrScan(), which resumes this peripheral.
        if peripheral.state == .connected, central.state == .poweredOn {
            beginGattSetup(for: peripheral)
        } else if central.state == .poweredOn {
            central.connect(peripheral)
            scheduleConnectionTimeout(for: peripheral)
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData _: [String: Any],
        rssi _: NSNumber
    ) {
        // stopScan is asynchronous; accept only the first callback already
        // queued on the main actor.
        guard shouldMaintainConnection, self.peripheral == nil else { return }
        attachAndConnect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard shouldMaintainConnection, peripheral === self.peripheral else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        reconnectTask?.cancel()
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        transportTeardownInProgress = false
        trace("physical link connected; peripheral=\(peripheral.identifier)")
        clearProtocolState()
        UserDefaults.standard.set(
            peripheral.identifier.uuidString,
            forKey: Self.knownPeripheralKey
        )
        // Physical BLE connection is only the first stage. Keep the UI in
        // Connecting until GATT discovery, subscription and the v1 handshake
        // have all completed in acceptDeviceReady().
        snapshot.connection = .connecting(name: peripheral.name ?? "MOTO GPS")
        beginGattSetup(for: peripheral)
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral === self.peripheral else { return }
        trace("physical link disconnected; error=\(error?.localizedDescription ?? "none")")
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        clearProtocolState()
        transportTeardownInProgress = false
        self.peripheral = nil
        snapshot.connection = .failed(message: error?.localizedDescription ?? "无法连接设备")
        scheduleReconnect()
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard peripheral === self.peripheral else { return }
        connectionTimeoutTask?.cancel()
        connectionTimeoutTask = nil
        clearProtocolState()
        transportTeardownInProgress = false
        self.peripheral = nil
        snapshot.connection = shouldMaintainConnection
            ? .failed(message: error?.localizedDescription ?? "设备连接已断开，等待重连")
            : .idle
        scheduleReconnect()
    }
}

extension ESP32BLECentral: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard shouldMaintainConnection, peripheral === self.peripheral else { return }
        if let error {
            recoverFromTransportError(error.localizedDescription)
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            recoverFromTransportError("设备缺少 MOTO GPS 导航服务")
            return
        }
        trace("navigation service discovered")
        peripheral.discoverCharacteristics(
            [Self.phoneToDeviceUUID, Self.deviceToPhoneUUID],
            for: service
        )
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard shouldMaintainConnection, peripheral === self.peripheral else { return }
        if let error {
            recoverFromTransportError(error.localizedDescription)
            return
        }
        configureCharacteristics(from: service, on: peripheral)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard shouldMaintainConnection, peripheral === self.peripheral else { return }
        // CoreBluetooth may recreate equivalent CBCharacteristic objects
        // across restoration/reconnection. Object identity is not stable; the
        // delegate's current peripheral plus canonical UUIDs are the safe
        // boundary. Session validation below rejects restored payloads.
        guard characteristic.uuid == Self.deviceToPhoneUUID,
              characteristic.service?.uuid == Self.serviceUUID
        else { return }
        trace("notification state updated; enabled=\(characteristic.isNotifying) error=\(error?.localizedDescription ?? "none")")
        if let error {
            recoverFromTransportError(error.localizedDescription)
            return
        }
        guard characteristic.isNotifying, phoneToDeviceCharacteristic != nil else {
            recoverFromTransportError("设备通知订阅没有生效")
            return
        }
        finishProtocolSetup(for: peripheral)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard shouldMaintainConnection, peripheral === self.peripheral else { return }
        guard characteristic.uuid == Self.deviceToPhoneUUID,
              characteristic.service?.uuid == Self.serviceUUID
        else { return }
        if let error {
            recoverFromTransportError(error.localizedDescription)
            return
        }
        // CoreBluetooth restoration can deliver a stale/empty notification
        // from the previous process before this connection has recreated its
        // codec. It is not a transport failure; the new handshake will start
        // as soon as notification subscription setup finishes.
        guard let codec else { return }
        guard let frame = characteristic.value, !frame.isEmpty else { return }

        do {
            let inbound = try codec.pushDeviceFrame(
                frame,
                receivedAtMs: Self.monotonicMs()
            )
            lastValidDeviceFrameAtMs = Self.monotonicMs()

            if inbound.duplicate {
                if inbound.ackRequested,
                   let commandID = commandIDBySequence[inbound.sequence],
                   let originalStatus = commandStatusByID[commandID]
                {
                    acknowledge(
                        sequence: inbound.sequence,
                        commandID: commandID,
                        status: originalStatus
                    )
                }
                return
            }

            guard inbound.complete else { return }
            if let status = inbound.connectionStatus {
                trace("Device status received; state=\(status.state) session=\(status.sessionID) frame=\(status.maximumFrameSize)")
                // A restored link can deliver the previous process's final
                // Ready before the ESP32 consumes our new Starting frame.
                // It belongs to a dead session and must not poison the new
                // reassembler's sequence baseline or tear down the radio.
                guard status.sessionID == sessionID else {
                    trace("ignored stale Device status for session=\(status.sessionID)")
                    codec.resetInboundState()
                    return
                }
                guard !protocolReady else { return }
                acceptDeviceReady(status)
                return
            }
            if let heartbeat = inbound.heartbeat {
                guard protocolReady, let handshake else {
                    trace("ignored heartbeat while establishing new session")
                    codec.resetInboundState()
                    return
                }
                try handshake.validateProtocolSession(heartbeat.sessionID)
                return
            }
            guard protocolReady else {
                trace("ignored restored business message before handshake")
                codec.resetInboundState()
                return
            }
            guard let command = inbound.deviceCommand else { return }
            snapshot.lastCommandID = command.commandID

            let status: BLECommandDisposition
            if commandStatusByID[command.commandID] != nil {
                status = .duplicate
                commandIDBySequence[command.sequence] = command.commandID
            } else {
                status = onDeviceCommand?(command) ?? .unsupported
                remember(
                    commandID: command.commandID,
                    sequence: command.sequence,
                    status: status
                )
            }
            if command.ackRequested {
                acknowledge(
                    sequence: command.sequence,
                    commandID: command.commandID,
                    status: status
                )
            }
        } catch {
            recoverFromTransportError(error.localizedDescription)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard shouldMaintainConnection, peripheral === self.peripheral else { return }
        flushWrites()
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard shouldMaintainConnection, peripheral === self.peripheral else { return }
        guard characteristic.uuid == Self.phoneToDeviceUUID,
              characteristic.service?.uuid == Self.serviceUUID
        else { return }
        writeWithResponseInFlight = false
        trace("reliable write completed; error=\(error?.localizedDescription ?? "none")")
        if let error {
            recoverFromTransportError(error.localizedDescription)
            return
        }
        flushWrites()
    }
}
