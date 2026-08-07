# iOS test suites

The repository contains two suites:

- `TurtleKeyboardExtensionTests/CommandAndPersistenceTests.swift` covers command metadata/routing boundaries, response decoding and fallback, App Group-compatible persistence, split calculations/history decoding, safe network error mapping, and telemetry privacy.
- `TurtleKeyboardUITests/OnboardingUITests.swift` covers the first onboarding transition and an accessibility text-size smoke test.

The Xcode project is hand-maintained and repository policy forbids automated tools from opening or editing files inside `TurtleKeyboard.xcodeproj`. Add two targets in Xcode with these exact names, assign each source directory to its matching target, and set the host application of `TurtleKeyboardUITests` to `TurtleKeyboard`.

Run in CI with:

```sh
xcodebuild test \
  -project ios/TurtleKeyboard.xcodeproj \
  -scheme TurtleKeyboard \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=NO
```

The remaining requested seams—draft restoration, OAuth callback parsing,
image-history migration, and keyboard height transitions—currently live as
private UIKit implementation details. Extract them into injected pure-logic
types before adding their tests; tests should not duplicate those algorithms.
