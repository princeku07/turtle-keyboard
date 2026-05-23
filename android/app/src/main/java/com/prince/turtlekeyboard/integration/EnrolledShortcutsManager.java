package com.prince.turtlekeyboard.integration;

import com.prince.kbd.core.AppProfile;
import com.prince.kbd.core.AppProfileRegistry;
import com.prince.kbd.core.CommandSpec;
import com.prince.kbd.core.IntegrationContext;
import com.prince.turtlekeyboard.command.CommandRegistry;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridges {@link AppProfileRegistry} and {@link CommandRegistry} by registering one
 * {@link CommandSpec} per shortcut returned by a {@link SuggestedShortcutSource} for
 * each enrolled app.
 */
public final class EnrolledShortcutsManager {

    private final AppProfileRegistry profiles;
    private final CommandRegistry commands;
    private final SuggestedShortcutSource source;
    private final Set<String> registered = new HashSet<>();

    public EnrolledShortcutsManager(AppProfileRegistry profiles,
                                    CommandRegistry commands,
                                    SuggestedShortcutSource source) {
        this.profiles = profiles;
        this.commands = commands;
        this.source = source;
    }

    /** Register shortcuts for every enrolled app. */
    public void registerAllEnrolled() {
        for (String pkg : profiles.enrolledPackages()) registerFor(pkg);
    }

    /** Register shortcuts for a single pkg. */
    public void registerFor(String pkg) {
        AppProfile profile = profiles.get(pkg);
        if (profile == null) return;
        List<SuggestedShortcut> shortcuts = source.shortcutsFor(pkg, profile.displayName);
        if (shortcuts == null || shortcuts.isEmpty()) return;
        Set<String> affinity = Collections.singleton(pkg);
        for (SuggestedShortcut s : shortcuts) {
            commands.register(new CommandSpec(
                    s.name, s.label, s.emoji, s.needsPrompt,
                    new TemplateHandler(s.template),
                    affinity));
            registered.add(s.name);
        }
    }

    private static final class TemplateHandler implements CommandSpec.Handler {
        private final String template;
        TemplateHandler(String template) { this.template = template; }
        @Override public void handle(String prompt, IntegrationContext ctx) {
            ctx.commitText(template);
        }
    }
}
