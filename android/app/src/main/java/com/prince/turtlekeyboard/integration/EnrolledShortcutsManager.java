package com.prince.turtlekeyboard.integration;

import com.prince.split.kbd.AppProfile;
import com.prince.split.kbd.AppProfileRegistry;
import com.prince.split.kbd.CommandSpec;
import com.prince.split.kbd.IntegrationContext;
import com.prince.turtlekeyboard.command.CommandRegistry;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridges {@link AppProfileRegistry} (which apps the user has enrolled) and
 * {@link CommandRegistry} (which slash commands the keyboard knows about).
 *
 * <p>For each enrolled app the manager asks a {@link SuggestedShortcutSource} for that
 * app's starter shortcuts and registers each one as a {@link CommandSpec} with affinity
 * for the enrolling pkg. Picking the shortcut from the Quick Panel goes through the same
 * composer-confirmation flow as any other command; tapping Go runs a local handler that
 * commits the template into the host editor.
 *
 * <p>Replayed on every IME cold start by walking {@link AppProfileRegistry#enrolledPackages}
 * — no extra storage layer for shortcut registrations themselves.
 */
public final class EnrolledShortcutsManager {

    private final AppProfileRegistry profiles;
    private final CommandRegistry commands;
    private final SuggestedShortcutSource source;
    /** Track triggers we own so a re-registration can update without piling up. */
    private final Set<String> registered = new HashSet<>();

    public EnrolledShortcutsManager(AppProfileRegistry profiles,
                                    CommandRegistry commands,
                                    SuggestedShortcutSource source) {
        this.profiles = profiles;
        this.commands = commands;
        this.source = source;
    }

    /** Register shortcuts for every enrolled app. Call once after the IME finishes
     *  building its registries (so module-contributed commands are already in). */
    public void registerAllEnrolled() {
        for (String pkg : profiles.enrolledPackages()) registerFor(pkg);
    }

    /** Register shortcuts for a single pkg — used when the user accepts the enrollment
     *  banner so suggestions show up without an IME restart. */
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

    /** Commits the template at the cursor when the composer hands off after Go. */
    private static final class TemplateHandler implements CommandSpec.Handler {
        private final String template;
        TemplateHandler(String template) { this.template = template; }
        @Override public void handle(String prompt, IntegrationContext ctx) {
            // Ignore the user's optional override text — the template is the value here.
            // (Future: substitute placeholders with `prompt` for templates that want it.)
            ctx.commitText(template);
        }
    }
}
