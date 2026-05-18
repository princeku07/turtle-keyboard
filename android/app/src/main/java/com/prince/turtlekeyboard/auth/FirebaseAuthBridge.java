package com.prince.turtlekeyboard.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.prince.kbd.core.GoogleAuth;
import com.revenuecat.purchases.CustomerInfo;
import com.revenuecat.purchases.PurchasesError;
import com.revenuecat.purchases.Purchases;
import com.revenuecat.purchases.interfaces.LogInCallback;

import java.util.HashMap;
import java.util.Map;

/**
 * Two-step bridge from {@link GoogleAuth}'s Google ID token to a Firebase Auth session,
 * plus the side effects that should fire on every successful sign-in.
 *
 * <p>Side effects per sign-in:
 * <ol>
 *   <li>Upsert {@code users/{uid}} in Firestore — first-time create writes
 *       {@code tier:"free"} + {@code createdAt}; subsequent calls update only the
 *       profile-fields allowlist defined in {@code firestore.rules}.</li>
 *   <li>Bind RevenueCat identity via {@code Purchases.logIn(uid)} so future purchase
 *       receipts map to the Firebase uid (the RevenueCat webhook → Cloud Function will
 *       write entitlements back to the same {@code users/{uid}} doc).</li>
 * </ol>
 *
 * <p>Construction wires GoogleAuth with the Firebase Web Client ID that the
 * {@code google-services} plugin auto-exposes as {@code R.string.default_web_client_id}:
 * <pre>
 *   String webClientId = context.getString(R.string.default_web_client_id);
 *   KeyValueStore store = new SharedPrefsKeyValueStore(context).scoped("google");
 *   GoogleAuth auth = new GoogleAuthImpl(context, store, webClientId);
 *   FirebaseAuthBridge bridge = new FirebaseAuthBridge(context, auth);
 * </pre>
 *
 * <p>Usage from a host {@link Activity}: call {@link #ensureSignedIn} on resume; if it
 * returns a {@link GoogleAuth.PendingUi}, launch the {@link android.content.IntentSender}
 * via {@code ActivityResultLauncher} and feed the result back to
 * {@link #onSignInActivityResult}. The bridge handles every other step.
 */
public final class FirebaseAuthBridge {

    private static final String TAG = "FirebaseAuthBridge";
    private static final String USERS_COLLECTION = "users";

    public interface Callback {
        void onSignedIn(@NonNull FirebaseUser user);
        void onError(@NonNull String reason, @Nullable GoogleAuth.PendingUi pendingUi);
    }

    private final Context appContext;
    private final GoogleAuth googleAuth;
    private final FirebaseAuth firebaseAuth;
    private final FirebaseFirestore firestore;

    public FirebaseAuthBridge(@NonNull Context context, @NonNull GoogleAuth googleAuth) {
        this.appContext = context.getApplicationContext();
        this.googleAuth = googleAuth;
        this.firebaseAuth = FirebaseAuth.getInstance();
        this.firestore = FirebaseFirestore.getInstance();
    }

    @Nullable public FirebaseUser currentUser() {
        return firebaseAuth.getCurrentUser();
    }

    /**
     * Fast-path if Firebase Auth already has a user; otherwise kicks off the
     * Google-ID-token-then-Firebase-credential flow. Caller must launch any returned
     * {@link GoogleAuth.PendingUi} and route the result to
     * {@link #onSignInActivityResult}.
     */
    public void ensureSignedIn(@Nullable Activity activity, @NonNull Callback cb) {
        FirebaseUser existing = firebaseAuth.getCurrentUser();
        if (existing != null) {
            // Firebase Auth persists across launches via its own session store. We still
            // re-bind RevenueCat in case the SDK was reset (process death after sign-in
            // but before logIn settled) — logIn is idempotent.
            bindRevenueCat(existing);
            cb.onSignedIn(existing);
            return;
        }
        googleAuth.freshIdToken(activity, new GoogleAuth.Callback() {
            @Override public void onToken(String idToken) {
                exchangeIdTokenForFirebaseUser(idToken, cb);
            }
            @Override public void onError(String reason, @Nullable GoogleAuth.PendingUi pendingUi) {
                cb.onError(reason, pendingUi);
            }
        });
    }

