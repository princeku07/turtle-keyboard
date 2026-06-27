package com.prince.turtlekeyboard.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import com.google.firebase.auth.FirebaseUser;
import com.prince.kbd.core.GoogleAuth;
import com.prince.kbd.core.GoogleAuthImpl;
import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.auth.FirebaseAuthBridge;
import com.prince.turtlekeyboard.databinding.ActivityMainBinding;
import com.prince.turtlekeyboard.settings.Prefs;

/**
 * Host app Home: live status card plus drill-in cards (Commands, Integrations,
 * History, Settings). Status card cycles NOT_ENABLED → ENABLED_NOT_SELECTED → ACTIVE.
 */
public class MainActivity extends AppCompatActivity {

    /** Set when the IME bounces here to request RECORD_AUDIO. */
    public static final String EXTRA_REQUEST_MIC = "extra_request_mic";

    private static final int REQ_MIC = 4242;
    private static final int REQ_NOTIF = 4243;
    private static final String IME_PACKAGE = "com.prince.turtlekeyboard";
    private static final String IME_SERVICE = "com.prince.turtlekeyboard.ime.TurtleInputMethodService";

    private ActivityMainBinding binding;
    private Prefs prefs;
    private GoogleAuth auth;
    private FirebaseAuthBridge firebaseBridge;
    private boolean firebaseSignInInFlight;

