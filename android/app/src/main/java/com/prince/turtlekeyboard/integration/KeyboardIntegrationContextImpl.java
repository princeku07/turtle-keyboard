package com.prince.turtlekeyboard.integration;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import com.prince.notion.ui.NotionConnectActivity;
import com.prince.slack.ui.SlackConnectActivity;
import com.prince.split.ui.SplitActivity;

import androidx.annotation.Nullable;

import com.prince.split.SplitStore;
import com.prince.split.kbd.AppProfileRegistry;
import com.prince.split.kbd.ChipSpec;
import com.prince.split.kbd.IntegrationContext;
import com.prince.split.kbd.LlmService;
import com.prince.turtlekeyboard.ime.view.KeyboardRootView;
import com.prince.turtlekeyboard.input.InputCommitter;
import com.prince.turtlekeyboard.settings.Prefs;

/**
 * Adapts the IME's {@link KeyboardRootView} + {@link InputCommitter} + {@link Prefs} into
 * the {@link IntegrationContext} the SDK exposes to integrations.
 */
public class KeyboardIntegrationContextImpl implements IntegrationContext {

    private static final String TAG = "SPLITTEST";

    private final Context appContext;
    private final KeyboardRootView root;
    private final InputCommitter committer;
    private final Prefs prefs;
    private final AppProfileRegistry profiles;
    private final LlmService llm;

    public KeyboardIntegrationContextImpl(Context appContext,
                                          KeyboardRootView root,
                                          InputCommitter committer,
                                          Prefs prefs,
                                          AppProfileRegistry profiles,
                                          LlmService llm) {
        this.appContext = appContext;
        this.root = root;
        this.committer = committer;
        this.prefs = prefs;
        this.profiles = profiles;
        this.llm = llm;
    }

    @Override public Context appContext() { return appContext; }

    @Override public void showPanel(View view) {
        root.panelHost().removeAllViews();
        root.panelHost().addView(view);
        root.panelHost().setVisibility(View.VISIBLE);
    }

    @Override public void hidePanel() {
        root.panelHost().removeAllViews();
        root.panelHost().setVisibility(View.GONE);
    }

    @Override public void showChip(ChipSpec spec, Runnable onTap) {
        Drawable icon = spec.iconPackage == null ? null : iconForPackage(spec.iconPackage);
        root.chip().show(spec.label, icon);
        root.chip().setOnTapListener(onTap == null ? null : onTap::run);
    }

    @Override public void hideChip() { root.chip().hide(); }

    @Override public void showBanner(String text, long autoHideMs) {
        root.banner().showAndAutoHide(text, autoHideMs);
    }

    @Override @Nullable public Drawable iconForPackage(String pkg) {
        try {
            return appContext.getPackageManager().getApplicationIcon(pkg);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            Log.d(TAG, "icon lookup failed for " + pkg);
            return null;
        }
    }

    @Override public SplitStore store() { return prefs; }

    @Override public AppProfileRegistry profiles() { return profiles; }

    @Override public LlmService llm() { return llm; }

    @Override public void commitText(CharSequence text) { committer.commitText(text); }

    @Override public void deleteBeforeCursor(int n) { committer.deleteBeforeCursor(n); }

    @Override public void openScreen(String screenId) {
        // Map integration-supplied screen ids to the host's Activities. When Split spins
        // out into its own APK this becomes an explicit-package Intent instead.
        Class<?> target;
        switch (screenId) {
            case "split-detail":    target = SplitActivity.class; break;
            case "notion-connect":  target = NotionConnectActivity.class; break;
            case "slack-connect":   target = SlackConnectActivity.class; break;
            default:
                Log.w(TAG, "openScreen: unknown id=" + screenId);
                return;
        }
        Intent i = new Intent(appContext, target);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appContext.startActivity(i);
    }
}
