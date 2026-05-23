package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Append-only on-disk log of generated media. Entries are {@code <ts>.<ext>} in
 * {@code filesDir/history/} with a sidecar {@code <ts>.txt} holding
 * {@code command\nprompt}. Capped at {@link #MAX_ENTRIES} via prune on record.
 */
public class ImageHistory {

    private static final String TAG = "ImageHistory";
    private static final int MAX_ENTRIES = 100;

    public static class Entry {
        public final long ts;
        public final String command;
        public final String prompt;
        public final File file;
        Entry(long ts, String command, String prompt, File file) {
            this.ts = ts; this.command = command; this.prompt = prompt; this.file = file;
        }
    }

    public static File historyDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "history");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Recognised extensions; anything else in the directory is ignored.
     *  WebP is required for /sticker so messengers route the alpha through their
     *  sticker pipeline instead of treating it as a photo. */
    private static final String[] TRACKED_EXTS = { ".png", ".gif", ".webp" };

    /** Best-effort copy + sidecar write; IO failures are logged and swallowed. */
    public static void record(Context ctx, File src, String command, String prompt) {
        if (src == null || !src.exists()) return;
        try {
            File dir = historyDir(ctx);
            long ts = System.currentTimeMillis();
            String ext = extensionOf(src.getName(), ".png");
            File copy = new File(dir, ts + ext);
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(copy)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            File meta = new File(dir, ts + ".txt");
            String body = (command == null ? "" : command) + "\n"
                    + (prompt == null ? "" : prompt);
            try (FileOutputStream out = new FileOutputStream(meta)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            prune(dir);
        } catch (Exception e) {
            Log.w(TAG, "history record failed", e);
        }
    }

    /** Newest first. Performs disk I/O — call from a background thread. */
    public static List<Entry> list(Context ctx) {
        File dir = historyDir(ctx);
        File[] files = dir.listFiles((d, name) -> hasTrackedExt(name));
        if (files == null) return Collections.emptyList();
        List<Entry> entries = new ArrayList<>(files.length);
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            String base = name.substring(0, dot);
            long ts;
            try { ts = Long.parseLong(base); } catch (NumberFormatException e) { continue; }
            File meta = new File(dir, ts + ".txt");
            String command = "";
            String prompt = "";
            if (meta.exists()) {
                try (FileInputStream in = new FileInputStream(meta)) {
                    int len = (int) meta.length();
                    byte[] buf = new byte[len];
                    int read = 0;
                    while (read < len) {
                        int r = in.read(buf, read, len - read);
                        if (r < 0) break;
                        read += r;
                    }
                    String text = new String(buf, 0, read, StandardCharsets.UTF_8);
                    int nl = text.indexOf('\n');
                    if (nl >= 0) {
                        command = text.substring(0, nl);
                        prompt = text.substring(nl + 1);
                    } else {
                        command = text;
                    }
                } catch (Exception ignored) {}
            }
            entries.add(new Entry(ts, command, prompt, f));
        }
        Collections.sort(entries, (a, b) -> Long.compare(b.ts, a.ts));
        return entries;
    }

    private static void prune(File dir) {
        File[] media = dir.listFiles((d, name) -> hasTrackedExt(name));
        if (media == null || media.length <= MAX_ENTRIES) return;
        // Filenames are <ts>.<ext>; lexicographic sort = chronological, oldest first.
        Arrays.sort(media, (a, b) -> a.getName().compareTo(b.getName()));
        int toDelete = media.length - MAX_ENTRIES;
        for (int i = 0; i < toDelete; i++) {
            String name = media[i].getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, name.substring(0, dot) + ".txt").delete();
            }
            //noinspection ResultOfMethodCallIgnored
            media[i].delete();
        }
    }

    private static boolean hasTrackedExt(String name) {
        for (String e : TRACKED_EXTS) {
            if (name.endsWith(e)) return true;
        }
        return false;
    }

    private static String extensionOf(String name, String fallback) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return fallback;
        String ext = name.substring(dot).toLowerCase();
        for (String tracked : TRACKED_EXTS) {
            if (tracked.equals(ext)) return tracked;
        }
        return fallback;
    }
}
