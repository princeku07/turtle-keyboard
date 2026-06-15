package com.prince.turtlekeyboard.suggest.dafsa;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * Runtime reader for the build-time DAFSA. The asset is mmapped directly from
 * the APK (requires {@code aaptOptions { noCompress("dawg") }} so the zip entry
 * is uncompressed and page-aligned), so cold-start cost is a single mmap call
 * — no parse, no per-word allocation, no GC pressure.
 *
 * <p>Prefix completion is a best-first walk pruned by per-node
 * {@code max_subtree_freq}: branches whose maximum can't beat the current
 * top-K minimum are skipped entirely, so a 5000-match prefix still returns
 * top-5 after visiting tens of nodes, not thousands.
 *
 * <p>Binary layout is documented in
 * {@code buildSrc/.../DafsaBuilder.java}; all values are little-endian.
 */
public final class DafsaDict {

    private static final String TAG = "TurtleDafsa";
    private static final int MAGIC = 0x47574144;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 16;
    // Per-node fixed prefix: u32 max_freq + u32 freq + u16 edge_count.
    private static final int NODE_PREFIX_BYTES = 10;
    private static final int EDGE_BYTES = 5;

    private final ByteBuffer buf;
    private final int rootOffset;
    private final int nodeCount;

    private DafsaDict(ByteBuffer buf, int rootOffset, int nodeCount) {
        this.buf = buf;
        this.rootOffset = rootOffset;
        this.nodeCount = nodeCount;
    }

    /**
     * Maps {@code assetPath} (e.g. {@code "dict/en.dawg"}) directly from the
     * APK. Returns null and logs on any failure (missing asset, bad magic,
     * unsupported version) — callers should fall back to a degraded path
     * rather than crash the IME.
     */
    public static DafsaDict openFromAsset(Context ctx, String assetPath) {
        try (AssetFileDescriptor afd = ctx.getAssets().openFd(assetPath);
             FileInputStream fis = afd.createInputStream();
             FileChannel ch = fis.getChannel()) {
            ByteBuffer buf = ch.map(
                            FileChannel.MapMode.READ_ONLY,
                            afd.getStartOffset(), afd.getLength())
                    .order(ByteOrder.LITTLE_ENDIAN);
            int magic = buf.getInt(0);
            int version = buf.getInt(4);
            if (magic != MAGIC) {
                Log.e(TAG, "bad magic: 0x" + Integer.toHexString(magic));
                return null;
            }
            if (version != VERSION) {
                Log.e(TAG, "unsupported version: " + version);
                return null;
            }
            int root = buf.getInt(8);
            int nodes = buf.getInt(12);
            return new DafsaDict(buf, root, nodes);
        } catch (IOException e) {
            Log.e(TAG, "failed to mmap " + assetPath, e);
            return null;
        }
    }

    public int nodeCount() { return nodeCount; }

    /** True iff {@code word} is in the dictionary. */
    public boolean contains(String word) {
        if (word == null || word.isEmpty()) return false;
        int node = walkTo(word);
        return node >= 0 && frequencyOf(node) > 0;
    }

    /**
     * Top {@code max} completions of {@code prefix} (the prefix itself is
     * included if it is a complete word). Returned lowercase, ordered by
     * frequency desc.
     */
    public List<String> completions(String prefix, int max) {
        if (prefix == null || prefix.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        String q = prefix.toLowerCase(Locale.ROOT);
        int prefixNode = walkTo(q);
        if (prefixNode < 0) return Collections.emptyList();

        // Best-first frontier ordered by the highest frequency reachable.
        PriorityQueue<Frame> frontier = new PriorityQueue<>(
                Comparator.<Frame>comparingInt(f -> f.maxSubtreeFreq).reversed());
        // Top-K results as a min-heap keyed by frequency — pop the worst when full.
        PriorityQueue<Hit> results = new PriorityQueue<>(
                max, Comparator.comparingInt(h -> h.frequency));

        frontier.add(new Frame(prefixNode, q, maxSubtreeFreqOf(prefixNode)));

        while (!frontier.isEmpty()) {
            Frame f = frontier.poll();
            // Prune: nothing reachable from here can crack the current top-K.
            if (results.size() >= max
                    && results.peek().frequency >= f.maxSubtreeFreq) {
                continue;
            }
            int nodeFreq = frequencyOf(f.offset);
            if (nodeFreq > 0) {
                if (results.size() < max) {
                    results.add(new Hit(f.word, nodeFreq));
                } else if (nodeFreq > results.peek().frequency) {
                    results.poll();
                    results.add(new Hit(f.word, nodeFreq));
                }
            }
            int edgeCount = edgeCountOf(f.offset);
            int edgesBase = f.offset + NODE_PREFIX_BYTES;
            for (int i = 0; i < edgeCount; i++) {
                int eOff = edgesBase + i * EDGE_BYTES;
                char label = (char) (buf.get(eOff) & 0xFF);
                int childOffset = buf.getInt(eOff + 1);
                int childMax = maxSubtreeFreqOf(childOffset);
                // Skip enqueue when even the best child can't beat the cutoff.
                if (results.size() >= max
                        && results.peek().frequency >= childMax) {
                    continue;
                }
                frontier.add(new Frame(childOffset, f.word + label, childMax));
            }
        }

        List<Hit> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.<Hit>comparingInt(h -> h.frequency).reversed());
        List<String> out = new ArrayList<>(sorted.size());
        for (Hit h : sorted) out.add(h.word);
        return out;
    }

    /** Returns the offset of the node reached by following {@code word} from root, or -1. */
    private int walkTo(String word) {
        int offset = rootOffset;
        for (int i = 0; i < word.length(); i++) {
            offset = followEdge(offset, (byte) word.charAt(i));
            if (offset < 0) return -1;
        }
        return offset;
    }

    /** Binary search the sorted edge table of the node at {@code nodeOffset}. */
    private int followEdge(int nodeOffset, byte label) {
        int n = edgeCountOf(nodeOffset);
        int base = nodeOffset + NODE_PREFIX_BYTES;
        int target = label & 0xFF;
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int midLabel = buf.get(base + mid * EDGE_BYTES) & 0xFF;
            if (midLabel == target) {
                return buf.getInt(base + mid * EDGE_BYTES + 1);
            } else if (midLabel < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return -1;
    }

    private int maxSubtreeFreqOf(int nodeOffset) {
        return buf.getInt(nodeOffset);
    }

    private int frequencyOf(int nodeOffset) {
        return buf.getInt(nodeOffset + 4);
    }

    private int edgeCountOf(int nodeOffset) {
        return buf.getShort(nodeOffset + 8) & 0xFFFF;
    }

    /** One element on the best-first frontier. */
    private static final class Frame {
        final int offset;
        final String word;
        final int maxSubtreeFreq;

        Frame(int offset, String word, int maxSubtreeFreq) {
            this.offset = offset;
            this.word = word;
            this.maxSubtreeFreq = maxSubtreeFreq;
        }
    }

    private static final class Hit {
        final String word;
        final int frequency;

        Hit(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }
}
