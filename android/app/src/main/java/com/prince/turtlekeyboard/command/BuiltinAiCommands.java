package com.prince.turtlekeyboard.command;

import com.prince.kbd.core.CommandProvider;
import com.prince.kbd.core.CommandSpec;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Built-in AI-backed slash commands. These have null handlers — the dispatcher routes
 * them to the AI backend instead of running locally. Lives in {@code :app} (not
 * {@code :core}) because adding/removing AI commands is a product concern, not part of
 * the SPI.
 *
 * <p>Each command carries a default {@link CommandSpec#affinityPkgs} set — the packages
 * where the command should float to the top of the Quick Panel + autocomplete strip.
 * These are starting heuristics; users can override them per-app via the Customize
 * Commands settings screen, which writes to {@code UserCommandPins}.
 */
public final class BuiltinAiCommands implements CommandProvider {

    // Packages used in multiple affinity sets. Keep the literals in one spot so a typo
    // in a package id only breaks one place.
    private static final String PKG_WHATSAPP   = "com.whatsapp";
    private static final String PKG_TELEGRAM   = "org.telegram.messenger";
    private static final String PKG_DISCORD    = "com.discord";
    private static final String PKG_MESSENGER  = "com.facebook.orca";
    private static final String PKG_INSTAGRAM  = "com.instagram.android";
    private static final String PKG_SLACK      = "com.Slack";
    private static final String PKG_GMAIL      = "com.google.android.gm";
    private static final String PKG_OUTLOOK    = "com.microsoft.office.outlook";
    private static final String PKG_LINKEDIN   = "com.linkedin.android";
    private static final String PKG_CHROME     = "com.android.chrome";
    private static final String PKG_NOTION     = "com.notion.id";
    private static final String PKG_KEEP       = "com.google.android.keep";

    /** Image gen — chat-style apps where you want to drop an image inline. */
    private static final Set<String> CHAT_AFFINITY = unmod(
            PKG_WHATSAPP, PKG_TELEGRAM, PKG_DISCORD, PKG_MESSENGER, PKG_INSTAGRAM);

    /** Writing assistance — email + professional chat. */
    private static final Set<String> WRITING_AFFINITY = unmod(
            PKG_GMAIL, PKG_OUTLOOK, PKG_SLACK, PKG_LINKEDIN, PKG_DISCORD);

    /** Reply drafting — email primarily; chat apps live in CHAT_AFFINITY. */
    private static final Set<String> EMAIL_AFFINITY = unmod(PKG_GMAIL, PKG_OUTLOOK);

    /** Translation — places where cross-lingual messages happen most. */
    private static final Set<String> TRANSLATE_AFFINITY = unmod(
            PKG_WHATSAPP, PKG_TELEGRAM, PKG_CHROME, PKG_INSTAGRAM);

    /** Note organizing — Notion, Keep, anywhere structured notes live. */
    private static final Set<String> NOTES_AFFINITY = unmod(PKG_NOTION, PKG_KEEP);

    @Override
    public List<CommandSpec> commands() {
        return Arrays.asList(
                new CommandSpec("cap",    "Image",     "🎨", true,  null, CHAT_AFFINITY),
                new CommandSpec("edit",   "Edit image","🖼️", true,  null, CHAT_AFFINITY),
                new CommandSpec("style",  "Style",     "✨", true,  null, CHAT_AFFINITY),
                new CommandSpec("sticker","Sticker",   "🪄", true,  null, CHAT_AFFINITY),
                new CommandSpec("fix",    "Fix",       "✏️", false, null, WRITING_AFFINITY),
                new CommandSpec("tone",   "Tone",      "🎭", true,  null, WRITING_AFFINITY),
                new CommandSpec("reply",  "Reply",     "💬", false, null, EMAIL_AFFINITY),
                new CommandSpec("tl",     "Translate", "🌐", true,  null, TRANSLATE_AFFINITY),
                new CommandSpec("search", "Search",    "🔍", true,  null),
                new CommandSpec("ask",    "Ask",       "❓", true,  null),
                new CommandSpec("org",    "Organize",  "🗂️", true,  null, NOTES_AFFINITY)
        );
    }

    private static Set<String> unmod(String... pkgs) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(pkgs)));
    }
}
