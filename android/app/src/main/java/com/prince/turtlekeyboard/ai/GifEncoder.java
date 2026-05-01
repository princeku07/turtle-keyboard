package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;

/**
 * Minimal GIF89a single-frame encoder. The keyboard needs this so the "GIF" share
 * option produces real {@code image/gif} bytes. Android's {@code Bitmap.compress}
 * doesn't include GIF, and pulling a library in for one feature isn't worth the
 * weight.
 *
 * <p>Algorithm:
 * <ul>
 *   <li>Quantize to ≤256 colors by first-fit (most images we render have a small
 *       palette anyway). When the palette fills, every additional color maps to its
 *       nearest existing entry.</li>
 *   <li>Standard GIF87a/89a structure with global color table and one image block.</li>
 *   <li>LZW compression per the GIF spec.</li>
 * </ul>
 *
 * <p>LZW compression code adapted from Jef Poskanzer's public-domain Acme GifEncoder
 * (which itself derives from the Unix {@code compress} utility).
 */
public final class GifEncoder {

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
