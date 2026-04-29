package com.prince.turtlekeyboard.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.prince.turtlekeyboard.databinding.ActivityMainBinding;

/**
 * Host app entry point — currently the two-step onboarding (PRD §6.2). Future surfaces
 * (Playground, History, Settings) plug in here as separate activities/fragments.
 */
public class MainActivity extends Activity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnEnable.setOnClickListener(v -> openInputMethodSettings());
        binding.btnChoose.setOnClickListener(v -> showInputMethodPicker());
    }

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
