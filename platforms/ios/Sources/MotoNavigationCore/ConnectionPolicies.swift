import Foundation

public enum TrafficRefreshPolicy {
    /// Traffic offsets are measured from the beginning of the complete route.
    /// They may only replace the current route's traffic when both responses use
    /// exactly the same GCJ-02 geometry and effectively the same route length.
    public static func sharesCompleteRouteBaseline(
        current: RoutePlan,
        refreshed: RoutePlan
    ) -> Bool {
        guard current.coordinateSystem == "GCJ-02",
              refreshed.coordinateSystem == current.coordinateSystem,
              refreshed.polyline == current.polyline
        else { return false }

        let toleranceM = max(5, current.totalDistanceM * 0.002)
        return abs(refreshed.totalDistanceM - current.totalDistanceM) <= toleranceM
    }

    /// The gateway returns duration for the complete original route. NavCore
    /// needs an ETA from the rider's current progress, so scale it to the
    /// remaining fraction without changing the traffic segment offsets.
    public static func remainingDurationSeconds(
        refreshedCompleteDurationS: Int,
        remainingDistanceM: Double,
        completeDistanceM: Double
    ) -> UInt32 {
        guard refreshedCompleteDurationS > 0,
              remainingDistanceM.isFinite,
              completeDistanceM.isFinite,
              completeDistanceM > 0
        else { return 0 }

        let fraction = max(0, min(1, remainingDistanceM / completeDistanceM))
        return UInt32(
            clamping: Int((Double(refreshedCompleteDurationS) * fraction).rounded())
        )
    }
}

public struct BLEDeviceHandshakeStatus: Equatable, Sendable {
    public let role: UInt8
    public let state: UInt8
    public let minimumVersion: UInt8
    public let maximumVersion: UInt8
    public let capabilities: UInt32
    public let sessionID: UInt32
    public let maximumFrameSize: UInt16
    public let heartbeatIntervalMs: UInt16

    public init(
        role: UInt8,
        state: UInt8,
        minimumVersion: UInt8,
        maximumVersion: UInt8,
        capabilities: UInt32,
        sessionID: UInt32,
        maximumFrameSize: UInt16,
        heartbeatIntervalMs: UInt16
    ) {
        self.role = role
        self.state = state
        self.minimumVersion = minimumVersion
        self.maximumVersion = maximumVersion
        self.capabilities = capabilities
        self.sessionID = sessionID
        self.maximumFrameSize = maximumFrameSize
        self.heartbeatIntervalMs = heartbeatIntervalMs
    }
}

public enum BLEHandshakeStage: Equatable, Sendable {
    case idle
    case awaitingInitialDeviceReady
    case awaitingFinalDeviceReady
    case ready
}

public enum BLEHandshakeTransition: Equatable, Sendable {
    case sendPhoneReady(maximumFrameSize: Int)
    case protocolReady(maximumFrameSize: Int)
}

public enum BLEHandshakeValidationError: LocalizedError, Equatable, Sendable {
    case invalidSession
    case unexpectedStage
    case unexpectedRole(UInt8)
    case unexpectedState(UInt8)
    case unsupportedVersion(minimum: UInt8, maximum: UInt8)
    case missingCapabilities(UInt32)
    case invalidFrameSize(UInt16)
    case invalidHeartbeat(UInt16)
    case inconsistentNegotiation

    public var errorDescription: String? {
        switch self {
        case .invalidSession:
            "BLE 握手会话编号无效"
        case .unexpectedStage:
            "BLE 握手消息顺序无效"
        case let .unexpectedRole(role):
            "BLE 握手端角色无效（\(role)）"
        case let .unexpectedState(state):
            "设备没有进入 Ready 状态（\(state)）"
        case let .unsupportedVersion(minimum, maximum):
            "设备协议版本不兼容（\(minimum)-\(maximum)）"
        case let .missingCapabilities(missing):
            String(format: "设备缺少必要能力（0x%08X）", missing)
        case let .invalidFrameSize(size):
            "设备报告的 BLE 帧大小无效（\(size)）"
        case let .invalidHeartbeat(interval):
            "设备报告的心跳间隔无效（\(interval) ms）"
        case .inconsistentNegotiation:
            "设备两次 Ready 的协商参数不一致"
        }
    }
}

public struct BLEHandshakeGate: Sendable {
    public static let protocolVersion: UInt8 = 1
    public static let requiredDeviceCapabilities: UInt32 =
        (1 << 0) | // navigation
        (1 << 1) | // route geometry
        (1 << 2) | // traffic
        (1 << 4) | // touch commands
        (1 << 6) // command ACK
    public static let minimumFrameSize = 13
    public static let maximumFrameSize = 512

