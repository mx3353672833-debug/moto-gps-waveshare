import Foundation

struct OfflineMapPointE6: Codable, Equatable, Hashable, Sendable {
    let latitudeE6: Int32
    let longitudeE6: Int32

    init(latitudeE6: Int32, longitudeE6: Int32) {
        self.latitudeE6 = latitudeE6
        self.longitudeE6 = longitudeE6
    }

    init(from decoder: Decoder) throws {
        var values = try decoder.unkeyedContainer()
        latitudeE6 = try values.decode(Int32.self)
        longitudeE6 = try values.decode(Int32.self)
        guard values.isAtEnd else {
            throw DecodingError.dataCorruptedError(
                in: values,
                debugDescription: "MapScene point must contain exactly latitudeE6 and longitudeE6"
            )
        }
    }

    func encode(to encoder: Encoder) throws {
        var values = encoder.unkeyedContainer()
        try values.encode(latitudeE6)
        try values.encode(longitudeE6)
    }
}

struct OfflineMapRoad: Codable, Equatable, Sendable {
    let osmWayID: Int64?
    let roadClass: String
    let points: [OfflineMapPointE6]

    private enum CodingKeys: String, CodingKey {
        case osmWayID = "osm_way_id"
        case roadClass = "class"
        case points = "points_e6"
    }
}

struct OfflineMapBuilding: Codable, Equatable, Sendable {
    let osmWayID: Int64?
    let name: String?
    let buildingClass: String
    let points: [OfflineMapPointE6]

    private enum CodingKeys: String, CodingKey {
        case osmWayID = "osm_way_id"
        case name
        case buildingClass = "class"
        case points = "points_e6"
    }
}

struct OfflineMapSource: Codable, Equatable, Sendable {
    let provider: String
    let licence: String
    let attributionURL: String
    let retrievedAt: String
    let bboxWGS84: [Double]?

    private enum CodingKeys: String, CodingKey {
        case provider
        case licence
        case attributionURL = "attribution_url"
        case retrievedAt = "retrieved_at"
        case bboxWGS84 = "bbox_wgs84"
    }
}

struct OfflineMapSceneDocument: Codable, Equatable, Sendable {
    let schemaVersion: Int
    let coordinateSystem: String
    let sceneRevision: UInt32
    let viewOrigin: OfflineMapPointE6
    let radiusM: UInt16
    let roads: [OfflineMapRoad]
    let buildings: [OfflineMapBuilding]
    let source: OfflineMapSource

    private enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case coordinateSystem = "coordinate_system"
        case sceneRevision = "scene_revision"
        case viewOrigin = "view_origin_e6"
        case radiusM = "radius_m"
        case roads
        case buildings
        case source
    }

    static func decode(_ data: Data) throws -> Self {
        let value = try JSONDecoder().decode(Self.self, from: data)
        guard value.schemaVersion == 1, value.coordinateSystem == "GCJ-02" else {
            throw OfflineMapSceneError.unsupportedFormat
        }
        guard value.roads.count <= 24,
              value.roads.reduce(0, { $0 + $1.points.count }) <= 192,
              value.buildings.count <= 16,
              value.buildings.reduce(0, { $0 + $1.points.count }) <= 128,
              value.roads.allSatisfy({ $0.points.count >= 2 }),
              value.buildings.allSatisfy({
                  $0.points.count >= 3 && $0.points.first != $0.points.last
              })
        else { throw OfflineMapSceneError.capacityExceeded }
        return value
    }
}

enum OfflineMapSceneError: Error, Equatable {
    case resourceMissing
    case unsupportedFormat
    case capacityExceeded
}

struct OfflineMapSceneWindow: Equatable, Sendable {
    let revision: UInt32
    let origin: OfflineMapPointE6
    let radiusM: UInt16
    let roads: [OfflineMapRoad]
    let buildings: [OfflineMapBuilding]
}

protocol OfflineMapSceneQuerying: Sendable {
    func query(
        around origin: OfflineMapPointE6,
        radiusM: UInt16,
        revision: UInt32
    ) -> OfflineMapSceneWindow
}

/// A replaceable spatial-index implementation for the bundled EVT fixture.
/// It deliberately keeps no SQLite dependency in the app target.  The full
/// Jinan pack can replace this behind OfflineMapSceneQuerying with SQLite
/// R-tree without changing BLE or AppModel code.
struct InMemoryOfflineMapSceneIndex: OfflineMapSceneQuerying {
    private struct Bounds: Sendable {
        let minimumLatitudeE6: Int32
        let maximumLatitudeE6: Int32
        let minimumLongitudeE6: Int32
        let maximumLongitudeE6: Int32

