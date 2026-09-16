import XCTest
import UIKit
import CoreLocation

final class MotoGPSUITests: XCTestCase {
    private var previousLocation: XCUILocation?

    func testLiveShanghaiCitySearchShowsDownloadCoverage() throws {
        let app = XCUIApplication()
        app.launch()
        let maps = app.buttons["map-downloads-button"]
        XCTAssertTrue(maps.waitForExistence(timeout: 5))
        if !maps.isHittable { app.swipeUp() }
        maps.tap()
        app.buttons["map-download-city"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 3))
        search.tap()
        search.typeText("上海")
        let city = app.buttons["map-city-result-310000"]
        XCTAssertTrue(city.waitForExistence(timeout: 25))
        city.tap()
        XCTAssertTrue(app.buttons["map-city-start-download"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["map-city-start-download"].isEnabled)
        keepScreenshot(of: app, named: "MOTO GPS Shanghai offline coverage")
    }

    func testMapDownloadsExposeCitySearchAndReturnHome() throws {
        let app = XCUIApplication()
        app.launch()
        let maps = app.buttons["map-downloads-button"]
        XCTAssertTrue(maps.waitForExistence(timeout: 5))
        if !maps.isHittable { app.swipeUp() }
        maps.tap()
        XCTAssertTrue(app.descendants(matching: .any)["map-downloads-sheet"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["自动加载周边地图"].exists)
        keepScreenshot(of: app, named: "MOTO GPS map downloads")
        app.buttons["map-download-city"].tap()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["添加常用城市"].exists)
        try tapSystemBack(in: app)
        app.buttons["map-downloads-done"].tap()
        XCTAssertTrue(app.textFields["destination-search-field"].waitForExistence(timeout: 3))
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        previousLocation = XCUIDevice.shared.location
        refreshTestLocation()
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.location = previousLocation
    }

