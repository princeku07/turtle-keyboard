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
 * Append-only on-disk log of {@code /cap} and {@code /edit} outputs. Each entry is
 * a {@code <ts>.png} in {@code filesDir/history/} with a sidecar {@code <ts>.txt}
 * holding {@code command\nprompt}. Sidecar files keep the format readable from a
 * shell and avoid bringing in a JSON dependency just for two fields.
 *
 * <p>Capped at {@link #MAX_ENTRIES} — older entries are pruned on every record
 * so the directory can't grow unbounded.
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

    /** Copies {@code img} into the history directory under a fresh timestamp and
     *  writes the sidecar. Best-effort — IO failures are logged and swallowed so
     *  history persistence never blocks the user-visible result. */
    public static void record(Context ctx, File img, String command, String prompt) {
        if (img == null || !img.exists()) return;
        try {
            File dir = historyDir(ctx);
            long ts = System.currentTimeMillis();
            File copy = new File(dir, ts + ".png");
            try (FileInputStream in = new FileInputStream(img);
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

    /** Newest entries first. Empty when the directory is missing or unreadable. */
    public static List<Entry> list(Context ctx) {
        File dir = historyDir(ctx);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (files == null) return Collections.emptyList();
        List<Entry> entries = new ArrayList<>(files.length);
        for (File png : files) {
            String base = png.getName().replace(".png", "");
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
                } catch (Exception ignored) { /* keep empty fields */ }
            }
            entries.add(new Entry(ts, command, prompt, png));
        }
        Collections.sort(entries, (a, b) -> Long.compare(b.ts, a.ts));
        return entries;
    }

    private static void prune(File dir) {
        File[] pngs = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (pngs == null || pngs.length <= MAX_ENTRIES) return;
        Arrays.sort(pngs, (a, b) -> a.getName().compareTo(b.getName()));
        int toDelete = pngs.length - MAX_ENTRIES;
        for (int i = 0; i < toDelete; i++) {
            String base = pngs[i].getName().replace(".png", "");
            //noinspection ResultOfMethodCallIgnored
            new File(dir, base + ".txt").delete();
            //noinspection ResultOfMethodCallIgnored
            pngs[i].delete();
        }
    }
}
