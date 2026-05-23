package com.prince.turtlekeyboard.command;

import androidx.annotation.NonNull;

import com.prince.kbd.core.KeyValueStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Per-app user-configurable command ordering, stored as a comma-separated list per
 * package in a {@link KeyValueStore}. {@link CommandRegistry#allSortedFor} reads this
 * first; missing names are silently skipped and pruned lazily on the next save.
 */
public final class UserCommandPins {

    private final KeyValueStore store;

    public UserCommandPins(KeyValueStore store) {
        this.store = store;
    }

    @NonNull
    public List<String> pinsFor(String pkg) {
        if (pkg == null) return Collections.emptyList();
        String csv = store.getString(pkg, "");
        if (csv == null || csv.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public void setPins(String pkg, List<String> names) {
        if (pkg == null) return;
        if (names == null || names.isEmpty()) {
            store.putString(pkg, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(names.get(i));
        }
        store.putString(pkg, sb.toString());
    }

    /** Append {@code name} to {@code pkg}'s pin list (or no-op if already pinned). */
    public void pin(String pkg, String name) {
        List<String> current = new ArrayList<>(pinsFor(pkg));
        if (current.contains(name)) return;
        current.add(name);
        setPins(pkg, current);
    }

    public void unpin(String pkg, String name) {
        List<String> current = new ArrayList<>(pinsFor(pkg));
        if (current.remove(name)) setPins(pkg, current);
    }

    /** Move the pin at {@code index} up by one position. No-op at the top. */
    public void moveUp(String pkg, int index) {
        List<String> current = new ArrayList<>(pinsFor(pkg));
        if (index <= 0 || index >= current.size()) return;
        Collections.swap(current, index, index - 1);
        setPins(pkg, current);
    }

    /** Move the pin at {@code index} down by one position. No-op at the bottom. */
    public void moveDown(String pkg, int index) {
        List<String> current = new ArrayList<>(pinsFor(pkg));
        if (index < 0 || index >= current.size() - 1) return;
        Collections.swap(current, index, index + 1);
        setPins(pkg, current);
    }
}
