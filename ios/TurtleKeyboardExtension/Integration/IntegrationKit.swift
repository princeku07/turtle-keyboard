import Foundation
#if os(iOS)
import UIKit

// MARK: - InputContext
//
// iOS keyboard extensions cannot detect the host app's bundle identifier
// (Apple deliberately hides it). We replace Android's `EditorInfo` /
// package-name based activation with a struct of *field traits* the keyboard
// can read from `UITextDocumentProxy` + `UITextInputTraits`. Integrations
// that on Android keyed off `pkg == "com.gpay"` instead key off field shape
// (numeric + non-secure) plus content heuristics (typed amount).

struct InputContext {
    let keyboardType: UIKeyboardType
    let returnKeyType: UIReturnKeyType
    let textContentType: UITextContentType?
    let autocapitalizationType: UITextAutocapitalizationType
    let isSecureTextEntry: Bool

    init(proxy: UITextDocumentProxy & UITextInputTraits) {
        self.keyboardType = proxy.keyboardType ?? .default
        self.returnKeyType = proxy.returnKeyType ?? .default
        self.textContentType = proxy.textContentType ?? nil
        self.autocapitalizationType = proxy.autocapitalizationType ?? .sentences
        self.isSecureTextEntry = proxy.isSecureTextEntry ?? false
    }

    /// True when the field accepts numeric input. Used by Split to decide
    /// whether to arm the amount watcher.
    var isNumericField: Bool {
        switch keyboardType {
        case .numberPad, .decimalPad, .numbersAndPunctuation, .phonePad, .asciiCapableNumberPad:
            return true
        default:
            return false
        }
    }

    /// True when the field has signals indicating it's a PIN, OTP, CVV, or
    /// password — places where contextual chips must never appear.
    var looksSensitive: Bool {
        if isSecureTextEntry { return true }
        switch textContentType {
        case .some(.password), .some(.newPassword), .some(.oneTimeCode):
            return true
        default:
            return false
        }
    }
}

// MARK: - ChipSpec

/// Data the IME needs to render the integration chip above the keys.
/// Android's `iconPackage` lookup doesn't have an iOS equivalent (no app
/// icon catalog visible to extensions), so iOS chips are text-only or carry
/// an SF Symbol name.
struct ChipSpec {
    let label: String
    let symbolName: String?

    static func textOnly(_ label: String) -> ChipSpec {
        ChipSpec(label: label, symbolName: nil)
    }

    static func withSymbol(_ label: String, _ symbolName: String) -> ChipSpec {
        ChipSpec(label: label, symbolName: symbolName)
    }
}

// MARK: - CommandSpec

/// A slash command an integration contributes. Commands with a non-nil
/// `handler` run locally — the dispatcher invokes the handler instead of
/// routing to the AI backend.
struct CommandSpec {
    typealias Handler = (_ prompt: String, _ ctx: IntegrationContext) -> Void

    let name: String
    let label: String
    let emoji: String
    let needsPrompt: Bool
    let handler: Handler
}

// MARK: - LlmService

/// Module-side handle to the keyboard's LLM. Mirrors Android's `LlmService`
/// so Notion / future Linear etc. can talk to the same model the rest of the
/// keyboard uses without depending on the concrete `CommandRouter`.
protocol LlmService: AnyObject {

    /// Unrouted: the caller owns the entire prompt string and it goes
    /// straight to the cloud model. Use this only when there is no slash
    /// command to plan around.
    func complete(prompt: String,
                  onText: @escaping (String) -> Void,
                  onError: @escaping (String) -> Void)

    /// Routed: runs `command` through the keyboard's tier plan
    /// (`CommandRouter`), so it is served on-device when the device and the
    /// user's `InferenceMode` allow, and escalates to cloud only when the
    /// cheaper tier can't do the job. `prompt` is just the user's text —
    /// the router owns the system prompt.
    func complete(command: String,
                  prompt: String,
                  onText: @escaping (String) -> Void,
                  onError: @escaping (String) -> Void)
}

