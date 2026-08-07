import XCTest

final class OnboardingUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testFirstLaunchShowsOnboardingAndCanAdvance() {
        let app = XCUIApplication()
        app.launchArguments += ["-onboarding.completed.v1", "NO"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Welcome to Turtle"].waitForExistence(timeout: 3))
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.isHittable)
        continueButton.tap()
        XCTAssertTrue(app.staticTexts["Try Turtle"].waitForExistence(timeout: 2))
    }

    func testOnboardingSupportsLargeAccessibilityText() {
        let app = XCUIApplication()
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
                                "-onboarding.completed.v1", "NO"]
        app.launch()
        XCTAssertTrue(app.buttons["Continue"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Continue"].isHittable)
    }
}
