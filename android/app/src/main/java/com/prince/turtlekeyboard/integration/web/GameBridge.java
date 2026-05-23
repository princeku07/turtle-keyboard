package com.prince.turtlekeyboard.integration.web;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native side of the WebView game bridge, exposed to JS as
 * {@code window.TurtleGame_native}. Backs the generic {@code games/{id}} Firestore
 * collection; games on a different backend need a sibling bridge.
 */
public final class GameBridge {

    private static final String TAG = "GameBridge";

    private static final String GAMES_COLLECTION = "games";
    private static final String PLAYERS_SUBCOLLECTION = "players";

    private final WebView webView;
    private final String gameType;
    private final String gameId;
    private final FirebaseFirestore db;

    @Nullable private ListenerRegistration docReg;
    @Nullable private ListenerRegistration playersReg;

    // First _onUpdate fires only after both listeners deliver, so JS never sees
    // a partial state.
    @Nullable private DocumentSnapshot lastDocSnap;
    @Nullable private QuerySnapshot lastPlayersSnap;
    private boolean docReady;
    private boolean playersReady;

    public GameBridge(@NonNull WebView webView, @NonNull String gameType, @NonNull String gameId) {
        this.webView = webView;
        this.gameType = gameType;
        this.gameId = gameId;
        this.db = FirebaseFirestore.getInstance();
    }

