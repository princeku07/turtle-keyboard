import Foundation
#if os(iOS)
import UIKit

/// Commands-only Split integration for iOS. iOS keyboard extensions cannot
/// detect the host app so the per-app chip activation Android does is not
/// possible — the user invokes Split explicitly via:
///
/// - `/split <amount>` → opens the split panel for a manually-entered amount
/// - `/splits`         → opens the saved-history panel
///
/// Both commands run locally; no AI hop, no network.
final class SplitIntegration: KeyboardIntegration {

    let id = "split"

    func commands() -> [CommandSpec] {
        [
            CommandSpec(
                name: "split", label: "Split", emoji: "💸", needsPrompt: true,
                handler: { prompt, ctx in Self.handleSplit(prompt: prompt, ctx: ctx) }
            ),
            CommandSpec(
                name: "splits", label: "Splits", emoji: "📜", needsPrompt: false,
                handler: { _, ctx in Self.handleSplits(ctx: ctx) }
            ),
        ]
    }

    // MARK: - Handlers

    static func handleSplit(prompt: String, ctx: IntegrationContext) {
        // Strip currency / commas / spaces — same shape as Android.
        let cleaned = prompt.unicodeScalars
            .filter { CharacterSet(charactersIn: "0123456789.").contains($0) }
            .map { String($0) }
            .joined()
        guard AmountWatcher.isAmount(cleaned) else {
            ctx.showBanner("Try /split 1500", autoHideMs: 1500)
            return
        }
        showPanel(ctx: ctx, amount: cleaned)
    }

    static func handleSplits(ctx: IntegrationContext) {
        let history = SplitHistory(store: ctx.store)
        let view = SplitHistoryView()
        let coordinator = HistoryCoordinator(ctx: ctx, history: history, view: view)
        view.show(entries: history.all(), listener: coordinator)
        view.setSnapshot(history.all())
        // Coordinator's strong ref is held inside the listener slot of the
        // view by virtue of being passed to show(...); but `listener` is
        // weak, so we also stash it on the view via associated object.
        objc_setAssociatedObject(view, &Self.coordinatorKey, coordinator, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        ctx.showPanel(view)
        ctx.hideChip()

        // Fire-and-forget cloud pull. Refreshes the view in place if any
        // remote rows arrived. No-op when the user isn't signed in (host
        // app owns sign-in UI).
        Task { @MainActor in
            let sync = cloudSync(ctx: ctx)
            guard sync.isSignedIn else { return }
            let changed = await sync.fetchAndMerge()
            if changed {
                let fresh = history.all()
                view.show(entries: fresh, listener: coordinator)
                view.setSnapshot(fresh)
            }
        }
    }

    /// Build a `SplitCloudSync` wired to the same shared store the keyboard
    /// uses + a refresh-only token provider that reads tokens from the
    /// shared Keychain Access Group. Returns a fresh instance each call —
    /// `SplitCloudSync` itself is stateless beyond what's in the store.
    private static func cloudSync(ctx: IntegrationContext) -> SplitCloudSync {
        let provider = SplitKeychainTokenProvider(store: ctx.store)
        return SplitCloudSync(store: ctx.store, oauth: provider)
    }

    static func showPanel(ctx: IntegrationContext, amount: String) {
        let defaultPeople = ctx.store.int(forKey: SplitKeys.defaultPeople,
                                          fallback: SplitContract.defaultPeople)
        let panel = SplitPanelView()
        let coordinator = PanelCoordinator(ctx: ctx)
        panel.show(rawAmount: amount, defaultPeople: defaultPeople, listener: coordinator)
        objc_setAssociatedObject(panel, &Self.coordinatorKey, coordinator, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        ctx.showPanel(panel)
        ctx.hideChip()
    }

    private static var coordinatorKey: UInt8 = 0

    // MARK: - Coordinators
    //
    // Listener protocols are AnyObject (weak refs in the view), so we need a
    // small object that owns the integration context and forwards UI events
    // to it. The view retains the coordinator via an associated object so
    // it lives exactly as long as the view does.

    private final class PanelCoordinator: SplitPanelView.Listener {
        let ctx: IntegrationContext
        init(ctx: IntegrationContext) { self.ctx = ctx }

        func splitPanelDidSave(amount: Double, people: Int) {
            let history = SplitHistory(store: ctx.store)
            let ts = history.add(amount: amount, people: people)
            ctx.store.setInt(people, forKey: SplitKeys.defaultPeople)
            ctx.hidePanel()
            ctx.showBanner("Split saved 💸", autoHideMs: 1500)

            // Mirror to the cloud sheet. Fire-and-forget — local write
            // already happened, network failure stays silent. Matches
            // android/split/SplitIntegration.java's push-on-save.
            let sync = SplitIntegration.cloudSync(ctx: ctx)
            sync.pushSave(amount: amount, people: people, timestampMs: ts)
        }

        func splitPanelDidCancel() {
            ctx.hidePanel()
        }
    }

    private final class HistoryCoordinator: SplitHistoryView.Listener {
        let ctx: IntegrationContext
        let history: SplitHistory
        weak var view: SplitHistoryView?

        init(ctx: IntegrationContext, history: SplitHistory, view: SplitHistoryView) {
            self.ctx = ctx
            self.history = history
            self.view = view
        }

        func splitHistoryDidCopy(_ entry: SplitHistory.Entry) {
            UIPasteboard.general.string = summary(entry: entry)
            ctx.showBanner("Copied 📋", autoHideMs: 1200)
        }

        func splitHistoryDidClear() {
            history.clear()
            view?.show(entries: [], listener: self)
            view?.setSnapshot([])
            // Mirror clear to cloud — wipes only this device's rows.
            SplitIntegration.cloudSync(ctx: ctx).pushClear()
        }

        func splitHistoryDidDismiss() {
            ctx.hidePanel()
        }

        func splitHistoryDidOpenReport() {
            ctx.hidePanel()
            ctx.openScreen("split-detail")
        }

        private func summary(entry: SplitHistory.Entry) -> String {
            let per = entry.people > 0 ? entry.amount / Double(entry.people) : entry.amount
            let noun = entry.people == 1 ? "person" : "people"
            return "Splitting ₹\(SplitContract.formatAmount(entry.amount)) between \(entry.people) \(noun) — ₹\(SplitContract.formatAmount(per)) each."
        }
    }
}
#endif
