package com.prince.turtlekeyboard.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.prince.turtlekeyboard.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Catalog of slash commands with category filter chips; each row opens
 * {@link AppPersonalizationActivity}. The list is a hand-curated static catalog
 * because the live registry only materializes inside the IME.
 */
public class CommandsActivity extends AppCompatActivity {

    private enum Category {
        ALL("All"),
        IMAGE("Image"),
        TEXT("Text"),
        REALTIME("Realtime"),
        CONNECTED("Connected");

        final String label;
        Category(String label) { this.label = label; }
    }

    private static final class Cmd {
        final String name;
        final String emoji;
        final String desc;
        final Category type;
        Cmd(String name, String emoji, String desc, Category type) {
            this.name = name; this.emoji = emoji; this.desc = desc; this.type = type;
        }
    }

    private static final List<Cmd> CATALOG = Arrays.asList(
            new Cmd("/cap",     "🎨",  "Generate any image inline", Category.IMAGE),
            new Cmd("/edit",    "🖼",  "Edit an image you picked", Category.IMAGE),
            new Cmd("/style",   "✨",  "Restyle an image", Category.IMAGE),
            new Cmd("/sticker", "🪄",  "Cutout sticker from your prompt", Category.IMAGE),
            new Cmd("/gif",     "🎞",  "Animated GIF from a sprite sheet", Category.IMAGE),
            new Cmd("/fix",     "✏️",  "Fix grammar + tighten the text", Category.TEXT),
            new Cmd("/tone",    "🎭",  "Rewrite in a different tone", Category.TEXT),
            new Cmd("/reply",   "💬",  "Draft a reply from context", Category.TEXT),
            new Cmd("/tl",      "🌐",  "Translate to a target language", Category.TEXT),
            new Cmd("/ask",     "❓",  "Ask a one-shot question", Category.TEXT),
            new Cmd("/poll",    "📊",  "Live poll bottom-sheet", Category.REALTIME),
            new Cmd("/wyr",     "🤔",  "Would-you-rather mini game", Category.REALTIME),
            new Cmd("/puzzle",  "🧩",  "Collaborative photo puzzle", Category.REALTIME),
            new Cmd("/us",      "💕",  "Couples photos from Google Drive", Category.CONNECTED),
            new Cmd("/splits",  "💰",  "Bill splits saved to your Sheets", Category.CONNECTED)
    );

    private LinearLayout commandsList;
    private LinearLayout filterRow;
    private Category active = Category.ALL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_commands);

        commandsList = findViewById(R.id.commands_list);
        filterRow = findViewById(R.id.filter_row);

        ((android.widget.TextView) findViewById(R.id.topbar_title)).setText("Commands");
        findViewById(R.id.topbar_back).setOnClickListener(v -> finish());

        renderFilters();
        renderList();
    }

    private void renderFilters() {
        filterRow.removeAllViews();
        for (Category c : Category.values()) {
            TextView chip = new TextView(this);
            chip.setText(c.label);
            chip.setTextSize(13f);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            boolean sel = c == active;
            chip.setBackgroundResource(sel ? R.drawable.bg_chip_green : R.drawable.bg_chip_mono);
            chip.setTextColor(ContextCompat.getColor(this,
                    sel ? R.color.green : R.color.text_secondary));
            chip.setOnClickListener(v -> { active = c; renderFilters(); renderList(); });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setGravity(Gravity.CENTER);

            filterRow.addView(chip);
        }
    }

    private void renderList() {
        commandsList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        List<Cmd> filtered = new ArrayList<>();
        for (Cmd c : CATALOG) {
            if (active == Category.ALL || c.type == active) filtered.add(c);
        }

        for (Cmd c : filtered) {
            View row = inflater.inflate(R.layout.item_command_row, commandsList, false);
            ((TextView) row.findViewById(R.id.cmd_emoji)).setText(c.emoji);
            ((TextView) row.findViewById(R.id.cmd_name)).setText(c.name);
            ((TextView) row.findViewById(R.id.cmd_type)).setText(c.type.label.toUpperCase());
            ((TextView) row.findViewById(R.id.cmd_desc)).setText(c.desc);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            row.setLayoutParams(lp);

            row.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, AppPersonalizationActivity.class)));

            commandsList.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
