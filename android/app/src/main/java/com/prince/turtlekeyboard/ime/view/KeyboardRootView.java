package com.prince.turtlekeyboard.ime.view;

import android.content.Context;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.theme.KeyboardTheme;

/** Container that holds the suggestion strip, status banner, integration affordances, and
 *  KeyboardView. Exposes typed accessors so the service doesn't reach into res-id internals. */
public class KeyboardRootView extends LinearLayout {

    private SuggestionStripView strip;
    private CommandPanelView panel;
    private PresetChipStripView presetStrip;
    private BannerView banner;
    private VoiceListeningView voiceListening;
    private VoiceStageView voiceStage;
    private ImagePreviewView preview;
    private IntegrationChipView chip;
    private AppEnrollmentBannerView enrollmentBanner;
    private CommandSuggestionStripView cmdSuggestions;
    private HostAppBadgeView hostAppBadge;
    private FrameLayout panelHost;
    private FrameLayout quickPanelHost;
    private ShimmerView shimmer;
    private GeneratingLoaderView generatingLoader;
    private KeyboardView keyboard;

    public KeyboardRootView(Context context) { super(context); }
    public KeyboardRootView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        strip = findViewById(R.id.suggestion_strip);
        panel = findViewById(R.id.command_panel);
        presetStrip = findViewById(R.id.preset_strip);
        banner = findViewById(R.id.banner);
        voiceListening = findViewById(R.id.voice_listening);
        voiceStage = findViewById(R.id.voice_stage);
        preview = findViewById(R.id.image_preview);
        chip = findViewById(R.id.integration_chip);
        enrollmentBanner = findViewById(R.id.enrollment_banner);
        cmdSuggestions = findViewById(R.id.cmd_suggestions);
        hostAppBadge = findViewById(R.id.host_app_badge);
        panelHost = findViewById(R.id.integration_panel_host);
        quickPanelHost = findViewById(R.id.quick_panel_host);
        shimmer = findViewById(R.id.shimmer);
        generatingLoader = findViewById(R.id.generating_loader);
        keyboard = findViewById(R.id.keyboard_view);
    }

    public SuggestionStripView strip() { return strip; }
    public CommandPanelView panel() { return panel; }
    public PresetChipStripView presetStrip() { return presetStrip; }
    public BannerView banner() { return banner; }
    public VoiceListeningView voiceListening() { return voiceListening; }
    public VoiceStageView voiceStage() { return voiceStage; }
    public ImagePreviewView preview() { return preview; }
    public IntegrationChipView chip() { return chip; }
    public AppEnrollmentBannerView enrollmentBanner() { return enrollmentBanner; }
    public CommandSuggestionStripView cmdSuggestions() { return cmdSuggestions; }
    public HostAppBadgeView hostAppBadge() { return hostAppBadge; }
    /** Generic slot integrations attach panel views to. Visibility flips when content
     *  is added/removed by callers. */
    public ViewGroup panelHost() { return panelHost; }
    /** Slot the Quick Panel mounts into. Sized to match the keyboard area when shown,
     *  so the keys are replaced (not overlaid) by the command grid. */
    public ViewGroup quickPanelHost() { return quickPanelHost; }
    public ShimmerView shimmer() { return shimmer; }
    public GeneratingLoaderView generatingLoader() { return generatingLoader; }
    public KeyboardView keyboardView() { return keyboard; }

    public void applyTheme(KeyboardTheme theme) {
        setBackgroundColor(theme.background);
        if (strip != null) strip.applyTheme(theme);
        // Banner colours are fixed by the dark gradient design (set in XML);
        // theme tokens here would pull it back to white. Intentionally skipped.
        if (voiceListening != null) voiceListening.applyTheme(theme);
        if (voiceStage != null) voiceStage.applyTheme(theme);
        if (panel != null) panel.applyTheme(theme);
        if (presetStrip != null) presetStrip.applyTheme(theme);
        if (enrollmentBanner != null) enrollmentBanner.applyTheme(theme);
        if (cmdSuggestions != null) cmdSuggestions.applyTheme(theme);
        if (preview != null) preview.applyTheme(theme);
        if (keyboard instanceof TurtleKeyboardView) {
            ((TurtleKeyboardView) keyboard).applyTheme(theme);
        } else if (keyboard != null) {
            keyboard.setBackgroundColor(theme.background);
        }
    }
}
