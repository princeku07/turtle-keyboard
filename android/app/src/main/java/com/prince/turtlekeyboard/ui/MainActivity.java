package com.prince.turtlekeyboard.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.notion.ui.NotionConnectActivity;
import com.prince.slack.ui.SlackConnectActivity;
import com.prince.split.SplitAuth;
import com.prince.split.SplitCloudSync;
import com.prince.split.SplitContract;
import com.prince.split.SplitKeys;
import com.prince.split.ui.SplitActivity;
import com.prince.turtlekeyboard.databinding.ActivityMainBinding;
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

    private ActivityMainBinding binding;
    private Prefs prefs;
    private SplitAuth auth;
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
        auth = new SplitAuth(this, prefs);

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

        refreshSplitStatus();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            requestMicPermission();
        }
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
            SplitCloudSync.ensureSheet(this, prefs, changed -> {
                SplitCloudSync.fetchAndMerge(MainActivity.this, prefs, null);
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
        signInDialog = new AlertDialog.Builder(this)
                .setTitle("Sign in to sync your splits")
                .setMessage(
                        "Turtle Keyboard saves your splits to a private spreadsheet in your "
                        + "Google Drive — only you can read it.\n\n"
                        + "What you allow:\n"
                        + "• Read & write the one sheet this app creates\n"
                        + "• Nothing else in your Drive is ever touched")
                .setCancelable(false)
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
        auth.authorize(this, new SplitAuth.AuthCallback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(MainActivity.this::onSignedIn);
            }
            @Override public void onError(String reason, SplitAuth.PendingUi pendingUi) {
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
        auth.onAuthorizationResult(this, data, new SplitAuth.AuthCallback() {
            @Override public void onToken(String accessToken) {
                runOnUiThread(MainActivity.this::onSignedIn);
            }
            @Override public void onError(String reason, SplitAuth.PendingUi pendingUi) {
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
        SplitCloudSync.ensureSheet(this, prefs, ready -> {
            SplitCloudSync.fetchAndMerge(MainActivity.this, prefs, null);
        });
    }

    // -- Split ----------------------------------------------------------------

    private void refreshSplitStatus() {
        int n = prefs.getInt(SplitKeys.DEFAULT_PEOPLE, SplitContract.DEFAULT_PEOPLE);
        binding.splitStatus.setText("Default split: " + n + (n == 1 ? " person" : " people"));
    }

    private void showSplitPicker() {
        int current = prefs.getInt(SplitKeys.DEFAULT_PEOPLE, SplitContract.DEFAULT_PEOPLE);

        NumberPicker picker = new NumberPicker(this);
        picker.setMinValue(SplitContract.MIN_PEOPLE);
        picker.setMaxValue(SplitContract.MAX_PEOPLE);
        picker.setValue(current);
        picker.setWrapSelectorWheel(false);

        new AlertDialog.Builder(this)
                .setTitle("Split between how many?")
                .setView(picker)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.putInt(SplitKeys.DEFAULT_PEOPLE, picker.getValue());
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
