package com.prince.turtlekeyboard.integration.poll;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.prince.turtlekeyboard.overlay.OverlayUrls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Realtime Database-backed poll client. Replaces the prior Firestore implementation
 * because:
 *   - one persistent WebSocket gives snappier live updates across clients than
 *     Firestore's per-listener gRPC streams,
 *   - polls have a hard 47-minute TTL (single hot window per poll, no long-tail), and
 *   - native atomic operations (multi-path updates, optimistic local writes) are
 *     baked into RTDB's offline SDK.
 *
 * <p>Schema (see {@code firebase/database.rules.json}):
 * <pre>
 *   polls/&lt;id&gt;/
 *     question      string
 *     options       [string, ...]
 *     createdByUid  string
 *     createdAt     number (ms since epoch, server-stamped)
 *     expiresAt     number (createdAt + 47*60*1000, client-computed)
 *     voters/&lt;uid&gt;: number (option index)
 * </pre>
 *
 * <p>Vote counts are derived client-side from the voters subtree on every emission —
 * same trade-off as the Firestore design. No aggregate counters means no
 * Cloud-Function-maintained denormalisation and no rule-level cross-path checks.
 *
 * <p>The 47-minute window is enforced two ways: (1) a scheduled Cloud Function sweeper
 * deletes expired polls server-side, (2) this client refuses to render polls whose
 * {@code expiresAt} has passed even if the sweep hasn't run yet.
 */
public final class RealtimePollClient {

    private static final String TAG = "RealtimePollClient";

    private static final String POLLS_PATH = "polls";
    private static final String VOTERS_PATH = "voters";

    /** Active window before a poll is swept by the Cloud Function. Keep in lockstep
     *  with the sweeper's expiry filter in {@code firebase/functions/index.js}. */
    public static final long POLL_TTL_MS = 47L * 60L * 1000L;

    /** Soft caps mirroring the rules-side validators. */
    static final int MAX_OPTIONS = 6;
    static final int MAX_QUESTION_LEN = 200;

    public static final class CreateResult {
        public final String id;
        public final String url;
        public CreateResult(String id, String url) {
            this.id = id;
            this.url = url;
        }
    }

    public static final class Poll {
        public final String id;
        public final String question;
        public final List<Option> options;
        public final long createdAt;
        public final long expiresAt;
        /** Option index the currently signed-in user voted for, or {@code -1} if
         *  they haven't voted (or no user is signed in). Derived from the voters
         *  subtree in {@link #parsePoll}. */
        public final int myVoteIndex;
        public Poll(String id, String question, List<Option> options,
                    long createdAt, long expiresAt, int myVoteIndex) {
            this.id = id;
            this.question = question;
            this.options = options;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.myVoteIndex = myVoteIndex;
        }
    }

    public static final class Option {
        public final String label;
        public final int votes;
        public Option(String label, int votes) {
            this.label = label;
            this.votes = votes;
        }
    }

    public interface CreateCallback {
        void onSuccess(@NonNull CreateResult result);
        void onError(@NonNull String reason);
    }

    public interface VoteCallback {
        void onSuccess();
        /** {@code code} ∈ {already_voted, not_signed_in, permission_denied,
         *  poll_expired, network, unknown}. */
        void onError(@NonNull String code, @Nullable String message);
    }

    public interface PollListener {
        void onPoll(@NonNull Poll poll);
        void onError(@NonNull String reason);
    }

    public interface Cancellable {
        void cancel();
    }

    private RealtimePollClient() {}

    // -- create --------------------------------------------------------------

