package com.prince.turtlekeyboard.integration.drive;

import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyboardIntegration;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drive integration — user-owned Google Drive storage for personalized image commands.
 * Currently a scaffold: /us is registered with chat-app affinity, but the handler
 * short-circuits to a banner until {@code DriveAuth} and the AI-backend dispatch path
 * are filled in.
 *
 * <p>Once those land, the handler becomes a thin gate: if Drive is not linked, open the
 * {@code drive-link} deep screen; if it is, hand off to the same AI dispatcher that
 * services /cap, /edit, etc., with the user's scoped Drive token attached so the backend
 * can fetch reference photos at gen time. After that point, this class is a candidate
 * to move into its own {@code :drive} Gradle module — mirroring split / notion / slack.
 */
public class DriveIntegration implements KeyboardIntegration {

    private static final Set<String> US_AFFINITY = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.whatsapp",
                    "org.telegram.messenger",
                    "com.facebook.orca",
                    "com.instagram.android",
                    "com.snapchat.android"
            )));

    @Override
    public String id() { return "drive"; }

    @Override
    @Nullable
    public IntegrationSession activate(EditorInfo info, IntegrationContext ctx) {
        // Storage-only integration — nothing to surface on focus. /us is callable
        // from any field via the slash menu / autocomplete.
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        // Null handler → CommandDispatcher routes /us to the AI client (LmStudioAiClient).
        // The AI side reads reference photos from prefs.scoped("drive") and validates
        // before hitting Gemini. This integration's job is just to register the command
        // metadata (label, emoji, chat-app affinity) and own the host-app screens
        // (drive-link) wired through ctx.openScreen.
        return Collections.singletonList(
                new CommandSpec("us", "Us", "💕", true, null, US_AFFINITY)
        );
    }
}
