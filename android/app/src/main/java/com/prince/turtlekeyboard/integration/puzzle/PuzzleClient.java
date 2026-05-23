package com.prince.turtlekeyboard.integration.puzzle;

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
 * Creates a {@code games/<id>} Firestore doc for a new puzzle. The caller supplies a
 * public image URL; uploads are handled elsewhere.
 *
 * <p>Schema: {@code games/<id> = { type: "puzzle", state: { imageUrl, gridSize },
 * createdByUid, createdAt }}.
 */
public final class PuzzleClient {

    private static final String TAG = "PuzzleClient";
    private static final String GAMES_COLLECTION = "games";

    private static final int MIN_GRID = 3;
    private static final int MAX_GRID = 5;

    public static final class CreateResult {
        public final String id;
        public final String url;
        public final String imageUrl;
        public CreateResult(String id, String url, String imageUrl) {
            this.id = id;
            this.url = url;
            this.imageUrl = imageUrl;
        }
    }

    public interface CreateCallback {
        void onSuccess(@NonNull CreateResult result);
        void onError(@NonNull String reason);
    }

    private PuzzleClient() {}

    public static void create(@NonNull String imageUrl, int gridSize, @NonNull CreateCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            cb.onError("not_signed_in");
            return;
        }
        if (gridSize < MIN_GRID || gridSize > MAX_GRID) {
            cb.onError("invalid_grid_size");
            return;
        }
        if (imageUrl.isEmpty()) {
            cb.onError("invalid_image");
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference puzzleDoc = db.collection(GAMES_COLLECTION).document();
        String id = puzzleDoc.getId();

        Map<String, Object> state = new HashMap<>();
        state.put("imageUrl", imageUrl);
        state.put("gridSize", gridSize);

        Map<String, Object> doc = new HashMap<>();
        doc.put("type", "puzzle");
        doc.put("state", state);
        doc.put("createdByUid", user.getUid());
        doc.put("createdAt", FieldValue.serverTimestamp());

        puzzleDoc.set(doc)
                .addOnSuccessListener(unused -> cb.onSuccess(new CreateResult(
                        id,
                        OverlayUrls.forArtifact(PuzzleIntegration.ROUTE_KEY, id),
                        imageUrl)))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "puzzle doc write failed", e);
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
