package com.prince.turtlekeyboard.buildtools.dafsa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Round-trip tests: build a DAFSA, read the on-disk bytes back through a
 * test-local reader that mirrors {@code DafsaDict}, and assert observable
 * behavior (completions, frequencies, minimization). The reader is intentionally
 * duplicated here rather than imported from {@code app/} — it lives in a
 * different Gradle module, and a parallel implementation cross-checks the
 * binary format contract.
 */
public class DafsaBuilderTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final int MAGIC = 0x47574144;
    private static final int VERSION = 1;

    // ---------------- builder API ----------------

    @Test
    public void unsortedInputThrows() {
        DafsaBuilder b = new DafsaBuilder();
        b.add("zebra", 100);
        try {
            b.add("apple", 200);
            fail("expected IllegalArgumentException for unsorted input");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not sorted"));
        }
    }

    @Test
    public void nonAsciiCharThrows() {
        DafsaBuilder b = new DafsaBuilder();
        try {
            b.add("café", 1);
            fail("expected IllegalArgumentException for non-ASCII");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-ASCII"));
        }
    }

    @Test
    public void allowsApostropheAndHyphen() throws Exception {
        Path out = build(line("can't", 100), line("re-do", 50));
        Reader r = Reader.read(out);
        assertTrue(r.contains("can't"));
        assertTrue(r.contains("re-do"));
    }

    // ---------------- header / structure ----------------

    @Test
    public void headerHasMagicAndVersion() throws Exception {
        Path out = build(line("apple", 1));
        ByteBuffer buf = mmapRead(out);
        assertEquals(MAGIC, buf.getInt(0));
        assertEquals(VERSION, buf.getInt(4));
        int rootOffset = buf.getInt(8);
        assertEquals("root must follow the 16-byte header", 16, rootOffset);
    }

    // ---------------- lookup / completions ----------------

    @Test
    public void singleWordRoundTrip() throws Exception {
        Path out = build(line("apple", 100));
        Reader r = Reader.read(out);
        assertTrue(r.contains("apple"));
        assertFalse("prefix is not a word", r.contains("app"));
        assertFalse(r.contains("apples"));
        assertEquals(Collections.singletonList("apple"), r.completions("a", 10));
        assertEquals(Collections.singletonList("apple"), r.completions("apple", 10));
        assertEquals(Collections.emptyList(), r.completions("z", 10));
    }

    @Test
    public void completionsRankedByFrequency() throws Exception {
        Path out = build(
                line("ape", 50),
                line("applaud", 75),
                line("apple", 100));
        Reader r = Reader.read(out);

        List<String> top3 = r.completions("ap", 3);
        assertEquals(Arrays.asList("apple", "applaud", "ape"), top3);

        List<String> top2 = r.completions("ap", 2);
        assertEquals(
                "pruning must keep the highest-frequency matches",
                Arrays.asList("apple", "applaud"),
                top2);

        List<String> top1 = r.completions("appl", 1);
        assertEquals(Collections.singletonList("apple"), top1);
    }

    @Test
    public void completionsIncludePrefixWordWhenItIsTerminal() throws Exception {
        // "app" is itself a word; "ap" prefix should surface it alongside longer matches.
        Path out = build(
                line("app", 200),
                line("apple", 50));
        Reader r = Reader.read(out);
        assertEquals(Arrays.asList("app", "apple"), r.completions("ap", 5));
        assertTrue(r.contains("app"));
        assertTrue(r.contains("apple"));
    }

    @Test
    public void missingPrefixReturnsEmpty() throws Exception {
        Path out = build(line("apple", 1));
        Reader r = Reader.read(out);
        assertEquals(Collections.emptyList(), r.completions("b", 10));
        assertEquals(Collections.emptyList(), r.completions("apricot", 10));
    }

    // ---------------- minimization ----------------

    @Test
    public void sharedTerminalSuffixIsCanonicalized() throws Exception {
        // Two distinct words with identical terminal frequencies AND same suffix
        // — the trailing terminal node is structurally identical, so the builder
        // must canonicalize it to a single shared instance.
        Path out = build(
                line("a", 100),
                line("ba", 100));
        ByteBuffer buf = mmapRead(out);
        int nodeCount = buf.getInt(12);

        // Naive trie would be: root, "a"(term), "b", "ba"(term) = 4.
        // After minimization "a"(term) and "ba"(term) are the same node
        // (terminal, freq=100, no edges) → 3 nodes.
        assertEquals("terminal nodes with same freq should merge", 3, nodeCount);

        Reader r = Reader.read(out);
        assertTrue(r.contains("a"));
        assertTrue(r.contains("ba"));
    }

    @Test
    public void differentFrequenciesPreventMerge() throws Exception {
        // Same shape as above but different frequencies → terminals can't merge.
        Path out = build(
                line("a", 100),
                line("ba", 200));
        ByteBuffer buf = mmapRead(out);
        int nodeCount = buf.getInt(12);
        assertEquals("different freqs ⇒ separate terminal nodes", 4, nodeCount);
    }

    @Test
    public void minimizationShrinksRealisticDict() throws Exception {
        // Pile of words sharing common suffixes; node count must be strictly
        // less than the naive sum of (1 + word_length) per word.
        String[] words = {
                "running", "walking", "talking", "singing", "thinking",
                "runs", "walks", "talks", "sings", "thinks",
                "run", "walk", "talk", "sing", "think",
        };
        Arrays.sort(words);

        List<String> lines = new ArrayList<>();
        for (String w : words) lines.add(line(w, 100));
        Path out = build(lines.toArray(new String[0]));
        ByteBuffer buf = mmapRead(out);
        int nodeCount = buf.getInt(12);

        int naiveNodeCount = 1; // root
        for (String w : words) naiveNodeCount += w.length();
        assertTrue(
                "minimization should produce fewer nodes than a raw trie "
                        + "(got " + nodeCount + ", naive " + naiveNodeCount + ")",
                nodeCount < naiveNodeCount);

        // And every word must still round-trip.
        Reader r = Reader.read(out);
        for (String w : words) {
            assertTrue("missing after build: " + w, r.contains(w));
        }
    }

    // ---------------- max_subtree_freq pruning ----------------

    @Test
    public void maxSubtreeFreqEnablesEarlyExit() throws Exception {
        // The point of max_subtree_freq is that a top-K walk doesn't have to
        // visit every descendant. We assert the walk stays small by counting
        // node visits in the test reader.
        List<String> lines = new ArrayList<>();
        // 100 words under prefix "a", all freq=1 except one super-high freq.
        // Top-1 must short-circuit after finding the one big hit.
        for (int i = 0; i < 100; i++) {
            String w = "a" + String.format("%03d", i);
            int freq = (i == 42) ? 1_000_000 : 1;
            lines.add(line(w, freq));
        }
        Collections.sort(lines);
        Path out = build(lines.toArray(new String[0]));
        Reader r = Reader.read(out);

        r.resetVisitCount();
        List<String> top1 = r.completions("a", 1);
        assertEquals(Collections.singletonList("a042"), top1);
        // Without pruning we'd visit all ~100 terminal nodes plus interior nodes.
        // With pruning the walk should be << 50 — generous bound to stay
        // resilient to construction details.
        int visited = r.lastVisitCount();
        assertTrue(
                "best-first pruning should keep visits low (got " + visited + ")",
                visited < 50);
    }

    // ---------------- helpers ----------------

    private static String line(String word, long freq) {
        return word + " " + freq;
    }

    /** Writes {@code lines} to a temp unigram file, runs the builder, returns the .dawg path. */
    private Path build(String... lines) throws IOException {
        Path src = tmp.newFile("in.txt").toPath();
        try (PrintWriter pw = new PrintWriter(
                Files.newBufferedWriter(src, StandardCharsets.UTF_8))) {
            for (String l : lines) pw.println(l);
        }
        Path out = tmp.newFile("out.dawg").toPath();
        DafsaBuilder.build(src, out);
        return out;
    }

    private static ByteBuffer mmapRead(Path p) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Self-contained reader that mirrors {@code app/.../DafsaDict.java}. Lives
     * in the test only so a format change has to be acknowledged in two places.
     * Tracks visit count so tests can assert pruning behavior.
     */
    private static final class Reader {
        private static final int HEADER_BYTES = 16;
        private static final int NODE_PREFIX_BYTES = 10;
        private static final int EDGE_BYTES = 5;

        private final ByteBuffer buf;
        private final int rootOffset;
        private int visitCount;

        private Reader(ByteBuffer buf, int rootOffset) {
            this.buf = buf;
            this.rootOffset = rootOffset;
        }

        static Reader read(Path p) throws IOException {
            ByteBuffer buf = mmapRead(p);
            int magic = buf.getInt(0);
            if (magic != MAGIC) throw new IOException("bad magic 0x" + Integer.toHexString(magic));
            int version = buf.getInt(4);
            if (version != VERSION) throw new IOException("bad version " + version);
            return new Reader(buf, buf.getInt(8));
        }

        boolean contains(String word) {
            int node = walkTo(word);
            return node >= 0 && frequencyOf(node) > 0;
        }

        List<String> completions(String prefix, int max) {
            int prefixNode = walkTo(prefix);
            if (prefixNode < 0) return Collections.emptyList();

            PriorityQueue<Frame> frontier = new PriorityQueue<>(
                    Comparator.<Frame>comparingInt(f -> f.maxSubtreeFreq).reversed());
            PriorityQueue<Hit> results = new PriorityQueue<>(
                    Math.max(1, max), Comparator.comparingInt(h -> h.frequency));

            frontier.add(new Frame(prefixNode, prefix, maxSubtreeFreqOf(prefixNode)));

            while (!frontier.isEmpty()) {
                Frame f = frontier.poll();
                if (results.size() >= max
                        && results.peek().frequency >= f.maxSubtreeFreq) {
                    continue;
                }
                visitCount++;
                int freq = frequencyOf(f.offset);
                if (freq > 0) {
                    if (results.size() < max) {
                        results.add(new Hit(f.word, freq));
                    } else if (freq > results.peek().frequency) {
                        results.poll();
                        results.add(new Hit(f.word, freq));
                    }
                }
                int edgeCount = edgeCountOf(f.offset);
                int edgesBase = f.offset + NODE_PREFIX_BYTES;
                for (int i = 0; i < edgeCount; i++) {
                    int eOff = edgesBase + i * EDGE_BYTES;
                    char label = (char) (buf.get(eOff) & 0xFF);
                    int childOffset = buf.getInt(eOff + 1);
                    int childMax = maxSubtreeFreqOf(childOffset);
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

        void resetVisitCount() { visitCount = 0; }
        int lastVisitCount() { return visitCount; }

        private int walkTo(String word) {
            int offset = rootOffset;
            for (int i = 0; i < word.length(); i++) {
                offset = followEdge(offset, (byte) word.charAt(i));
                if (offset < 0) return -1;
            }
            return offset;
        }

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

        private int maxSubtreeFreqOf(int o) { return buf.getInt(o); }
        private int frequencyOf(int o) { return buf.getInt(o + 4); }
        private int edgeCountOf(int o) { return buf.getShort(o + 8) & 0xFFFF; }
    }

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
