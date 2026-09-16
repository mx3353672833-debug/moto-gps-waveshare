import Foundation
import XCTest
@testable import MOTO_GPS

private actor MapTileTestLoader {
    private(set) var calls = 0
    let failingTile: MapTileID?

    init(failingTile: MapTileID? = nil) { self.failingTile = failingTile }

    func load(_ tile: MapTileID) async throws -> Data {
        calls += 1
        try await Task.sleep(nanoseconds: 30_000_000)
        if tile == failingTile { throw URLError(.notConnectedToInternet) }
        return try mapTileTestData(tile)
    }
}

private func mapTileTestData(_ tile: MapTileID, roadCount: Int = 1) throws -> Data {
    let roads = (0 ..< roadCount).map { index in
        OfflineMapRoad(osmWayID: Int64(index), roadClass: "residential", points: [
            OfflineMapPointE6(latitudeE6: 40_000_000 + Int32(index), longitudeE6: -73_000_000),
            OfflineMapPointE6(latitudeE6: 40_000_100 + Int32(index), longitudeE6: -73_000_000),
        ])
    }
    return try JSONEncoder().encode(MapTileDocument(
        schemaVersion: 1, coordinateSystem: "GCJ-02", tile: tile, roads: roads, buildings: [],
        source: OfflineMapSource(provider: "test-fixture", licence: "ODbL-1.0",
                                 attributionURL: "https://www.openstreetmap.org/copyright",
                                 retrievedAt: "2026-09-16", bboxWGS84: nil)))
}

