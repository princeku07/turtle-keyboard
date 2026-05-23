package com.prince.turtlekeyboard.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.prince.notion.ui.NotionConnectActivity;
import com.prince.slack.ui.SlackConnectActivity;
import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.integration.drive.DriveLinkActivity;
import com.prince.turtlekeyboard.settings.Prefs;
import com.prince.turtlekeyboard.ui.mcp.McpServersActivity;

/**
 * Status-card list for third-party integrations. Connected state is inferred from
 * each module's shared-prefs scope; stale tokens fall through to the per-module auth flow.
 */
public class IntegrationsActivity extends AppCompatActivity {

    private LinearLayout list;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_integrations);
        prefs = new Prefs(this);
        list = findViewById(R.id.integrations_list);

        ((TextView) findViewById(R.id.topbar_title)).setText("Integrations");
        findViewById(R.id.topbar_back).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderCards();
    }

    private void renderCards() {
        // Keep first child (description line); drop previously added cards.
        while (list.getChildCount() > 1) list.removeViewAt(1);

        boolean drive  = prefs.root().scoped("google").getString("access_token", null) != null;
        boolean notion = prefs.root().scoped("notion").getString("access_token", null) != null;
        boolean slack  = prefs.root().scoped("slack").getString("access_token", null) != null;
        boolean mcp    = prefs.root().scoped("usermcp").getString("bindings", null) != null;

        list.addView(makeCard(
                "📁",
                "Google Drive",
                "powers /us · /splits",
                "Photos for /us live in a folder you pick. Splits sync to a private spreadsheet. We never touch the rest of your Drive.",
                drive,
                new Intent(this, DriveLinkActivity.class)));

        list.addView(makeCard(
                "📝",
                "Notion",
                "save outputs to a page",
                "Pick a Notion page and Turtle can append generated text or images there from any /command.",
                notion,
                new Intent(this, NotionConnectActivity.class)));

        list.addView(makeCard(
                "💬",
                "Slack",
                "forward to a channel",
                "Bridge slash output straight into a Slack channel or DM.",
                slack,
                new Intent(this, SlackConnectActivity.class)));

        list.addView(makeCard(
                "🔌",
                "MCP Servers",
                "user-defined commands",
                "Bind any MCP server's tools to your own slash commands. Bring your own backend.",
                mcp,
                new Intent(this, McpServersActivity.class)));
    }

    private View makeCard(String emoji, String name, String command, String desc,
                          boolean connected, Intent target) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_integration_card, list, false);

        ((TextView) v.findViewById(R.id.itg_icon)).setText(emoji);
        ((TextView) v.findViewById(R.id.itg_name)).setText(name);
        ((TextView) v.findViewById(R.id.itg_command)).setText(command);
        ((TextView) v.findViewById(R.id.itg_desc)).setText(desc);

        TextView state = v.findViewById(R.id.itg_state);
        state.setText(connected ? "CONNECTED" : "NOT YET");
        state.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.green : R.color.text_tertiary));

        Button cta = v.findViewById(R.id.itg_cta);
        cta.setText(connected ? "Manage" : "Connect");
        cta.setOnClickListener(x -> startActivity(target));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
