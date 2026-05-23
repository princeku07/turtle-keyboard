package com.prince.turtlekeyboard.command;

import com.prince.kbd.core.CommandProvider;
import com.prince.kbd.core.CommandSpec;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Built-in AI-backed slash commands. Null handlers route to the AI backend; each carries
 * a default {@link CommandSpec#affinityPkgs} set for Quick Panel ordering per host app.
 */
public final class BuiltinAiCommands implements CommandProvider {

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

    private static final Set<String> CHAT_AFFINITY = unmod(
            PKG_WHATSAPP, PKG_TELEGRAM, PKG_DISCORD, PKG_MESSENGER, PKG_INSTAGRAM);
    private static final Set<String> WRITING_AFFINITY = unmod(
            PKG_GMAIL, PKG_OUTLOOK, PKG_SLACK, PKG_LINKEDIN, PKG_DISCORD);
    private static final Set<String> EMAIL_AFFINITY = unmod(PKG_GMAIL, PKG_OUTLOOK);
    private static final Set<String> TRANSLATE_AFFINITY = unmod(
            PKG_WHATSAPP, PKG_TELEGRAM, PKG_CHROME, PKG_INSTAGRAM);
    private static final Set<String> NOTES_AFFINITY = unmod(PKG_NOTION, PKG_KEEP);

    @Override
    public List<CommandSpec> commands() {
        return Arrays.asList(
                new CommandSpec("cap",     "Image",      "🎨", true,  null, CHAT_AFFINITY,      "Generating image"),
                new CommandSpec("edit",    "Edit image", "🖼️", true,  null, CHAT_AFFINITY,      "Editing image"),
                new CommandSpec("style",   "Style",      "✨", true,  null, CHAT_AFFINITY,      "Restyling image"),
                // /sticker is registered by StickerIntegration for the two-pass difference-matte pipeline.
                new CommandSpec("fix",     "Fix",        "✏️", false, null, WRITING_AFFINITY,   "Fixing text"),
                new CommandSpec("tone",    "Tone",       "🎭", true,  null, WRITING_AFFINITY,   "Adjusting tone"),
                new CommandSpec("reply",   "Reply",      "💬", false, null, EMAIL_AFFINITY,     "Drafting reply"),
                new CommandSpec("tl",      "Translate",  "🌐", true,  null, TRANSLATE_AFFINITY, "Translating"),
                new CommandSpec("search",  "Search",     "🔍", true,  null, Collections.emptySet(), "Searching"),
                new CommandSpec("ask",     "Ask",        "❓", true,  null, Collections.emptySet(), "Thinking"),
                new CommandSpec("org",     "Organize",   "🗂️", true,  null, NOTES_AFFINITY,     "Organizing")
        );
    }

    private static Set<String> unmod(String... pkgs) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(pkgs)));
    }
}
