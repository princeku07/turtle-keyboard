package com.prince.slack.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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

import com.prince.slack.SlackAuth;
import com.prince.slack.SlackClient;
import com.prince.slack.SlackKeys;
import com.prince.split.SharedPreferencesSplitStore;
import com.prince.split.SplitContract;
import com.prince.split.SplitStore;

import java.util.List;

/**
 * Three-stage onboarding (mirrors {@code NotionConnectActivity}):
 *
 * <ol>
 *   <li>Initial — "Connect Slack" launches the OAuth browser flow.</li>
 *   <li>OAuth callback — App-Link redirect lands here with {@code ?code=…}.</li>
 *   <li>Channel picker — list of channels the user is a member of; tap to set default.</li>
 * </ol>
 *
 * <p>Side effect during the picker render: every visible channel name is also written to
 * {@code slack.channel_map.<name> → id} so {@link com.prince.slack.SlackIntegration}'s
 * {@code #channel} override can resolve names without an extra API call.
 */
public class SlackConnectActivity extends AppCompatActivity {

    private SplitStore store;
    private SlackAuth auth;
    private LinearLayout column;
    private TextView statusView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SharedPreferencesSplitStore(getApplicationContext(), SplitContract.STORAGE_FILE);
        auth = new SlackAuth(store);

        ScrollView root = new ScrollView(this);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, pad);
        root.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        setTitle("Connect Slack");

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        statusView.setTextColor(0x99000000);
        statusView.setPadding(0, dp(8), 0, dp(16));
        column.addView(title("Connect Slack"));
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

        if (error != null) { renderInitial("Slack declined: " + error); return; }
        if (code != null && !code.isEmpty() && !auth.isSignedIn()) {
            renderExchanging();
            auth.exchangeCode(getApplicationContext(), code, new SlackAuth.ExchangeCallback() {
                @Override public void onSuccess(String accessToken, @Nullable String teamName, @Nullable String teamDomain) {
                    // Slack's oauth.v2.access doesn't return team domain; fetch it now so
                    // the channel picker render has it before the user sees this screen again.
                    new SlackClient(accessToken).teamInfo(new SlackClient.TeamInfoCallback() {
                        @Override public void onSuccess(String teamId, String domain) {
                            store.putString(SlackKeys.TEAM_DOMAIN, domain);
                            runOnUiThread(() -> renderChannelPicker(teamName));
                        }
                        @Override public void onError(String reason) {
                            // Non-fatal — the picker still works without a domain.
                            runOnUiThread(() -> renderChannelPicker(teamName));
                        }
                    });
                }
                @Override public void onError(String reason) {
                    runOnUiThread(() -> renderInitial("Connect failed: " + reason));
                }
            });
            return;
        }
        if (auth.isSignedIn()) {
            renderChannelPicker(store.getString(SlackKeys.TEAM_NAME, "your workspace"));
            return;
        }
        renderInitial(null);
    }

    private void renderInitial(@Nullable String errorOrNull) {
        clearBelowStatus();
        statusView.setText(errorOrNull == null
                ? "Connect your Slack workspace so /slack can post messages from the keyboard."
                : errorOrNull);
        column.addView(primaryButton("Connect Slack", v -> {
            try {
                startActivity(auth.authorizeIntent());
            } catch (Exception e) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void renderExchanging() {
        clearBelowStatus();
        statusView.setText("Finishing connection…");
    }

    private void renderChannelPicker(@Nullable String teamName) {
        clearBelowStatus();
        statusView.setText("Connected to "
                + (teamName == null || teamName.isEmpty() ? "your workspace" : teamName)
                + ".\nPick a default channel — every /slack dispatch posts here unless "
                + "you prefix the message with #channel-name.");

        TextView loading = new TextView(this);
        loading.setText("Loading channels…");
        loading.setTextColor(0x99000000);
        loading.setPadding(0, dp(8), 0, dp(8));
        column.addView(loading);

        new SlackClient(auth.accessToken()).listChannels(new SlackClient.ChannelsCallback() {
            @Override public void onResults(List<SlackClient.Channel> channels) {
                runOnUiThread(() -> {
                    column.removeView(loading);
                    if (channels.isEmpty()) {
                        TextView empty = new TextView(SlackConnectActivity.this);
                        empty.setText("No channels found. Join at least one channel in Slack, then tap Refresh.");
                        empty.setTextColor(0x99000000);
                        empty.setPadding(0, dp(8), 0, dp(8));
                        column.addView(empty);
                        column.addView(primaryButton("Refresh", v -> renderChannelPicker(teamName)));
                        return;
                    }
                    String currentChannel = store.getString(SlackKeys.DEFAULT_CHANNEL, "");
                    for (SlackClient.Channel c : channels) {
                        // Side effect: maintain the name→id cache used by /slack #channel.
                        store.putString("slack.channel_map." + c.name.toLowerCase(), c.id);
                        column.addView(channelRow(c, c.id.equals(currentChannel)));
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
                    TextView err = new TextView(SlackConnectActivity.this);
                    err.setText("Couldn't load channels: " + reason);
                    err.setTextColor(0xFFB00020);
                    err.setPadding(0, dp(8), 0, dp(8));
                    column.addView(err);
                    column.addView(primaryButton("Retry", v -> renderChannelPicker(teamName)));
                });
            }
        });
    }

    private View channelRow(SlackClient.Channel channel, boolean selected) {
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
            store.putString(SlackKeys.DEFAULT_CHANNEL, channel.id);
            store.putString(SlackKeys.DEFAULT_CHANNEL_NAME, channel.name);
            renderChannelPicker(store.getString(SlackKeys.TEAM_NAME, ""));
        });

        TextView name = new TextView(this);
        name.setText((channel.isPrivate ? "🔒 " : "# ") + channel.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        name.setTextColor(0xFF111111);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nameLp);

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
}