    public private(set) var stage: BLEHandshakeStage = .idle
    public private(set) var sessionID: UInt32 = 0
    public private(set) var negotiatedMaximumFrameSize = 0
    public private(set) var negotiatedHeartbeatIntervalMs: UInt16 = 0

    public var deviceLivenessTimeoutMs: UInt64 {
        max(5_000, UInt64(negotiatedHeartbeatIntervalMs) * 3)
    }

    private let localMaximumFrameSize: Int
    private var initialMinimumVersion: UInt8 = 0
    private var initialMaximumVersion: UInt8 = 0
    private var initialCapabilities: UInt32 = 0
    private var initialPeerMaximumFrameSize: UInt16 = 0
    private var initialHeartbeatIntervalMs: UInt16 = 0

    public init(localMaximumFrameSize: Int) {
        self.localMaximumFrameSize = max(
            Self.minimumFrameSize,
            min(Self.maximumFrameSize, localMaximumFrameSize)
        )
    }

    public mutating func begin(sessionID: UInt32) throws {
        guard sessionID != 0 else { throw BLEHandshakeValidationError.invalidSession }
        self.sessionID = sessionID
        negotiatedMaximumFrameSize = 0
        negotiatedHeartbeatIntervalMs = 0
        initialMinimumVersion = 0
        initialMaximumVersion = 0
        initialCapabilities = 0
        initialPeerMaximumFrameSize = 0
        initialHeartbeatIntervalMs = 0
        stage = .awaitingInitialDeviceReady
    }

    public mutating func acceptDeviceReady(
        _ status: BLEDeviceHandshakeStatus
    ) throws -> BLEHandshakeTransition {
        try validate(status)
        let negotiated = min(localMaximumFrameSize, Int(status.maximumFrameSize))

        switch stage {
        case .awaitingInitialDeviceReady:
            initialMinimumVersion = status.minimumVersion
            initialMaximumVersion = status.maximumVersion
            initialCapabilities = status.capabilities
            initialPeerMaximumFrameSize = status.maximumFrameSize
            initialHeartbeatIntervalMs = status.heartbeatIntervalMs
            negotiatedMaximumFrameSize = negotiated
            negotiatedHeartbeatIntervalMs = status.heartbeatIntervalMs
            stage = .awaitingFinalDeviceReady
            return .sendPhoneReady(maximumFrameSize: negotiated)

        case .awaitingFinalDeviceReady:
            guard status.minimumVersion == initialMinimumVersion,
                  status.maximumVersion == initialMaximumVersion,
                  status.capabilities == initialCapabilities,
                  status.maximumFrameSize == initialPeerMaximumFrameSize,
                  status.heartbeatIntervalMs == initialHeartbeatIntervalMs,
                  negotiated == negotiatedMaximumFrameSize
            else { throw BLEHandshakeValidationError.inconsistentNegotiation }
            stage = .ready
            return .protocolReady(maximumFrameSize: negotiated)

        case .idle, .ready:
            throw BLEHandshakeValidationError.unexpectedStage
        }
    }

    public mutating func reset() {
        self = BLEHandshakeGate(localMaximumFrameSize: localMaximumFrameSize)
    }

    /// Session-bearing messages are only valid after the two-step Ready
    /// exchange and must belong to the active handshake session.
    public func validateProtocolSession(_ candidateSessionID: UInt32) throws {
        guard stage == .ready else {
            throw BLEHandshakeValidationError.unexpectedStage
        }
        guard candidateSessionID != 0, candidateSessionID == sessionID else {
            throw BLEHandshakeValidationError.invalidSession
        }
    }

    private func validate(_ status: BLEDeviceHandshakeStatus) throws {
        guard status.role == 2 else {
            throw BLEHandshakeValidationError.unexpectedRole(status.role)
        }
        guard status.state == 1 else {
            throw BLEHandshakeValidationError.unexpectedState(status.state)
        }
        guard status.minimumVersion <= Self.protocolVersion,
              status.maximumVersion >= Self.protocolVersion
        else {
            throw BLEHandshakeValidationError.unsupportedVersion(
                minimum: status.minimumVersion,
                maximum: status.maximumVersion
            )
        }
        guard status.sessionID == sessionID else {
            throw BLEHandshakeValidationError.invalidSession
        }
        let missing = Self.requiredDeviceCapabilities & ~status.capabilities
        guard missing == 0 else {
            throw BLEHandshakeValidationError.missingCapabilities(missing)
        }
        guard (Self.minimumFrameSize ... Self.maximumFrameSize)
            .contains(Int(status.maximumFrameSize))
        else {
            throw BLEHandshakeValidationError.invalidFrameSize(status.maximumFrameSize)
        }
        guard status.heartbeatIntervalMs >= 250 else {
            throw BLEHandshakeValidationError.invalidHeartbeat(status.heartbeatIntervalMs)
        }
    }
}
