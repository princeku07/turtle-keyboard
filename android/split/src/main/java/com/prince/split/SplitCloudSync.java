package com.prince.split;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.prince.kbd.core.GoogleAuth;
import com.prince.kbd.core.KeyValueStore;

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
 * <p>All cloud calls are no-ops unless the user is signed in via {@link GoogleAuth} and a
 * sheet has been provisioned by {@link #ensureSheet}. The {@link GoogleAuth} instance is
 * passed in by every caller (rather than constructed here) so the same shared instance —
 * with the cached token in the {@code google} namespace — is reused across modules.
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
    public static void ensureSheet(final Context ctx, final GoogleAuth auth,
                                   final KeyValueStore store,
                                   @Nullable final SyncCallback cb) {
        if (!auth.isSignedIn()) {
            deliver(cb, false);
            return;
        }
        // Backfill: pre-invite-feature installs created sheets without stamping OWNER_EMAIL.
        // Anyone with a SHEET_ID but no OWNER_EMAIL is necessarily the owner — at the time
        // the sheet was created, the only way to have a sheet was to create it yourself.
        if (!store.getString(SplitKeys.SHEET_ID, "").isEmpty()
                && store.getString(SplitKeys.OWNER_EMAIL, "").isEmpty()) {
            String myEmail = auth.accountEmail();
            if (myEmail != null && !myEmail.isEmpty()) {
                store.putString(SplitKeys.OWNER_EMAIL, myEmail);
            }
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
                                // The user who creates the sheet owns it.
                                String email = auth.accountEmail();
                                if (email != null) {
                                    store.putString(SplitKeys.OWNER_EMAIL, email);
                                }
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
    public static void pushSave(final Context ctx, final GoogleAuth auth,
                                final KeyValueStore store,
                                final double amount, final int people, final long timestampMs) {
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
    public static void pushClear(final Context ctx, final GoogleAuth auth,
                                 final KeyValueStore store) {
        pushClearInternal(ctx, auth, store, false);
    }

    /**
     * Owner-only: nukes every data row (across all devices) from the shared sheet.
     * No-op if the current user isn't the sheet owner.
     */
    public static void pushClearAll(final Context ctx, final GoogleAuth auth,
                                    final KeyValueStore store) {
        if (!isOwner(auth, store)) return;
        pushClearInternal(ctx, auth, store, true);
    }

    private static void pushClearInternal(final Context ctx, final GoogleAuth auth,
                                          final KeyValueStore store,
                                          final boolean wipeAll) {
        if (!auth.isSignedIn()) return;
        final String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        if (sheetId.isEmpty()) return;
        final String deviceId = ensureDeviceId(store);
        withFreshToken(auth, null, new TokenAction() {
            @Override public void run(String token) {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            if (wipeAll) {
                                SplitSheetsClient.deleteAllDataRows(token, sheetId);
                            } else {
                                SplitSheetsClient.deleteRowsForDevice(token, sheetId, deviceId);
                            }
                        } catch (Exception ignored) {}
                    }
                });
            }
            @Override public void onError() { /* silent */ }
        });
    }

    /** True iff the current account is the email stamped at sheet-creation time. */
    public static boolean isOwner(GoogleAuth auth, KeyValueStore store) {
        String me = auth.accountEmail();
        if (me == null) me = "";
        String owner = store.getString(SplitKeys.OWNER_EMAIL, "");
        return !me.isEmpty() && me.equalsIgnoreCase(owner);
    }

    /**
     * Pulls all rows from the sheet, dedupes against local by
     * {@code (timestampMs, amount, people)}, writes anything new, and fires {@code cb}
     * on the main thread.
     */
    public static void fetchAndMerge(final Context ctx, final GoogleAuth auth,
                                     final KeyValueStore store,
                                     @Nullable final SyncCallback cb) {
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

    private static void withFreshToken(GoogleAuth auth,
                                       @Nullable android.app.Activity activity,
                                       final TokenAction action) {
        auth.freshToken(activity, SplitOAuthScopes.SCOPES, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) { action.run(accessToken); }
            @Override public void onError(String reason, @Nullable GoogleAuth.PendingUi pendingUi) {
                action.onError();
            }
        });
    }

    private static void migrateLocalRows(String token, String sheetId, KeyValueStore store)
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

    private static boolean mergeIntoLocal(KeyValueStore store, List<SplitSheetsClient.Row> remote) {
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

    private static String ensureDeviceId(KeyValueStore store) {
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

    // -- single-QR membership flow ------------------------------------------

    /**
     * Single-QR invite flow.
     *
     * <ol>
     *   <li>Owner taps "Invite" → {@link #openMembership} adds an anyone-with-link
     *       writer permission and returns a {@code turtlekeyboard://join} deep link.</li>
     *   <li>Joiner scans with any OS camera → deep link opens
     *       {@link com.prince.split.ui.JoinSplitActivity} → {@link #joinSharedSheet}
     *       stamps local pointers and refreshes.</li>
     *   <li>Owner taps "Stop accepting members" → {@link #closeMembership} revokes the
     *       anyone-with-link permission. Existing members keep direct grants if any;
     *       in this minimal v1 they retain access until the owner removes them
     *       (anyone-with-link removal alone doesn't revoke anyone — Google leaves the
     *       file accessible to anyone who already opened it via the link).</li>
     * </ol>
     *
     * <p>Same security model as Google Docs' "anyone with link can edit": whoever has
     * the QR can read/write until the owner stops accepting members. Owner is in control
     * of the lifecycle.
     */
    public static final String DEEP_LINK_JOIN = "turtlekeyboard://join";

    /**
     * Owner-only: enables anyone-with-link writer sharing on the sheet, persists the
     * Drive permissionId, and returns a deep-link URL the owner can render as a QR.
     * Fires {@code cb} on the main thread; the URL is delivered via the wider
     * {@link InviteCallback}.
     */
    public interface InviteCallback {
        void onReady(@Nullable String deepLink);
    }

    public static void openMembership(final Context ctx, final GoogleAuth auth,
                                      final KeyValueStore store,
                                      final InviteCallback cb) {
        if (!isOwner(auth, store)) { deliverInvite(cb, null); return; }
        if (!auth.isSignedIn()) { deliverInvite(cb, null); return; }
        final String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        if (sheetId.isEmpty()) { deliverInvite(cb, null); return; }
        withFreshToken(auth, null, new TokenAction() {
            @Override public void run(final String token) {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            String existing = store.getString(SplitKeys.ANYONE_PERMISSION_ID, "");
                            String permId = existing;
                            if (permId.isEmpty()) {
                                permId = SplitDriveClient.grantAnyoneWriter(token, sheetId);
                                store.putString(SplitKeys.ANYONE_PERMISSION_ID, permId);
                            }
                            String url = buildJoinDeepLink(store);
                            deliverInvite(cb, url);
                        } catch (Exception e) {
                            deliverInvite(cb, null);
                        }
                    }
                });
            }
            @Override public void onError() { deliverInvite(cb, null); }
        });
    }

    /** Owner-only: revokes the anyone-with-link permission. */
    public static void closeMembership(final Context ctx, final GoogleAuth auth,
                                       final KeyValueStore store,
                                       @Nullable final SyncCallback cb) {
        if (!isOwner(auth, store)) { deliver(cb, false); return; }
        if (!auth.isSignedIn()) { deliver(cb, false); return; }
        final String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        final String permId = store.getString(SplitKeys.ANYONE_PERMISSION_ID, "");
        if (sheetId.isEmpty() || permId.isEmpty()) {
            // Already closed — clear any stale state and report success.
            store.putString(SplitKeys.ANYONE_PERMISSION_ID, "");
            deliver(cb, true);
            return;
        }
        withFreshToken(auth, null, new TokenAction() {
            @Override public void run(final String token) {
                EXEC.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            SplitDriveClient.revokePermission(token, sheetId, permId);
                            store.putString(SplitKeys.ANYONE_PERMISSION_ID, "");
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

    /** Whether the owner currently has an anyone-with-link share open. */
    public static boolean isMembershipOpen(KeyValueStore store) {
        return !store.getString(SplitKeys.ANYONE_PERMISSION_ID, "").isEmpty();
    }

    /** Builds the join deep link encoding {@code sheetId} + owner email for the QR. */
    public static String buildJoinDeepLink(KeyValueStore store) {
        String sheetId = store.getString(SplitKeys.SHEET_ID, "");
        String owner = store.getString(SplitKeys.OWNER_EMAIL, "");
        if (sheetId.isEmpty()) {
            throw new IllegalStateException("no sheet provisioned yet");
        }
        return DEEP_LINK_JOIN + "?sheetId=" + urlEncode(sheetId)
                + "&owner=" + urlEncode(owner);
    }

    /**
     * Joiner-side: switches the local store onto {@code sheetId} (owned by
     * {@code ownerEmail}) and refreshes from the sheet. Owner must have membership open
     * for the {@code fetchAndMerge} call to succeed.
     */
    public static void joinSharedSheet(final Context ctx, final GoogleAuth auth,
                                       final KeyValueStore store,
                                       final String sheetId, final String ownerEmail,
                                       @Nullable final SyncCallback cb) {
        store.putString(SplitKeys.SHEET_ID, sheetId);
        store.putString(SplitKeys.OWNER_EMAIL, ownerEmail == null ? "" : ownerEmail);
        // Joiner doesn't own this sheet, so no migration of local rows; subsequent saves
        // are mirrored to the new sheet on append.
        store.putString(SplitKeys.MIGRATED_LOCAL, "1");
        // Joiner is not the owner of any anyone-with-link share — clear any leftover from
        // a previous owner role on this install.
        store.putString(SplitKeys.ANYONE_PERMISSION_ID, "");
        fetchAndMerge(ctx, auth, store, cb);
    }

    private static void deliverInvite(@Nullable final InviteCallback cb, @Nullable final String url) {
        if (cb == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() { cb.onReady(url); }
        });
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
