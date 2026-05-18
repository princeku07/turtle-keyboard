package com.prince.turtlekeyboard.integration.web;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.prince.turtlekeyboard.overlay.OverlayUrls;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic create-side helper for the {@code games/{id}} collection. Per-game-type
 * subscription / reads / writes live in {@link GameBridge} on the WebView side — this
 * class is only the host-app create flow: keyboard integration parses user/AI input,
 * builds a {@code state} map, and POSTs to Firestore here.
 *
 * <p>Schema (rules validate {@code type} allowlist + per-type state shape):
 * <pre>
 *   games/{id} = { type, state, createdByUid, createdAt }
 * </pre>
 *
 * <p>{@link CreateResult#url} uses {@link OverlayUrls#forArtifact} so the shareable URL
 * format ({@code https://www.turtlekeyboard.com/<routeKey>/<id>}) matches the existing
 * App Link intent filter and the WebView shell loads
 * {@code https://games.turtlekeyboard.com/<routeKey>/?id=<id>}.
 */
public final class GamesFirestoreClient {

    private static final String TAG = "GamesFirestoreClient";
    private static final String GAMES_COLLECTION = "games";

    public static final class CreateResult {
        public final String id;
        public final String url;
        public CreateResult(String id, String url) {
            this.id = id;
            this.url = url;
        }
    }

    public interface CreateCallback {
        void onSuccess(@NonNull CreateResult result);
        void onError(@NonNull String reason);
    }

    private GamesFirestoreClient() {}

    /**
     * @param type   game type — must be in the {@code type} allowlist in
     *               {@code firestore.rules}. Currently {@code 'wyr'}.
     * @param state  game-type-specific state, persisted verbatim. The rules apply a
     *               per-{@code type} schema check; malformed state fails at the rule
     *               layer with {@code permission_denied}.
     */
    public static void createGame(@NonNull String type, @NonNull Map<String, Object> state,
                                  @NonNull CreateCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            cb.onError("not_signed_in");
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference newDoc = db.collection(GAMES_COLLECTION).document();

        Map<String, Object> data = new HashMap<>();
        data.put("type", type);
        data.put("state", state);
        data.put("createdByUid", user.getUid());
        data.put("createdAt", FieldValue.serverTimestamp());

        newDoc.set(data)
                .addOnSuccessListener(unused -> cb.onSuccess(new CreateResult(
                        newDoc.getId(),
                        OverlayUrls.forArtifact(type, newDoc.getId()))))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "createGame failed", e);
                    cb.onError(mapErrorCode(e));
                });
    }

    @NonNull
    private static String mapErrorCode(@NonNull Exception e) {
        if (e instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) e).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return "permission_denied";
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) return "network";
        }
        return e.getMessage() == null ? "unknown" : e.getMessage();
    }
}
