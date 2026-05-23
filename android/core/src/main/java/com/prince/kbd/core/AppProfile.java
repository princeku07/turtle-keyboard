package com.prince.kbd.core;

import java.util.Collections;
import java.util.Set;

/**
 * Profile for a host app the keyboard knows about. Tags drive integration affinity
 * (e.g. a payments integration looks for {@code "payment"}).
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