    private func refreshTestLocation() {
        // Keep test location fresh across launches and appearance changes.
        // Production correctly rejects a cached fix older than 15 seconds.
        XCUIDevice.shared.location = XCUILocation(location: CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 36.6748039, longitude: 117.1224488),
            altitude: 0,
            horizontalAccuracy: 5,
            verticalAccuracy: 5,
            timestamp: Date()
        ))
    }

    func testDeviceDetailsPreserveDestinationSearch() throws {
        let app = XCUIApplication()
        app.launch()

        let search = app.textFields["destination-search-field"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        let query = "奥体中心"
        search.typeText(query)

        let detailsButton = app.buttons["device-details-button"]
        XCTAssertTrue(detailsButton.waitForExistence(timeout: 3))
        detailsButton.tap()

        let sheet = app.descendants(matching: .any)["device-details-sheet"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 3))
        keepScreenshot(of: app, named: "MOTO GPS device details")

        // Device availability varies on the simulator. Inspecting the sheet
        // must preserve the search independently of its current BLE state.
        let done = app.buttons["device-details-done"]
        XCTAssertTrue(done.waitForExistence(timeout: 3))
        done.tap()

        let dismissed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: sheet
        )
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 3), .completed)
        XCTAssertTrue(search.waitForExistence(timeout: 3))
        XCTAssertEqual(search.value as? String, query)
        XCTAssertFalse(app.descendants(matching: .any)["route-preview-map"].exists)
    }

    func testNativeHomeLightVisualState() throws {
        try captureHomeVisualState(
            appearance: .light,
            name: "MOTO GPS native home — light"
        )
    }

    func testNativeHomeDarkVisualState() throws {
        try captureHomeVisualState(
            appearance: .dark,
            name: "MOTO GPS native home — dark"
        )
    }

    func testNativeHomeAccessibilityXXXLVisualState() throws {
        try captureHomeVisualState(
            appearance: .light,
            arguments: [
                "-UIPreferredContentSizeCategoryName",
                UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue,
            ],
            name: "MOTO GPS native home — Accessibility XXXL"
        )
    }

    func testNearbyDestinationCanBeSelectedAndReturnsToRecentSearches() throws {
        let app = XCUIApplication()
        app.launch()

        let search = app.textFields["destination-search-field"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("奥体中心")

        let firstResult = app.buttons["place-result-0"]
        XCTAssertTrue(firstResult.waitForExistence(timeout: 15))
        refreshTestLocation()
        firstResult.tap()

        let preview = app.descendants(matching: .any)["route-preview-map"]
        XCTAssertTrue(preview.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["route-option-0"].exists)

        let start = app.buttons["primary-navigation-action"]
        XCTAssertTrue(start.waitForExistence(timeout: 3))
        XCTAssertTrue(start.isEnabled)

        try tapSystemBack(in: app)
        XCTAssertTrue(app.staticTexts["最近搜索"].waitForExistence(timeout: 3))
    }

    func testSelectedRouteStartsNavigationAndEndsAtHome() throws {
        let app = XCUIApplication()
        app.launch()

        let search = app.textFields["destination-search-field"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("奥体中心")

        let firstResult = app.buttons["place-result-0"]
        XCTAssertTrue(firstResult.waitForExistence(timeout: 20))
        refreshTestLocation()
        firstResult.tap()

        let preview = app.descendants(matching: .any)["route-preview-map"]
        XCTAssertTrue(preview.waitForExistence(timeout: 30))
        XCTAssertTrue(app.buttons["route-option-0"].exists)
        XCTAssertFalse(app.buttons["结束导航"].exists)

        // Explicitly start the real selected route. Selecting a search result
        // alone must remain a preview, including when no round screen is paired.
        let start = app.buttons["primary-navigation-action"]
        XCTAssertTrue(start.waitForExistence(timeout: 3))
        XCTAssertTrue(start.isEnabled)
        refreshTestLocation()
        start.tap()

        let navigationBar = app.navigationBars["导航中"]
        XCTAssertTrue(navigationBar.waitForExistence(timeout: 15))
        let end = app.buttons["结束导航"]
        XCTAssertTrue(end.waitForExistence(timeout: 5))
        XCTAssertTrue(end.isEnabled)
        keepScreenshot(of: app, named: "MOTO GPS native navigation — selected destination")
        end.tap()

        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(search.isHittable)
        XCTAssertTrue(app.staticTexts["最近搜索"].exists)
        XCTAssertFalse(end.exists)
        XCTAssertFalse(navigationBar.exists)
        XCTAssertFalse(preview.exists)
        XCTAssertFalse(app.buttons["route-option-0"].exists)
    }

    func testRoutePreviewSwipeRightReturnsToSearch() throws {
        let app = XCUIApplication()
        app.launch()

        let search = app.textFields["destination-search-field"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("奥体中心")

        let firstResult = app.buttons["place-result-0"]
        XCTAssertTrue(firstResult.waitForExistence(timeout: 20))
        refreshTestLocation()
        firstResult.tap()

        // Wait for the pushed preview screen (start action is enough; map tiles
        // are slower and not required to prove navigation-stack back works).
        let start = app.buttons["primary-navigation-action"]
        XCTAssertTrue(start.waitForExistence(timeout: 30))

        // Standard iOS edge swipe-back: left edge → right.
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.4))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.4))
        from.press(forDuration: 0.01, thenDragTo: to)

        XCTAssertTrue(app.staticTexts["最近搜索"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["destination-search-field"].exists)
        // Preview-only content must disappear when returning to search.
        XCTAssertFalse(app.descendants(matching: .any)["route-preview-map"].exists)
        XCTAssertFalse(app.buttons["route-option-0"].exists)
    }

    func testRoutePreviewSystemBackReturnsToSearch() throws {
        let app = XCUIApplication()
        app.launch()

        let search = app.textFields["destination-search-field"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("奥体中心")

        let firstResult = app.buttons["place-result-0"]
        XCTAssertTrue(firstResult.waitForExistence(timeout: 20))
        refreshTestLocation()
        firstResult.tap()

        let start = app.buttons["primary-navigation-action"]
        XCTAssertTrue(start.waitForExistence(timeout: 30))

        try tapSystemBack(in: app)
        XCTAssertTrue(app.staticTexts["最近搜索"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["destination-search-field"].exists)
    }

    func testBuiltInNavigationDemoStarts() throws {
        let app = XCUIApplication()
        app.launch()

        let demo = app.buttons["demo-navigation-button"]
        XCTAssertTrue(demo.waitForExistence(timeout: 5))
        demo.tap()

        // Demo may briefly show acquiring/planning before navigating.
        let end = app.buttons["结束导航"]
        XCTAssertTrue(end.waitForExistence(timeout: 15))
        XCTAssertTrue(end.isEnabled)
        keepScreenshot(of: app, named: "MOTO GPS native navigation — demo")
    }

    func testEndingDemoNavigationReturnsToHome() throws {
        let app = XCUIApplication()
        app.launch()

        let demo = app.buttons["demo-navigation-button"]
        XCTAssertTrue(demo.waitForExistence(timeout: 5))
        demo.tap()

        let end = app.buttons["结束导航"]
        XCTAssertTrue(end.waitForExistence(timeout: 15))
        end.tap()

        // Ending navigation must clear the stuck route-preview state and
        // bring the rider back to the destination-search home screen.
        XCTAssertTrue(app.textFields["destination-search-field"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["demo-navigation-button"].exists)
        XCTAssertFalse(end.exists)
        XCTAssertFalse(app.staticTexts["正在骑行"].exists)
    }

    /// Keeps a real route-preview screenshot in the test result so layout
    /// regressions are reviewable without changing the app's runtime behavior.
    func testRoutePreviewVisualState() throws {
        let app = XCUIApplication()
        app.launch()

        let search = app.textFields["destination-search-field"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("奥体中心")

        let firstResult = app.buttons["place-result-0"]
        XCTAssertTrue(firstResult.waitForExistence(timeout: 15))
        refreshTestLocation()
        firstResult.tap()

        let preview = app.descendants(matching: .any)["route-preview-map"]
        XCTAssertTrue(preview.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["route-option-0"].waitForExistence(timeout: 3))
        sleep(3) // Let MapKit finish its tile and camera transition.

        keepScreenshot(of: app, named: "MOTO GPS route preview — overview")

        app.swipeUp()
        sleep(1)
        keepScreenshot(of: app, named: "MOTO GPS route preview — choices")

        if app.buttons["route-option-1"].exists {
            app.buttons["route-option-1"].tap()
            sleep(1)
            keepScreenshot(of: app, named: "MOTO GPS route preview — alternate selected")
        }
    }

    private func captureHomeVisualState(
        appearance: XCUIDevice.Appearance,
        arguments: [String] = [],
        name: String
    ) throws {
        let device = XCUIDevice.shared
        let previousAppearance = device.appearance
        device.appearance = appearance
        defer { device.appearance = previousAppearance }

        let app = XCUIApplication()
        app.launchArguments = arguments
        app.launch()
        // XCTest may reset appearance while it launches the application.
        // Apply it to the live scene as well, then capture the rendered result.
        device.appearance = appearance
        XCTAssertEqual(device.appearance, appearance)

        let search = app.textFields["destination-search-field"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(search.isHittable)
        keepScreenshot(of: app, named: name)

        // Keep the device controls reachable in every appearance/text-size
        // variant without asserting any particular Bluetooth state.
        let detailsButton = app.buttons["device-details-button"]
        XCTAssertTrue(detailsButton.waitForExistence(timeout: 3))
        XCTAssertTrue(detailsButton.isHittable)
        detailsButton.tap()

        let sheet = app.descendants(matching: .any)["device-details-sheet"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 3))
        keepScreenshot(of: app, named: "\(name) — device details")

        let done = app.buttons["device-details-done"]
        XCTAssertTrue(done.waitForExistence(timeout: 3))
        XCTAssertTrue(done.isHittable)
        done.tap()
        let dismissed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: sheet
        )
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 3), .completed)
        XCTAssertTrue(search.waitForExistence(timeout: 3))
        XCTAssertTrue(search.isHittable)
    }

    private func keepScreenshot(of app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func tapSystemBack(in app: XCUIApplication) throws {
        let backButton = app.navigationBars.buttons.element(boundBy: 0)
        if backButton.waitForExistence(timeout: 2), backButton.isHittable {
            backButton.tap()
            return
        }
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.45))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.45))
        start.press(forDuration: 0.05, thenDragTo: end)
    }
}
