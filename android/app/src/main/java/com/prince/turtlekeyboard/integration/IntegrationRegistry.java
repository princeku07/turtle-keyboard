package com.prince.turtlekeyboard.integration;

import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;

import com.prince.kbd.core.CommandProvider;
import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.IntegrationContext;
import com.prince.kbd.core.IntegrationSession;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.turtlekeyboard.command.CommandRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the IME's list of {@link KeyboardIntegration}s and dispatches lifecycle events to
 * whichever integration claims the current input session. First-match-wins on activation.
 *
 * <p>Constructed with a separate {@link CommandProvider} list so non-integration providers
 * (e.g. built-in AI commands) can register through the same path. Every command in the
 * registry came from a provider — there is no implicit list anywhere else.
 */
public class IntegrationRegistry {

    private final List<KeyboardIntegration> integrations;
    private final IntegrationContext ctx;
    @Nullable private IntegrationSession active;

    public IntegrationRegistry(List<KeyboardIntegration> integrations,
                               List<CommandProvider> extraProviders,
                               IntegrationContext ctx,
                               CommandRegistry commands) {
        this.integrations = new ArrayList<>(integrations);
        this.ctx = ctx;
        for (CommandProvider p : extraProviders) {
            for (CommandSpec spec : p.commands()) commands.register(spec);
        }
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
