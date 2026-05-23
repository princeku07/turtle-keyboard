package com.prince.turtlekeyboard.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.GoogleAuth;
import com.prince.kbd.core.GoogleAuthImpl;
import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.settings.Prefs;

/** Settings shell; Account and Privacy are live, Appearance/Typing/Voice rows are wireframed as "SOON". */
public class SettingsActivity extends AppCompatActivity {

    private static final String GITHUB_URL = "https://github.com/PrinceKumarDev/turtle-keyboard";

    private Prefs prefs;
    private GoogleAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        String webClientId = getString(R.string.default_web_client_id);
        auth = new GoogleAuthImpl(this, prefs.root().scoped("google"), webClientId);

        ((TextView) findViewById(R.id.topbar_title)).setText("Settings");
        findViewById(R.id.topbar_back).setOnClickListener(v -> finish());

        bindAccount();
        bindAppearance();
        bindTyping();
        bindVoice();
        bindPrivacy();
    }

    // ---- Account ----

    private void bindAccount() {
        TextView email = findViewById(R.id.account_email);
        TextView plan = findViewById(R.id.account_plan);
        Button signOut = findViewById(R.id.btn_sign_out);
        Button upgrade = findViewById(R.id.btn_upgrade);

        String signedInEmail = auth.isSignedIn() ? auth.accountEmail() : null;
        if (signedInEmail != null) {
            email.setText(signedInEmail);
            plan.setText("Free plan");
            signOut.setVisibility(View.VISIBLE);
            signOut.setOnClickListener(v -> confirmSignOut());
        } else {
            email.setText("Not signed in");
            plan.setText("Sign in to sync /splits and /us across devices");
            signOut.setVisibility(View.GONE);
        }

        upgrade.setOnClickListener(v ->
                Toast.makeText(this, "Subscriptions coming soon", Toast.LENGTH_SHORT).show());
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign out of Turtle?")
                .setMessage("Your /splits and /us data stay in your Google Drive. "
                        + "You can sign back in any time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out", (d, w) -> {
                    auth.signOut();
                    Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show();
                    bindAccount();
                })
                .show();
    }

    // ---- Wireframed rows ----

    private void bindAppearance() {
        configRow(R.id.row_kb_theme, "Keyboard theme", "Carbon", true);
        configRow(R.id.row_app_theme, "App theme", "Dark", true);
    }

    private void bindTyping() {
        configRow(R.id.row_autocorrect, "Autocorrect", "On", true);
        configRow(R.id.row_gesture, "Gesture typing", "Off", true);
        configRow(R.id.row_sound, "Sound on keypress", "Off", true);
        configRow(R.id.row_haptics, "Haptic feedback", "On", true);
    }

    private void bindVoice() {
        configRow(R.id.row_mic, "Microphone", "Allowed", true);
        configRow(R.id.row_voice_lang, "Language", "English (US)", true);
    }

    private void configRow(int id, String title, String sub, boolean soon) {
        View row = findViewById(id);
        if (row == null) return;
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_sub)).setText(sub);

        TextView badge = row.findViewById(R.id.row_badge);
        if (soon) {
            badge.setVisibility(View.VISIBLE);
            badge.setText("SOON");
            row.setAlpha(0.55f);
            row.setOnClickListener(v ->
                    Toast.makeText(this, title + " is coming soon", Toast.LENGTH_SHORT).show());
        } else {
            badge.setVisibility(View.GONE);
            row.setAlpha(1f);
        }
    }

    // ---- Privacy ----

    private void bindPrivacy() {
        findViewById(R.id.btn_github).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't open browser", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
