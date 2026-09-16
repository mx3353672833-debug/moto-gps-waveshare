import Foundation
import MotoNavigationCore
import XCTest
@testable import MOTO_GPS

@MainActor
final class MapTilePlannerTests: XCTestCase {
    func testKnownGCJ02FixUsesTheSameTilesAsItsWGS84Location() throws {
        // Pairs verified through the independent backend coordinate transform;
        // z/x/y also match the real Protomaps samples documented on 2026-09-16.
        let pairs: [(gcj: GCJ02Point, wgs: WGS84Point, tile: MapTileID)] = [
            (GCJ02Point(longitudeDeg: 117.1261301, latitudeDeg: 36.6704414),
             WGS84Point(longitudeDeg: 117.12, latitudeDeg: 36.67),
             MapTileID(z: 15, x: 27044, y: 12791)),
            (GCJ02Point(longitudeDeg: 121.4782231, latitudeDeg: 31.2284577),
             WGS84Point(longitudeDeg: 121.4737, latitudeDeg: 31.2304),
             MapTileID(z: 15, x: 27440, y: 13389)),
        ]

        for pair in pairs {
            let onlineTiles = try MapTilePlanner.aroundGCJ02(
                latitudeDeg: pair.gcj.latitudeDeg, longitudeDeg: pair.gcj.longitudeDeg, radiusM: 0
            )
            let cityTiles = try MapTilePlanner.boundsWGS84([
                pair.wgs.longitudeDeg - 0.000001, pair.wgs.latitudeDeg - 0.000001,
                pair.wgs.longitudeDeg + 0.000001, pair.wgs.latitudeDeg + 0.000001,
            ])
            XCTAssertEqual(onlineTiles, [pair.tile])
            XCTAssertEqual(Set(onlineTiles), Set(cityTiles))
        }
    }

    func testSparseIntercityRouteIncludesIntermediateEightHundredMetreWindows() throws {
        // Deliberately only two vertices over roughly 300 km: a downloader that
        // visits vertices alone will miss all of the intermediate checkpoints.
        let tiles = Set(try MapTilePlanner.corridorGCJ02(jinanToQingdao))
        let start = WGS84Point(longitudeDeg: 117.01, latitudeDeg: 36.65)
        let finish = WGS84Point(longitudeDeg: 120.38, latitudeDeg: 36.07)

        for fraction in [0.0, 0.123, 0.25, 0.501, 0.75, 0.937, 1.0] {
            let longitude = start.longitudeDeg + (finish.longitudeDeg - start.longitudeDeg) * fraction
            let latitude = start.latitudeDeg + (finish.latitudeDeg - start.latitudeDeg) * fraction
            let north = 800.0 / 111_195
            let east = north / cos(latitude * .pi / 180)
            for offset in [(0.0, 0.0), (east, 0), (-east, 0), (0, north), (0, -north)] {
                let expected = webMercatorTile(longitude: longitude + offset.0, latitude: latitude + offset.1)
                XCTAssertTrue(tiles.contains(expected), "Missing route window at fraction \(fraction), offset \(offset)")
            }
        }
    }

    func testIntercityDownloadDoesNotFillTheRectangleBetweenCities() throws {
        let corridor = try MapTilePlanner.corridorGCJ02(jinanToQingdao)
        let cityRectangle = try MapTilePlanner.boundsWGS84([117.01, 36.07, 120.38, 36.65])

        XCTAssertLessThan(corridor.count, cityRectangle.count / 5)
        XCTAssertEqual(corridor.count, Set(corridor).count, "Overlapping route windows must not duplicate downloads")
        XCTAssertFalse(corridor.contains(webMercatorTile(longitude: 120.35, latitude: 36.62)))
        XCTAssertFalse(corridor.contains(webMercatorTile(longitude: 117.04, latitude: 36.10)))
    }

    func testInvalidCityBoundsFailBeforePlanningDownloads() {
        let invalidBounds: [[Double]] = [
            [117, .nan, 118, 37], [117, 36, .infinity, 37],
            [118, 36, 117, 37], [117, 37, 118, 36],
            [117, 36, 117, 37], [117, 36, 118],
            [-181, 36, 118, 37], [117, -86, 118, 37],
        ]
        for bounds in invalidBounds {
            XCTAssertThrowsError(try MapTilePlanner.boundsWGS84(bounds)) { error in
                guard case MapPlanningError.invalidCoordinates = error else {
                    return XCTFail("Expected invalid coordinates, got \(error)")
                }
            }
        }
    }

    func testHugeCityBoundsAreRejectedInsteadOfAllocatingAMassiveDownload() {
        XCTAssertThrowsError(try MapTilePlanner.boundsWGS84([-180, -80, 180, 80])) { error in
            guard case MapPlanningError.areaTooLarge = error else {
                return XCTFail("Expected area limit, got \(error)")
            }
        }
    }

    func testInvalidLiveFixesAndRadiusAreRejected() {
        for fix in [(Double.nan, 117.0, 800.0), (36.0, Double.infinity, 800.0),
                    (36.0, 117.0, Double.nan), (36.0, 117.0, -1.0),
                    (36.0, 117.0, 10_001.0), (90.0, 117.0, 800.0)] {
            XCTAssertThrowsError(try MapTilePlanner.aroundGCJ02(
                latitudeDeg: fix.0, longitudeDeg: fix.1, radiusM: fix.2
            )) { error in
                guard case MapPlanningError.invalidCoordinates = error else {
                    return XCTFail("Expected invalid coordinates, got \(error)")
                }
            }
        }
    }

    func testMissingAndMalformedRoutesCannotStartDownloads() {
        XCTAssertThrowsError(try MapTilePlanner.corridorGCJ02([])) { error in
            guard case MapPlanningError.missingRoute = error else {
                return XCTFail("Expected missing route, got \(error)")
            }
        }
        XCTAssertThrowsError(try MapTilePlanner.corridorGCJ02([
            jinanToQingdao[0], GCJ02Point(longitudeDeg: .nan, latitudeDeg: 36),
        ])) { error in
            guard case MapPlanningError.invalidCoordinates = error else {
                return XCTFail("Expected invalid coordinates, got \(error)")
            }
        }
    }

    private var jinanToQingdao: [GCJ02Point] {
        [GCJ02Point(longitudeDeg: 117.0158836, latitudeDeg: 36.6502822),
         GCJ02Point(longitudeDeg: 120.3851322, latitudeDeg: 36.0702746)]
    }

    // A point-in-tile oracle: independent from corridor sampling and buffer
    // construction, using standard Web Mercator and known WGS84 locations.
    private func webMercatorTile(longitude: Double, latitude: Double) -> MapTileID {
        let n = pow(2.0, 15.0)
        let phi = latitude * .pi / 180
        return MapTileID(z: 15,
            x: Int(floor((longitude + 180) / 360 * n)),
            y: Int(floor((1 - log(tan(phi) + 1 / cos(phi)) / .pi) / 2 * n)))
    }
}