extension LlmService {
    /// Conformers that predate tier routing still answer the unrouted way.
    func complete(command: String,
                  prompt: String,
                  onText: @escaping (String) -> Void,
                  onError: @escaping (String) -> Void) {
        complete(prompt: prompt, onText: onText, onError: onError)
    }
}

// MARK: - IntegrationContext

/// Surface integrations talk to. Owned by the keyboard extension; passed
/// into every integration call so integrations don't reach into the IME's
/// internals.
protocol IntegrationContext: AnyObject {

    /// Mount a panel view above the keys. Replaces whatever was previously shown.
    func showPanel(_ view: UIView)

    /// Detach the active panel view, if any.
    func hidePanel()

    /// Show a chip above the keys. The `onTap` fires when the user taps it.
    func showChip(_ spec: ChipSpec, onTap: @escaping () -> Void)

    func hideChip()

    /// Transient notice above the keyboard. Auto-hides after `autoHideMs`.
    func showBanner(_ text: String, autoHideMs: Int)

    /// Claim the keyboard's generating overlay for a long-running operation.
    ///
    /// Call this the moment an integration kicks off async work — a model
    /// call, a network round trip — so the user sees the same "AI is working"
    /// surface every other command shows instead of a keyboard that looks
    /// idle. `message` replaces the overlay's text.
    ///
    /// There is no matching `hideBusy`: the first terminal callback
    /// (`commitText`, `showBanner`, `showPanel`, `hidePanel`, `openScreen`)
    /// releases the overlay, because every integration path already ends in
    /// one of those. A watchdog releases it regardless if none ever arrives.
    func showBusy(_ message: String)

    /// Persistent storage for both keyboard and integration UI.
    var store: SplitStore { get }

    /// LLM completion service. Same model as the rest of the keyboard.
    var llm: LlmService { get }

    /// Commit text into the host editor at the cursor.
    func commitText(_ text: String)

    /// Delete `n` characters before the cursor in the host editor.
    func deleteBeforeCursor(_ n: Int)

    /// Hand off to a deeper screen the host app provides. The `screenId` is
    /// a stable string the integration and the host agree on (e.g.
    /// "split-detail"). On iOS this opens a URL scheme into the host app.
    /// No-op when the host doesn't recognize the screen id.
    func openScreen(_ screenId: String)

    /// Open an arbitrary URL via `extensionContext.open(_:)`. For https
    /// URLs this routes to the default browser (Safari). Used by /web's
    /// "Open in Safari" hand-off. Completion fires `false` if the system
    /// blocks the open.
    func openExternalURL(_ url: URL)
}

extension IntegrationContext {
    func openExternalURL(_ url: URL) {} // default no-op for older conformers
}

// MARK: - IntegrationSession

/// Live integration bound to the current input session. Created by
/// `KeyboardIntegration.activate(...)` when the integration applies to the
/// current field; torn down via `onDeactivate` on field change or input end.
protocol IntegrationSession: AnyObject {
    func onTextChanged(before: String, after: String)
    func onDeactivate()
}

// MARK: - KeyboardIntegration

/// Pluggable contextual integration for the keyboard. An integration can
/// activate per input session, contribute slash commands, or both.
protocol KeyboardIntegration: AnyObject {
    /// Stable identifier (e.g. `"split"`).
    var id: String { get }

    /// @return a session if this integration applies to the current input,
    /// nil otherwise.
    func activate(input: InputContext, ctx: IntegrationContext) -> IntegrationSession?

    /// Slash commands this integration ships. Default: none.
    func commands() -> [CommandSpec]
}

extension KeyboardIntegration {
    func commands() -> [CommandSpec] { [] }
    func activate(input: InputContext, ctx: IntegrationContext) -> IntegrationSession? { nil }
}
#endif
