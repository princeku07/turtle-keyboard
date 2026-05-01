package com.prince.split;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.split.kbd.ChipSpec;
import com.prince.split.kbd.CommandSpec;
import com.prince.split.kbd.IntegrationContext;
import com.prince.split.kbd.IntegrationSession;
import com.prince.split.kbd.KeyboardIntegration;
import com.prince.split.view.SplitHistoryView;
import com.prince.split.view.SplitPanelView;

import java.util.Locale;

import java.util.Arrays;
import java.util.List;

/**
 * Pluggable Split integration. Activates whenever the user is typing into a numeric,
 * non-sensitive field of a known payment app — surfaces a chip, opens the in-keyboard
 * split panel on tap, and persists saves to {@link SplitHistory}.
 *
 * <p>Also contributes two slash commands available in any field:
 * <ul>
 *   <li>{@code /split <amount>} — open the split panel for a manually-entered amount</li>
 *   <li>{@code /splits} — open the saved-history activity</li>
 * </ul>
 */
public class SplitIntegration implements KeyboardIntegration {

    /** Pref key to disable Split entirely (used as the master toggle for this integration). */
    public static final String KEY_ENABLED = "split_enabled";

    @Override public String id() { return "split"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        if (ctx.store().getInt(KEY_ENABLED, 1) == 0) return null;
        HostApp.Info host = info == null ? null : HostApp.paymentInfoFor(info.packageName);
        if (host == null) return null;

        boolean armable = EditorFieldHeuristics.isNumericField(info)
                && !EditorFieldHeuristics.looksSensitive(info);

        return new SplitSession(ctx, host, armable);
    }

    @Override
    public List<CommandSpec> commands() {
        return Arrays.asList(
                new CommandSpec("split", "Split", "💸", true, this::handleSplit),
                new CommandSpec("splits", "Splits", "📜", false, this::handleSplits));
    }

    private void handleSplit(String prompt, IntegrationContext ctx) {
        // Strip currency / commas / spaces — same shape as AmountWatcher.
        String cleaned = prompt == null ? "" : prompt.replaceAll("[^\\d.]", "");
        if (cleaned.isEmpty() || !cleaned.matches("^\\d{1,7}(\\.\\d{1,2})?$")) {
            ctx.showBanner("Try /split 1500", 1500L);
            return;
        }
        showPanel(ctx, cleaned);
    }

    private void handleSplits(String prompt, IntegrationContext ctx) {
        final SplitHistory history = new SplitHistory(ctx.store());
        final SplitHistoryView view = new SplitHistoryView(ctx.appContext());
        ctx.showPanel(view);
        ctx.hideChip();
        final SplitHistoryView.Listener listener = new SplitHistoryView.Listener() {
            @Override public void onCopy(SplitHistory.Entry e) {
                copyToClipboard(ctx, summary(e));
                ctx.showBanner("Copied 📋", 1200L);
            }
            @Override public void onClear() {
                history.clear();
                view.show(history.all(), this);
            }
            @Override public void onDismiss() {
                ctx.hidePanel();
            }
            @Override public void onOpenReport() {
                ctx.hidePanel();
                ctx.openScreen("split-detail");
            }
        };
        // Render local immediately, then refresh once the cloud pull lands.
        view.show(history.all(), listener);
        SplitCloudSync.syncFromCloud(ctx.store(), new SplitCloudSync.SyncCallback() {
            @Override public void onComplete(boolean changed) {
                if (changed) view.show(history.all(), listener);
            }
        });
    }

    private static String summary(SplitHistory.Entry e) {
        double per = e.people > 0 ? e.amount / e.people : e.amount;
        return "Splitting ₹" + format(e.amount) + " between " + e.people
                + (e.people == 1 ? " person" : " people")
                + " — ₹" + format(per) + " each.";
    }

    private static String format(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void copyToClipboard(IntegrationContext ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.appContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Split", text));
    }

    /** Builds + attaches the split panel, wires save/cancel against the SDK's history. */
    static void showPanel(IntegrationContext ctx, String amount) {
        int defaultPeople = ctx.store().getInt(SplitKeys.DEFAULT_PEOPLE, SplitContract.DEFAULT_PEOPLE);
        SplitPanelView panel = new SplitPanelView(ctx.appContext());
        ctx.showPanel(panel);
        ctx.hideChip();
        panel.show(amount, defaultPeople, new SplitPanelView.Listener() {
            @Override public void onSave(double amt, int people) {
                new SplitHistory(ctx.store()).add(amt, people);
                ctx.store().putInt(SplitKeys.DEFAULT_PEOPLE, people);
                ctx.hidePanel();
                ctx.showBanner("Split saved 💸", 1500L);
            }
            @Override public void onCancel() {
                ctx.hidePanel();
            }
        });
    }

    // -- Session ---------------------------------------------------------------

    /**
     * Lives for one input session. If {@code armable} is true we watch text for amount
     * shapes; if false (e.g. PIN field inside GPay) we still mark the integration active
     * so the chip can re-light when the user moves to the amount field, but never surface
     * the chip while inside the sensitive field.
     */
    private static class SplitSession implements IntegrationSession {

        private final IntegrationContext ctx;
        private final HostApp.Info host;
        private final boolean armable;
        @Nullable private final AmountWatcher watcher;
        @Nullable private String currentAmount;

        SplitSession(IntegrationContext ctx, HostApp.Info host, boolean armable) {
            this.ctx = ctx;
            this.host = host;
            this.armable = armable;
            this.watcher = armable ? new AmountWatcher(this::onAmount) : null;
            if (watcher != null) watcher.arm();
            // Surface the host badge immediately, even before an amount is detected.
            ctx.showChip(ChipSpec.withHostIcon(host.displayName, host.pkg), this::onChipTap);
        }

        @Override
        public void onTextChanged(CharSequence before, CharSequence after) {
            if (watcher != null) watcher.onTextChanged(before, after);
        }

        @Override
        public void onDeactivate() {
            if (watcher != null) watcher.disarm();
            currentAmount = null;
            ctx.hideChip();
            ctx.hidePanel();
        }

        private void onAmount(@Nullable String amount) {
            currentAmount = amount;
            String label = amount == null
                    ? host.displayName
                    : host.displayName + " · Split ₹" + amount;
            ctx.showChip(ChipSpec.withHostIcon(label, host.pkg), this::onChipTap);
        }

        private void onChipTap() {
            if (currentAmount == null) return;
            showPanel(ctx, currentAmount);
        }
    }
}
