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
 * Bridges {@link GoogleAuth}'s Google ID token to a Firebase Auth session and upserts
 * the {@code users/{uid}} doc plus RevenueCat identity on every successful sign-in.
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
     * Returns the current Firebase user via callback, or kicks off the Google sign-in
     * flow. Caller must launch any returned {@link GoogleAuth.PendingUi} and route the
     * result to {@link #onSignInActivityResult}.
     */
    public void ensureSignedIn(@Nullable Activity activity, @NonNull Callback cb) {
        FirebaseUser existing = firebaseAuth.getCurrentUser();
        if (existing != null) {
            // Re-bind RevenueCat in case the SDK was reset between sign-in and logIn; idempotent.
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

    /** Completes sign-in after the host {@link Activity} returns from the One Tap intent. */
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

    /**
     * Drops Firebase session and RevenueCat identity. Does NOT call
     * {@link GoogleAuth#signOut()} — Drive/Sheets access-token state is a separate concern.
     */
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
     * Upserts {@code users/{uid}}. Read-then-write so the first call writes
     * {@code tier:"free"} and subsequent calls only update the profile allowlist
     * (never re-sending {@code tier}, which the RevenueCat webhook owns). Best-effort.
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
            // REVENUECAT_SDK_KEY missing; startup already logged this.
            return;
        }
        Purchases.getSharedInstance().logIn(user.getUid(), new LogInCallback() {
            @Override public void onReceived(@NonNull CustomerInfo customerInfo, boolean created) {
                // Entitlements arrive via webhook → users/{uid}; CustomerInfo is not the source of truth.
            }
            @Override public void onError(@NonNull PurchasesError error) {
                Log.w(TAG, "Purchases.logIn failed: " + error.getMessage());
            }
        });
    }
}