    @JavascriptInterface
    @Nullable
    public String uid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u == null ? null : u.getUid();
    }

    /** Returns the game's route key so the JS bundle can verify it loaded the right type. */
    @JavascriptInterface
    @NonNull
    public String type() {
        return gameType;
    }

    /** Returns the artifact id. The WebView loads a {@code file://} URL with no query
     *  string, so this method is the only way for JS to learn which instance it renders. */
    @JavascriptInterface
    @NonNull
    public String artifactId() {
        return gameId;
    }

    /** Starts (or re-starts) the dual snapshot subscription. JS must set
     *  {@code _onUpdate} / {@code _onError} before calling. Safe to call multiple times. */
    @JavascriptInterface
    public void subscribe() {
        unsubscribe();
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            fireError("not_signed_in");
            return;
        }
        DocumentReference docRef = db.collection(GAMES_COLLECTION).document(gameId);
        CollectionReference playersRef = docRef.collection(PLAYERS_SUBCOLLECTION);

        docReg = docRef.addSnapshotListener((snap, e) -> {
            if (e != null) {
                fireError(e.getMessage() == null ? "snapshot_error" : e.getMessage());
                return;
            }
            if (snap == null || !snap.exists()) {
                fireError("game_not_found");
                return;
            }
            lastDocSnap = snap;
            docReady = true;
            maybeEmit();
        });

        playersReg = playersRef.addSnapshotListener((snap, e) -> {
            if (e != null) {
                // Keep doc listener alive; roster stays stale rather than tearing the sheet down.
                Log.w(TAG, "players listener error", e);
                return;
            }
            lastPlayersSnap = snap;
            playersReady = true;
            maybeEmit();
        });
    }

    @JavascriptInterface
    public void unsubscribe() {
        if (docReg != null) { docReg.remove(); docReg = null; }
        if (playersReg != null) { playersReg.remove(); playersReg = null; }
        docReady = false;
        playersReady = false;
        lastDocSnap = null;
        lastPlayersSnap = null;
    }

    /**
     * Writes the caller's player doc. {@code payloadJson} is opaque to the bridge;
     * schema is validated by Firestore rules per game type. Result is reported via
     * {@code window.TurtleGame._onWriteResult(writeId, ok, err)}.
     */
    @JavascriptInterface
    public void writePlayerState(@NonNull String writeId, @NonNull String payloadJson) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            fireWriteResult(writeId, false, "not_signed_in");
            return;
        }
        Map<String, Object> stateMap;
        try {
            stateMap = jsonToMap(new JSONObject(new JSONTokener(payloadJson)));
        } catch (JSONException e) {
            fireWriteResult(writeId, false, "invalid_payload");
            return;
        }

        DocumentReference playerRef = db.collection(GAMES_COLLECTION).document(gameId)
                .collection(PLAYERS_SUBCOLLECTION).document(user.getUid());

        Map<String, Object> doc = new HashMap<>();
        doc.put("state", stateMap);
        doc.put("joinedAt", FieldValue.serverTimestamp());

        playerRef.set(doc)
                .addOnSuccessListener(unused -> fireWriteResult(writeId, true, null))
                .addOnFailureListener(e -> {
                    Log.w(TAG, "writePlayerState failed", e);
                    fireWriteResult(writeId, false, mapErrorCode(e));
                });
    }

    /** Tears down listeners; called by the host sheet view in {@code onDismiss}. */
    public void cancel() {
        unsubscribe();
    }

    private void maybeEmit() {
        if (!docReady || !playersReady) return;
        try {
            JSONObject payload = buildEmission(lastDocSnap, lastPlayersSnap);
            evalJs("window.TurtleGame && window.TurtleGame._onUpdate && "
                    + "window.TurtleGame._onUpdate(" + JSONObject.quote(payload.toString()) + ")");
        } catch (JSONException e) {
            Log.w(TAG, "emission build failed", e);
            fireError("emission_build_failed");
        }
    }

    /** Builds {@code { id, type, state, createdAt, players: [...] }}. Players are
     *  sorted by {@code joinedAt} to keep "Player 1" / "Player 2" stable. */
    private JSONObject buildEmission(DocumentSnapshot docSnap, QuerySnapshot playersSnap)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("id", docSnap.getId());
        root.put("type", docSnap.getString("type"));
        Object state = docSnap.get("state");
        root.put("state", state == null ? JSONObject.NULL : objectToJson(state));
        Timestamp createdAt = docSnap.getTimestamp("createdAt");
        root.put("createdAt", createdAt == null ? 0L : createdAt.toDate().getTime());

        List<DocumentSnapshot> playerDocs = new ArrayList<>(playersSnap.getDocuments());
        playerDocs.sort((a, b) -> {
            Timestamp ta = a.getTimestamp("joinedAt");
            Timestamp tb = b.getTimestamp("joinedAt");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return ta.compareTo(tb);
        });
        JSONArray players = new JSONArray();
        for (DocumentSnapshot p : playerDocs) {
            JSONObject pj = new JSONObject();
            pj.put("uid", p.getId());
            Object pState = p.get("state");
            pj.put("state", pState == null ? JSONObject.NULL : objectToJson(pState));
            Timestamp joinedAt = p.getTimestamp("joinedAt");
            pj.put("joinedAt", joinedAt == null ? 0L : joinedAt.toDate().getTime());
            players.put(pj);
        }
        root.put("players", players);
        return root;
    }

    private void fireError(String reason) {
        evalJs("window.TurtleGame && window.TurtleGame._onError && "
                + "window.TurtleGame._onError(" + JSONObject.quote(reason) + ")");
    }

    private void fireWriteResult(String writeId, boolean ok, @Nullable String error) {
        String errArg = error == null ? "null" : JSONObject.quote(error);
        evalJs("window.TurtleGame && window.TurtleGame._onWriteResult && "
                + "window.TurtleGame._onWriteResult("
                + JSONObject.quote(writeId) + ", " + ok + ", " + errArg + ")");
    }

    private void evalJs(String script) {
        // Firestore callbacks fire on main thread; evaluateJavascript requires UI thread.
        webView.evaluateJavascript(script, null);
    }

    private static Map<String, Object> jsonToMap(JSONObject o) throws JSONException {
        Map<String, Object> out = new HashMap<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String key = it.next();
            out.put(key, jsonValueToJava(o.get(key)));
        }
        return out;
    }

    private static List<Object> jsonToList(JSONArray a) throws JSONException {
        List<Object> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) out.add(jsonValueToJava(a.get(i)));
        return out;
    }

    private static Object jsonValueToJava(Object v) throws JSONException {
        if (v instanceof JSONObject) return jsonToMap((JSONObject) v);
        if (v instanceof JSONArray) return jsonToList((JSONArray) v);
        if (v == JSONObject.NULL) return null;
        return v;
    }

    /** Converts a Firestore value to a JSON-safe shape; timestamps flatten to epoch millis. */
    private static Object objectToJson(Object v) throws JSONException {
        if (v == null) return JSONObject.NULL;
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                o.put(String.valueOf(e.getKey()), objectToJson(e.getValue()));
            }
            return o;
        }
        if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object o : (List<?>) v) a.put(objectToJson(o));
            return a;
        }
        if (v instanceof Timestamp) return ((Timestamp) v).toDate().getTime();
        return v;
    }

    private static String mapErrorCode(Throwable e) {
        if (e instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) e).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return "permission_denied";
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) return "network";
            if (code == FirebaseFirestoreException.Code.ALREADY_EXISTS) return "already_joined";
        }
        return e.getMessage() == null ? "unknown" : e.getMessage();
    }
}
