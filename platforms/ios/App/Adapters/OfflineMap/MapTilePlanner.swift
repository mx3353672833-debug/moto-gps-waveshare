import Foundation
import MotoNavigationCore

enum MapPlanningError: LocalizedError {
    case invalidCoordinates
    case areaTooLarge
    case missingRoute

    var errorDescription: String? {
        switch self {
        case .invalidCoordinates: return "地图范围无效，请重新选择城市或路线。"
        case .areaTooLarge: return "这个范围太大，请选择市内的区县，或分段下载路线。"
        case .missingRoute: return "请先规划一条路线。"
        }
    }
}

/// Tile addresses always refer to WGS84 Web Mercator. Geometry sent to the
/// round screen remains GCJ-02; convert only at this planning boundary.
enum MapTilePlanner {
    static let zoom = 15
    static let maximumTiles = 40_000
    private static let count = 1 << zoom
    private static let maximumLatitude = 85.05112878

    static func aroundGCJ02(latitudeDeg: Double, longitudeDeg: Double, radiusM: Double) throws -> [MapTileID] {
        guard latitudeDeg.isFinite, longitudeDeg.isFinite,
              abs(latitudeDeg) <= maximumLatitude, abs(longitudeDeg) <= 180,
              radiusM.isFinite, (0 ... 10_000).contains(radiusM)
        else { throw MapPlanningError.invalidCoordinates }
        let point = ChinaCoordinateTransform.gcj02ToWGS84(
            GCJ02Point(longitudeDeg: longitudeDeg, latitudeDeg: latitudeDeg)
        )
        return try aroundWGS84(point, radiusM: radiusM)
    }

    static func boundsWGS84(_ bounds: [Double]) throws -> [MapTileID] {
        guard bounds.count == 4, bounds.allSatisfy(\.isFinite),
              (-180 ... 180).contains(bounds[0]), (-180 ... 180).contains(bounds[2]),
              abs(bounds[1]) <= maximumLatitude, abs(bounds[3]) <= maximumLatitude,
              bounds[0] < bounds[2], bounds[1] < bounds[3]
        else { throw MapPlanningError.invalidCoordinates }
        let topLeft = tile(longitude: bounds[0], latitude: bounds[3])
        let bottomRight = tile(longitude: bounds[2], latitude: bounds[1])
        let columns = bottomRight.x - topLeft.x + 1
        let rows = bottomRight.y - topLeft.y + 1
        guard columns > 0, rows > 0, columns * rows <= maximumTiles else {
            throw MapPlanningError.areaTooLarge
        }
        return (topLeft.y ... bottomRight.y).flatMap { y in
            (topLeft.x ... bottomRight.x).map { x in MapTileID(z: zoom, x: x, y: y) }
        }
    }

    /// Sample each segment densely, including long sparse segments. A 1.2km
    /// square half-width and <=400m sampling conservatively cover an 800m
    /// display window anywhere along the route without downloading its bbox.
    static func corridorGCJ02(_ polyline: [GCJ02Point]) throws -> [MapTileID] {
        guard polyline.count >= 2 else { throw MapPlanningError.missingRoute }
        guard polyline.count <= 100_000,
              polyline.allSatisfy({ $0.latitudeDeg.isFinite && $0.longitudeDeg.isFinite &&
                  abs($0.latitudeDeg) <= maximumLatitude && abs($0.longitudeDeg) <= 180 })
        else { throw MapPlanningError.invalidCoordinates }
        let points = polyline.map(ChinaCoordinateTransform.gcj02ToWGS84)
        var result = Set<MapTileID>()
        var sampleCount = 0
        for index in 1 ..< points.count {
            let a = points[index - 1], b = points[index]
            let latitude = (a.latitudeDeg + b.latitudeDeg) / 2 * .pi / 180
            let distance = hypot((b.latitudeDeg - a.latitudeDeg) * 111_195,
                (b.longitudeDeg - a.longitudeDeg) * 111_195 * cos(latitude))
            guard distance.isFinite, abs(b.longitudeDeg - a.longitudeDeg) <= 180 else {
                throw MapPlanningError.invalidCoordinates
            }
            let steps = max(1, Int(ceil(distance / 400)))
            sampleCount += steps
            guard sampleCount <= 100_000 else { throw MapPlanningError.areaTooLarge }
            for step in 0 ... steps {
                let fraction = Double(step) / Double(steps)
                let point = WGS84Point(
                    longitudeDeg: a.longitudeDeg + (b.longitudeDeg - a.longitudeDeg) * fraction,
                    latitudeDeg: a.latitudeDeg + (b.latitudeDeg - a.latitudeDeg) * fraction
                )
                result.formUnion(try aroundWGS84(point, radiusM: 1_200))
                guard result.count <= maximumTiles else { throw MapPlanningError.areaTooLarge }
            }
        }
        return result.sorted { $0.y == $1.y ? $0.x < $1.x : $0.y < $1.y }
    }

    private static func aroundWGS84(_ point: WGS84Point, radiusM: Double) throws -> [MapTileID] {
        let dy = max(1, radiusM) / 111_195
        let dx = dy / max(0.01, cos(point.latitudeDeg * .pi / 180))
        return try boundsWGS84([
            max(-180, point.longitudeDeg - dx), max(-maximumLatitude, point.latitudeDeg - dy),
            min(180, point.longitudeDeg + dx), min(maximumLatitude, point.latitudeDeg + dy)
        ])
    }

    private static func tile(longitude: Double, latitude: Double) -> (x: Int, y: Int) {
        let x = Int(floor((longitude + 180) / 360 * Double(count)))
        let y = Int(floor((1 - asinh(tan(latitude * .pi / 180)) / .pi) / 2 * Double(count)))
        return (min(count - 1, max(0, x)), min(count - 1, max(0, y)))
    }
}