    public static void createPoll(@NonNull String question, @NonNull List<String> options,
                                  @NonNull CreateCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            cb.onError("not_signed_in");
            return;
        }
        String trimmed = question.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_QUESTION_LEN
                || options.size() < 2 || options.size() > MAX_OPTIONS) {
            cb.onError("invalid_payload");
            return;
        }

        DatabaseReference root = FirebaseDatabase.getInstance().getReference();
        DatabaseReference newRef = root.child(POLLS_PATH).push();
        String id = newRef.getKey();
        if (id == null) {
            cb.onError("unknown");
            return;
        }

        // Client-computed expiresAt — the rules require it's > now at the moment of
        // write. Tiny clock-skew risk is acceptable (TTL is approximate by design).
        long expiresAt = System.currentTimeMillis() + POLL_TTL_MS;

        Map<String, Object> payload = new HashMap<>();
        payload.put("question", trimmed);
        payload.put("options", new ArrayList<>(options));
        payload.put("createdByUid", user.getUid());
        payload.put("createdAt", ServerValue.TIMESTAMP);
        payload.put("expiresAt", expiresAt);

        newRef.setValue(payload)
                .addOnSuccessListener(unused -> cb.onSuccess(new CreateResult(
                        id, OverlayUrls.forArtifact(PollIntegration.ROUTE_KEY, id))))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "createPoll failed", e);
                    cb.onError(mapErrorCode(e));
                });
    }

    // -- subscribe (realtime read) ------------------------------------------

    /**
     * Subscribes to a single poll node. One {@link ValueEventListener} delivers the
     * entire shape (question, options, voters tree) on every change — no dual-listener
     * merge like the prior Firestore design. Vote counts are derived from the voters
     * subtree on each emission.
     *
     * <p>Refuses to emit if {@code expiresAt} has already passed — the sweeper may
     * not have run yet, but the poll is conceptually dead.
     */
    @NonNull
    public static Cancellable subscribePoll(@NonNull String id,
                                            @NonNull PollListener listener) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference(POLLS_PATH).child(id);

        ValueEventListener veListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!snap.exists()) {
                    listener.onError("poll_not_found");
                    return;
                }
                long expiresAt = longOf(snap.child("expiresAt"));
                if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
                    listener.onError("poll_expired");
                    return;
                }
                Poll poll = parsePoll(snap);
                if (poll == null) {
                    listener.onError("poll_malformed");
                    return;
                }
                listener.onPoll(poll);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "subscribePoll cancelled: " + error.getMessage());
                listener.onError(error.getCode() == DatabaseError.PERMISSION_DENIED
                        ? "permission_denied"
                        : (error.getMessage() == null ? "unknown" : error.getMessage()));
            }
        };
        ref.addValueEventListener(veListener);
        return () -> ref.removeEventListener(veListener);
    }

    // -- vote ---------------------------------------------------------------

    public static void vote(@NonNull String pollId, int optionIndex, @NonNull VoteCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            cb.onError("not_signed_in", null);
            return;
        }
        if (optionIndex < 0 || optionIndex >= MAX_OPTIONS) {
            cb.onError("invalid_payload", "optionIndex out of range");
            return;
        }

        DatabaseReference voterRef = FirebaseDatabase.getInstance()
                .getReference(POLLS_PATH).child(pollId)
                .child(VOTERS_PATH).child(user.getUid());

        // setValue() queues the write locally and fires the parent listener
        // immediately (hasPendingWrites=true). The voter sees their count update in
        // ~50ms. Rule layer rejects re-writes (`!data.exists()`) — PERMISSION_DENIED
        // bubbles up as already_voted.
        voterRef.setValue(optionIndex)
                .addOnSuccessListener(unused -> cb.onSuccess())
                .addOnFailureListener(e -> {
                    Log.w(TAG, "vote failed", e);
                    cb.onError(mapVoteErrorCode(e), e.getMessage());
                });
    }

    // -- parse helpers ------------------------------------------------------

    @Nullable
    private static Poll parsePoll(@NonNull DataSnapshot snap) {
        String question = stringOf(snap.child("question"));
        if (question == null) return null;

        List<String> labels = new ArrayList<>();
        for (DataSnapshot s : snap.child("options").getChildren()) {
            Object v = s.getValue();
            if (v instanceof String) labels.add((String) v);
        }
        if (labels.isEmpty()) return null;

        FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
        String myUid = current == null ? null : current.getUid();

        int[] counts = new int[labels.size()];
        int myVoteIndex = -1;
        for (DataSnapshot s : snap.child(VOTERS_PATH).getChildren()) {
            Object v = s.getValue();
            int idx = (v instanceof Long) ? ((Long) v).intValue()
                    : (v instanceof Integer) ? (Integer) v
                    : -1;
            if (idx >= 0 && idx < counts.length) {
                counts[idx]++;
                if (myUid != null && myUid.equals(s.getKey())) myVoteIndex = idx;
            }
        }

        List<Option> options = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            options.add(new Option(labels.get(i), counts[i]));
        }

        return new Poll(
                snap.getKey() == null ? "" : snap.getKey(),
                question,
                options,
                longOf(snap.child("createdAt")),
                longOf(snap.child("expiresAt")),
                myVoteIndex
        );
    }

    @Nullable
    private static String stringOf(@NonNull DataSnapshot s) {
        Object v = s.getValue();
        return v instanceof String ? (String) v : null;
    }

    private static long longOf(@NonNull DataSnapshot s) {
        Object v = s.getValue();
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }

    // -- error mapping ------------------------------------------------------

    @NonNull
    private static String mapErrorCode(@NonNull Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("permission denied")) return "permission_denied";
        if (msg.contains("network") || msg.contains("disconnect")) return "network";
        return e.getMessage() == null ? "unknown" : e.getMessage();
    }

    @NonNull
    private static String mapVoteErrorCode(@NonNull Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("permission denied")) {
            // Dominant cause: voters/<uid> already exists (rule rejects re-write).
            // Could also be unauth or rule misconfig but those are setup-time errors.
            return "already_voted";
        }
        if (msg.contains("network") || msg.contains("disconnect")) return "network";
        return "unknown";
    }
}