        init(points: [OfflineMapPointE6]) {
            minimumLatitudeE6 = points.map(\.latitudeE6).min() ?? 0
            maximumLatitudeE6 = points.map(\.latitudeE6).max() ?? 0
            minimumLongitudeE6 = points.map(\.longitudeE6).min() ?? 0
            maximumLongitudeE6 = points.map(\.longitudeE6).max() ?? 0
        }

        func intersects(origin: OfflineMapPointE6, radiusM: Double) -> Bool {
            let metresPerLatitudeE6 = 111_195.0 / 1_000_000
            let latitudeRadians = Double(origin.latitudeE6) / 1_000_000 * .pi / 180
            let metresPerLongitudeE6 = metresPerLatitudeE6 * cos(latitudeRadians)
            let nearestLatitude = min(max(origin.latitudeE6, minimumLatitudeE6), maximumLatitudeE6)
            let nearestLongitude = min(max(origin.longitudeE6, minimumLongitudeE6), maximumLongitudeE6)
            let northM = Double(nearestLatitude - origin.latitudeE6) * metresPerLatitudeE6
            let eastM = Double(nearestLongitude - origin.longitudeE6) * metresPerLongitudeE6
            return hypot(northM, eastM) <= radiusM
        }
    }

    private struct IndexedRoad: Sendable {
        let value: OfflineMapRoad
        let bounds: Bounds
    }

    private struct IndexedBuilding: Sendable {
        let value: OfflineMapBuilding
        let bounds: Bounds
    }

    private let roads: [IndexedRoad]
    private let buildings: [IndexedBuilding]

    init(document: OfflineMapSceneDocument) {
        roads = document.roads.map { IndexedRoad(value: $0, bounds: Bounds(points: $0.points)) }
        buildings = document.buildings.map {
            IndexedBuilding(value: $0, bounds: Bounds(points: $0.points))
        }
    }

    func query(
        around origin: OfflineMapPointE6,
        radiusM: UInt16,
        revision: UInt32
    ) -> OfflineMapSceneWindow {
        let radius = Double(max(500, min(800, radiusM)))
        let roadCandidates = roads
            .filter { $0.bounds.intersects(origin: origin, radiusM: radius) }
            .flatMap { indexed in
                Self.clippedRuns(indexed.value, origin: origin, radiusM: radius).map { run in
                    (
                        road: run,
                        importance: Self.roadImportance(run.roadClass),
                        distanceM: Self.minimumDistanceM(run.points, from: origin)
                    )
                }
            }
            .sorted {
                if $0.importance != $1.importance { return $0.importance < $1.importance }
                if $0.distanceM != $1.distanceM { return $0.distanceM < $1.distanceM }
                return ($0.road.osmWayID ?? .max) < ($1.road.osmWayID ?? .max)
            }

        var selectedRoads: [OfflineMapRoad] = []
        var roadPointCount = 0
        for candidate in roadCandidates {
            guard selectedRoads.count < 24 else { break }
            guard roadPointCount + candidate.road.points.count <= 192 else { continue }
            selectedRoads.append(candidate.road)
            roadPointCount += candidate.road.points.count
        }

        let buildingCandidates = buildings
            .filter { $0.bounds.intersects(origin: origin, radiusM: radius) }
            .filter {
                Self.polygonIntersects($0.value.points, origin: origin, radiusM: radius)
            }
            .map { indexed in
                let distance = Self.minimumDistanceM(indexed.value.points, from: origin)
                let area = Self.polygonAreaM2(indexed.value.points, around: origin)
                // Larger footprints remain visible a little farther away, while
                // nearby buildings still dominate the tiny round viewport.
                let visualScore = distance - min(150, sqrt(area) * 0.75)
                return (building: indexed.value, visualScore: visualScore, areaM2: area)
            }
            .sorted {
                if $0.visualScore != $1.visualScore { return $0.visualScore < $1.visualScore }
                if $0.areaM2 != $1.areaM2 { return $0.areaM2 > $1.areaM2 }
                return ($0.building.osmWayID ?? .max) < ($1.building.osmWayID ?? .max)
            }

        var selectedBuildings: [OfflineMapBuilding] = []
        var buildingPointCount = 0
        for candidate in buildingCandidates {
            guard selectedBuildings.count < 16 else { break }
            guard buildingPointCount + candidate.building.points.count <= 128 else { continue }
            selectedBuildings.append(candidate.building)
            buildingPointCount += candidate.building.points.count
        }

        return OfflineMapSceneWindow(
            revision: revision,
            origin: origin,
            radiusM: UInt16(radius),
            roads: selectedRoads,
            buildings: selectedBuildings
        )
    }

