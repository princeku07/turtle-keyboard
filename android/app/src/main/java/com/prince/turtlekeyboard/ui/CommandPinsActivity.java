package com.prince.turtlekeyboard.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.AppProfile;
import com.prince.kbd.core.AppProfileRegistry;
import com.prince.kbd.core.CommandProvider;
import com.prince.kbd.core.CommandSpec;
import com.prince.turtlekeyboard.command.BuiltinAiCommands;
import com.prince.turtlekeyboard.command.UserCommandPins;
import com.prince.turtlekeyboard.integration.PersistentAppProfileRegistry;
import com.prince.turtlekeyboard.settings.Prefs;

import com.prince.notion.NotionIntegration;
import com.prince.slack.SlackIntegration;
import com.prince.split.SplitIntegration;
import com.prince.turtlekeyboard.integration.drive.DriveIntegration;
import com.prince.turtlekeyboard.integration.poll.PollIntegration;
import com.prince.turtlekeyboard.integration.wyr.WyrIntegration;
import com.prince.web.WebIntegration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-app command-pin editor. Pinned section reorders with ↑↓ and unpins with ✕;
 * Available section pins on tap. State lives in {@link UserCommandPins}; this activity
 * is the only writer.
 */
public class CommandPinsActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "extra_pkg";

    public static Intent intentFor(Context ctx, String pkg) {
        Intent i = new Intent(ctx, CommandPinsActivity.class);
        i.putExtra(EXTRA_PKG, pkg);
        return i;
    }

    private String pkg;
    private UserCommandPins pins;
    private AppProfileRegistry profiles;
    private Map<String, CommandMeta> all;

    private LinearLayout pinnedList;
    private LinearLayout availableList;
    private TextView pinnedEmpty;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null || pkg.isEmpty()) {
            Toast.makeText(this, "No app specified", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Prefs prefs = new Prefs(this);
        pins = new UserCommandPins(prefs.root().scoped("pins"));
        profiles = new PersistentAppProfileRegistry(getApplicationContext(), prefs.root());
        all = collectCommandMeta();

        setContentView(buildLayout());
        AppProfile profile = profiles.get(pkg);
        setTitle((profile != null ? profile.displayName : pkg));
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private View buildLayout() {
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(Brutal.CREAM);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Brutal.dp(this, 20);
        column.setPadding(pad, pad, pad, pad);
        root.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(appHeader());
        column.addView(subhero(
                "Pinned commands appear first when the slash panel opens in this app. "
                + "Reorder with ↑↓; tap an available command to pin it."));

        // Pinned card
        LinearLayout pinnedCard = card();
        pinnedCard.addView(sectionLabel("Pinned"));
        pinnedList = section();
        pinnedCard.addView(pinnedList);
        pinnedEmpty = empty("Nothing pinned. Tap a command below to pin it here.");
        pinnedCard.addView(pinnedEmpty);
        column.addView(pinnedCard);

        // Available card
        LinearLayout availableCard = card();
        availableCard.addView(sectionLabel("Available"));
        availableList = section();
        availableCard.addView(availableList);
        column.addView(availableCard);

        return root;
    }

    private View appHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, Brutal.dp(this, 6));

        ImageView icon = new ImageView(this);
        Drawable d = iconFor(pkg);
        icon.setImageDrawable(d);
        icon.setVisibility(d == null ? View.GONE : View.VISIBLE);
        int iconPx = Brutal.dp(this, 40);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.rightMargin = Brutal.dp(this, 14);
        row.addView(icon, iconLp);

        AppProfile profile = profiles.get(pkg);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText("CUSTOMIZE COMMANDS");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTextColor(Brutal.MUTED);
        label.setAllCaps(true);
        label.setLetterSpacing(0.1f);
        label.setTypeface(label.getTypeface(), Typeface.BOLD);
        titleCol.addView(label);

        TextView name = new TextView(this);
        name.setText(profile != null ? profile.displayName : pkg);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        name.setTextColor(Brutal.INK);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        titleCol.addView(name);
        row.addView(titleCol);

        return row;
    }

    private void rebuild() {
        pinnedList.removeAllViews();
        availableList.removeAllViews();

        List<String> pinNames = new ArrayList<>(pins.pinsFor(pkg));
        pinNames.removeIf(n -> !all.containsKey(n.toLowerCase()));

        for (int i = 0; i < pinNames.size(); i++) {
            String name = pinNames.get(i);
            boolean isFirst = (i == 0);
            boolean isLast  = (i == pinNames.size() - 1);
            pinnedList.addView(buildPinnedRow(name, all.get(name.toLowerCase()), i, isFirst, isLast));
        }
        pinnedEmpty.setVisibility(pinNames.isEmpty() ? View.VISIBLE : View.GONE);

        for (Map.Entry<String, CommandMeta> e : all.entrySet()) {
            if (pinNames.contains(e.getValue().name)) continue;
            availableList.addView(buildAvailableRow(e.getValue()));
        }
    }

    private View buildPinnedRow(String name, CommandMeta meta, int index,
                                boolean isFirst, boolean isLast) {
        LinearLayout row = baseRow();

        TextView title = commandTitle(meta);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, titleLp);

        TextView upBtn = iconBtn("↑");
        upBtn.setAlpha(isFirst ? 0.25f : 1f);
        upBtn.setOnClickListener(v -> {
            if (!isFirst) { pins.moveUp(pkg, index); rebuild(); }
        });
        row.addView(upBtn);

        TextView downBtn = iconBtn("↓");
        downBtn.setAlpha(isLast ? 0.25f : 1f);
        downBtn.setOnClickListener(v -> {
            if (!isLast) { pins.moveDown(pkg, index); rebuild(); }
        });
        row.addView(downBtn);

        TextView removeBtn = iconBtn("✕");
        removeBtn.setOnClickListener(v -> {
            pins.unpin(pkg, name);
            rebuild();
        });
        row.addView(removeBtn);

        return row;
    }

    private View buildAvailableRow(CommandMeta meta) {
        LinearLayout row = baseRow();
        row.setOnClickListener(v -> {
            pins.pin(pkg, meta.name);
            rebuild();
        });

        TextView title = commandTitle(meta);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, titleLp);

        TextView pinBtn = new TextView(this);
        pinBtn.setText("Pin");
        pinBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pinBtn.setTextColor(Brutal.INK);
        pinBtn.setTypeface(pinBtn.getTypeface(), Typeface.BOLD);
        pinBtn.setGravity(Gravity.CENTER);
        pinBtn.setPadding(Brutal.dp(this, 14), Brutal.dp(this, 6),
                Brutal.dp(this, 14), Brutal.dp(this, 6));
        pinBtn.setBackground(Brutal.buttonPrimary(this));
        pinBtn.setClickable(false);
        row.addView(pinBtn);

        return row;
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padV = Brutal.dp(this, 8), padH = Brutal.dp(this, 4);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowLp);
        return row;
    }

    private TextView commandTitle(CommandMeta meta) {
        TextView t = new TextView(this);
        t.setText(meta.emoji + "  /" + meta.name + "    " + meta.label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTextColor(Brutal.INK);
        return t;
    }

    private TextView iconBtn(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        t.setTextColor(Brutal.INK);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        int w = Brutal.dp(this, 40), h = Brutal.dp(this, 36);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.leftMargin = Brutal.dp(this, 4);
        t.setLayoutParams(lp);
        t.setBackground(Brutal.buttonSecondary(this));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Union of every {@link CommandProvider} the IME registers; keep in sync when adding modules. */
    private Map<String, CommandMeta> collectCommandMeta() {
        Map<String, CommandMeta> out = new LinkedHashMap<>();
        for (CommandProvider p : new CommandProvider[]{
                new BuiltinAiCommands(),
                new SplitIntegration(),
                new NotionIntegration(),
                new SlackIntegration(),
                new WebIntegration(),
                new DriveIntegration(),
                new PollIntegration(),
                new WyrIntegration(),
                new com.prince.turtlekeyboard.integration.sticker.StickerIntegration()}) {
            for (CommandSpec spec : p.commands()) {
                out.put(spec.name.toLowerCase(),
                        new CommandMeta(spec.name, spec.label, spec.emoji));
            }
        }
        return out;
    }

    // -- layout helpers ------------------------------------------------------

    private LinearLayout card() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(Brutal.card(this, Brutal.SURFACE));
        int pad = Brutal.dp(this, 16);
        wrap.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Brutal.dp(this, 14);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    private LinearLayout section() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView subhero(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(Brutal.MUTED);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(0, 0, 0, Brutal.dp(this, 18));
        return t;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        t.setTextColor(Brutal.MUTED);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, 0, 0, Brutal.dp(this, 8));
        return t;
    }

    private TextView empty(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(Brutal.MUTED);
        t.setPadding(Brutal.dp(this, 4), Brutal.dp(this, 8),
                Brutal.dp(this, 4), Brutal.dp(this, 8));
        return t;
    }

    @Nullable
    private Drawable iconFor(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static final class CommandMeta {
        final String name, label, emoji;
        CommandMeta(String n, String l, String e) { name = n; label = l; emoji = e; }
    }
}
