import CoreLocation
import MotoNavigationCore

@MainActor
final class CoreLocationNavigationSource: NSObject, NavigationLocationSource {
    private static let maximumFixAgeS: TimeInterval = 15
    private let manager = CLLocationManager()
    private var onFix: (@MainActor (NavigationFix) -> Void)?
    private var onFailure: (@MainActor (String) -> Void)?
    private var wantsUpdates = false

    override init() {
        super.init()
        manager.delegate = self
        manager.activityType = .automotiveNavigation
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = 2
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true
        #if os(iOS)
        manager.showsBackgroundLocationIndicator = true
        #endif
    }

    func start(
        onFix: @escaping @MainActor (NavigationFix) -> Void,
        onFailure: @escaping @MainActor (String) -> Void
    ) throws {
        self.onFix = onFix
        self.onFailure = onFailure
        wantsUpdates = true

        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestAlwaysAuthorization()
        case .authorizedAlways:
            manager.startUpdatingLocation()
        case .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
            manager.startUpdatingLocation()
        case .restricted, .denied:
            throw NavigationSourceError.permissionDenied
        @unknown default:
            throw NavigationSourceError.unavailable("当前系统无法确认定位权限")
        }
    }

    func stop() {
        wantsUpdates = false
        manager.stopUpdatingLocation()
        onFix = nil
        onFailure = nil
    }
}

extension CoreLocationNavigationSource: @preconcurrency CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard wantsUpdates else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
        case .restricted, .denied:
            onFailure?("定位权限已关闭，请在系统设置中允许“始终”定位")
        case .notDetermined:
            break
        @unknown default:
            onFailure?("当前系统无法确认定位权限")
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let now = Date()
        guard let location = locations
            .filter({
                $0.horizontalAccuracy >= 0 &&
                    $0.coordinate.latitude.isFinite &&
                    $0.coordinate.longitude.isFinite &&
                    abs($0.timestamp.timeIntervalSince(now)) <= Self.maximumFixAgeS
            })
            .max(by: { $0.timestamp < $1.timestamp })
        else { return }

        onFix?(
            NavigationFix(
                coordinate: WGS84Point(
                    longitudeDeg: location.coordinate.longitude,
                    latitudeDeg: location.coordinate.latitude
                ),
                altitudeM: location.verticalAccuracy >= 0 ? location.altitude : nil,
                horizontalAccuracyM: location.horizontalAccuracy,
                speedMps: location.speed >= 0 ? location.speed : nil,
                courseDeg: location.course >= 0 ? location.course : nil,
                timestamp: location.timestamp
            )
        )
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let locationError = error as? CLError
        if locationError?.code == .locationUnknown { return }
        onFailure?(error.localizedDescription)
    }
}
