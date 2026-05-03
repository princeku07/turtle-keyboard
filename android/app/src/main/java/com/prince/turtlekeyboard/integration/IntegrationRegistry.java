package com.prince.turtlekeyboard.integration;

import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.split.kbd.CommandSpec;
import com.prince.split.kbd.IntegrationContext;
import com.prince.split.kbd.IntegrationSession;
import com.prince.split.kbd.KeyboardIntegration;
import com.prince.turtlekeyboard.command.CommandRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the IME's list of {@link KeyboardIntegration}s and dispatches lifecycle events to
 * whichever integration claims the current input session. First-match-wins on activation.
 *
 * <p>Also forwards each integration's slash commands into the keyboard's
 * {@link CommandRegistry} on construction.
 */
public class IntegrationRegistry {

    private final List<KeyboardIntegration> integrations;
    private final IntegrationContext ctx;
    @Nullable private IntegrationSession active;

    public IntegrationRegistry(List<KeyboardIntegration> integrations,
                               IntegrationContext ctx,
                               CommandRegistry commands) {
        this.integrations = new ArrayList<>(integrations);
        this.ctx = ctx;
        for (KeyboardIntegration i : this.integrations) {
            for (CommandSpec spec : i.commands()) commands.register(spec);
        }
    }

    public void onInputStart(@Nullable EditorInfo info) {
        deactivate();
        for (KeyboardIntegration i : integrations) {
            IntegrationSession s = i.activate(info, ctx);
            if (s != null) { active = s; return; }
        }
    }

    public void onTextChanged(CharSequence before, CharSequence after) {
        if (active != null) active.onTextChanged(before, after);
    }

    public void onInputEnd() {
        deactivate();
    }

    private void deactivate() {
        if (active != null) {
            active.onDeactivate();
            active = null;
        }
        ctx.hideChip();
        ctx.hidePanel();
    }
}