@MainActor
final class SurroundingMapStoreTests: XCTestCase {
    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("moto-map-\(UUID().uuidString)")
    }

    func testFullTileAcceptsMoreThanBLECapacityAndRejectsWrongTile() throws {
        let tile = MapTileID(x: 1, y: 2)
        let data = try mapTileTestData(tile, roadCount: 30)
        XCTAssertEqual(try MapTileDocument.decode(data, expectedTile: tile).roads.count, 30)
        XCTAssertThrowsError(try MapTileDocument.decode(data, expectedTile: MapTileID(x: 2, y: 2)))
        XCTAssertThrowsError(try MapTileDocument.decode(Data(count: MapTileDocument.maximumBytes + 1), expectedTile: tile))
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var roads = try XCTUnwrap(object["roads"] as? [[String: Any]])
        roads[0]["points_e6"] = [[91_000_000, -73_000_000], [40_000_100, -73_000_000]]
        object["roads"] = roads
        XCTAssertThrowsError(try MapTileDocument.decode(JSONSerialization.data(withJSONObject: object), expectedTile: tile))
    }

    func testConcurrentConsumersShareOneTileRequest() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let loader = MapTileTestLoader()
        let cache = MapTileCache(root: root, loader: { try await loader.load($0) })
        let tile = MapTileID(x: 1, y: 2)
        async let first = cache.fetch(tile, refresh: true)
        async let second = cache.fetch(tile, refresh: true)
        _ = try await (first, second)
        let calls = await loader.calls
        XCTAssertEqual(calls, 1)
    }

    func testIncompletePackPersistsAndResumeOnlyDownloadsMissingTile() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let first = MapTileID(x: 1, y: 2)
        let second = MapTileID(x: 2, y: 2)
        let loader = MapTileTestLoader(failingTile: second)
        let cache = MapTileCache(root: root, loader: { try await loader.load($0) })
        let id = try await cache.createPack(name: "测试路线", detail: "路线走廊", tiles: [first, second])
        _ = try await cache.fetch(first, refresh: false)
        do {
            _ = try await cache.fetch(second, refresh: false)
            XCTFail("A failed tile must leave the pack incomplete")
        } catch {}
        let before = try await cache.packs()
        XCTAssertEqual(before.count, 1)
        XCTAssertFalse(before[0].isComplete)
        let resumedLoader = MapTileTestLoader()
        let resumed = MapTileCache(root: root, loader: { try await resumedLoader.load($0) })
        for tile in try await resumed.tiles(for: id) { _ = try await resumed.fetch(tile, refresh: false) }
        let after = try await resumed.packs()
        let calls = await resumedLoader.calls
        XCTAssertTrue(after[0].isComplete)
        XCTAssertEqual(calls, 1)
    }

    func testDeletingOnePackKeepsSharedTilesAndTemporaryClearKeepsPinnedTiles() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = MapTileCache(root: root, loader: { try mapTileTestData($0) })
        let shared = MapTileID(x: 1, y: 2)
        let temporary = MapTileID(x: 3, y: 2)
        let first = try await cache.createPack(name: "城市", detail: "区域", tiles: [shared])
        let second = try await cache.createPack(name: "路线", detail: "走廊", tiles: [shared])
        _ = try await cache.fetch(shared, refresh: false)
        _ = try await cache.fetch(temporary, refresh: true)
        try await cache.deletePack(id: first)
        try await cache.clearTemporaryCache()
        let retained = try await cache.cached(shared)
        let cleared = try await cache.cached(temporary)
        XCTAssertNotNil(retained)
        XCTAssertNil(cleared)
        try await cache.deletePack(id: second)
        let removed = try await cache.cached(shared)
        XCTAssertNil(removed)
    }

    func testDuplicateDownloadReusesPackAndTemporaryCountLimitNeverEvictsPinnedTiles() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = MapTileCache(root: root, cacheTileLimit: 2, loader: { try mapTileTestData($0) })
        let pinned = MapTileID(x: 1, y: 2)
        let first = try await cache.createPack(name: "城市", detail: "区域", tiles: [pinned])
        let repeated = try await cache.createPack(name: "城市", detail: "区域更新", tiles: [pinned, pinned])
        XCTAssertEqual(first, repeated)
        _ = try await cache.fetch(pinned, refresh: true)
        for x in 2 ... 4 { _ = try await cache.fetch(MapTileID(x: x, y: 2), refresh: true) }
        let packs = try await cache.packs()
        let pinnedData = try await cache.cached(pinned)
        let oldestTemporary = try await cache.cached(MapTileID(x: 2, y: 2))
        XCTAssertEqual(packs.count, 1)
        XCTAssertNotNil(pinnedData)
        XCTAssertNil(oldestTemporary)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("tiles").path).count, 3)
    }

    func testCorruptManifestIsQuarantinedAndDoesNotDisableOnlineFetch() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let tile = MapTileID(x: 1, y: 2)
        let cache = MapTileCache(root: root, loader: { try mapTileTestData($0) })
        _ = try await cache.fetch(tile, refresh: true)
        try Data("broken manifest".utf8).write(to: root.appendingPathComponent("packs.json"))
        let reopened = MapTileCache(root: root, loader: { try mapTileTestData($0) })
        let restored = try await reopened.cached(tile)
        let packs = try await reopened.packs()
        let notice = await reopened.takeRecoveryNotice()
        XCTAssertNotNil(restored)
        XCTAssertTrue(packs.isEmpty)
        XCTAssertNotNil(notice)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.path)
            .contains { $0.hasPrefix("packs.corrupt-") })
        _ = try await reopened.fetch(MapTileID(x: 3, y: 2), refresh: true)
    }

    func testCorruptTileMakesPackIncompleteAndCanBeReplaced() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let tile = MapTileID(x: 1, y: 2)
        let cache = MapTileCache(root: root, loader: { try mapTileTestData($0) })
        _ = try await cache.createPack(name: "城市", detail: "区域", tiles: [tile])
        _ = try await cache.fetch(tile, refresh: false)
        try Data("broken tile".utf8).write(to: root.appendingPathComponent("tiles").appendingPathComponent(tile.filename))
        let corrupt = try await cache.cached(tile)
        let incomplete = try await cache.packs()
        XCTAssertNil(corrupt)
        XCTAssertFalse(incomplete[0].isComplete)
        _ = try await cache.fetch(tile, refresh: false)
        let repaired = try await cache.packs()
        XCTAssertTrue(repaired[0].isComplete)
    }

    func testPinnedQuotaRejectsNewTileWithoutLosingExistingTile() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let first = MapTileID(x: 1, y: 2)
        let second = MapTileID(x: 2, y: 2)
        let limit = Int64(try mapTileTestData(first).count + 10)
        let cache = MapTileCache(root: root, packLimit: limit, loader: { try mapTileTestData($0) })
        _ = try await cache.createPack(name: "城市", detail: "区域", tiles: [first, second])
        _ = try await cache.fetch(first, refresh: false)
        do {
            _ = try await cache.fetch(second, refresh: false)
            XCTFail("The pinned quota must be enforced before writing")
        } catch {}
        let retained = try await cache.cached(first)
        let packs = try await cache.packs()
        XCTAssertNotNil(retained)
        XCTAssertFalse(packs[0].isComplete)
    }

    func testResetDiscardsLateOnlineWindowAndKeepsRevisionsMonotonic() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let firstScene = expectation(description: "Immediate offline window")
        let store = SurroundingMapStore(baseURL: URL(string: "https://example.invalid/")!,
                                        storageURL: root, loader: { tile in
            try await Task.sleep(nanoseconds: 150_000_000)
            return try mapTileTestData(tile)
        })
        var revisions: [UInt32] = []
        store.onScene = { scene in
            revisions.append(scene.revision)
            if revisions.count == 1 { firstScene.fulfill() }
        }
        store.update(latitudeDeg: 40, longitudeDeg: -73)
        await fulfillment(of: [firstScene], timeout: 2)
        store.reset()
        let countAtReset = revisions.count
        try await Task.sleep(nanoseconds: 350_000_000)
        XCTAssertEqual(revisions.count, countAtReset)
        let nextScene = expectation(description: "New session window")
        store.onScene = { scene in
            XCTAssertGreaterThan(scene.revision, revisions.last ?? 0)
            store.onScene = nil
            nextScene.fulfill()
        }
        store.update(latitudeDeg: 40, longitudeDeg: -73)
        await fulfillment(of: [nextScene], timeout: 2)
        store.reset()
    }

    func testOnlineFailureOutsideBundledCoverageDoesNotClaimOfflineCoverage() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let finished = expectation(description: "Failed online request emits fallback")
        let store = SurroundingMapStore(baseURL: URL(string: "https://example.invalid/")!,
                                        storageURL: root, loader: { _ in throw URLError(.notConnectedToInternet) })
        var scenes = 0
        store.onScene = { scene in
            scenes += 1
            XCTAssertTrue(scene.roads.isEmpty)
            XCTAssertTrue(scene.buildings.isEmpty)
            if scenes == 2 { finished.fulfill() }
        }
        store.update(latitudeDeg: 40, longitudeDeg: -73)
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertEqual(store.statusText, "在线地图不可用 · 此处无离线地图")
        store.reset()
    }

    func testFreshViewportCacheIsReusedWithoutClaimingOnlineConnectivity() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let loader = MapTileTestLoader()
        let cache = MapTileCache(root: root, loader: { try await loader.load($0) })
        for tile in try MapTilePlanner.aroundGCJ02(latitudeDeg: 40, longitudeDeg: -73, radiusM: 500) {
            _ = try await cache.fetch(tile, refresh: true)
        }
        let cachedRequestCount = await loader.calls
        let finished = expectation(description: "Cached viewport ready")
        let store = SurroundingMapStore(baseURL: URL(string: "https://example.invalid/")!,
                                        storageURL: root, loader: { try await loader.load($0) })
        var scenes = 0
        store.onScene = { _ in
            scenes += 1
            if scenes == 2 { finished.fulfill() }
        }
        store.update(latitudeDeg: 40, longitudeDeg: -73)
        await fulfillment(of: [finished], timeout: 2)
        let finalRequestCount = await loader.calls
        XCTAssertEqual(finalRequestCount, cachedRequestCount)
        XCTAssertEqual(store.statusText, "周边地图已就绪 · 使用缓存")
        store.reset()
    }

    func testExpiredCompleteCacheSurvivesServerFailureWithHonestOfflineStatus() async throws {
        let root = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = MapTileCache(root: root, loader: { try mapTileTestData($0) })
        for tile in try MapTilePlanner.aroundGCJ02(latitudeDeg: 40, longitudeDeg: -73, radiusM: 500) {
            _ = try await cache.fetch(tile, refresh: true)
            let path = root.appendingPathComponent("tiles").appendingPathComponent(tile.filename).path
            try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(-8 * 24 * 60 * 60)],
                                                  ofItemAtPath: path)
        }
        let finished = expectation(description: "Stale cache remains usable after HTTP 500")
        let store = SurroundingMapStore(baseURL: URL(string: "https://example.invalid/")!,
                                        storageURL: root, loader: { _ in throw SurroundingMapError.httpStatus(500) })
        var scenes = 0
        store.onScene = { scene in
            scenes += 1
            XCTAssertFalse(scene.roads.isEmpty)
            if scenes == 2 { finished.fulfill() }
        }
        store.update(latitudeDeg: 40, longitudeDeg: -73)
        await fulfillment(of: [finished], timeout: 2)
        XCTAssertEqual(store.statusText, "离线地图 · 当前区域已缓存")
        store.reset()
    }
}
