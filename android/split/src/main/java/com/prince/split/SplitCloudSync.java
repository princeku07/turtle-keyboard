package com.prince.split;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates cloud sync for {@link SplitHistory} against the user's own Google Sheet.
 * Local SharedPreferences remains the source of truth for the keyboard panel; this class
 * mirrors saves to the cloud and pulls remote rows on demand.
 *
 * <p>All cloud calls are no-ops unless the user is signed in via {@link SplitAuth} and a
 * sheet has been provisioned by {@link #ensureSheet}.
 */
public final class SplitCloudSync {

    public interface SyncCallback {
        /** @param changed true if local history was modified by the merge */
        void onComplete(boolean changed);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SplitCloudSync() {}

    /**
     * Ensures the user has a "Turtle Splits" spreadsheet in their Drive, creating one if
     * needed and migrating any pre-existing local rows on first run. Safe to call on
     * every app launch — short-circuits when already provisioned.
     */
    public static void ensureSheet(final Context ctx, final SplitStore store,
                                   @Nullable final SyncCallback cb) {
        final SplitAuth auth = new SplitAuth(ctx, store);
        if (!auth.isSignedIn()) {
            deliver(cb, false);
            return;
        }
        if (!store.getString(SplitKeys.SHEET_ID, "").isEmpty()
                && "1".equals(store.getString(SplitKeys.MIGRATED_LOCAL, ""))) {
            deliver(cb, false);
            return;
        }
        withFreshToken(auth, null, new TokenAction() {
            @Override public void run(String token) {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            String sheetId = store.getString(SplitKeys.SHEET_ID, "");
                            if (sheetId.isEmpty()) {
                                sheetId = SplitSheetsClient.createSpreadsheet(token);
                                store.putString(SplitKeys.SHEET_ID, sheetId);
                            }
                            if (!"1".equals(store.getString(SplitKeys.MIGRATED_LOCAL, ""))) {
                                migrateLocalRows(token, sheetId, store);
                                store.putString(SplitKeys.MIGRATED_LOCAL, "1");
                            }
                            deliver(cb, true);
                        } catch (Exception e) {
                            deliver(cb, false);
                        }
                    }
                });
            }
            @Override public void onError() { deliver(cb, false); }
        });
    }

    /** Mirrors a save to the user's sheet. Fire-and-forget; local write already happened. */
    public static void pushSave(final Context ctx, final SplitStore store,
                                final double amount, final int people, final long timestampMs) {
        final SplitAuth auth = new SplitAuth(ctx, store);
        if (!auth.isSignedIn()) return;
        final String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        if (sheetId.isEmpty()) return;
        final String deviceId = ensureDeviceId(store);
        withFreshToken(auth, null, new TokenAction() {
            @Override public void run(String token) {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            SplitSheetsClient.appendRows(token, sheetId,
                                    Collections.singletonList(buildRow(amount, people, timestampMs, deviceId)));
                        } catch (Exception ignored) {}
                    }
                });
            }
            @Override public void onError() { /* silent */ }
        });
    }

    /** Mirrors a clear — removes only this device's rows from the sheet. */
    public static void pushClear(final Context ctx, final SplitStore store) {
        final SplitAuth auth = new SplitAuth(ctx, store);
        if (!auth.isSignedIn()) return;
        final String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        if (sheetId.isEmpty()) return;
        final String deviceId = ensureDeviceId(store);
        withFreshToken(auth, null, new TokenAction() {
            @Override public void run(String token) {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            SplitSheetsClient.deleteRowsForDevice(token, sheetId, deviceId);
                        } catch (Exception ignored) {}
                    }
                });
            }
            @Override public void onError() { /* silent */ }
        });
    }

    /**
     * Pulls all rows from the sheet, dedupes against local by
     * {@code (timestampMs, amount, people)}, writes anything new, and fires {@code cb}
     * on the main thread.
     */
    public static void fetchAndMerge(final Context ctx, final SplitStore store,
                                     @Nullable final SyncCallback cb) {
        final SplitAuth auth = new SplitAuth(ctx, store);
        if (!auth.isSignedIn()) { deliver(cb, false); return; }
        final String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        if (sheetId.isEmpty()) { deliver(cb, false); return; }
        withFreshToken(auth, null, new TokenAction() {
            @Override public void run(final String token) {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            List<SplitSheetsClient.Row> remote =
                                    SplitSheetsClient.listRows(token, sheetId);
                            boolean changed = mergeIntoLocal(store, remote);
                            deliver(cb, changed);
                        } catch (Exception e) {
                            deliver(cb, false);
                        }
                    }
                });
            }
            @Override public void onError() { deliver(cb, false); }
        });
    }

    // -- internals ------------------------------------------------------------

    private interface TokenAction {
        void run(String token);
        void onError();
    }

    private static void withFreshToken(SplitAuth auth, @Nullable android.app.Activity activity,
                                       final TokenAction action) {
        auth.freshAccessToken(activity, new SplitAuth.AuthCallback() {
            @Override public void onToken(String accessToken) { action.run(accessToken); }
            @Override public void onError(String reason, @Nullable SplitAuth.PendingUi pendingUi) {
                action.onError();
            }
        });
    }

    private static void migrateLocalRows(String token, String sheetId, SplitStore store)
            throws IOException {
        String existing = store.getString(SplitKeys.HISTORY, "");
        if (existing.isEmpty()) return;
        String deviceId = ensureDeviceId(store);
        List<List<Object>> rows = new ArrayList<>();
        for (String line : existing.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length != 3) continue;
            try {
                double amount = Double.parseDouble(parts[0]);
                int people = Integer.parseInt(parts[1]);
                long ts = Long.parseLong(parts[2]);
                rows.add(buildRow(amount, people, ts, deviceId));
            } catch (NumberFormatException ignored) {}
        }
        if (!rows.isEmpty()) SplitSheetsClient.appendRows(token, sheetId, rows);
    }

    private static List<Object> buildRow(double amount, int people, long timestampMs,
                                         String deviceId) {
        double per = people > 0 ? amount / people : amount;
        return Arrays.asList(
                java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(timestampMs)),
                timestampMs,
                deviceId,
                amount,
                people,
                per);
    }

    private static boolean mergeIntoLocal(SplitStore store, List<SplitSheetsClient.Row> remote) {
        String existing = store.getString(SplitKeys.HISTORY, "");
        Set<String> seen = new HashSet<>();
        List<LocalRow> merged = new ArrayList<>();
        if (!existing.isEmpty()) {
            for (String line : existing.split("\n")) {
                String[] parts = line.split("\\|");
                if (parts.length != 3) continue;
                try {
                    LocalRow row = new LocalRow(
                            Double.parseDouble(parts[0]),
                            Integer.parseInt(parts[1]),
                            Long.parseLong(parts[2]));
                    if (seen.add(row.dedupeKey())) merged.add(row);
                } catch (NumberFormatException ignored) {}
            }
        }
        boolean changed = false;
        for (SplitSheetsClient.Row r : remote) {
            LocalRow row = new LocalRow(r.amount, r.people, r.timestampMs);
            if (seen.add(row.dedupeKey())) {
                merged.add(row);
                changed = true;
            }
        }
        if (!changed) return false;
        Collections.sort(merged, new Comparator<LocalRow>() {
            @Override public int compare(LocalRow a, LocalRow b) {
                return Long.compare(b.timestampMs, a.timestampMs);
            }
        });
        if (merged.size() > SplitHistory.MAX) {
            merged = merged.subList(0, SplitHistory.MAX);
        }
        StringBuilder out = new StringBuilder();
        for (LocalRow row : merged) {
            if (out.length() > 0) out.append('\n');
            out.append(row.amount).append('|').append(row.people).append('|').append(row.timestampMs);
        }
        store.putString(SplitKeys.HISTORY, out.toString());
        return true;
    }

    private static String ensureDeviceId(SplitStore store) {
        String id = store.getString(SplitKeys.DEVICE_ID, "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            store.putString(SplitKeys.DEVICE_ID, id);
        }
        return id;
    }

    private static void deliver(@Nullable final SyncCallback cb, final boolean changed) {
        if (cb == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() { cb.onComplete(changed); }
        });
    }

    private static final class LocalRow {
        final double amount;
        final int people;
        final long timestampMs;
        LocalRow(double amount, int people, long timestampMs) {
            this.amount = amount;
            this.people = people;
            this.timestampMs = timestampMs;
        }
        String dedupeKey() { return timestampMs + "|" + amount + "|" + people; }
    }

}
