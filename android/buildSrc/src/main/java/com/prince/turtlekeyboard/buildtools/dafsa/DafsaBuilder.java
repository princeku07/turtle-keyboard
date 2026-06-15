package com.prince.turtlekeyboard.buildtools.dafsa;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Build-time tool: reads a unigram file ({@code "<word> <frequency>\n"} per line),
 * builds a minimized DAFSA via Daciuk's incremental algorithm, writes a packed
 * binary the runtime can mmap and walk directly.
 *
 * <p>Binary layout — all little-endian:
 * <pre>
 * header (16 bytes):
 *   u32 magic   = 0x47574144  ('DAWG' in LE)
 *   u32 version = 1
 *   u32 root_offset
 *   u32 node_count
 *
 * node (variable):
 *   u32 max_subtree_freq   (max frequency anywhere at-or-below this node)
 *   u32 frequency          (0 if not a word terminal)
 *   u16 edge_count
 *   edge_count * { u8 label; u32 child_offset }
 * </pre>
 *
 * Edges are sorted by label so the reader can binary-search.
 * Labels are single bytes — the source vocabulary is restricted to lowercase
 * ASCII letters, apostrophe, and hyphen; an exception is thrown otherwise.
 */
public final class DafsaBuilder {

    private static final int MAGIC = 0x47574144;
    private static final int VERSION = 1;

    private final Node root = new Node();
    private String previousWord = "";
    private final HashMap<Node, Node> register = new HashMap<>();
    private int wordCount;

    /** Adds words from a sorted-by-word stream. Caller must pre-sort. */
    public void addAllSorted(Iterable<WordFreq> words) {
        for (WordFreq w : words) add(w.word, w.frequency);
    }

    /** Words MUST be supplied in ascending lexicographic order. */
    public void add(String word, long frequency) {
        if (word.compareTo(previousWord) < 0) {
            throw new IllegalArgumentException(
                    "DAFSA input not sorted: " + previousWord + " > " + word);
        }
        // Cap to u32; English unigram counts fit, but a stray 64-bit value would silently overflow.
        int freq = (int) Math.min(frequency, 0xFFFFFFFFL);

        int common = commonPrefixLen(previousWord, word);
        // Walk down to the deepest reusable state.
        Node last = root;
        for (int i = 0; i < common; i++) last = last.edge(word.charAt(i));

        // Finalize the branch that diverges from `last` toward the previous word.
        // (Everything below `last` along the old path won't change again.)
        if (last.lastChild() != null) replaceOrRegister(last);

        // Splice in the new suffix.
        for (int i = common; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c > 127) {
                throw new IllegalArgumentException("non-ASCII char in dict: " + word);
            }
            Node n = new Node();
            last.addEdge(c, n);
            last = n;
        }
        last.terminal = true;
        last.frequency = freq;

