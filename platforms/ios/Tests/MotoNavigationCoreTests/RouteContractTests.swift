import Foundation
import XCTest
@testable import MotoNavigationCore

final class RouteContractTests: XCTestCase {
    func testRequestEncodesTheExistingV1SnakeCaseContract() throws {
        let request = RouteRequest(
            requestID: 41,
            origin: WGS84Point(longitudeDeg: 116.397389, latitudeDeg: 39.908722),
            destination: WGS84Point(longitudeDeg: 116.410886, latitudeDeg: 39.920150)
        )

        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(request)) as? [String: Any]
        )
        XCTAssertEqual(object["protocol_version"] as? Int, 1)
        XCTAssertEqual(object["request_id"] as? Int, 41)
        XCTAssertEqual(object["route_mode"] as? String, "driving")
        XCTAssertEqual(object["is_reroute"] as? Bool, false)

        let origin = try XCTUnwrap(object["origin"] as? [String: Any])
        XCTAssertEqual(origin["coordinate_system"] as? String, "WGS84")
        XCTAssertEqual(origin["longitude_deg"] as? Double, 116.397389)
    }

    func testSpeedConversionClampsInvalidNegativeSensorValues() {
        let fix = NavigationFix(
            coordinate: WGS84Point(longitudeDeg: 0, latitudeDeg: 0),
            horizontalAccuracyM: 1,
            speedMps: -1,
            timestamp: .distantPast
        )
        XCTAssertEqual(fix.speedKph, 0)
    }
}
