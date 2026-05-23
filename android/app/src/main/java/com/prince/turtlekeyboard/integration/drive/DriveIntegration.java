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
 * {@code /us} — Drive-backed personalized image command. Registers the slash command
 * with chat-app affinity; the AI dispatcher consumes the reference photos written into
 * {@code ctx.store("drive")}.
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
        return null;
    }

    @Override
    public List<CommandSpec> commands() {
        // Null handler → CommandDispatcher routes /us to the AI client. The AI side
        // reads reference photos from prefs.scoped("drive") before hitting Gemini.
        return Collections.singletonList(
                new CommandSpec("us", "Us", "💕", true, null, US_AFFINITY)
        );
    }
}
