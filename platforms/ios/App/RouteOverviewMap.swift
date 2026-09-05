import MapKit
import MotoNavigationCore
import SwiftUI

struct RouteOverviewMap: UIViewRepresentable {
    let candidates: [RoutePreviewCandidate]
    let selectedID: String?
    let origin: WGS84Point?
    let destination: WGS84Point

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView(frame: .zero)
        mapView.delegate = context.coordinator
        mapView.overrideUserInterfaceStyle = .dark
        mapView.mapType = .mutedStandard
        mapView.pointOfInterestFilter = .excludingAll
        mapView.showsCompass = false
        mapView.showsScale = false
        mapView.showsTraffic = true
        // This map is the route-selection overview, not a free-roaming map.
        // Let vertical gestures reach the enclosing SwiftUI ScrollView so all
        // route choices remain reachable instead of accidentally panning the
        // selected route out of view.
        mapView.isScrollEnabled = false
        mapView.isZoomEnabled = false
        mapView.isRotateEnabled = false
        mapView.isPitchEnabled = false
        return mapView
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        context.coordinator.selectedID = selectedID
        let signature = candidates.map(\.id).joined(separator: "|") +
            "::" + (selectedID ?? "")
        guard context.coordinator.signature != signature else { return }
        context.coordinator.signature = signature

        mapView.removeOverlays(mapView.overlays)
        mapView.removeAnnotations(mapView.annotations)

        let ordered = candidates.sorted { lhs, rhs in
            (lhs.id == selectedID ? 1 : 0) < (rhs.id == selectedID ? 1 : 0)
        }
        var allCoordinates: [CLLocationCoordinate2D] = []
        for candidate in ordered {
            let coordinates = candidate.route.polyline.map { point in
                let wgs84 = ChinaCoordinateTransform.gcj02ToWGS84(point)
                return CLLocationCoordinate2D(
                    latitude: wgs84.latitudeDeg,
                    longitude: wgs84.longitudeDeg
                )
            }
            guard coordinates.count >= 2 else { continue }
            let polyline = MKPolyline(coordinates: coordinates, count: coordinates.count)
            polyline.title = candidate.id
            mapView.addOverlay(polyline, level: .aboveRoads)
            allCoordinates.append(contentsOf: coordinates)
        }

        if let origin {
            let annotation = MKPointAnnotation()
            annotation.coordinate = CLLocationCoordinate2D(
                latitude: origin.latitudeDeg,
                longitude: origin.longitudeDeg
            )
            annotation.title = "当前位置"
            annotation.subtitle = "moto-start"
            mapView.addAnnotation(annotation)
            allCoordinates.append(annotation.coordinate)
        }

        let destinationAnnotation = MKPointAnnotation()
        destinationAnnotation.coordinate = CLLocationCoordinate2D(
            latitude: destination.latitudeDeg,
            longitude: destination.longitudeDeg
        )
        destinationAnnotation.title = "目的地"
        destinationAnnotation.subtitle = "moto-finish"
        mapView.addAnnotation(destinationAnnotation)
        allCoordinates.append(destinationAnnotation.coordinate)

        guard !allCoordinates.isEmpty else { return }
        var visibleRect = MKMapRect.null
        for coordinate in allCoordinates {
            let point = MKMapPoint(coordinate)
            visibleRect = visibleRect.union(
                MKMapRect(x: point.x, y: point.y, width: 0.1, height: 0.1)
            )
        }
        mapView.setVisibleMapRect(
            visibleRect,
            edgePadding: UIEdgeInsets(top: 38, left: 30, bottom: 42, right: 30),
            animated: context.coordinator.hasPresentedRoute
        )
        context.coordinator.hasPresentedRoute = true
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var signature = ""
        var selectedID: String?
        var hasPresentedRoute = false

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let polyline = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKPolylineRenderer(polyline: polyline)
            let isSelected = polyline.title == selectedID
            renderer.strokeColor = isSelected
                ? UIColor(red: 0.239, green: 0.731, blue: 0.973, alpha: 1)
                : UIColor.white.withAlphaComponent(0.34)
            renderer.lineWidth = isSelected ? 7 : 4
            renderer.lineCap = .round
            renderer.lineJoin = .round
            return renderer
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard let point = annotation as? MKPointAnnotation else { return nil }
            let identifier = "route-endpoint"
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                ?? MKMarkerAnnotationView(annotation: point, reuseIdentifier: identifier)
            view.annotation = point
            let isStart = point.subtitle == "moto-start"
            view.markerTintColor = isStart
                ? UIColor(red: 0.337, green: 0.875, blue: 0.596, alpha: 1)
                : UIColor(red: 0.969, green: 0.699, blue: 0.259, alpha: 1)
            view.glyphImage = UIImage(systemName: isStart ? "location.fill" : "flag.fill")
            view.displayPriority = .required
            return view
        }
    }
}
