package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

/** Container that holds the suggestion strip, status banner, and KeyboardView. Exposes
 *  typed accessors so the service doesn't reach into res-id internals. */
public class KeyboardRootView extends LinearLayout {

    private SuggestionStripView strip;
    private CommandPanelView panel;
    private BannerView banner;
    private KeyboardView keyboard;

    public KeyboardRootView(Context context) { super(context); }
    public KeyboardRootView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        strip = findViewById(R.id.suggestion_strip);
        panel = findViewById(R.id.command_panel);
        banner = findViewById(R.id.banner);
        keyboard = findViewById(R.id.keyboard_view);
    }

    public SuggestionStripView strip() { return strip; }
    public CommandPanelView panel() { return panel; }
    public BannerView banner() { return banner; }
    public KeyboardView keyboardView() { return keyboard; }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.background);
        if (strip != null) strip.applyTheme(theme);
        if (banner != null) {
            banner.setBackgroundColor(theme.bannerBg);
            banner.setTextColor(theme.bannerText);
        }
        if (panel != null) panel.applyTheme(theme);
        if (keyboard != null) {
            keyboard.setBackgroundColor(theme.background);
        }
    }
}
