import Foundation
import SQLite3
import XCTest
@testable import MOTO_GPS

@MainActor
final class OfflineMapSceneTests: XCTestCase {
    func testFullJinanSQLitePackIsBundledAndQueryable() throws {
        guard let url = Bundle.main.url(forResource: "jinan-v1", withExtension: "sqlite") else {
            XCTFail("jinan-v1.sqlite is missing from the application bundle")
            return
        }
        XCTAssertGreaterThan(try Data(contentsOf: url, options: .mappedIfSafe).count, 1_000_000)
        let repository = try SQLiteOfflineMapSceneIndex(url: url)
        let window = repository.query(
            around: OfflineMapPointE6(latitudeE6: 36_675_246, longitudeE6: 117_128_578),
            radiusM: 500,
            revision: 77
        )
        XCTAssertEqual(window.revision, 77)
        XCTAssertFalse(window.roads.isEmpty)
        XCTAssertFalse(window.buildings.isEmpty)
        XCTAssertLessThanOrEqual(window.roads.count, 24)
        XCTAssertLessThanOrEqual(window.roads.reduce(0) { $0 + $1.points.count }, 192)
        XCTAssertLessThanOrEqual(window.buildings.count, 16)
        XCTAssertLessThanOrEqual(window.buildings.reduce(0) { $0 + $1.points.count }, 128)
    }

    func testBundledOSMSceneDecodesAndCropsRealFeatures() throws {
        let document = try loadDocument()
        XCTAssertEqual(document.roads.count, 24)
        XCTAssertEqual(document.roads.reduce(0) { $0 + $1.points.count }, 192)
        XCTAssertEqual(document.buildings.count, 16)
        XCTAssertLessThanOrEqual(
            document.buildings.reduce(0) { $0 + $1.points.count },
            128
        )
        XCTAssertTrue(document.roads.allSatisfy { $0.osmWayID != nil })
        XCTAssertTrue(document.buildings.allSatisfy { $0.osmWayID != nil })

        let index = InMemoryOfflineMapSceneIndex(document: document)
        let window = index.query(
            around: document.viewOrigin,
            radiusM: 650,
            revision: 9
        )
        XCTAssertEqual(window.revision, 9)
        XCTAssertFalse(window.roads.isEmpty)
        XCTAssertFalse(window.buildings.isEmpty)
        XCTAssertLessThanOrEqual(window.roads.count, 24)
        XCTAssertLessThanOrEqual(window.roads.reduce(0) { $0 + $1.points.count }, 192)
        XCTAssertLessThanOrEqual(window.buildings.count, 16)
        XCTAssertLessThanOrEqual(window.buildings.reduce(0) { $0 + $1.points.count }, 128)
    }

    func testSceneOutsideFixtureCoverageIsHonestEmptyWindow() throws {
        let document = try loadDocument()
        let index = InMemoryOfflineMapSceneIndex(document: document)
        let farAway = OfflineMapPointE6(
            latitudeE6: document.viewOrigin.latitudeE6 - 100_000,
            longitudeE6: document.viewOrigin.longitudeE6
        )
        let window = index.query(around: farAway, radiusM: 650, revision: 2)
        XCTAssertTrue(window.roads.isEmpty)
        XCTAssertTrue(window.buildings.isEmpty)
    }

    func testCoordinatorRefreshesOnlyAfterOneHundredMetres() throws {
        let document = try loadDocument()
        let coordinator = OfflineMapSceneCoordinator(
            index: InMemoryOfflineMapSceneIndex(document: document),
            radiusM: 650,
            refreshDistanceM: 100
        )
        let latitude = Double(document.viewOrigin.latitudeE6) / 1_000_000
        let longitude = Double(document.viewOrigin.longitudeE6) / 1_000_000
        XCTAssertEqual(
            coordinator.sceneIfNeeded(latitudeDeg: latitude, longitudeDeg: longitude)?.revision,
            1
        )
        XCTAssertNil(coordinator.sceneIfNeeded(
            latitudeDeg: latitude + 0.0002,
            longitudeDeg: longitude
        ))
        XCTAssertEqual(
            coordinator.sceneIfNeeded(
                latitudeDeg: latitude + 0.001,
                longitudeDeg: longitude
            )?.revision,
            2
        )
    }

    func testCroppedSceneEncodesInto182ByteBLEFrames() throws {
        let document = try loadDocument()
        let window = InMemoryOfflineMapSceneIndex(document: document).query(
            around: document.viewOrigin,
            radiusM: 650,
            revision: 3
        )
        let codec = MotoBLEProtocolCodec(maximumFrameSize: 182)
        let payload = try codec.encodeMapScenePayload(forTesting: window.makeBLEInput())
        XCTAssertLessThanOrEqual(payload.count, 4_096)
        let frames = try codec.encodeMapScene(window.makeBLEInput())
        XCTAssertGreaterThan(frames.count, 1)
        XCTAssertTrue(frames.allSatisfy { $0.count <= 182 })
        // Frame flags byte: ACK_REQUESTED must be present on every fragment.
        XCTAssertTrue(frames.allSatisfy { $0.count >= 4 && ($0[3] & 0x04) != 0 })
    }