    private final ActivityResultLauncher<IntentSenderRequest> firebaseSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> finishFirebaseSignIn(result.getData()));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Must run before super.onCreate so the system swaps from the splash
        // theme back to Theme.TurtleKeyboard for the rest of the activity life.
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = new Prefs(this);

        // Dev: always show onboarding on launch. Re-gate with KEY_FEATURE_ONBOARDING_SHOWN
        // once the design is locked.
        String campaign = prefs.getString(Prefs.KEY_INSTALL_CAMPAIGN_ID, null);
        startActivity(FeatureOnboardingActivity.intentFor(this, campaign));
        String webClientId = getString(R.string.default_web_client_id);
        auth = new GoogleAuthImpl(this, prefs.root().scoped("google"), webClientId);
        firebaseBridge = new FirebaseAuthBridge(this, auth);

        binding.btnEnable.setOnClickListener(v -> openInputMethodSettings());
        binding.btnChoose.setOnClickListener(v -> showInputMethodPicker());

        binding.cardCommands.setOnClickListener(v ->
                startActivity(new Intent(this, CommandsActivity.class)));
        binding.cardIntegrations.setOnClickListener(v ->
                startActivity(new Intent(this, IntegrationsActivity.class)));
        binding.cardHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        binding.cardSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            requestMicPermission();
        }
        // Android 13+ POST_NOTIFICATIONS for the IME's "image ready" notification; IME can't request itself.
        maybeRequestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        // Silent Firebase Auth backfill: only fires for users with cached Google sign-in.
        if (auth.isSignedIn()
                && firebaseBridge.currentUser() == null
                && !firebaseSignInInFlight) {
            kickoffFirebaseSignIn();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) requestMicPermission();
    }

    // -- Live status -----------------------------------------------------------

    private void refreshStatus() {
        ImeState state = currentImeState();

        switch (state) {
            case ACTIVE:
                binding.statusDot.setBackgroundResource(R.drawable.dot_connected);
                binding.statusLabel.setText("Active");
                binding.statusBody.setText("Turtle is your keyboard. "
                        + "Open any text field and slash to start.");
                binding.onboardingActions.setVisibility(View.GONE);
                binding.heroTitle.setText("Ready to slash.");
                binding.heroSub.setText("Every model, one slash, any app.");
                break;
            case ENABLED_NOT_SELECTED:
                binding.statusDot.setBackgroundResource(R.drawable.dot_pending);
                binding.statusLabel.setText("One step left");
                binding.statusBody.setText("Turtle is installed but not in use. "
                        + "Switch to it to start typing slash commands.");
                binding.onboardingActions.setVisibility(View.VISIBLE);
                binding.btnEnable.setVisibility(View.GONE);
                binding.btnChoose.setVisibility(View.VISIBLE);
                binding.heroTitle.setText("Almost there.");
                binding.heroSub.setText("Pick Turtle in the keyboard switcher.");
                break;
            case NOT_ENABLED:
            default:
                binding.statusDot.setBackgroundResource(R.drawable.dot_pending);
                binding.statusLabel.setText("Setup needed");
                binding.statusBody.setText("Turtle isn't enabled yet. "
                        + "Two taps and you're done.");
                binding.onboardingActions.setVisibility(View.VISIBLE);
                binding.btnEnable.setVisibility(View.VISIBLE);
                binding.btnChoose.setVisibility(View.VISIBLE);
                binding.heroTitle.setText("Let's set up Turtle.");
                binding.heroSub.setText("The AI keyboard. Every model, one slash, any app.");
                break;
        }

        refreshCardSummaries();
    }

    private void refreshCardSummaries() {
        // Static count avoids loading the full registry just to render a summary line.
        binding.commandsSummary.setText("12 commands ready · tap to customize");

        renderIntegrationDots();

        binding.settingsSummary.setText("Account · AI · Privacy");
    }

    private void renderIntegrationDots() {
        LinearLayout host = binding.integrationsDots;
        host.removeAllViews();

        boolean drive = auth.isSignedIn();
        boolean notion = prefs.root().scoped("notion").getString("access_token", null) != null;
        boolean slack = prefs.root().scoped("slack").getString("access_token", null) != null;
        boolean mcp = prefs.root().scoped("usermcp").getString("bindings", null) != null;

        int connected = (drive ? 1 : 0) + (notion ? 1 : 0) + (slack ? 1 : 0) + (mcp ? 1 : 0);
        binding.integrationsSummary.setText(connected + " of 4 connected");

        host.addView(makeIntegrationChip("Drive", drive));
        host.addView(makeIntegrationChip("Notion", notion));
        host.addView(makeIntegrationChip("Slack", slack));
        host.addView(makeIntegrationChip("MCP", mcp));
    }

    private View makeIntegrationChip(String label, boolean connected) {
        int padH = dp(10);
        int padV = dp(6);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(connected ? R.drawable.bg_chip_green : R.drawable.bg_chip_mono);
        chip.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        chip.setLayoutParams(lp);

        View dot = new View(this);
        dot.setBackgroundResource(connected ? R.drawable.dot_connected : R.drawable.dot_pending);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.setMarginEnd(dp(7));
        chip.addView(dot, dotLp);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(connected
                ? ContextCompat.getColor(this, R.color.green)
                : ContextCompat.getColor(this, R.color.text_secondary));
        text.setTextSize(13f);
        chip.addView(text);

        return chip;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // -- IME state probes ------------------------------------------------------

    private enum ImeState { NOT_ENABLED, ENABLED_NOT_SELECTED, ACTIVE }

    private ImeState currentImeState() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return ImeState.NOT_ENABLED;

        boolean enabled = false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            ComponentName cn = info.getComponent();
            if (cn != null
                    && IME_PACKAGE.equals(cn.getPackageName())
                    && IME_SERVICE.equals(cn.getClassName())) {
                enabled = true;
                break;
            }
        }
        if (!enabled) return ImeState.NOT_ENABLED;

        String selected = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (selected != null && selected.startsWith(IME_PACKAGE + "/")) {
            return ImeState.ACTIVE;
        }
        return ImeState.ENABLED_NOT_SELECTED;
    }

    // -- Firebase Auth bridge (silent backfill) --------------------------------

    private void kickoffFirebaseSignIn() {
        firebaseSignInInFlight = true;
        firebaseBridge.ensureSignedIn(this, new FirebaseAuthBridge.Callback() {
            @Override public void onSignedIn(FirebaseUser user) {
                firebaseSignInInFlight = false;
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                if (pendingUi != null) {
                    runOnUiThread(() -> launchFirebaseSignInUi(pendingUi.intentSender));
                    return;
                }
                firebaseSignInInFlight = false;
            }
        });
    }

    private void launchFirebaseSignInUi(IntentSender sender) {
        try {
            firebaseSignInLauncher.launch(new IntentSenderRequest.Builder(sender).build());
        } catch (Exception e) {
            firebaseSignInInFlight = false;
        }
    }

    private void finishFirebaseSignIn(Intent data) {
        firebaseBridge.onSignInActivityResult(this, data, new FirebaseAuthBridge.Callback() {
            @Override public void onSignedIn(FirebaseUser user) {
                firebaseSignInInFlight = false;
            }
            @Override public void onError(String reason, GoogleAuth.PendingUi pendingUi) {
                firebaseSignInInFlight = false;
            }
        });
    }

    // -- Permissions -----------------------------------------------------------

    private void maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

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

    // -- IME setup helpers -----------------------------------------------------

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
