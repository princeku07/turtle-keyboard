package com.prince.split.kbd;

import java.util.Collections;
import java.util.Set;

/**
 * Profile for a host app the keyboard knows about. Replaces the hardcoded host map that
 * each integration used to carry. Profiles are seeded at startup and (later) extended at
 * runtime via user enrollment + community contributions.
 *
 * <p>Tags drive integration affinity. A payments integration looks for the {@code
 * "payment"} tag; a chat-template integration would look for {@code "chat"}. New
 * integrations add new tags without touching existing ones.
 */
public final class AppProfile {

    public final String pkg;
    public final String displayName;
    public final Set<String> tags;

    public AppProfile(String pkg, String displayName, Set<String> tags) {
        this.pkg = pkg;
        this.displayName = displayName;
        this.tags = tags == null ? Collections.emptySet() : Collections.unmodifiableSet(tags);
    }

    public boolean hasTag(String tag) { return tags.contains(tag); }
}