    func testCapacitySelectionUsesVisualPriorityInsteadOfInputOrder() {
        let origin = OfflineMapPointE6(latitudeE6: 36_680_000, longitudeE6: 117_130_000)
        let smallBuilding = [
            point(origin, northM: 200, eastM: 200), point(origin, northM: 200, eastM: 205),
            point(origin, northM: 205, eastM: 205), point(origin, northM: 205, eastM: 200),
        ]
        var roads = (0 ..< 24).map { index in
            OfflineMapRoad(
                osmWayID: Int64(index),
                roadClass: "service",
                points: [
                    point(origin, northM: Double(index), eastM: 10),
                    point(origin, northM: Double(index), eastM: 15),
                ]
            )
        }
        roads.append(OfflineMapRoad(
            osmWayID: 999,
            roadClass: "primary",
            points: [point(origin, northM: 300, eastM: 0), point(origin, northM: 320, eastM: 0)]
        ))
        var buildings = (0 ..< 16).map { index in
            OfflineMapBuilding(
                osmWayID: Int64(index),
                name: nil,
                buildingClass: "generic",
                points: smallBuilding.map {
                    point($0, northM: Double(index * 2), eastM: Double(index * 2))
                }
            )
        }
        buildings.append(OfflineMapBuilding(
            osmWayID: 999,
            name: "large-real-footprint",
            buildingClass: "landmark",
            points: [
                point(origin, northM: 120, eastM: 120), point(origin, northM: 120, eastM: 220),
                point(origin, northM: 220, eastM: 220), point(origin, northM: 220, eastM: 120),
            ]
        ))
        let document = makeDocument(origin: origin, roads: roads, buildings: buildings)
        let window = InMemoryOfflineMapSceneIndex(document: document).query(
            around: origin,
            radiusM: 500,
            revision: 1
        )

        XCTAssertEqual(window.roads.count, 24)
        XCTAssertTrue(window.roads.contains { $0.osmWayID == 999 })
        XCTAssertEqual(window.buildings.count, 16)
        XCTAssertTrue(window.buildings.contains { $0.osmWayID == 999 })
    }

    func testSQLiteRTreeRepositoryReadsTheSameRealOSMFixtureShape() throws {
        let document = try loadDocument()
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("motogps-offline-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: databaseURL) }
        try writeDatabase(at: databaseURL, document: document)

        let repository = try SQLiteOfflineMapSceneIndex(url: databaseURL)
        let window = repository.query(
            around: document.viewOrigin,
            radiusM: 500,
            revision: 42
        )
        XCTAssertEqual(window.revision, 42)
        XCTAssertFalse(window.roads.isEmpty)
        XCTAssertFalse(window.buildings.isEmpty)
        XCTAssertTrue(window.roads.allSatisfy { $0.osmWayID != nil })
        XCTAssertTrue(window.buildings.allSatisfy { $0.osmWayID != nil })
        XCTAssertLessThanOrEqual(window.roads.count, 24)
        XCTAssertLessThanOrEqual(window.buildings.count, 16)
    }

    private func loadDocument() throws -> OfflineMapSceneDocument {
        guard let url = Bundle(for: Self.self).url(
            forResource: "jinan_map_scene_sample",
            withExtension: "json"
        ) else { throw OfflineMapSceneError.resourceMissing }
        return try OfflineMapSceneDocument.decode(Data(contentsOf: url))
    }

    private func makeDocument(
        origin: OfflineMapPointE6,
        roads: [OfflineMapRoad],
        buildings: [OfflineMapBuilding]
    ) -> OfflineMapSceneDocument {
        OfflineMapSceneDocument(
            schemaVersion: 1,
            coordinateSystem: "GCJ-02",
            sceneRevision: 1,
            viewOrigin: origin,
            radiusM: 500,
            roads: roads,
            buildings: buildings,
            source: OfflineMapSource(
                provider: "test",
                licence: "test",
                attributionURL: "",
                retrievedAt: "",
                bboxWGS84: nil
            )
        )
    }

    private func point(
        _ origin: OfflineMapPointE6,
        northM: Double,
        eastM: Double
    ) -> OfflineMapPointE6 {
        let latitudeRadians = Double(origin.latitudeE6) / 1_000_000 * .pi / 180
        return OfflineMapPointE6(
            latitudeE6: origin.latitudeE6 + Int32((northM / 111_195 * 1_000_000).rounded()),
            longitudeE6: origin.longitudeE6 + Int32(
                (eastM / (111_195 * cos(latitudeRadians)) * 1_000_000).rounded()
            )
        )
    }

    private func writeDatabase(at url: URL, document: OfflineMapSceneDocument) throws {
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &database), SQLITE_OK)
        guard let database else { throw SQLiteOfflineMapError.invalidSchema }
        defer { sqlite3_close(database) }
        let schema = """
        PRAGMA user_version=1;
        CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID;
        INSERT INTO metadata VALUES('schema_version','1'),('coordinate_system','GCJ-02');
        CREATE TABLE roads(id INTEGER PRIMARY KEY,osm_way_id INTEGER,class INTEGER NOT NULL,
          min_lat_e6 INTEGER,max_lat_e6 INTEGER,min_lon_e6 INTEGER,max_lon_e6 INTEGER,points BLOB);
        CREATE VIRTUAL TABLE road_rtree USING rtree_i32(id,min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6);
        CREATE TABLE buildings(id INTEGER PRIMARY KEY,osm_way_id INTEGER,name TEXT,class INTEGER NOT NULL,
          min_lat_e6 INTEGER,max_lat_e6 INTEGER,min_lon_e6 INTEGER,max_lon_e6 INTEGER,points BLOB);
        CREATE VIRTUAL TABLE building_rtree USING rtree_i32(id,min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6);
        """
        XCTAssertEqual(sqlite3_exec(database, schema, nil, nil, nil), SQLITE_OK)

