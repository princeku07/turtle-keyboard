package com.prince.split.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.SharedPrefsKeyValueStore;
import com.prince.split.SplitAuth;
import com.prince.split.SplitCloudSync;
import com.prince.split.SplitContract;

/**
 * Handles {@code turtlekeyboard://join?sheetId=...&owner=...} deep links — the second
 * step of the joiner-shows-QR / owner-scans flow. The owner has already granted writer
 * access via {@link GrantAccessActivity}, so all this activity does is point the local
 * store at the shared sheet and trigger an initial fetch.
 */
public class JoinSplitActivity extends AppCompatActivity {

    private static final int CREAM = 0xFFF4EFE4;
    private static final int INK   = 0xFF0C0C0C;
    private static final int LIME  = 0xFF15803D;
    private static final int MUTED = 0xFF6B6B6B;

    private KeyValueStore store;
    private SplitAuth auth;
    private String sheetId;
    private String ownerEmail;

    private TextView statusLine;
    private Button joinBtn;

    private final ActivityResultLauncher<IntentSenderRequest> authLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> finishAuth(result.getData()));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SharedPrefsKeyValueStore(this, SharedPrefsKeyValueStore.DEFAULT_FILE).scoped("split");
        auth = new SplitAuth(this, store);

        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data == null) { abort("No invite link"); return; }
        sheetId = data.getQueryParameter("sheetId");
        ownerEmail = data.getQueryParameter("owner");
        if (sheetId == null || sheetId.isEmpty()) {
            abort("Invite link is missing the sheet ID");
            return;
        }
        setContentView(buildLayout());
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setText("Connect to split book");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(INK);
        head.setGravity(Gravity.CENTER);
        root.addView(head);

        TextView body = new TextView(this);
        String who = (ownerEmail == null || ownerEmail.isEmpty())
                ? "the owner" : ownerEmail;
        body.setText(who + " just added you as a writer.\n\n"
                + "Tap Connect to start syncing your splits with theirs.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(MUTED);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(16);
        body.setLayoutParams(bLp);
        root.addView(body);

        statusLine = new TextView(this);
        statusLine.setText("");
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusLine.setTextColor(INK);
        statusLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(20);
        statusLine.setLayoutParams(sLp);
        root.addView(statusLine);

        joinBtn = new Button(this);
        joinBtn.setText("Connect");
        joinBtn.setBackgroundColor(LIME);
        joinBtn.setTextColor(0xFFFFFFFF);
        joinBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams jLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        jLp.topMargin = dp(24);
        joinBtn.setLayoutParams(jLp);
        joinBtn.setOnClickListener(v -> proceed());
        root.addView(joinBtn);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setBackgroundColor(0x00000000);
        cancel.setTextColor(INK);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(8);
        cancel.setLayoutParams(cLp);
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel);

        return root;
    }

    private void proceed() {
        joinBtn.setEnabled(false);
        statusLine.setText("Connecting…");
        if (auth.isSignedIn() && auth.cachedAccessToken() != null) {
            doJoin();
            return;
        }
        // Prompt for the broader Sheets/Drive scope if the joiner hasn't signed in yet.
        auth.authorize(this, new SplitAuth.AuthCallback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(JoinSplitActivity.this::doJoin);
            }
            @Override public void onError(String reason, SplitAuth.PendingUi pendingUi) {
                if (pendingUi != null) {
                    runOnUiThread(() -> launchAuthUi(pendingUi.intentSender));
                } else {
                    runOnUiThread(() -> abort("Sign-in failed: " + reason));
                }
            }
        });
    }

    private void launchAuthUi(IntentSender sender) {
        try {
            authLauncher.launch(new IntentSenderRequest.Builder(sender).build());
        } catch (Exception e) {
            abort("Could not open sign-in: " + e.getMessage());
        }
    }

    private void finishAuth(Intent data) {
        auth.onAuthorizationResult(this, data, new SplitAuth.AuthCallback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(JoinSplitActivity.this::doJoin);
            }
            @Override public void onError(String reason, SplitAuth.PendingUi pendingUi) {
                runOnUiThread(() -> abort("Sign-in failed: " + reason));
            }
        });
    }

    private void doJoin() {
        statusLine.setText("Loading shared splits…");
        SplitCloudSync.joinSharedSheet(this, store, sheetId, ownerEmail,
                ok -> runOnUiThread(() -> {
                    if (ok) {
                        new AlertDialog.Builder(this)
                                .setTitle("Connected")
                                .setMessage("You're now a writer on the shared split book. "
                                        + "Open \"View saved splits\" to see and add splits.")
                                .setPositiveButton("OK", (d, w) -> finish())
                                .setCancelable(false)
                                .show();
                    } else {
                        abort("Could not load shared sheet — make sure the owner added "
                                + "you, then try again.");
                    }
                }));
    }

    private void abort(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finish();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
