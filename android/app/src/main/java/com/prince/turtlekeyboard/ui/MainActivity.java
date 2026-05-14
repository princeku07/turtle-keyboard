package com.prince.turtlekeyboard.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.GoogleAuth;
import com.prince.kbd.core.GoogleAuthImpl;
import com.prince.notion.ui.NotionConnectActivity;
import com.prince.slack.ui.SlackConnectActivity;
import com.prince.split.SplitCloudSync;
import com.prince.split.SplitContract;
import com.prince.split.SplitKeys;
import com.prince.split.SplitOAuthScopes;
import com.prince.split.ui.SplitActivity;
import com.prince.turtlekeyboard.databinding.ActivityMainBinding;
import com.prince.turtlekeyboard.integration.drive.DriveLinkActivity;
import com.prince.turtlekeyboard.overlay.BottomSheetActivity;
import com.prince.turtlekeyboard.overlay.OverlayUrls;
import com.prince.turtlekeyboard.settings.Prefs;

/**
 * Host app entry point — keyboard onboarding plus a small playground for the Split feature
 * (set the default head-count, jump straight into the on-demand Split view). Also holds
 * the mandatory Google Sign-In gate that authorizes Sheets/Drive sync for the Split SDK.
 */
public class MainActivity extends AppCompatActivity {

    /** Set on the launching Intent when the IME bounces the user here to grant
     *  the RECORD_AUDIO permission. {@link #onCreate} reads it and triggers the
     *  request immediately, then finishes once the OS dialog returns. */
    public static final String EXTRA_REQUEST_MIC = "extra_request_mic";

    private static final int REQ_MIC = 4242;
    private static final int REQ_NOTIF = 4243;

    private ActivityMainBinding binding;
    private Prefs prefs;
    private com.prince.kbd.core.KeyValueStore splitStore;
    private GoogleAuth auth;
    private AlertDialog signInDialog;