    private static func clippedRuns(
        _ road: OfflineMapRoad,
        origin: OfflineMapPointE6,
        radiusM: Double
    ) -> [OfflineMapRoad] {
        guard road.points.count >= 2 else { return [] }
        var result: [OfflineMapRoad] = []
        var run: [OfflineMapPointE6] = []
        for index in 1 ..< road.points.count {
            let start = road.points[index - 1]
            let end = road.points[index]
            if segmentDistanceM(origin, start, end) <= radiusM {
                if run.isEmpty { run.append(start) }
                if run.last != end { run.append(end) }
            } else if run.count >= 2 {
                result.append(OfflineMapRoad(
                    osmWayID: road.osmWayID,
                    roadClass: road.roadClass,
                    points: run
                ))
                run.removeAll(keepingCapacity: true)
            } else {
                run.removeAll(keepingCapacity: true)
            }
        }
        if run.count >= 2 {
            result.append(OfflineMapRoad(
                osmWayID: road.osmWayID,
                roadClass: road.roadClass,
                points: run
            ))
        }
        return result
    }

    private static func polygonIntersects(
        _ points: [OfflineMapPointE6],
        origin: OfflineMapPointE6,
        radiusM: Double
    ) -> Bool {
        guard points.count >= 3 else { return false }
        if points.contains(where: { distanceM(origin, $0) <= radiusM }) { return true }
        for index in points.indices {
            if segmentDistanceM(origin, points[index], points[(index + 1) % points.count]) <= radiusM {
                return true
            }
        }
        return pointInPolygon(origin, points)
    }

    private static func roadImportance(_ roadClass: String) -> Int {
        switch roadClass {
        case "primary": 0
        case "secondary": 1
        case "motorway": 2
        case "residential": 3
        case "service": 4
        default: 5
        }
    }

    private static func minimumDistanceM(
        _ points: [OfflineMapPointE6],
        from origin: OfflineMapPointE6
    ) -> Double {
        guard !points.isEmpty else { return .infinity }
        if points.count == 1 { return distanceM(origin, points[0]) }
        return (1 ..< points.count).reduce(.infinity) { minimum, index in
            min(minimum, segmentDistanceM(origin, points[index - 1], points[index]))
        }
    }

    private static func polygonAreaM2(
        _ points: [OfflineMapPointE6],
        around origin: OfflineMapPointE6
    ) -> Double {
        guard points.count >= 3 else { return 0 }
        var twiceArea = 0.0
        for index in points.indices {
            let (x1, y1) = localMetres(points[index], around: origin)
            let (x2, y2) = localMetres(points[(index + 1) % points.count], around: origin)
            twiceArea += x1 * y2 - x2 * y1
        }
        return abs(twiceArea) / 2
    }

    private static func pointInPolygon(
        _ point: OfflineMapPointE6,
        _ polygon: [OfflineMapPointE6]
    ) -> Bool {
        var inside = false
        var previous = polygon.last!
        for current in polygon {
            let crosses = (current.latitudeE6 > point.latitudeE6) !=
                (previous.latitudeE6 > point.latitudeE6)
            if crosses {
                let longitude = Double(previous.longitudeE6 - current.longitudeE6) *
                    Double(point.latitudeE6 - current.latitudeE6) /
                    Double(previous.latitudeE6 - current.latitudeE6) +
                    Double(current.longitudeE6)
                if Double(point.longitudeE6) < longitude { inside.toggle() }
            }
            previous = current
        }
        return inside
    }

    private static func segmentDistanceM(
        _ point: OfflineMapPointE6,
        _ start: OfflineMapPointE6,
        _ end: OfflineMapPointE6
    ) -> Double {
        let (px, py) = localMetres(point, around: point)
        let (ax, ay) = localMetres(start, around: point)
        let (bx, by) = localMetres(end, around: point)
        let dx = bx - ax
        let dy = by - ay
        let denominator = dx * dx + dy * dy
        let ratio = denominator == 0 ? 0 :
            max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / denominator))
        return hypot(px - (ax + ratio * dx), py - (ay + ratio * dy))
    }

    private static func distanceM(
        _ lhs: OfflineMapPointE6,
        _ rhs: OfflineMapPointE6
    ) -> Double {
        let (x, y) = localMetres(lhs, around: rhs)
        return hypot(x, y)
    }

    private static func localMetres(
        _ point: OfflineMapPointE6,
        around origin: OfflineMapPointE6
    ) -> (Double, Double) {
        let metresPerLatitudeE6 = 111_195.0 / 1_000_000
        let latitudeRadians = Double(origin.latitudeE6) / 1_000_000 * .pi / 180
        let metresPerLongitudeE6 = metresPerLatitudeE6 * cos(latitudeRadians)
        return (
            Double(point.longitudeE6 - origin.longitudeE6) * metresPerLongitudeE6,
            Double(point.latitudeE6 - origin.latitudeE6) * metresPerLatitudeE6
        )
    }
}

