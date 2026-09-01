//  Setup state shared by the host app and the widget extension.
//
//  Both of these used to live inside `ViewController.swift` as file-private
//  declarations. They moved out verbatim (`KeyboardHomeState` lost its
//  `private`) when `SetupStatusWidget` needed the same derivation — the rule
//  for "is Turtle actually working" is subtle enough (heartbeat vs. legacy
//  seen-at, 30-day staleness, failure-newer-than-success) that a second copy
//  in the widget would have drifted.
//
//  Pure Foundation, no UIKit — that's what lets the widget target compile it.
//
//  One asymmetry to know about: everything here reads the App Group suite and
//  is therefore identical in both processes, EXCEPT `OnboardingState`'s
//  `isComplete` / `complete()`, which use `UserDefaults.standard`. Standard
//  defaults are per-process, so the widget would always read `false`. It has
//  no business asking whether onboarding finished, so nothing there calls it.

import Foundation

enum OnboardingState {
    private static let completionKey = "onboarding.completed.v1"
    static let appGroupID = "group.com.samarth.turtlekeyboard.split"
    static let keyboardSeenKey = "onboarding.keyboardSeenAt"
    static let fullAccessKey = "onboarding.fullAccess"

    static var isComplete: Bool {
        UserDefaults.standard.bool(forKey: completionKey)
    }

    static func complete() {
        UserDefaults.standard.set(true, forKey: completionKey)
    }
}

enum KeyboardHomeState {
    case notConfigured
    case keyboardEnabled
    case fullAccessRequired
    case ready(lastCommand: String?)
    case actionNeeded(message: String)

    static func current() -> KeyboardHomeState {
        guard let defaults = UserDefaults(suiteName: OnboardingState.appGroupID) else {
            return .notConfigured
        }
        let heartbeat = defaults.double(forKey: "keyboard.lastHeartbeatAt")
        let legacySeen = defaults.double(forKey: OnboardingState.keyboardSeenKey)
        let lastSeen = max(heartbeat, legacySeen)
        guard lastSeen > 0 else { return .notConfigured }

        if Date().timeIntervalSince1970 - lastSeen > 30 * 24 * 60 * 60 {
            return .actionNeeded(message: "Turtle hasn’t checked in recently. Open it once from any text field.")
        }

        guard defaults.object(forKey: OnboardingState.fullAccessKey) != nil else {
            return .keyboardEnabled
        }
        guard defaults.bool(forKey: OnboardingState.fullAccessKey) else {
            return .fullAccessRequired
        }

        let successAt = defaults.double(forKey: "keyboard.lastSuccessAt")
        let failureAt = defaults.double(forKey: "keyboard.lastFailureAt")
        if failureAt > successAt,
           Date().timeIntervalSince1970 - failureAt < 24 * 60 * 60 {
            let message = defaults.string(forKey: "keyboard.lastFailureMessage")
                ?? "A recent command needs your attention."
            return .actionNeeded(message: message)
        }
        return .ready(lastCommand: defaults.string(forKey: "keyboard.lastSuccessfulCommand"))
    }
}
