package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal GIF89a encoder. The keyboard needs this so the "GIF" share option produces
 * real {@code image/gif} bytes. Android's {@code Bitmap.compress} doesn't include GIF,
 * and pulling a library in for one feature isn't worth the weight.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #encode(Bitmap, OutputStream)} — single-frame, opaque. Used by the
 *       "Save as GIF" share path.</li>
 *   <li>{@link #encodeAnimated(List, int, int, OutputStream)} — multi-frame, with
 *       per-frame transparency. Used by {@code /gif} to assemble the sprite sheet
 *       from Nano Banana into a looping animation.</li>
 * </ul>
 *
 * <p>Algorithm:
 * <ul>
 *   <li>Quantize to ≤256 colors by first-fit (most images we render have a small
 *       palette anyway). When the palette fills, every additional color maps to its
 *       nearest existing entry.</li>
 *   <li>For the animated variant, the palette is built once across all frames (one
 *       global color table) so frame-to-frame palette flicker is impossible.</li>
 *   <li>Standard GIF89a structure: header, logical screen descriptor, GCT, optional
 *       NETSCAPE2.0 loop extension, one or more frames (GCE + image descriptor + LZW),
 *       trailer.</li>
 * </ul>
 *
 * <p>LZW compression code adapted from Jef Poskanzer's public-domain Acme GifEncoder
 * (which itself derives from the Unix {@code compress} utility).
 */
public final class GifEncoder {

    private static final String TAG = "GifEncoder";

    private GifEncoder() {}

    public static void encode(Bitmap bitmap, OutputStream out) throws IOException {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] palette = new int[256];
        int paletteSize = 0;
        byte[] indexed = new byte[w * h];
        HashMap<Integer, Integer> cache = new HashMap<>();

        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i] & 0xFFFFFF;
            Integer cached = cache.get(rgb);
            int idx;
            if (cached != null) {
                idx = cached;
            } else if (paletteSize < 256) {
                palette[paletteSize] = rgb;
                idx = paletteSize++;
                cache.put(rgb, idx);
            } else {
                idx = nearestIndex(palette, paletteSize, rgb);
                cache.put(rgb, idx);
            }
            indexed[i] = (byte) idx;
        }

        // ── GIF89a header ───────────────────────────────────────────────
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});

        // Logical screen descriptor
        writeShort(out, w);
        writeShort(out, h);
        // Packed: GCT=1, color_res=7, sort=0, GCT size=7 (=> 256 entries)
        out.write(0xF7);
        out.write(0); // background color index
        out.write(0); // pixel aspect ratio

        // Global color table — always 256 entries (pad with zeros)
        for (int i = 0; i < 256; i++) {
            int p = i < paletteSize ? palette[i] : 0;
            out.write((p >> 16) & 0xff);
            out.write((p >> 8) & 0xff);
            out.write(p & 0xff);
        }

        // Image descriptor
        out.write(0x2C);
        writeShort(out, 0); // left
        writeShort(out, 0); // top
        writeShort(out, w);
        writeShort(out, h);
        out.write(0); // no LCT, no interlace

        // LZW image data
        out.write(8); // initial LZW code size
        new LzwEncoder(indexed, 8).encode(out);
        out.write(0); // block terminator

        // Trailer
        out.write(0x3B);
        out.flush();
    }

    /**
     * Encodes {@code frames} into a single looping animated GIF. All frames must share
     * the same dimensions; the first frame's size is used as the canvas size.
     *
     * <p>Transparency: pixels with alpha &lt; 128 in any frame are encoded as the
     * GIF's transparent index. Disposal method 2 (restore-to-background) is set on
     * every frame, so a transparent region in frame N+1 does not retain pixels from
     * frame N.
     *
     * @param frames         the frames in display order. Must be non-empty, all the
     *                       same width and height, all non-recycled.
     * @param frameDelayCs   delay between frames in centiseconds (1/100 s). For 10 fps
     *                       pass 10.
     * @param loopCount      0 = loop forever (the common case for /gif); 1..65535 =
     *                       loop that many additional times after the first play.
     * @param out            the destination stream.
     */
    public static void encodeAnimated(List<Bitmap> frames, int frameDelayCs,
                                      int loopCount, OutputStream out) throws IOException {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must be non-empty");
        }
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        for (Bitmap b : frames) {
            if (b.getWidth() != w || b.getHeight() != h) {
                throw new IllegalArgumentException("all frames must share the same size");
            }
        }

        // ── Two-pass palette construction ───────────────────────────────────
        // Pass 1: histogram of every opaque pixel's 5-bit-binned RGB across all
        //   frames. Binning to 5 bits/channel (32 levels) collapses anti-aliased
        //   near-white variants (FE/FF/FD/FC ...) into a single bucket so they
        //   don't each claim a palette slot.
        // Pass 2: pick the top-255 most-frequent binned RGBs as the palette.
        //   This is why we're two-pass: first-fit was claiming slots in scan
        //   order, so a row of near-white bg pixels would exhaust the palette
        //   before any subject pixel was seen, and subject pixels would then
        //   nearest-match onto white — collapsing the GIF to silhouette/B&W.
        // Pass 3: re-iterate pixels and emit indices into the chosen palette.
        // Index 0 is reserved as the transparent placeholder regardless.
        int frameCount = frames.size();
        int[][] frameArgb = new int[frameCount][w * h];
        HashMap<Integer, Integer> histogram = new HashMap<>();

        for (int f = 0; f < frameCount; f++) {
            int[] argbBuf = frameArgb[f];
            frames.get(f).getPixels(argbBuf, 0, w, 0, 0, w, h);
            for (int argb : argbBuf) {
                if (((argb >>> 24) & 0xff) < 128) continue;
                int binned = argb & 0xF8F8F8;
                Integer prev = histogram.get(binned);
                histogram.put(binned, prev == null ? 1 : prev + 1);
            }
        }

        // Top-255 by frequency. Sort the entry list once; for typical sheets
        // (≤32K unique binned RGBs) this is well under 10 ms.
        List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(histogram.entrySet());
        Collections.sort(ranked, new Comparator<Map.Entry<Integer, Integer>>() {
            @Override public int compare(Map.Entry<Integer, Integer> a, Map.Entry<Integer, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue()); // desc
            }
        });

        int[] palette = new int[256];
        int paletteSize = 1; // slot 0 reserved (transparent)
        int take = Math.min(255, ranked.size());
        for (int i = 0; i < take; i++) {
            palette[paletteSize++] = ranked.get(i).getKey();
        }

        // Map every distinct binned RGB → palette index once, then reuse.
        // Top-255 colors get exact-match slots; everything else nearest-matches.
        HashMap<Integer, Integer> rgbToIdx = new HashMap<>(paletteSize * 2);
        for (int i = 1; i < paletteSize; i++) {
            rgbToIdx.put(palette[i], i);
        }

        byte[][] indexedFrames = new byte[frameCount][w * h];
        for (int f = 0; f < frameCount; f++) {
            int[] argbBuf = frameArgb[f];
            byte[] indexed = indexedFrames[f];
            int transparentPixels = 0, opaquePixels = 0;
            int minA = 255, maxA = 0;
            int rSum = 0, gSum = 0, bSum = 0;
            int sampleR = -1, sampleG = -1, sampleB = -1;
            int exactHits = 0, nearestMisses = 0;
            for (int i = 0; i < argbBuf.length; i++) {
                int argb = argbBuf[i];
                int alpha = (argb >>> 24) & 0xff;
                if (alpha < minA) minA = alpha;
                if (alpha > maxA) maxA = alpha;
                if (alpha < 128) {
                    indexed[i] = 0;
                    transparentPixels++;
                    continue;
                }
                int binned = argb & 0xF8F8F8;
                int r = (binned >> 16) & 0xff;
                int g = (binned >>  8) & 0xff;
                int b =  binned        & 0xff;
                rSum += r; gSum += g; bSum += b;
                opaquePixels++;
                if (sampleR < 0 && (r != g || g != b)) {
                    sampleR = r; sampleG = g; sampleB = b;
                }
                Integer cached = rgbToIdx.get(binned);
                int idx;
                if (cached != null) {
                    idx = cached;
                    exactHits++;
                } else {
                    idx = nearestIndex(palette, paletteSize, binned, 1);
                    rgbToIdx.put(binned, idx);
                    nearestMisses++;
                }
                indexed[i] = (byte) idx;
            }
            int meanR = opaquePixels == 0 ? 0 : rSum / opaquePixels;
            int meanG = opaquePixels == 0 ? 0 : gSum / opaquePixels;
            int meanB = opaquePixels == 0 ? 0 : bSum / opaquePixels;
            Log.d(TAG, String.format(
                    "frame %d/%d alpha=[%d..%d] op=%d tr=%d mean=(%d,%d,%d) firstChromatic=(%d,%d,%d) exact=%d near=%d",
                    f, frameCount, minA, maxA, opaquePixels, transparentPixels,
                    meanR, meanG, meanB, sampleR, sampleG, sampleB, exactHits, nearestMisses));
            frameArgb[f] = null; // release per-frame buffer once indexed
        }

        // Sample the first 8 non-placeholder palette entries (now sorted by
        // global frequency desc, so palette[1] is the dominant color of the
        // sheet — typically the white background).
        StringBuilder palSample = new StringBuilder("palette[1..");
        int dumpEnd = Math.min(paletteSize, 9);
        palSample.append(dumpEnd - 1).append("]=");
        for (int i = 1; i < dumpEnd; i++) {
            palSample.append(String.format("%06X ", palette[i] & 0xFFFFFF));
        }
        Log.d(TAG, palSample.toString() + " (total " + paletteSize
                + ", uniqueBins=" + histogram.size() + ")");

        // ── GIF89a header + LSD ─────────────────────────────────────────────
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        writeShort(out, w);
        writeShort(out, h);
        out.write(0xF7);   // GCT=1, color_res=7, sort=0, GCT size=7 (=> 256 entries)
        out.write(0);      // background color index — index 0 (transparent)
        out.write(0);      // pixel aspect ratio

        // Global color table — 256 entries, slot 0 = (0,0,0) placeholder. The
        // transparent flag in each frame's GCE is what actually makes index 0
        // render as transparent; the RGB value at slot 0 only shows in viewers
        // that ignore the transparent flag and fall back to the background color.
        for (int i = 0; i < 256; i++) {
            int p = i < paletteSize ? palette[i] : 0;
            out.write((p >> 16) & 0xff);
            out.write((p >> 8) & 0xff);
            out.write(p & 0xff);
        }

        // ── NETSCAPE2.0 application extension (looping) ─────────────────────
        // Standard incantation viewers use to enable infinite-loop playback.
        out.write(0x21);   // extension introducer
        out.write(0xFF);   // application extension label
        out.write(0x0B);   // block size (11)
        out.write(new byte[]{'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0'});
        out.write(0x03);   // sub-block size
        out.write(0x01);   // sub-block id
        writeShort(out, loopCount & 0xFFFF);
        out.write(0x00);   // block terminator

        // ── Per-frame blocks ────────────────────────────────────────────────
        for (int f = 0; f < frameCount; f++) {
            // Graphic Control Extension: transparency on, disposal=2 (restore
            // background), delay = frameDelayCs centiseconds.
            //   packed byte layout: reserved(3) | disposal(3) | userInput(1) | transparency(1)
            //   disposal=2, userInput=0, transparency=1 → 0b00001001 = 0x09
            out.write(0x21);
            out.write(0xF9);
            out.write(0x04);   // block size
            out.write(0x09);
            writeShort(out, frameDelayCs);
            out.write(0x00);   // transparent color index
            out.write(0x00);   // block terminator

            // Image Descriptor — full-canvas frame, no local color table.
            out.write(0x2C);
            writeShort(out, 0); // left
            writeShort(out, 0); // top
            writeShort(out, w);
            writeShort(out, h);
            out.write(0x00);   // packed: no LCT, no interlace, no sort

            // LZW image data
            out.write(8);      // initial LZW code size
            new LzwEncoder(indexedFrames[f], 8).encode(out);
            out.write(0x00);   // block terminator
        }

        out.write(0x3B);       // trailer
        out.flush();
    }

    /** Nearest-match in palette[from..size). The original single-frame {@link #encode}
     *  scans from index 0; the animated path needs to skip the transparent placeholder
     *  at slot 0 so opaque pixels never collapse onto the transparent index. */
    private static int nearestIndex(int[] palette, int size, int rgb, int from) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        int best = from, bestDist = Integer.MAX_VALUE;
        for (int k = from; k < size; k++) {
            int p = palette[k];
            int dr = ((p >> 16) & 0xff) - r;
            int dg = ((p >> 8) & 0xff) - g;
            int db = (p & 0xff) - b;
            int d = dr * dr + dg * dg + db * db;
            if (d < bestDist) { bestDist = d; best = k; }
        }
        return best;
    }

    private static int nearestIndex(int[] palette, int size, int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int k = 0; k < size; k++) {
            int p = palette[k];
            int dr = ((p >> 16) & 0xff) - r;
            int dg = ((p >> 8) & 0xff) - g;
            int db = (p & 0xff) - b;
            int d = dr * dr + dg * dg + db * db;
            if (d < bestDist) { bestDist = d; best = k; }
        }
        return best;
    }

    private static void writeShort(OutputStream o, int v) throws IOException {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    // ── LZW (GIF variant) ───────────────────────────────────────────────
    // Adapted from Jef Poskanzer's Acme GifEncoder (public domain).
    private static final class LzwEncoder {
        private static final int EOF = -1;
        private static final int BITS = 12;
        private static final int HSIZE = 5003;

        private final byte[] pixels;
        private final int initCodeSize;
        private int remaining, curPixel;

        private int nBits;
        private int maxbits = BITS;
        private int maxcode;
        private final int maxmaxcode = 1 << BITS;
        private final int[] htab = new int[HSIZE];
        private final int[] codetab = new int[HSIZE];
        private int freeEnt = 0;
        private boolean clearFlg = false;
        private int gInitBits;
        private int clearCode;
        private int eofCode;
        private int curAccum = 0;
        private int curBits = 0;
        private int aCount;
        private final byte[] accum = new byte[256];

        private static final int[] MASKS = {
                0x0000, 0x0001, 0x0003, 0x0007, 0x000F,
                0x001F, 0x003F, 0x007F, 0x00FF,
                0x01FF, 0x03FF, 0x07FF, 0x0FFF,
                0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
        };

        LzwEncoder(byte[] pixels, int colorDepth) {
            this.pixels = pixels;
            this.initCodeSize = Math.max(2, colorDepth);
        }

        void encode(OutputStream out) throws IOException {
            remaining = pixels.length;
            curPixel = 0;
            compress(initCodeSize + 1, out);
        }

        private int maxcode(int n) { return (1 << n) - 1; }

        private int nextPixel() {
            if (remaining == 0) return EOF;
            remaining--;
            return pixels[curPixel++] & 0xff;
        }

        private void compress(int initBits, OutputStream out) throws IOException {
            int fcode, c, ent, disp, hsizeReg, hshift;

            gInitBits = initBits;
            clearFlg = false;
            nBits = gInitBits;
            maxcode = maxcode(nBits);
            clearCode = 1 << (initBits - 1);
            eofCode = clearCode + 1;
            freeEnt = clearCode + 2;
            aCount = 0;

            ent = nextPixel();
            hshift = 0;
            for (fcode = HSIZE; fcode < 65536; fcode *= 2) hshift++;
            hshift = 8 - hshift;
            hsizeReg = HSIZE;
            clearHash(hsizeReg);
            output(clearCode, out);

            outerLoop:
            while ((c = nextPixel()) != EOF) {
                fcode = (c << maxbits) + ent;
                int i = (c << hshift) ^ ent;

                if (htab[i] == fcode) {
                    ent = codetab[i];
                    continue;
                } else if (htab[i] >= 0) {
                    disp = hsizeReg - i;
                    if (i == 0) disp = 1;
                    do {
                        if ((i -= disp) < 0) i += hsizeReg;
                        if (htab[i] == fcode) {
                            ent = codetab[i];
                            continue outerLoop;
                        }
                    } while (htab[i] >= 0);
                }
                output(ent, out);
                ent = c;
                if (freeEnt < maxmaxcode) {
                    codetab[i] = freeEnt++;
                    htab[i] = fcode;
                } else {
                    clearBlock(out);
                }
            }
            output(ent, out);
            output(eofCode, out);
        }

        private void clearHash(int hsize) {
            for (int i = 0; i < hsize; i++) htab[i] = -1;
        }

        private void clearBlock(OutputStream out) throws IOException {
            clearHash(HSIZE);
            freeEnt = clearCode + 2;
            clearFlg = true;
            output(clearCode, out);
        }

        private void output(int code, OutputStream out) throws IOException {
            curAccum &= MASKS[curBits];
            if (curBits > 0) curAccum |= (code << curBits);
            else curAccum = code;
            curBits += nBits;

            while (curBits >= 8) {
                charOut((byte) (curAccum & 0xff), out);
                curAccum >>= 8;
                curBits -= 8;
            }

            if (freeEnt > maxcode || clearFlg) {
                if (clearFlg) {
                    nBits = gInitBits;
                    maxcode = maxcode(nBits);
                    clearFlg = false;
                } else {
                    ++nBits;
                    if (nBits == maxbits) maxcode = maxmaxcode;
                    else maxcode = maxcode(nBits);
                }
            }

            if (code == eofCode) {
                while (curBits > 0) {
                    charOut((byte) (curAccum & 0xff), out);
                    curAccum >>= 8;
                    curBits -= 8;
                }
                flushChar(out);
            }
        }

        private void charOut(byte c, OutputStream out) throws IOException {
            accum[aCount++] = c;
            if (aCount >= 254) flushChar(out);
        }

        private void flushChar(OutputStream out) throws IOException {
            if (aCount > 0) {
                out.write(aCount);
                out.write(accum, 0, aCount);
                aCount = 0;
            }
        }
    }
}