    /**
     * Completes the flow after the host {@link Activity} returned from the
     * {@link GoogleAuth.PendingUi} intent. Mirrors GoogleAuth's
     * {@code onAuthorizationResult} but for the One Tap sign-in intent path.
     */
    public void onSignInActivityResult(@Nullable Activity activity, @Nullable Intent data,
                                       @NonNull Callback cb) {
        googleAuth.onSignInResult(activity, data, new GoogleAuth.Callback() {
            @Override public void onToken(String idToken) {
                exchangeIdTokenForFirebaseUser(idToken, cb);
            }
            @Override public void onError(String reason, @Nullable GoogleAuth.PendingUi pendingUi) {
                cb.onError(reason, pendingUi);
            }
        });
    }

    /** Drops Firebase session + RC identity. Does NOT call
     *  {@link GoogleAuth#signOut()} — access-token state for Drive/Sheets is a separate
     *  concern and callers may want one without the other. */
    public void signOut() {
        firebaseAuth.signOut();
        if (Purchases.isConfigured()) {
            Purchases.getSharedInstance().logOut(null);
        }
    }

    private void exchangeIdTokenForFirebaseUser(String idToken, Callback cb) {
        AuthCredential cred = GoogleAuthProvider.getCredential(idToken, null);
        firebaseAuth.signInWithCredential(cred)
                .addOnSuccessListener(result -> {
                    FirebaseUser user = result.getUser();
                    if (user == null) {
                        cb.onError("no_firebase_user", null);
                        return;
                    }
                    syncUserDoc(user);
                    bindRevenueCat(user);
                    cb.onSignedIn(user);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "signInWithCredential failed", e);
                    cb.onError(e.getMessage() == null ? "firebase_signin_failed" : e.getMessage(), null);
                });
    }

    /**
     * Upserts {@code users/{uid}}. Read-then-write so the first call satisfies the
     * {@code create} rule ({@code tier == 'free'}) and subsequent calls satisfy the
     * {@code update} rule (allowlisted fields only — never re-sending {@code tier},
     * which the RevenueCat webhook owns).
     *
     * <p>Best-effort: failures are logged, not surfaced. Sign-in itself already
     * succeeded; a stale profile doc isn't worth blocking the user on.
     */
    private void syncUserDoc(FirebaseUser user) {
        DocumentReference ref = firestore.collection(USERS_COLLECTION).document(user.getUid());
        ref.get().addOnSuccessListener(snapshot -> {
            Map<String, Object> data = new HashMap<>();
            data.put("email", user.getEmail());
            data.put("displayName", user.getDisplayName());
            if (user.getPhotoUrl() != null) {
                data.put("photoUrl", user.getPhotoUrl().toString());
            }
            data.put("updatedAt", FieldValue.serverTimestamp());

            if (!snapshot.exists()) {
                data.put("tier", "free");
                data.put("createdAt", FieldValue.serverTimestamp());
                ref.set(data).addOnFailureListener(e ->
                        Log.w(TAG, "users/" + user.getUid() + " create failed", e));
            } else {
                ref.update(data).addOnFailureListener(e ->
                        Log.w(TAG, "users/" + user.getUid() + " update failed", e));
            }
        }).addOnFailureListener(e ->
                Log.w(TAG, "users/" + user.getUid() + " read failed", e));
    }

    private void bindRevenueCat(FirebaseUser user) {
        if (!Purchases.isConfigured()) {
            // TurtleApp left RC unconfigured — REVENUECAT_SDK_KEY missing from
            // local.properties. Already logged at startup; don't spam here.
            return;
        }
        Purchases.getSharedInstance().logIn(user.getUid(), new LogInCallback() {
            @Override public void onReceived(@NonNull CustomerInfo customerInfo, boolean created) {
                // No-op: entitlements arrive separately via the webhook → users/{uid}.
                // Client-side CustomerInfo is not the source of truth for tier.
            }
            @Override public void onError(@NonNull PurchasesError error) {
                Log.w(TAG, "Purchases.logIn failed: " + error.getMessage());
            }
        });
    }
}
