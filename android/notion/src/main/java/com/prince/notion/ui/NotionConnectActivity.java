package com.prince.notion.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.notion.NotionAuth;
import com.prince.notion.NotionClient;
import com.prince.notion.NotionKeys;
import com.prince.split.SplitContract;
import com.prince.split.SplitStore;
import com.prince.split.SharedPreferencesSplitStore;

import java.util.List;

/**
 * Three-stage onboarding screen:
 *
 * <ol>
 *   <li>Initial — "Connect Notion" button launches the OAuth browser flow.</li>
 *   <li>OAuth callback — when Notion redirects back to {@code turtlekeyboard://notion-redirect},
 *       this Activity (singleTask + intent-filter) re-enters with the {@code ?code=}, exchanges
 *       it for an access token, then fetches granted top-level pages.</li>
 *   <li>Parent picker — user taps one page from the list; that becomes the default
 *       parent for every {@code /notion} dispatch from the keyboard.</li>
 * </ol>
 */
public class NotionConnectActivity extends AppCompatActivity {

    private SplitStore store;
    private NotionAuth auth;
    private LinearLayout column;
    private TextView statusView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SharedPreferencesSplitStore(getApplicationContext(), SplitContract.STORAGE_FILE);
        auth = new NotionAuth(store);

        ScrollView root = new ScrollView(this);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);
        root.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        setTitle("Connect Notion");

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        statusView.setTextColor(0x99000000);
        statusView.setPadding(0, dp(8), 0, dp(16));
        column.addView(title("Connect Notion"));
        column.addView(statusView);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(@Nullable Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        String code = data == null ? null : data.getQueryParameter("code");
        String error = data == null ? null : data.getQueryParameter("error");

        if (error != null) {
            renderInitial("Notion declined: " + error);
            return;
        }
        if (code != null && !code.isEmpty() && !auth.isSignedIn()) {
            renderExchanging();
            auth.exchangeCode(getApplicationContext(), code, new NotionAuth.ExchangeCallback() {
                @Override public void onSuccess(String accessToken, @Nullable String workspaceName) {
                    runOnUiThread(() -> renderParentPicker(workspaceName));
                }
                @Override public void onError(String reason) {
                    runOnUiThread(() -> renderInitial("Connect failed: " + reason));
                }
            });
            return;
        }
        if (auth.isSignedIn()) {
            String wsName = store.getString(NotionKeys.WORKSPACE_NAME, "your workspace");
            renderParentPicker(wsName);
            return;
        }
        renderInitial(null);
    }

    private void renderInitial(@Nullable String errorOrNull) {
        clearBelowStatus();
        statusView.setText(errorOrNull == null
                ? "Connect your Notion workspace so the keyboard can create pages from /notion."
                : errorOrNull);
        TextView btn = primaryButton("Connect Notion", v -> {
            try {
                startActivity(auth.authorizeIntent());
            } catch (Exception e) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
            }
        });
        column.addView(btn);
    }

    private void renderExchanging() {
        clearBelowStatus();
        statusView.setText("Finishing connection…");
    }

    private void renderParentPicker(@Nullable String workspaceName) {
        clearBelowStatus();
        statusView.setText("Connected to "
                + (workspaceName == null || workspaceName.isEmpty() ? "your workspace" : workspaceName)
                + ".\nPick a parent page — every /notion dispatch will create a child here.");

        TextView loading = new TextView(this);
        loading.setText("Loading pages…");
        loading.setTextColor(0x99000000);
        loading.setPadding(0, dp(8), 0, dp(8));
        column.addView(loading);

        new NotionClient(auth.accessToken()).searchPages(new NotionClient.SearchCallback() {
            @Override public void onResults(List<NotionClient.Page> pages) {
                runOnUiThread(() -> {
                    column.removeView(loading);
                    if (pages.isEmpty()) {
                        TextView empty = new TextView(NotionConnectActivity.this);
                        empty.setText("No pages granted. In Notion, share a parent page with this "
                                + "integration, then tap Refresh.");
                        empty.setTextColor(0x99000000);
                        empty.setPadding(0, dp(8), 0, dp(8));
                        column.addView(empty);
                        column.addView(primaryButton("Refresh", v -> renderParentPicker(workspaceName)));
                        return;
                    }
                    String currentParent = store.getString(NotionKeys.DEFAULT_PARENT, "");
                    for (NotionClient.Page p : pages) {
                        column.addView(parentRow(p, p.id.equals(currentParent)));
                    }
                    column.addView(secondaryButton("Disconnect", v -> {
                        auth.clear();
                        renderInitial(null);
                    }));
                });
            }
            @Override public void onError(String reason) {
                runOnUiThread(() -> {
                    column.removeView(loading);
                    TextView err = new TextView(NotionConnectActivity.this);
                    err.setText("Couldn't load pages: " + reason);
                    err.setTextColor(0xFFB00020);
                    err.setPadding(0, dp(8), 0, dp(8));
                    column.addView(err);
                    column.addView(primaryButton("Retry", v -> renderParentPicker(workspaceName)));
                });
            }
        });
    }

    private View parentRow(NotionClient.Page page, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = dp(12), padH = dp(14);
        row.setPadding(padH, padV, padH, padV);
        row.setBackground(rounded(selected ? 0x1F00C853 : 0x11000000, dp(10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            store.putString(NotionKeys.DEFAULT_PARENT, page.id);
            store.putString(NotionKeys.DEFAULT_PARENT_T, page.title);
            renderParentPicker(store.getString(NotionKeys.WORKSPACE_NAME, ""));
        });

        TextView title = new TextView(this);
        title.setText(page.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTextColor(0xFF111111);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, titleLp);

        if (selected) {
            TextView mark = new TextView(this);
            mark.setText("✓ default");
            mark.setTextColor(0xFF15803d);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            row.addView(mark);
        }
        return row;
    }

    private void clearBelowStatus() {
        // Keep title + status; drop everything else so renders are idempotent.
        while (column.getChildCount() > 2) column.removeViewAt(2);
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        t.setTextColor(0xFF111111);
        return t;
    }

    private TextView primaryButton(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        t.setBackground(rounded(0xFF15803d, dp(12)));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView secondaryButton(String text, View.OnClickListener click) {
        TextView t = primaryButton(text, click);
        t.setBackground(rounded(0x11000000, dp(12)));
        t.setTextColor(0xFF111111);
        return t;
    }

    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Hold a posted runnable so we can cancel before finish() if needed. */
    @SuppressWarnings("unused")
    private static void postLater(long delayMs, Runnable r) {
        new Handler(Looper.getMainLooper()).postDelayed(r, delayMs);
    }
}
