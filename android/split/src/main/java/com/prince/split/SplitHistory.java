package com.prince.split;

import com.prince.kbd.core.KeyValueStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent log of saved splits stored as newline-delimited
 * {@code amount|people|timestampMs} entries. Most-recent first, capped at {@link #MAX}.
 */
public class SplitHistory {

    public static final int MAX = 50;

    public static final class Entry {
        public final double amount;
        public final int people;
        public final long timestampMs;
        Entry(double amount, int people, long timestampMs) {
            this.amount = amount;
            this.people = people;
            this.timestampMs = timestampMs;
        }
    }

    private final KeyValueStore store;

    public SplitHistory(KeyValueStore store) {
        this.store = store;
    }

    /** @return the timestamp stamped on the new entry, so callers can mirror to the cloud. */
    public long add(double amount, int people) {
        long ts = System.currentTimeMillis();
        String line = amount + "|" + people + "|" + ts;
        String existing = store.getString(SplitKeys.HISTORY, "");
        StringBuilder next = new StringBuilder(line);
        if (!existing.isEmpty()) {
            String[] lines = existing.split("\n");
            int keep = Math.min(lines.length, MAX - 1);
            for (int i = 0; i < keep; i++) next.append('\n').append(lines[i]);
        }
        store.putString(SplitKeys.HISTORY, next.toString());
        return ts;
    }

    public List<Entry> all() {
        String s = store.getString(SplitKeys.HISTORY, "");
        if (s.isEmpty()) return Collections.emptyList();
        String[] lines = s.split("\n");
        List<Entry> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            String[] parts = line.split("\\|");
            if (parts.length != 3) continue;
            try {
                out.add(new Entry(
                        Double.parseDouble(parts[0]),
                        Integer.parseInt(parts[1]),
                        Long.parseLong(parts[2])));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public void clear() {
        store.putString(SplitKeys.HISTORY, "");
    }
}
