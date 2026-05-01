package com.prince.turtlekeyboard.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.NumberPicker;
import android.widget.Toast;

import com.prince.split.SplitCloudSync;
import com.prince.split.SplitContract;
import com.prince.split.SplitKeys;
import com.prince.turtlekeyboard.databinding.ActivityMainBinding;
import com.prince.turtlekeyboard.settings.Prefs;

/**
 * Host app entry point — keyboard onboarding plus a small playground for the Split feature
 * (set the default head-count, jump straight into the on-demand Split view).
 */
public class MainActivity extends Activity {

    /** Set on the launching Intent when the IME bounces the user here to grant
     *  the RECORD_AUDIO permission. {@link #onCreate} reads it and triggers the
     *  request immediately, then finishes once the OS dialog returns. */
    public static final String EXTRA_REQUEST_MIC = "extra_request_mic";

    private static final int REQ_MIC = 4242;

    private ActivityMainBinding binding;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        prefs = new Prefs(this);

        binding.btnEnable.setOnClickListener(v -> openInputMethodSettings());
        binding.btnChoose.setOnClickListener(v -> showInputMethodPicker());
        binding.btnSetSplit.setOnClickListener(v -> showSplitPicker());

        refreshSplitStatus();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            requestMicPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pull latest splits from the sheet on every app entry so the local cache stays
        // in sync with edits made on other devices.
        SplitCloudSync.syncFromCloud(prefs, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) requestMicPermission();
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
