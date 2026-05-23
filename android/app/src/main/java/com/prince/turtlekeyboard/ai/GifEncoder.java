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
 * Minimal GIF89a encoder. Single-frame {@link #encode} for opaque output;
 * {@link #encodeAnimated} for multi-frame animations with per-frame transparency
 * and a single global color table. LZW code adapted from Jef Poskanzer's
 * public-domain Acme GifEncoder.
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

        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});

        // Logical screen descriptor
        writeShort(out, w);
        writeShort(out, h);
        out.write(0xF7); // GCT=1, color_res=7, sort=0, GCT size=7 (256 entries)
        out.write(0);
        out.write(0);

        for (int i = 0; i < 256; i++) {
            int p = i < paletteSize ? palette[i] : 0;
            out.write((p >> 16) & 0xff);
            out.write((p >> 8) & 0xff);
            out.write(p & 0xff);
        }

        out.write(0x2C);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, w);
        writeShort(out, h);
        out.write(0);

        out.write(8);
        new LzwEncoder(indexed, 8).encode(out);
        out.write(0);

        out.write(0x3B);
        out.flush();
    }

    /**
     * Encodes frames into a looping animated GIF. All frames must share dimensions.
     * Pixels with alpha &lt; 128 become transparent; disposal method 2 prevents
     * frame-N pixels bleeding through frame-N+1 transparent regions.
     *
     * @param frames        frames in display order; non-empty, matching dimensions
     * @param frameDelayCs  per-frame delay in centiseconds (1/100 s)
     * @param loopCount     0 = loop forever; otherwise 1..65535 additional plays
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

        // Two-pass: 5-bit-binned histogram across all frames, take top-255 as the
        // palette (slot 0 reserved for transparent), then index every pixel. First-fit
        // would let bg pixels exhaust the palette before any subject pixel was seen.
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

        List<Map.Entry<Integer, Integer>> ranked = new ArrayList<>(histogram.entrySet());
        Collections.sort(ranked, new Comparator<Map.Entry<Integer, Integer>>() {
            @Override public int compare(Map.Entry<Integer, Integer> a, Map.Entry<Integer, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });

        int[] palette = new int[256];
        int paletteSize = 1; // slot 0 reserved (transparent)
        int take = Math.min(255, ranked.size());
        for (int i = 0; i < take; i++) {
            palette[paletteSize++] = ranked.get(i).getKey();
        }

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
            frameArgb[f] = null;
        }

        StringBuilder palSample = new StringBuilder("palette[1..");
        int dumpEnd = Math.min(paletteSize, 9);
        palSample.append(dumpEnd - 1).append("]=");
        for (int i = 1; i < dumpEnd; i++) {
            palSample.append(String.format("%06X ", palette[i] & 0xFFFFFF));
        }
        Log.d(TAG, palSample.toString() + " (total " + paletteSize
                + ", uniqueBins=" + histogram.size() + ")");

        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        writeShort(out, w);
        writeShort(out, h);
        out.write(0xF7);
        out.write(0);
        out.write(0);

        for (int i = 0; i < 256; i++) {
            int p = i < paletteSize ? palette[i] : 0;
            out.write((p >> 16) & 0xff);
            out.write((p >> 8) & 0xff);
            out.write(p & 0xff);
        }

        // NETSCAPE2.0 application extension enables infinite-loop playback.
        out.write(0x21);
        out.write(0xFF);
        out.write(0x0B);
        out.write(new byte[]{'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0'});
        out.write(0x03);
        out.write(0x01);
        writeShort(out, loopCount & 0xFFFF);
        out.write(0x00);

        for (int f = 0; f < frameCount; f++) {
            // GCE: disposal=2, transparency=1 → 0x09
            out.write(0x21);
            out.write(0xF9);
            out.write(0x04);
            out.write(0x09);
            writeShort(out, frameDelayCs);
            out.write(0x00);
            out.write(0x00);

            out.write(0x2C);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, w);
            writeShort(out, h);
            out.write(0x00);

            out.write(8);
            new LzwEncoder(indexedFrames[f], 8).encode(out);
            out.write(0x00);
        }

        out.write(0x3B);
        out.flush();
    }

    /** Nearest-match in palette[from..size). The animated path passes from=1 to skip
     *  the transparent placeholder so opaque pixels never collapse onto it. */
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

    // LZW (GIF variant) — adapted from Jef Poskanzer's Acme GifEncoder (public domain).
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