        previousWord = word;
        wordCount++;
    }

    /** Must be called once after the final add() to flush the trailing branch. */
    public void finish() {
        if (root.lastChild() != null) replaceOrRegister(root);
        // Root carries the max over the whole DAFSA; recompute after the final freeze.
        root.maxSubtreeFreq = computeMax(root);
    }

    private int computeMax(Node n) {
        int m = n.frequency;
        for (Edge e : n.edges) {
            if (e.target.maxSubtreeFreq > m) m = e.target.maxSubtreeFreq;
        }
        return m;
    }

    /**
     * Walk the rightmost branch of {@code state} bottom-up; replace each frozen
     * child with the canonical instance from the register, or register it if new.
     * Computes max_subtree_freq as a side-effect since children are finalized
     * before parents.
     */
    private void replaceOrRegister(Node state) {
        Edge last = state.edges.get(state.edges.size() - 1);
        Node child = last.target;
        if (child.lastChild() != null) replaceOrRegister(child);
        child.maxSubtreeFreq = computeMax(child);

        Node canon = register.get(child);
        if (canon != null) {
            last.target = canon;
        } else {
            register.put(child, child);
        }
    }

    /** Serializes the built DAFSA. Call after {@link #finish()}. */
    public void writeTo(Path out) throws IOException {
        // Two passes: (1) DFS-assign byte offsets, (2) write nodes in offset order.
        List<Node> ordered = new ArrayList<>();
        HashMap<Node, Integer> idx = new HashMap<>();
        assignOffsets(root, ordered, idx);

        // Compute byte offsets per node (header = 16 bytes; nodes follow).
        int[] offsets = new int[ordered.size()];
        int pos = 16;
        for (int i = 0; i < ordered.size(); i++) {
            offsets[i] = pos;
            pos += nodeSize(ordered.get(i));
        }
        int totalSize = pos;
        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putInt(offsets[idx.get(root)]);
        buf.putInt(ordered.size());

        for (Node n : ordered) {
            buf.putInt(n.maxSubtreeFreq);
            buf.putInt(n.frequency);
            buf.putShort((short) n.edges.size());
            // Edges sorted by label so the reader can binary-search.
            n.edges.sort(Comparator.comparingInt(e -> e.label & 0xFF));
            for (Edge e : n.edges) {
                buf.put(e.label);
                buf.putInt(offsets[idx.get(e.target)]);
            }
        }

        Files.createDirectories(out.getParent());
        try (FileOutputStream fos = new FileOutputStream(out.toFile());
             DataOutputStream dos = new DataOutputStream(fos)) {
            dos.write(buf.array(), 0, totalSize);
        }
    }

    private static int nodeSize(Node n) {
        return 4 + 4 + 2 + n.edges.size() * 5;
    }

    /** DFS, assigning each unique node an index; the index is used to look up its file offset. */
    private void assignOffsets(Node n, List<Node> ordered, HashMap<Node, Integer> idx) {
        if (idx.containsKey(n)) return;
        idx.put(n, ordered.size());
        ordered.add(n);
        for (Edge e : n.edges) assignOffsets(e.target, ordered, idx);
    }

    private static int commonPrefixLen(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return n;
    }

    // ----- model classes -----

    /** A DAFSA node. Equality is structural over (terminal-with-freq, edges-with-targets). */
    static final class Node {
        final ArrayList<Edge> edges = new ArrayList<>(2);
        boolean terminal;
        int frequency;
        int maxSubtreeFreq;

        Edge lastChild() {
            return edges.isEmpty() ? null : edges.get(edges.size() - 1);
        }

        void addEdge(char label, Node target) {
            edges.add(new Edge((byte) label, target));
        }

        Node edge(char label) {
            for (Edge e : edges) if ((e.label & 0xFF) == label) return e.target;
            return null;
        }

        @Override
        public int hashCode() {
            int h = (terminal ? 1 : 0) * 31 + frequency;
            for (Edge e : edges) h = h * 31 + (e.label & 0xFF) * 31 + System.identityHashCode(e.target);
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Node)) return false;
            Node n = (Node) o;
            if (terminal != n.terminal || frequency != n.frequency) return false;
            if (edges.size() != n.edges.size()) return false;
            for (int i = 0; i < edges.size(); i++) {
                Edge a = edges.get(i), b = n.edges.get(i);
                if (a.label != b.label || a.target != b.target) return false;
            }
            return true;
        }
    }

    static final class Edge {
        final byte label;
        Node target;

        Edge(byte label, Node target) {
            this.label = label;
            this.target = target;
        }
    }

    public static final class WordFreq {
        public final String word;
        public final long frequency;

        public WordFreq(String word, long frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }

    public int wordCount() { return wordCount; }

    public int nodeCount() { return register.size() + 1; }

    // ----- CLI entry: builds en.dawg from a unigram file -----

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: DafsaBuilder <unigrams.txt> <out.dawg>");
            System.exit(2);
        }
        build(Paths.get(args[0]), Paths.get(args[1]));
    }

    public static void build(Path input, Path output) throws IOException {
        List<WordFreq> words = readUnigrams(input);
        words.sort(Comparator.comparing(w -> w.word));

        DafsaBuilder b = new DafsaBuilder();
        for (WordFreq w : words) b.add(w.word, w.frequency);
        b.finish();
        b.writeTo(output);
        System.out.printf(
                "DAFSA: %d words → %d nodes → %d bytes%n",
                b.wordCount(), b.nodeCount(), Files.size(output));
    }

    private static List<WordFreq> readUnigrams(Path input) throws IOException {
        List<WordFreq> out = new ArrayList<>(82_000);
        try (FileInputStream fis = new FileInputStream(input.toFile());
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0 || sp >= line.length() - 1) continue;
                long freq;
                try {
                    freq = Long.parseLong(line.substring(sp + 1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                out.add(new WordFreq(line.substring(0, sp), freq));
            }
        }
        return out;
    }
}
