import Foundation

public protocol NavigationRouteProviding: Sendable {
    func route(for request: RouteRequest) async throws -> RouteEnvelope
}

@MainActor
public protocol NavigationLocationSource: AnyObject {
    func start(
        onFix: @escaping @MainActor (NavigationFix) -> Void,
        onFailure: @escaping @MainActor (String) -> Void
    ) throws

    func stop()
}

public enum NavigationSourceError: LocalizedError, Equatable {
    case permissionDenied
    case unavailable(String)

    public var errorDescription: String? {
        switch self {
        case .permissionDenied:
            "没有后台定位权限"
        case let .unavailable(message):
            message
        }
    }
}
