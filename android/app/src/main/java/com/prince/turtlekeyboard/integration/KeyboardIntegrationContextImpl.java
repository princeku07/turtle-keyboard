package com.prince.turtlekeyboard.integration;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.view.View;

import com.prince.notion.ui.NotionConnectActivity;
import com.prince.slack.ui.SlackConnectActivity;
import com.prince.split.ui.SplitActivity;
import com.prince.turtlekeyboard.integration.drive.DriveLinkActivity;

import androidx.annotation.Nullable;

import com.prince.kbd.core.AppProfileRegistry;
import com.prince.kbd.core.ChipSpec;
import com.prince.kbd.core.GeminiService;
import com.prince.kbd.core.GoogleAuth;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.McpService;
import com.prince.turtlekeyboard.ime.view.KeyboardRootView;
import com.prince.turtlekeyboard.input.InputCommitter;

/**
 * Adapts the IME's {@link KeyboardRootView}, {@link InputCommitter}, and a root
 * {@link KeyValueStore} into the {@link IntegrationContext} surface exposed to
 * integrations.
 */
public class KeyboardIntegrationContextImpl implements IntegrationContext {

    private static final String TAG = "SPLITTEST";

    private final Context appContext;
    private final KeyboardRootView root;
    private final InputCommitter committer;
    private final KeyValueStore rootStore;
    private final AppProfileRegistry profiles;
    private final GeminiService ai;
    private final McpService mcp;
    private final GoogleAuth googleAuth;
    private final ImageBridge imageBridge;

    public KeyboardIntegrationContextImpl(Context appContext,
                                          KeyboardRootView root,
                                          InputCommitter committer,
                                          KeyValueStore rootStore,
                                          AppProfileRegistry profiles,
                                          GeminiService ai,
                                          McpService mcp,
                                          GoogleAuth googleAuth,
                                          ImageBridge imageBridge) {
        this.appContext = appContext;
        this.root = root;
        this.committer = committer;
        this.rootStore = rootStore;
        this.profiles = profiles;
        this.ai = ai;
        this.mcp = mcp;
        this.googleAuth = googleAuth;
        this.imageBridge = imageBridge;
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
        // Trailing "…" is the shared loading-marker convention — show the gradient
        // loader instead of the transient banner.
        if (text != null && text.endsWith("…")) {
            root.generatingLoader().show(text);
            root.banner().clear();
            root.generatingLoader().postDelayed(
                    () -> root.generatingLoader().hide(), autoHideMs);
            return;
        }
        root.generatingLoader().hide();
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

    @Override public KeyValueStore store(String namespace) { return rootStore.scoped(namespace); }

    @Override public AppProfileRegistry profiles() { return profiles; }

    @Override public GeminiService ai() { return ai; }

    @Override public McpService mcp() { return mcp; }

    @Override public GoogleAuth googleAuth() { return googleAuth; }

    @Override public void commitText(CharSequence text) {
        root.generatingLoader().hide();
        committer.commitText(text);
    }

    @Override public void deleteBeforeCursor(int n) { committer.deleteBeforeCursor(n); }

    @Override public void pickImage(ImagePickCallback cb) { imageBridge.pickImage(cb); }

    @Override public void commitImage(Uri uri, String mime) {
        root.generatingLoader().hide();
        imageBridge.commitImage(uri, mime);
    }

    @Override public void openScreen(String screenId) {
        Class<?> target;
        switch (screenId) {
            case "split-detail":    target = SplitActivity.class; break;
            case "notion-connect":  target = NotionConnectActivity.class; break;
            case "slack-connect":   target = SlackConnectActivity.class; break;
            case "drive-link":      target = DriveLinkActivity.class; break;
            default:
                Log.w(TAG, "openScreen: unknown id=" + screenId);
                return;
        }
        Intent i = new Intent(appContext, target);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        appContext.startActivity(i);
    }
}
