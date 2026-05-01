package com.prince.turtlekeyboard.ai;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native Canvas renderer for the same component vocabulary that
 * {@link AttachedHtmlRenderer} renders via WebView. Takes a JSON document
 * shaped like {@code {"blocks":[ {"type":"heading","text":"..."}, ... ]}} and
 * returns a 500×500 ARGB_8888 bitmap. Synchronous, no WebView, no Chromium
 * cold-start, no IME-process restrictions.
 *
 * <p>Steady-state cost is dominated by {@link StaticLayout} construction
 * (text shaping). Typical content renders in 5–25 ms.
 */
public final class NativeCardRenderer {

    private static final String TAG = "NativeCardRenderer";

    public static final int SIZE = 500;
    private static final int PADDING = 20;
    private static final int BLOCK_GAP = 10;

    // Design system colors — mirror cream/ink/lime/pink/blue/orange from CSS.
    private static final int COLOR_BG       = 0xFFF4EFE4;
    private static final int COLOR_INK      = 0xFF0C0C0C;
    private static final int COLOR_LIME     = 0xFF15803D;
    private static final int COLOR_PINK     = 0xFFFF4FA3;
    private static final int COLOR_BLUE     = 0xFF5B6CFF;
    private static final int COLOR_ORANGE   = 0xFFFF7A1A;
    private static final int COLOR_CREAM2   = 0xFFFFFAF0;
    private static final int COLOR_WHITE    = 0xFFFFFFFF;

    private NativeCardRenderer() {}