        for (index, road) in document.roads.enumerated() {
            try insertFeature(
                database: database,
                table: "roads",
                rtree: "road_rtree",
                id: index + 1,
                osmWayID: road.osmWayID,
                name: nil,
                classValue: roadClassValue(road.roadClass),
                points: road.points
            )
        }
        for (index, building) in document.buildings.enumerated() {
            try insertFeature(
                database: database,
                table: "buildings",
                rtree: "building_rtree",
                id: index + 1,
                osmWayID: building.osmWayID,
                name: building.name,
                classValue: building.buildingClass == "landmark" ? 1 :
                    (building.buildingClass == "parking" ? 2 : 0),
                points: building.points
            )
        }
    }

    private func insertFeature(
        database: OpaquePointer,
        table: String,
        rtree: String,
        id: Int,
        osmWayID: Int64?,
        name: String?,
        classValue: Int32,
        points: [OfflineMapPointE6]
    ) throws {
        let latitudes = points.map(\.latitudeE6)
        let longitudes = points.map(\.longitudeE6)
        let bounds = (latitudes.min()!, latitudes.max()!, longitudes.min()!, longitudes.max()!)
        let nameColumn = table == "buildings" ? ",name" : ""
        let nameValue = table == "buildings" ? ",?9" : ""
        let sql = "INSERT INTO \(table)(id,osm_way_id,class,min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6,points\(nameColumn)) VALUES(?1,?2,?3,?4,?5,?6,?7,?8\(nameValue))"
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(database, sql, -1, &statement, nil), SQLITE_OK)
        guard let statement else { throw SQLiteOfflineMapError.invalidSchema }
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_int(statement, 1, Int32(id))
        if let osmWayID { sqlite3_bind_int64(statement, 2, osmWayID) }
        sqlite3_bind_int(statement, 3, classValue)
        sqlite3_bind_int(statement, 4, bounds.0)
        sqlite3_bind_int(statement, 5, bounds.1)
        sqlite3_bind_int(statement, 6, bounds.2)
        sqlite3_bind_int(statement, 7, bounds.3)
        let blob = encodePoints(points)
        blob.withUnsafeBytes { bytes in
            sqlite3_bind_blob(statement, 8, bytes.baseAddress, Int32(bytes.count), testSQLiteTransient)
        }
        if table == "buildings", let name {
            sqlite3_bind_text(statement, 9, name, -1, testSQLiteTransient)
        }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_DONE)
        let rtreeSQL = "INSERT INTO \(rtree) VALUES(\(id),\(bounds.0),\(bounds.1),\(bounds.2),\(bounds.3))"
        XCTAssertEqual(sqlite3_exec(database, rtreeSQL, nil, nil, nil), SQLITE_OK)
    }

    private func encodePoints(_ points: [OfflineMapPointE6]) -> Data {
        var data = Data()
        for point in points {
            for raw in [UInt32(bitPattern: point.latitudeE6), UInt32(bitPattern: point.longitudeE6)] {
                data.append(UInt8(truncatingIfNeeded: raw))
                data.append(UInt8(truncatingIfNeeded: raw >> 8))
                data.append(UInt8(truncatingIfNeeded: raw >> 16))
                data.append(UInt8(truncatingIfNeeded: raw >> 24))
            }
        }
        return data
    }

    private func roadClassValue(_ value: String) -> Int32 {
        switch value {
        case "motorway": 0
        case "primary": 1
        case "secondary": 2
        case "residential": 3
        case "service": 4
        default: 5
        }
    }
}

private let testSQLiteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
