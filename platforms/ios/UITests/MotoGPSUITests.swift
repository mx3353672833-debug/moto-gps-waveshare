import XCTest

final class MotoGPSUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
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
        firstResult.tap()

        let preview = app.descendants(matching: .any)["route-preview-map"]
        XCTAssertTrue(preview.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["route-option-0"].exists)

        let start = app.buttons["primary-navigation-action"]
        XCTAssertTrue(start.waitForExistence(timeout: 3))
        XCTAssertTrue(start.isEnabled)

        let change = app.buttons["更换"]
        XCTAssertTrue(change.waitForExistence(timeout: 3))
        change.tap()
        XCTAssertTrue(app.staticTexts["最近搜索"].waitForExistence(timeout: 3))
    }

    func testBuiltInNavigationDemoStarts() throws {
        let app = XCUIApplication()
        app.launch()

        let demo = app.buttons["demo-navigation-button"]
        XCTAssertTrue(demo.waitForExistence(timeout: 5))
        demo.tap()

        XCTAssertTrue(app.staticTexts["正在演示导航"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["结束导航"].isEnabled)
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
        firstResult.tap()

        let preview = app.descendants(matching: .any)["route-preview-map"]
        XCTAssertTrue(preview.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["route-option-0"].waitForExistence(timeout: 3))
        sleep(3) // Let MapKit finish its tile and camera transition.

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "MOTO GPS route preview — overview"
        attachment.lifetime = .keepAlways
        add(attachment)

        app.swipeUp()
        sleep(1)
        let routeChoices = XCTAttachment(screenshot: app.screenshot())
        routeChoices.name = "MOTO GPS route preview — choices"
        routeChoices.lifetime = .keepAlways
        add(routeChoices)

        if app.buttons["route-option-1"].exists {
            app.buttons["route-option-1"].tap()
            sleep(1)
            let alternate = XCTAttachment(screenshot: app.screenshot())
            alternate.name = "MOTO GPS route preview — alternate selected"
            alternate.lifetime = .keepAlways
            add(alternate)
        }
    }
}