@MainActor
final class OfflineMapSceneCoordinator {
    private let index: any OfflineMapSceneQuerying
    private let radiusM: UInt16
    private let refreshDistanceM: Double
    private var lastOrigin: OfflineMapPointE6?
    private var revision: UInt32 = 0

    init(index: any OfflineMapSceneQuerying, radiusM: UInt16 = 500, refreshDistanceM: Double = 100) {
        self.index = index
        self.radiusM = max(500, min(800, radiusM))
        self.refreshDistanceM = max(25, refreshDistanceM)
    }

    convenience init(bundle: Bundle = .main) throws {
        let databaseName = "jinan-v1"
        if let supportRoot = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first {
            let downloadedURL = supportRoot
                .appendingPathComponent("OfflineMaps", isDirectory: true)
                .appendingPathComponent("\(databaseName).sqlite")
            if FileManager.default.fileExists(atPath: downloadedURL.path) {
                self.init(index: try SQLiteOfflineMapSceneIndex(url: downloadedURL))
                return
            }
        }
        if let bundledDatabase = bundle.url(forResource: databaseName, withExtension: "sqlite") {
            self.init(index: try SQLiteOfflineMapSceneIndex(url: bundledDatabase))
            return
        }
        guard let url = bundle.url(
            forResource: "jinan_map_scene_sample",
            withExtension: "json"
        ) else { throw OfflineMapSceneError.resourceMissing }
        let document = try OfflineMapSceneDocument.decode(Data(contentsOf: url))
        self.init(index: InMemoryOfflineMapSceneIndex(document: document))
    }

    func sceneIfNeeded(latitudeDeg: Double, longitudeDeg: Double) -> OfflineMapSceneWindow? {
        guard latitudeDeg.isFinite, longitudeDeg.isFinite else { return nil }
        let origin = OfflineMapPointE6(
            latitudeE6: Int32(clamping: Int64((latitudeDeg * 1_000_000).rounded())),
            longitudeE6: Int32(clamping: Int64((longitudeDeg * 1_000_000).rounded()))
        )
        if let lastOrigin,
           Self.distanceM(lastOrigin, origin) < refreshDistanceM {
            return nil
        }
        revision &+= 1
        if revision == 0 { revision = 1 }
        lastOrigin = origin
        return index.query(around: origin, radiusM: radiusM, revision: revision)
    }

    func reset() {
        lastOrigin = nil
    }

    private static func distanceM(_ lhs: OfflineMapPointE6, _ rhs: OfflineMapPointE6) -> Double {
        let latitudeRadians = Double(rhs.latitudeE6) / 1_000_000 * .pi / 180
        let northM = Double(lhs.latitudeE6 - rhs.latitudeE6) * 111_195 / 1_000_000
        let eastM = Double(lhs.longitudeE6 - rhs.longitudeE6) *
            111_195 * cos(latitudeRadians) / 1_000_000
        return hypot(eastM, northM)
    }
}

extension OfflineMapSceneWindow {
    func makeBLEInput() -> MotoBLEMapSceneInput {
        let input = MotoBLEMapSceneInput()
        input.sceneRevision = revision
        input.originLatitudeE6 = origin.latitudeE6
        input.originLongitudeE6 = origin.longitudeE6
        input.radiusM = radiusM
        input.roads = roads.map { road in
            let value = MotoBLEMapRoadInput()
            value.className = road.roadClass
            value.points = road.points.map { point in
                let value = MotoBLEMapPointInput()
                value.latitudeE6 = point.latitudeE6
                value.longitudeE6 = point.longitudeE6
                return value
            }
            return value
        }
        input.buildings = buildings.map { building in
            let value = MotoBLEMapBuildingInput()
            value.className = building.buildingClass
            value.points = building.points.map { point in
                let value = MotoBLEMapPointInput()
                value.latitudeE6 = point.latitudeE6
                value.longitudeE6 = point.longitudeE6
                return value
            }
            return value
        }
        return input
    }
}