    private final ActivityResultLauncher<IntentSenderRequest> authLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> finishAuth(result.getData()));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = new Prefs(this);
        splitStore = prefs.root().scoped("split");
        auth = new GoogleAuthImpl(this, prefs.root().scoped("google"));

        binding.btnEnable.setOnClickListener(v -> openInputMethodSettings());
        binding.btnChoose.setOnClickListener(v -> showInputMethodPicker());
        binding.btnSetSplit.setOnClickListener(v -> showSplitPicker());
        binding.btnViewSplits.setOnClickListener(v ->
                startActivity(new Intent(this, SplitActivity.class)));
        binding.btnAppPersonalization.setOnClickListener(v ->
                startActivity(new Intent(this, AppPersonalizationActivity.class)));
        binding.btnConnectNotion.setOnClickListener(v ->
                startActivity(new Intent(this, NotionConnectActivity.class)));
        binding.btnConnectSlack.setOnClickListener(v ->
                startActivity(new Intent(this, SlackConnectActivity.class)));
        binding.btnConnectDrive.setOnClickListener(v ->
                startActivity(new Intent(this, DriveLinkActivity.class)));
        binding.btnHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        // Dev button — direct-launches BottomSheetActivity with a synthetic URL so the
        // sheet rails can be verified without depending on deep-link routing (which
        // requires assetlinks.json hosted on the App Link domain). Remove once Cloudflare
        // Worker is live and tap-link-in-chat is verified end-to-end.
        binding.btnPreviewSheet.setOnClickListener(v -> {
            Intent i = new Intent(this, BottomSheetActivity.class);
            i.setData(Uri.parse(OverlayUrls.forArtifact("poll", "testid123")));
            startActivity(i);
        });

        refreshSplitStatus();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            requestMicPermission();
        }
        // Android 13+ runtime permission for the "image ready" notification the IME
        // posts when the keyboard is closed mid-generation. The IME is a Service and
        // can't request runtime perms itself, so we ask here on host-app open.
        maybeRequestNotificationPermission();
    }

    private void maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!auth.isSignedIn()) {
            promptSignIn();
        } else {
            dismissSignInDialog();
            // Heal legacy installs that signed in before the email scope was added.
            auth.fetchAndStoreEmailIfMissing();
            // Provision sheet on first run; pull latest rows on every entry.
            SplitCloudSync.ensureSheet(this, auth, splitStore, changed -> {
                SplitCloudSync.fetchAndMerge(MainActivity.this, auth, splitStore, null);
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) requestMicPermission();
    }

    // -- Sign-in gate --------------------------------------------------------

    private void promptSignIn() {
        if (signInDialog != null && signInDialog.isShowing()) return;
        // Dismissible — users who only want /us, /cap, etc. can skip Split sync entirely.
        // Sheets / Drive scopes are only requested if the user opts in here.
        signInDialog = new AlertDialog.Builder(this)
                .setTitle("Sync your splits across devices?")
                .setMessage(
                        "Optional. Turtle saves your splits to a private spreadsheet in "
                        + "your Google Drive so they sync to your other devices.\n\n"
                        + "What you'd allow:\n"
                        + "• Read & write the one sheet this app creates\n"
                        + "• Nothing else in your Drive is ever touched\n\n"
                        + "Skip this if you only want /us, /cap, and other commands.")
                .setCancelable(true)
                .setNegativeButton("Maybe later", (d, w) -> dismissSignInDialog())
                .setPositiveButton("Continue with Google", (d, w) -> startAuth())
                .create();
        signInDialog.show();
    }

    private void dismissSignInDialog() {
        if (signInDialog != null && signInDialog.isShowing()) {
            signInDialog.dismiss();
        }
        signInDialog = null;
    }

    private void startAuth() {
        auth.authorize(this, SplitOAuthScopes.SCOPES, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(MainActivity.this::onSignedIn);
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                if (pendingUi != null) {
                    launchAuthUi(pendingUi.intentSender);
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "Sign-in failed: " + reason, Toast.LENGTH_LONG).show();
                        promptSignIn();
                    });
                }
            }
        });
    }

    private void launchAuthUi(IntentSender sender) {
        try {
            authLauncher.launch(new IntentSenderRequest.Builder(sender).build());
        } catch (Exception e) {
            Toast.makeText(this, "Could not start sign-in: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void finishAuth(Intent data) {
        auth.onAuthorizationResult(this, data, new GoogleAuth.Callback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(MainActivity.this::onSignedIn);
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Sign-in failed: " + reason, Toast.LENGTH_LONG).show();
                    promptSignIn();
                });
            }
        });
    }

    private void onSignedIn() {
        dismissSignInDialog();
        Toast.makeText(this, "Signed in — provisioning sheet…", Toast.LENGTH_SHORT).show();
        SplitCloudSync.ensureSheet(this, auth, splitStore, ready -> {
            SplitCloudSync.fetchAndMerge(MainActivity.this, auth, splitStore, null);
        });
    }

    // -- Split ----------------------------------------------------------------

    private void refreshSplitStatus() {
        int n = prefs.root().scoped("split").getInt(SplitKeys.DEFAULT_PEOPLE, SplitContract.DEFAULT_PEOPLE);
        binding.splitStatus.setText("Default split: " + n + (n == 1 ? " person" : " people"));
    }

    private void showSplitPicker() {
        int current = prefs.root().scoped("split").getInt(SplitKeys.DEFAULT_PEOPLE, SplitContract.DEFAULT_PEOPLE);

        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(SplitContract.MIN_PEOPLE);
        picker.setMaxValue(SplitContract.MAX_PEOPLE);
        picker.setValue(current);
        picker.setWrapSelectorWheel(false);

        new AlertDialog.Builder(this)
                .setTitle("Split between how many?")
                .setView(picker)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.root().scoped("split").putInt(SplitKeys.DEFAULT_PEOPLE, picker.getValue());
                    refreshSplitStatus();
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -- Mic permission (IME bounces here) -----------------------------------

    private void requestMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mic already enabled", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "Mic enabled — switch back to keyboard" : "Mic denied",
                    Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_NOTIF) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this,
                        "Notifications denied — image-ready alerts won't show",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // -- IME setup helpers ----------------------------------------------------

    private void openInputMethodSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open keyboard settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInputMethodPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }
}