    public static Bitmap render(JSONObject doc) {
        long t0 = SystemClock.uptimeMillis();
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(COLOR_BG);

        JSONArray blocks = doc == null ? null : doc.optJSONArray("blocks");
        if (blocks == null || blocks.length() == 0) {
            Log.w(TAG, "no blocks in document");
            return bmp;
        }

        int contentWidth = SIZE - PADDING * 2;

        // Measure pass: each Block knows its own height for the given width.
        List<Block> measured = new ArrayList<>(blocks.length());
        int totalH = 0;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) continue;
            Block blk = buildBlock(b, contentWidth);
            if (blk == null) continue;
            measured.add(blk);
            if (totalH > 0) totalH += BLOCK_GAP;
            totalH += blk.height;
        }

        // Vertical-center the stack if it fits; otherwise top-anchor and let
        // the bottom clip — matches the flex/justify-content behavior of the
        // HTML version. Always leaves PADDING on top and bottom when possible.
        int avail = SIZE - PADDING * 2;
        int y = PADDING;
        if (totalH < avail) y = (SIZE - totalH) / 2;

        for (Block b : measured) {
            b.draw(canvas, PADDING, y);
            y += b.height + BLOCK_GAP;
        }

        long ms = SystemClock.uptimeMillis() - t0;
        Log.d(TAG, "rendered " + measured.size() + " blocks in " + ms + "ms");
        return bmp;
    }

    // --- Paints (re-created per render: cheap, keeps the renderer thread-safe) ---

    private static TextPaint textPaint(int sizePx, int color, boolean bold) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(sizePx);
        p.setTypeface(bold ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) : Typeface.SANS_SERIF);
        return p;
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    private static Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(w);
        return p;
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, int width, Layout.Alignment align) {
        if (text == null) text = "";
        return StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(align == null ? Layout.Alignment.ALIGN_NORMAL : align)
                .setIncludePad(false)
                .setLineSpacing(2f, 1f)
                .build();
    }

    private static int parseColor(String name, int fallback) {
        if (name == null) return fallback;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "green":
            case "lime":   return COLOR_LIME;
            case "pink":   return COLOR_PINK;
            case "blue":   return COLOR_BLUE;
            case "orange": return COLOR_ORANGE;
            case "ink":
            case "black":  return COLOR_INK;
            default:       return fallback;
        }
    }

    // --- Block dispatch ---

    private static Block buildBlock(JSONObject json, int width) {
        String type = json.optString("type", "").toLowerCase(Locale.ROOT);
        switch (type) {
            case "heading":   return new HeadingBlock(json, width);
            case "paragraph": return new ParagraphBlock(json, width);
            case "list":      return new ListBlock(json, width);
            case "checklist": return new ChecklistBlock(json, width);
            case "table":     return new TableBlock(json, width);
            case "kv":        return new KvBlock(json, width);
            case "stat":      return new StatBlock(json, width);
            case "callout":   return new CalloutBlock(json, width);
            case "badge":     return new BadgeBlock(json, width);
            case "grid":      return new GridBlock(json, width);
            default:
                Log.w(TAG, "unknown block type: " + type);
                return null;
        }
    }

    private abstract static class Block {
        int width;
        int height;
        abstract void draw(Canvas c, int x, int y);
    }

    // --- Heading ----------------------------------------------------------

    private static class HeadingBlock extends Block {
        final StaticLayout layout;
        HeadingBlock(JSONObject json, int w) {
            int level = json.optInt("level", 3);
            int size = level == 1 ? 26 : level == 2 ? 22 : 20;
            TextPaint p = textPaint(size, COLOR_INK, true);
            this.width = w;
            this.layout = layout(json.optString("text", ""), p, w, Layout.Alignment.ALIGN_NORMAL);
            this.height = layout.getHeight();
        }
        @Override void draw(Canvas c, int x, int y) {
            c.save(); c.translate(x, y); layout.draw(c); c.restore();
        }
    }

    // --- Paragraph --------------------------------------------------------

    private static class ParagraphBlock extends Block {
        final StaticLayout layout;
        ParagraphBlock(JSONObject json, int w) {
            TextPaint p = textPaint(17, COLOR_INK, false);
            this.width = w;
            this.layout = layout(json.optString("text", ""), p, w, Layout.Alignment.ALIGN_NORMAL);
            this.height = layout.getHeight();
        }
        @Override void draw(Canvas c, int x, int y) {
            c.save(); c.translate(x, y); layout.draw(c); c.restore();
        }
    }

    // --- List (ul / ol) ---------------------------------------------------

    private static class ListBlock extends Block {
        static final int INDENT = 22;
        static final int ITEM_GAP = 4;
        final boolean ordered;
        final List<StaticLayout> items = new ArrayList<>();
        final TextPaint marker;
        ListBlock(JSONObject json, int w) {
            this.ordered = json.optBoolean("ordered", false);
            this.width = w;
            this.marker = textPaint(17, COLOR_INK, false);
            TextPaint body = textPaint(17, COLOR_INK, false);
            JSONArray arr = json.optJSONArray("items");
            int n = arr == null ? 0 : arr.length();
            int itemW = w - INDENT;
            int total = 0;
            for (int i = 0; i < n; i++) {
                StaticLayout l = layout(arr.optString(i, ""), body, itemW, Layout.Alignment.ALIGN_NORMAL);
                items.add(l);
                if (i > 0) total += ITEM_GAP;
                total += l.getHeight();
            }
            this.height = total;
        }
        @Override void draw(Canvas c, int x, int y) {
            int cy = y;
            for (int i = 0; i < items.size(); i++) {
                StaticLayout l = items.get(i);
                String mk = ordered ? (i + 1) + "." : "•";
                c.drawText(mk, x, cy + l.getLineBaseline(0), marker);
                c.save(); c.translate(x + INDENT, cy); l.draw(c); c.restore();
                cy += l.getHeight() + ITEM_GAP;
            }
        }
    }

    // --- Checklist --------------------------------------------------------

    private static class ChecklistBlock extends Block {
        static final int INDENT = 28;
        static final int ITEM_GAP = 4;
        final List<StaticLayout> items = new ArrayList<>();
        final TextPaint check;
        ChecklistBlock(JSONObject json, int w) {
            this.width = w;
            this.check = textPaint(20, COLOR_LIME, true);
            TextPaint body = textPaint(17, COLOR_INK, false);
            JSONArray arr = json.optJSONArray("items");
            int n = arr == null ? 0 : arr.length();
            int itemW = w - INDENT;
            int total = 0;
            for (int i = 0; i < n; i++) {
                StaticLayout l = layout(arr.optString(i, ""), body, itemW, Layout.Alignment.ALIGN_NORMAL);
                items.add(l);
                if (i > 0) total += ITEM_GAP;
                total += l.getHeight();
            }
            this.height = total;
        }
        @Override void draw(Canvas c, int x, int y) {
            int cy = y;
            for (StaticLayout l : items) {
                c.drawText("✓", x, cy + l.getLineBaseline(0), check);
                c.save(); c.translate(x + INDENT, cy); l.draw(c); c.restore();
                cy += l.getHeight() + ITEM_GAP;
            }
        }
    }

    // --- Callout ----------------------------------------------------------

    private static class CalloutBlock extends Block {
        static final int PAD_X = 14;
        static final int PAD_Y = 12;
        static final int LEFT_BAR = 8;
        static final int BORDER = 2;
        final StaticLayout layout;
        CalloutBlock(JSONObject json, int w) {
            this.width = w;
            TextPaint p = textPaint(17, COLOR_INK, false);
            int textW = w - PAD_X * 2 - LEFT_BAR;
            this.layout = layout(json.optString("text", ""), p, textW, Layout.Alignment.ALIGN_NORMAL);
            this.height = layout.getHeight() + PAD_Y * 2;
        }
        @Override void draw(Canvas c, int x, int y) {
            RectF box = new RectF(x, y, x + width, y + height);
            c.drawRect(box, fill(COLOR_CREAM2));
            c.drawRect(new RectF(x, y, x + LEFT_BAR, y + height), fill(COLOR_ORANGE));
            c.drawRect(box, stroke(COLOR_INK, BORDER));
            c.save();
            c.translate(x + LEFT_BAR + PAD_X, y + PAD_Y);
            layout.draw(c);
            c.restore();
        }
    }

    // --- Badge ------------------------------------------------------------

    private static class BadgeBlock extends Block {
        static final int PAD_X = 8;
        static final int PAD_Y = 2;
        static final int BORDER = 2;
        final String text;
        final int bg;
        final TextPaint paint;
        final float textWidth;
        final Paint.FontMetrics fm;
        BadgeBlock(JSONObject json, int w) {
            this.width = w;
            this.text = json.optString("text", "");
            this.bg = parseColor(json.optString("color", null), COLOR_BLUE);
            this.paint = textPaint(14, COLOR_WHITE, true);
            this.textWidth = paint.measureText(text);
            this.fm = paint.getFontMetrics();
            this.height = (int) Math.ceil(fm.descent - fm.ascent) + PAD_Y * 2 + BORDER * 2;
        }
        @Override void draw(Canvas c, int x, int y) {
            float w = textWidth + PAD_X * 2 + BORDER * 2;
            float r = height / 2f;
            RectF box = new RectF(x, y, x + w, y + height);
            c.drawRoundRect(box, r, r, fill(bg));
            c.drawRoundRect(box, r, r, stroke(COLOR_INK, BORDER));
            float baseline = y + BORDER + PAD_Y - fm.ascent;
            c.drawText(text, x + BORDER + PAD_X, baseline, paint);
        }
    }

    // --- Stat -------------------------------------------------------------

    private static class StatBlock extends Block {
        final StaticLayout num;
        final StaticLayout label;
        static final int GAP = 6;
        StatBlock(JSONObject json, int w) {
            this.width = w;
            TextPaint pNum = textPaint(64, COLOR_LIME, true);
            TextPaint pLbl = textPaint(16, COLOR_INK, true);
            pLbl.setLetterSpacing(0.05f);
            String value = json.optString("value", "");
            String lbl = json.optString("label", "").toUpperCase(Locale.ROOT);
            this.num = layout(value, pNum, w, Layout.Alignment.ALIGN_CENTER);
            this.label = layout(lbl, pLbl, w, Layout.Alignment.ALIGN_CENTER);
            this.height = num.getHeight() + GAP + label.getHeight();
        }
        @Override void draw(Canvas c, int x, int y) {
            c.save(); c.translate(x, y); num.draw(c); c.restore();
            c.save(); c.translate(x, y + num.getHeight() + GAP); label.draw(c); c.restore();
        }
    }

    // --- Key/Value (dl) ---------------------------------------------------

    private static class KvBlock extends Block {
        static final int COL_GAP = 14;
        static final int ROW_GAP = 6;
        final List<StaticLayout> keys = new ArrayList<>();
        final List<StaticLayout> values = new ArrayList<>();
        int keyColW;
        KvBlock(JSONObject json, int w) {
            this.width = w;
            TextPaint kp = textPaint(16, COLOR_INK, true);
            TextPaint vp = textPaint(16, COLOR_INK, false);
            JSONArray rows = json.optJSONArray("rows");
            int n = rows == null ? 0 : rows.length();

            // Compute key column width = widest key, capped at 50% of total.
            float widest = 0;
            String[] kTexts = new String[n];
            String[] vTexts = new String[n];
            for (int i = 0; i < n; i++) {
                JSONArray r = rows.optJSONArray(i);
                kTexts[i] = r == null ? "" : r.optString(0, "");
                vTexts[i] = r == null ? "" : r.optString(1, "");
                widest = Math.max(widest, kp.measureText(kTexts[i]));
            }
            this.keyColW = (int) Math.min(widest, w * 0.5f);
            int valColW = w - keyColW - COL_GAP;

            int total = 0;
            for (int i = 0; i < n; i++) {
                StaticLayout k = layout(kTexts[i], kp, keyColW, Layout.Alignment.ALIGN_NORMAL);
                StaticLayout v = layout(vTexts[i], vp, valColW, Layout.Alignment.ALIGN_OPPOSITE);
                keys.add(k); values.add(v);
                if (i > 0) total += ROW_GAP;
                total += Math.max(k.getHeight(), v.getHeight());
            }
            this.height = total;
        }
        @Override void draw(Canvas c, int x, int y) {
            int cy = y;
            for (int i = 0; i < keys.size(); i++) {
                StaticLayout k = keys.get(i);
                StaticLayout v = values.get(i);
                c.save(); c.translate(x, cy); k.draw(c); c.restore();
                c.save(); c.translate(x + keyColW + COL_GAP, cy); v.draw(c); c.restore();
                cy += Math.max(k.getHeight(), v.getHeight()) + ROW_GAP;
            }
        }
    }

    // --- Table ------------------------------------------------------------

    private static class TableBlock extends Block {
        static final int CELL_PAD_X = 10;
        static final int CELL_PAD_Y = 8;
        static final int BORDER = 2;
        static final int SHADOW = 3;

        final String[] headers;
        final String[][] body;
        final String[] footer;
        final int[] colW;
        final int headerRowH;
        final int[] bodyRowH;
        final int footerRowH;

        TableBlock(JSONObject json, int w) {
            this.width = w - SHADOW;  // leave room for offset shadow
            JSONArray hArr = json.optJSONArray("headers");
            JSONArray rArr = json.optJSONArray("rows");
            JSONArray fArr = json.optJSONArray("footer");

            int cols = hArr == null ? 0 : hArr.length();
            if (cols == 0 && rArr != null && rArr.length() > 0) {
                JSONArray first = rArr.optJSONArray(0);
                cols = first == null ? 0 : first.length();
            }
            this.headers = new String[cols];
            for (int i = 0; i < cols; i++) headers[i] = hArr == null ? "" : hArr.optString(i, "");

            int rows = rArr == null ? 0 : rArr.length();
            this.body = new String[rows][cols];
            for (int r = 0; r < rows; r++) {
                JSONArray row = rArr.optJSONArray(r);
                for (int cc = 0; cc < cols; cc++) {
                    body[r][cc] = row == null ? "" : row.optString(cc, "");
                }
            }
            this.footer = fArr == null ? null : new String[cols];
            if (footer != null) for (int i = 0; i < cols; i++) footer[i] = fArr.optString(i, "");

            // Equal column widths.
            this.colW = new int[cols];
            int each = cols == 0 ? 0 : width / cols;
            for (int i = 0; i < cols; i++) colW[i] = each;
            if (cols > 0) colW[cols - 1] = width - each * (cols - 1);

            TextPaint hp = textPaint(17, COLOR_WHITE, true);
            TextPaint bp = textPaint(17, COLOR_INK, false);
            TextPaint fp = textPaint(17, COLOR_WHITE, true);

            this.headerRowH = measureRow(headers, hp);
            this.bodyRowH = new int[rows];
            int total = headerRowH;
            for (int r = 0; r < rows; r++) {
                bodyRowH[r] = measureRow(body[r], bp);
                total += bodyRowH[r];
            }
            this.footerRowH = footer == null ? 0 : measureRow(footer, fp);
            total += footerRowH;
            this.height = total + SHADOW;
        }

        private int measureRow(String[] cells, TextPaint paint) {
            int max = 0;
            for (int i = 0; i < cells.length; i++) {
                StaticLayout l = layout(cells[i], paint, colW[i] - CELL_PAD_X * 2, Layout.Alignment.ALIGN_NORMAL);
                max = Math.max(max, l.getHeight());
            }
            return max + CELL_PAD_Y * 2;
        }

        @Override void draw(Canvas c, int x, int y) {
            int cols = colW.length;
            int tableW = 0; for (int w : colW) tableW += w;

            // Drop shadow
            c.drawRect(new RectF(x + SHADOW, y + SHADOW,
                                 x + SHADOW + tableW, y + SHADOW + headerRowH + sumBody() + footerRowH),
                       fill(COLOR_INK));

            int cy = y;
            // Header row
            drawRow(c, x, cy, headers, headerRowH, COLOR_LIME, COLOR_WHITE, true);
            cy += headerRowH;
            // Body rows (zebra stripe even)
            for (int r = 0; r < body.length; r++) {
                int bg = (r % 2 == 1) ? COLOR_CREAM2 : COLOR_WHITE;
                drawRow(c, x, cy, body[r], bodyRowH[r], bg, COLOR_INK, false);
                cy += bodyRowH[r];
            }
            if (footer != null) {
                drawRow(c, x, cy, footer, footerRowH, COLOR_INK, COLOR_WHITE, true);
            }
        }

        private int sumBody() { int s = 0; for (int h : bodyRowH) s += h; return s; }

        private void drawRow(Canvas c, int x, int y, String[] cells, int rowH, int bg, int fg, boolean bold) {
            int cx = x;
            TextPaint paint = textPaint(17, fg, bold);
            for (int i = 0; i < cells.length; i++) {
                RectF cell = new RectF(cx, y, cx + colW[i], y + rowH);
                c.drawRect(cell, fill(bg));
                c.drawRect(cell, stroke(COLOR_INK, BORDER));
                StaticLayout l = layout(cells[i], paint, colW[i] - CELL_PAD_X * 2, Layout.Alignment.ALIGN_NORMAL);
                c.save();
                c.translate(cx + CELL_PAD_X, y + CELL_PAD_Y);
                l.draw(c);
                c.restore();
                cx += colW[i];
            }
        }
    }

    // --- Grid (cards) -----------------------------------------------------

    private static class GridBlock extends Block {
        static final int GAP = 8;
        static final int CARD_PAD = 12;
        static final int BORDER = 2;
        static final int SHADOW = 3;
        final int cols;
        final List<Card> cards = new ArrayList<>();

        static class Card {
            StaticLayout title, body;
            int cardH;
        }

        GridBlock(JSONObject json, int w) {
            this.width = w;
            this.cols = Math.max(1, Math.min(3, json.optInt("cols", 2)));
            JSONArray arr = json.optJSONArray("cards");
            int n = arr == null ? 0 : arr.length();
            int cardW = (w - GAP * (cols - 1) - SHADOW) / cols;
            int textW = cardW - CARD_PAD * 2;

            TextPaint tp = textPaint(14, COLOR_INK, true);
            tp.setLetterSpacing(0.04f);
            TextPaint bp = textPaint(16, COLOR_INK, false);

            int rowMax = 0; int curRow = 0;
            int totalH = 0;
            for (int i = 0; i < n; i++) {
                JSONObject co = arr.optJSONObject(i);
                Card card = new Card();
                String t = co == null ? "" : co.optString("title", "").toUpperCase(Locale.ROOT);
                String b = co == null ? "" : co.optString("body", "");
                card.title = layout(t, tp, textW, Layout.Alignment.ALIGN_NORMAL);
                card.body  = layout(b, bp, textW, Layout.Alignment.ALIGN_NORMAL);
                card.cardH = card.title.getHeight() + 4 + card.body.getHeight() + CARD_PAD * 2;
                cards.add(card);

                rowMax = Math.max(rowMax, card.cardH);
                curRow++;
                if (curRow == cols || i == n - 1) {
                    totalH += rowMax;
                    if (i < n - 1) totalH += GAP;
                    rowMax = 0; curRow = 0;
                }
            }
            this.height = totalH + SHADOW;
        }

        @Override void draw(Canvas c, int x, int y) {
            int cardW = (width - GAP * (cols - 1) - SHADOW) / cols;
            int cy = y;
            int curRow = 0;
            int rowMax = 0;
            int rowStart = 0;
            for (int i = 0; i < cards.size(); i++) {
                rowMax = Math.max(rowMax, cards.get(i).cardH);
                curRow++;
                if (curRow == cols || i == cards.size() - 1) {
                    // Draw the row from rowStart..i with uniform height = rowMax.
                    for (int j = rowStart; j <= i; j++) {
                        int col = j - rowStart;
                        int cx = x + col * (cardW + GAP);
                        drawCard(c, cx, cy, cardW, rowMax, cards.get(j));
                    }
                    cy += rowMax + GAP;
                    rowStart = i + 1;
                    rowMax = 0;
                    curRow = 0;
                }
            }
        }

        private void drawCard(Canvas c, int x, int y, int w, int h, Card card) {
            // Hard offset shadow
            c.drawRect(new RectF(x + SHADOW, y + SHADOW, x + SHADOW + w, y + SHADOW + h), fill(COLOR_INK));
            RectF box = new RectF(x, y, x + w, y + h);
            c.drawRect(box, fill(COLOR_WHITE));
            c.drawRect(box, stroke(COLOR_INK, BORDER));
            c.save();
            c.translate(x + CARD_PAD, y + CARD_PAD);
            card.title.draw(c);
            c.translate(0, card.title.getHeight() + 4);
            card.body.draw(c);
            c.restore();
        }
    }
}
