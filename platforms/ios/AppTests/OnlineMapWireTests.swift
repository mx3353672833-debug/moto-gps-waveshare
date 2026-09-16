import Foundation
import XCTest
@testable import MOTO_GPS

@MainActor
final class OnlineMapWireTests: XCTestCase {
    func testActualShanghaiGatewayTileReachesTheExistingBLESceneCodec() throws {
        // Captured from the deployed gateway on 2026-09-16. This checks the JS
        // response against Swift and the existing round-screen wire budgets.
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "shanghai-online-map-tile", withExtension: "json"))
        let tile = try MapTileDocument.decode(Data(contentsOf: url),
            expectedTile: MapTileID(x: 27440, y: 13389))
        XCTAssertEqual(tile.roads.count, 158)
        XCTAssertEqual(tile.buildings.count, 288)
        let origin = OfflineMapPointE6(latitudeE6: 31_228_458, longitudeE6: 121_478_223)
        let document = OfflineMapSceneDocument(schemaVersion: 1, coordinateSystem: "GCJ-02",
            sceneRevision: 10, viewOrigin: origin, radiusM: 500, roads: tile.roads,
            buildings: tile.buildings, source: tile.source)
        let scene = InMemoryOfflineMapSceneIndex(document: document).query(around: origin, radiusM: 500, revision: 10)
        XCTAssertFalse(scene.roads.isEmpty)
        XCTAssertFalse(scene.buildings.isEmpty)
        XCTAssertLessThanOrEqual(scene.roads.count, 24)
        XCTAssertLessThanOrEqual(scene.buildings.count, 16)
        let frames = try MotoBLEProtocolCodec(maximumFrameSize: 182).encodeMapScene(scene.makeBLEInput())
        XCTAssertFalse(frames.isEmpty)
        XCTAssertTrue(frames.allSatisfy { $0.count <= 182 })
    }
}
