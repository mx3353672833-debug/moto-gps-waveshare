import Foundation

public enum NavigationPhase: String, Equatable, Sendable {
    case idle
    case acquiringLocation
    case planningRoute
    case navigating
    case failed
}

public struct NavigationSessionState: Equatable, Sendable {
    public var phase: NavigationPhase = .idle
    public var destination: WGS84Point?
    public var latestFix: NavigationFix?
    public var route: RoutePlan?
    public var activeRequestID: UInt32?
    public var failureMessage: String?

    public init() {}

    public var isRunning: Bool {
        phase == .acquiringLocation || phase == .planningRoute || phase == .navigating
    }
}

public enum NavigationSessionAction: Equatable, Sendable {
    case start(destination: WGS84Point)
    case location(NavigationFix)
    case routeLoaded(RouteEnvelope)
    case routeFailed(requestID: UInt32, message: String)
    case locationFailed(String)
    case stop
}

public enum NavigationSessionEffect: Equatable, Sendable {
    case startLocation
    case stopLocation
    case requestRoute(RouteRequest)
}

public struct NavigationSessionReducer: Sendable {
    public private(set) var state = NavigationSessionState()
    private var nextRequestID: UInt32 = 1

    public init() {}

    @discardableResult
    public mutating func reduce(_ action: NavigationSessionAction) -> [NavigationSessionEffect] {
        switch action {
        case let .start(destination):
            guard destination.isValid else {
                state.phase = .failed
                state.failureMessage = "终点坐标无效"
                return []
            }
            state = NavigationSessionState()
            state.phase = .acquiringLocation
            state.destination = destination
            return [.startLocation]

        case let .location(fix):
            guard state.isRunning else { return [] }
            state.latestFix = fix
            guard state.route == nil,
                  state.phase != .planningRoute,
                  let destination = state.destination
            else { return [] }

            let requestID = nextRequestID
            nextRequestID = nextRequestID == .max ? 1 : nextRequestID + 1
            state.activeRequestID = requestID
            state.phase = .planningRoute
            return [
                .requestRoute(
                    RouteRequest(
                        requestID: requestID,
                        origin: fix.coordinate,
                        destination: destination
                    )
                ),
            ]

        case let .routeLoaded(envelope):
            guard state.activeRequestID == envelope.requestID else { return [] }
            state.route = envelope.route
            state.phase = .navigating
            state.failureMessage = nil
            return []

        case let .routeFailed(requestID, message):
            guard state.activeRequestID == requestID else { return [] }
            state.phase = .failed
            state.failureMessage = message
            return [.stopLocation]

        case let .locationFailed(message):
            guard state.isRunning else { return [] }
            state.phase = .failed
            state.failureMessage = message
            return [.stopLocation]

        case .stop:
            let shouldStopSource = state.isRunning
            state = NavigationSessionState()
            return shouldStopSource ? [.stopLocation] : []
        }
    }
}

@MainActor
public final class NavigationSession {
    public private(set) var state = NavigationSessionState()
    public var onStateChange: (@MainActor (NavigationSessionState) -> Void)?

    private var reducer = NavigationSessionReducer()
    private let locationSource: any NavigationLocationSource
    private let routeProvider: any NavigationRouteProviding
    private var routeTask: Task<Void, Never>?

    public init(
        locationSource: any NavigationLocationSource,
        routeProvider: any NavigationRouteProviding
    ) {
        self.locationSource = locationSource
        self.routeProvider = routeProvider
    }

    public func start(destination: WGS84Point) {
        routeTask?.cancel()
        process(.start(destination: destination))
    }

    public func stop() {
        routeTask?.cancel()
        routeTask = nil
        process(.stop)
    }

    private func process(_ action: NavigationSessionAction) {
        let effects = reducer.reduce(action)
        state = reducer.state
        onStateChange?(state)

        for effect in effects {
            switch effect {
            case .startLocation:
                do {
                    try locationSource.start(
                        onFix: { [weak self] fix in self?.process(.location(fix)) },
                        onFailure: { [weak self] message in self?.process(.locationFailed(message)) }
                    )
                } catch {
                    process(.locationFailed(error.localizedDescription))
                }

            case .stopLocation:
                locationSource.stop()

            case let .requestRoute(request):
                routeTask?.cancel()
                routeTask = Task { @MainActor [weak self, routeProvider] in
                    do {
                        let envelope = try await routeProvider.route(for: request)
                        guard !Task.isCancelled else { return }
                        self?.process(.routeLoaded(envelope))
                    } catch is CancellationError {
                        return
                    } catch {
                        guard !Task.isCancelled else { return }
                        self?.process(
                            .routeFailed(
                                requestID: request.requestID,
                                message: error.localizedDescription
                            )
                        )
                    }
                }
            }
        }
    }
}
