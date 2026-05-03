package com.prince.turtlekeyboard.integration;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local catalog of shortcut templates keyed by Android package name. No network, no AI —
 * just text templates that survive ENterPromptMode → tap-Go → commit. Each enrolled app
 * gets these registered as commands with affinity for the package, so they float to the
 * top of the Quick Panel inside that app and stay reachable elsewhere too.
 */
public final class StaticSuggestedShortcutSource implements SuggestedShortcutSource {

    private final Map<String, List<SuggestedShortcut>> catalog;

    public StaticSuggestedShortcutSource() {
        Map<String, List<SuggestedShortcut>> m = new HashMap<>();

        m.put("com.Slack", Arrays.asList(
                new SuggestedShortcut("standup", "Standup", "🗓️",
                        "*Yesterday:* \n*Today:* \n*Blockers:* ", true),
                new SuggestedShortcut("eta", "ETA", "⏳",
                        "I'll get back to you by ", true),
                new SuggestedShortcut("oof", "Out of office", "🏖️",
                        "I'm OOO and will respond when I'm back. For urgent items please ping ", true)
        ));

        m.put("com.whatsapp", Arrays.asList(
                new SuggestedShortcut("birthday", "Birthday", "🎂",
                        "Happy birthday! 🎉 Wishing you ", true),
                new SuggestedShortcut("sorry", "Apology", "🙏",
                        "Hey, sorry about that — ", true)
        ));

        m.put("com.notion.id", Arrays.asList(
                new SuggestedShortcut("today", "Today", "📅",
                        "## Today\n- ", true),
                new SuggestedShortcut("meeting", "Meeting notes", "📝",
                        "## Meeting — \n**Attendees:** \n**Notes:**\n- \n**Actions:**\n- [ ] ", true)
        ));

        m.put("com.google.android.gm", Arrays.asList(
                new SuggestedShortcut("followup", "Follow-up", "📧",
                        "Just following up on my previous email — ", true),
                new SuggestedShortcut("oof", "Out of office", "🏖️",
                        "I'm currently out of the office until ", true)
        ));

        m.put("com.instagram.android", Collections.singletonList(
                new SuggestedShortcut("hashtags", "Hashtags", "#️⃣",
                        "#trending #reels #explore ", true)
        ));

        m.put("com.twitter.android", Collections.singletonList(
                new SuggestedShortcut("thread", "Thread", "🧵",
                        "1/ \n\n2/ \n\n3/ ", true)
        ));

        m.put("com.discord", Collections.singletonList(
                new SuggestedShortcut("afk", "AFK", "🚪",
                        "AFK for a bit — back in ", true)
        ));

        // Chrome: search-modifier templates that drop into the omnibox before a query.
        // The user types the rest after the prefix, then submits as normal.
        m.put("com.android.chrome", Arrays.asList(
                new SuggestedShortcut("site", "Site search", "🔎",
                        "site:", true),
                new SuggestedShortcut("reddit", "Search Reddit", "👽",
                        "site:reddit.com ", true),
                new SuggestedShortcut("yt", "Search YouTube", "▶️",
                        "site:youtube.com ", true),
                new SuggestedShortcut("gh", "Search GitHub", "🐙",
                        "site:github.com ", true),
                new SuggestedShortcut("before", "Before date", "📅",
                        "before:", true),
                new SuggestedShortcut("filetype", "By filetype", "📄",
                        "filetype:pdf ", true)
        ));

        this.catalog = Collections.unmodifiableMap(m);
    }

    @Override
    public List<SuggestedShortcut> shortcutsFor(String pkg, String displayName) {
        List<SuggestedShortcut> hit = catalog.get(pkg);
        return hit == null ? Collections.emptyList() : hit;
    }
}
