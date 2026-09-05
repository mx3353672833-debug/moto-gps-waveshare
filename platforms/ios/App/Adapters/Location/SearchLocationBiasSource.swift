import CoreLocation
import MotoNavigationCore

enum SearchLocationBiasStatus: Equatable {
    case preparing
    case available
    case permissionDenied
    case unavailable
}

/// Obtains one coarse, recent point for POI ranking. Real navigation keeps its
/// separate best-for-navigation CLLocationManager and background lifecycle.
@MainActor
final class SearchLocationBiasSource: NSObject {
    private static let maximumAcceptedFixAgeS: TimeInterval = 15
    var onLocationChange: ((WGS84Point?) -> Void)?
    var onStatusChange: ((SearchLocationBiasStatus) -> Void)?

    private let manager = CLLocationManager()
    private(set) var latestPoint: WGS84Point?
    private(set) var status: SearchLocationBiasStatus = .preparing

    override init() {
        super.init()
        manager.delegate = self
        manager.activityType = .otherNavigation
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func prepare() {
        guard CLLocationManager.locationServicesEnabled() else {
            publish(nil, status: .unavailable)
            return
        }
        switch manager.authorizationStatus {
        case .notDetermined:
            setStatus(.preparing)
            manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            setStatus(.preparing)
            manager.requestLocation()
        case .restricted, .denied:
            publish(nil, status: .permissionDenied)
        @unknown default:
            publish(nil, status: .unavailable)
        }
    }

    func refresh() {
        guard manager.authorizationStatus == .authorizedAlways ||
                manager.authorizationStatus == .authorizedWhenInUse
        else { return }
        if latestPoint == nil {
            setStatus(.preparing)
        }
        manager.requestLocation()
    }

    /// Route alternatives must start at the rider's position now, not at the
    /// one-shot search bias captured when the app originally opened.
    @discardableResult
    func refreshForRoutePlanning() -> Bool {
        guard manager.authorizationStatus == .authorizedAlways ||
                manager.authorizationStatus == .authorizedWhenInUse
        else { return false }
        latestPoint = nil
        manager.desiredAccuracy = kCLLocationAccuracyBest
        setStatus(.preparing)
        manager.requestLocation()
        return true
    }

    private func setStatus(_ newStatus: SearchLocationBiasStatus) {
        guard status != newStatus else { return }
        status = newStatus
        onStatusChange?(newStatus)
    }

    private func publish(_ point: WGS84Point?, status newStatus: SearchLocationBiasStatus) {
        latestPoint = point
        setStatus(newStatus)
        onLocationChange?(point)
    }
}

extension SearchLocationBiasSource: @preconcurrency CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            setStatus(.preparing)
            manager.requestLocation()
        case .restricted, .denied:
            publish(nil, status: .permissionDenied)
        case .notDetermined:
            break
        @unknown default:
            publish(nil, status: .unavailable)
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let now = Date()
        guard let location = locations
            .filter({
                $0.horizontalAccuracy >= 0 &&
                    $0.horizontalAccuracy <= 2_000 &&
                    abs($0.timestamp.timeIntervalSince(now)) <=
                        Self.maximumAcceptedFixAgeS
            })
            .max(by: { $0.timestamp < $1.timestamp })
        else {
            publish(nil, status: .unavailable)
            return
        }
        publish(
            WGS84Point(
                longitudeDeg: location.coordinate.longitude,
                latitudeDeg: location.coordinate.latitude
            ),
            status: .available
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == .locationUnknown { return }
        publish(nil, status: .unavailable)
    }
}
