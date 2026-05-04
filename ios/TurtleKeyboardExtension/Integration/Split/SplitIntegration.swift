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
            history.add(amount: amount, people: people)
            ctx.store.setInt(people, forKey: SplitKeys.defaultPeople)
            ctx.hidePanel()
            ctx.showBanner("Split saved 💸", autoHideMs: 1500)
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
            return "Splitting ₹\(SplitPanelView.formatAmount(entry.amount)) between \(entry.people) \(noun) — ₹\(SplitPanelView.formatAmount(per)) each."
        }
    }
}
#endif
