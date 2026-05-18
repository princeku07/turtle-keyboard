package com.prince.turtlekeyboard.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.ai.GifEncoder;
import com.prince.turtlekeyboard.ai.ImageHistory;
import com.prince.turtlekeyboard.integration.gif.BackgroundChromaKey;
import com.prince.turtlekeyboard.integration.gif.SpriteSheetSlicer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dev-only screen for testing the sprite-sheet → animated GIF pipeline in
 * isolation from the AI. Lets you drop in any PNG, runs it through
 * {@link SpriteSheetSlicer} + {@link GifEncoder#encodeAnimated}, and previews
 * the resulting animated GIF in-app (or hands it off to an external viewer for
 * pre-API-28 devices that can't render {@link AnimatedImageDrawable}).
 *
 * <p>Pipeline mirrors {@code GifIntegration.sliceAndEncode} so a bug fixed here
 * is a bug fixed in /gif. Useful for:
 * <ul>
 *   <li>Verifying {@link GifEncoder#encodeAnimated} produces valid {@code image/gif}
 *       bytes that real viewers accept.</li>
 *   <li>Eyeballing the 5×1 vs 5×2 aspect detection threshold against arbitrary
 *       sheets.</li>
 *   <li>Reproducing matte issues using saved {@code gif_sheet_matte_*.png}
 *       artifacts from {@code ImageHistory}.</li>
 * </ul>
 */
public class SpriteToGifTestActivity extends AppCompatActivity {

    private static final String TAG = "SpriteToGifTest";

    private static final int REQ_PICK_SHEET = 100;

    /** Same threshold and frame-delay numbers as the production pipeline so the
     *  test reflects what /gif would actually do. See GifIntegration for the
     *  layout rationale (4×4 preferred, 4×2 fallback, 4×1 last-resort). */
    private static final int COLS = 4;
    private static final double GRID_4X4_MAX_ASPECT  = 1.5;
    private static final double STRIP_4X1_MIN_ASPECT = 3.0;
    private static final int FRAME_DELAY_CS_4X4 = 6;
    private static final int FRAME_DELAY_CS_4X2 = 12;
    private static final int FRAME_DELAY_CS_4X1 = 25;
    private static final int LOOP_FOREVER = 0;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ImageView sheetThumb;
    private TextView sheetInfo;
    private EditText colsInput;
    private EditText rowsInput;
    private EditText delayInput;
    private ImageView firstTilePreview;
    private TextView firstTileInfo;
    private CheckBox chromaKeyToggle;
    private Button generateBtn;
    private TextView resultInfo;
    private ImageView gifPreview;
    private Button shareBtn;

    /** True while {@link #syncInputsFromState()} is mutating the EditText
     *  values from code — the TextWatchers swap currentCols/Rows/DelayCs
     *  back to whatever the user types, but we don't want that re-entry
     *  during a programmatic refresh on sheet load. */
    private boolean suppressInputWatch = false;

    /** Last loaded sheet (from picker or bundled drawable), kept in memory
     *  between load and generate. */
    @Nullable private Bitmap currentSheet;

    /** Grid + frame-delay config for the current sheet. Set by the load path
     *  (aspect-detected for picked sheets, hardcoded for bundled). */
    private int currentCols = COLS;
    private int currentRows = 4;
    private int currentDelayCs = FRAME_DELAY_CS_4X4;

    /** Last encoded GIF on disk — used by the share button. */
    @Nullable private File lastGifFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Sprite → GIF test");
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addHeader(root, "1. Load a sprite sheet");
        Button pickBtn = new Button(this);
        pickBtn.setText("Pick from gallery");
        pickBtn.setOnClickListener(v -> launchPicker());
        root.addView(pickBtn, fullWidth());

        Button bundledBtn = new Button(this);
        bundledBtn.setText("Use bundled oneko.png");
        bundledBtn.setOnClickListener(v -> loadBundledSheet());
        LinearLayout.LayoutParams bundledLp = fullWidth();
        bundledLp.topMargin = dp(8);
        root.addView(bundledBtn, bundledLp);

        Button historyBtn = new Button(this);
        historyBtn.setText("Pick from /gif history");
        historyBtn.setOnClickListener(v -> pickFromGifHistory());
        LinearLayout.LayoutParams historyLp = fullWidth();
        historyLp.topMargin = dp(8);
        root.addView(historyBtn, historyLp);

        sheetThumb = new ImageView(this);
        sheetThumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sheetThumb.setAdjustViewBounds(true);
        sheetThumb.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        thumbLp.topMargin = dp(8);
        root.addView(sheetThumb, thumbLp);

        sheetInfo = new TextView(this);
        sheetInfo.setText("(no sheet picked yet)");
        sheetInfo.setTextSize(13);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(4);
        root.addView(sheetInfo, infoLp);

        // ── Manual grid override row ────────────────────────────────────
        // Replaces the previously-hardcoded 8×4 for the bundled sheet and
        // makes the picker / history paths editable too. Auto-detect from
        // aspect still runs on load and seeds these fields; the user is
        // free to override before generating.
        LinearLayout gridRow = new LinearLayout(this);
        gridRow.setOrientation(LinearLayout.HORIZONTAL);
        gridRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gridLp.topMargin = dp(10);
        root.addView(gridRow, gridLp);

        TextView gridLabel = new TextView(this);
        gridLabel.setText("Grid: ");
        gridLabel.setTextSize(13);
        gridRow.addView(gridLabel, wrapH());

        colsInput = makeNumberInput("4");
        gridRow.addView(colsInput, numberLp());

        TextView times = new TextView(this);
        times.setText("  ×  ");
        times.setTextSize(13);
        gridRow.addView(times, wrapH());

        rowsInput = makeNumberInput("4");
        gridRow.addView(rowsInput, numberLp());

        TextView delayLabel = new TextView(this);
        delayLabel.setText("   Delay: ");
        delayLabel.setTextSize(13);
        gridRow.addView(delayLabel, wrapH());

        delayInput = makeNumberInput("6");
        gridRow.addView(delayInput, numberLp());

        TextView delayUnit = new TextView(this);
        delayUnit.setText(" cs");
        delayUnit.setTextSize(13);
        gridRow.addView(delayUnit, wrapH());

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (suppressInputWatch) return;
                applyGridFromInputs();
            }
        };
        colsInput.addTextChangedListener(watcher);
        rowsInput.addTextChangedListener(watcher);
        delayInput.addTextChangedListener(watcher);

        TextView gridHelp = new TextView(this);
        gridHelp.setText("Auto-filled from sheet aspect on load; override above and "
                + "the first-tile preview updates live.");
        gridHelp.setTextSize(11);
        gridHelp.setTextColor(0xFF888888);
        LinearLayout.LayoutParams gridHelpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gridHelpLp.topMargin = dp(2);
        root.addView(gridHelp, gridHelpLp);

        addHeader(root, "2. First-tile preview");
        firstTileInfo = new TextView(this);
        firstTileInfo.setText("Frame 0 will appear here after a sheet is loaded. "
                + "If this tile doesn't look like a single clean cell, the slicer "
                + "is mis-grided and the encoded GIF will look scrambled.");
        firstTileInfo.setTextSize(12);
        firstTileInfo.setTextColor(0xFF666666);
        root.addView(firstTileInfo, fullWidth());

        firstTilePreview = new ImageView(this);
        // Pixel art (oneko) and screenshot-style sheets both benefit from
        // NEAREST scaling rather than the default bilinear — disable it
        // when we set the drawable so 32×32 cells stay crisp at upscale.
        firstTilePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        firstTilePreview.setAdjustViewBounds(true);
        firstTilePreview.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        tileLp.topMargin = dp(6);
        root.addView(firstTilePreview, tileLp);

        addHeader(root, "3. Slice + encode");
        chromaKeyToggle = new CheckBox(this);
        chromaKeyToggle.setText("Mask #FFFFFF background (tolerance 10) → transparent");
        chromaKeyToggle.setChecked(false);
        root.addView(chromaKeyToggle, fullWidth());

        TextView chromaHelp = new TextView(this);
        chromaHelp.setText("Deterministic white-pixel mask — every pixel within "
                + "Euclidean RGB distance ≤10 of #FFFFFF becomes transparent. "
                + "Turn on for /gif-style sheets (model outputs white bg per "
                + "gif.txt). Leave off for oneko: its cat body is white inside "
                + "too, and masking would hollow it out.");
        chromaHelp.setTextSize(11);
        chromaHelp.setTextColor(0xFF888888);
        LinearLayout.LayoutParams   helpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        helpLp.bottomMargin = dp(8);
        root.addView(chromaHelp, helpLp);

        generateBtn = new Button(this);
        generateBtn.setText("Generate GIF");
        generateBtn.setEnabled(false);
        generateBtn.setOnClickListener(v -> generate());
        root.addView(generateBtn, fullWidth());

        resultInfo = new TextView(this);
        resultInfo.setText("");
        resultInfo.setTextSize(13);
        LinearLayout.LayoutParams resLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        resLp.topMargin = dp(4);
        root.addView(resultInfo, resLp);

        addHeader(root, "4. Encoded GIF preview");
        gifPreview = new ImageView(this);
        gifPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gifPreview.setAdjustViewBounds(true);
        // Checkerboard-ish background so transparent GIFs are visible against
        // the activity's solid surface. Solid grey is fine for a dev screen.
        gifPreview.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        previewLp.topMargin = dp(8);
        root.addView(gifPreview, previewLp);

        shareBtn = new Button(this);
        shareBtn.setText("Open / share GIF externally");
        shareBtn.setEnabled(false);
        shareBtn.setOnClickListener(v -> shareGif());
        LinearLayout.LayoutParams shareLp = fullWidth();
        shareLp.topMargin = dp(8);
        root.addView(shareBtn, shareLp);

        TextView footer = new TextView(this);
        footer.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? "Preview uses AnimatedImageDrawable (API 28+). If a result looks "
                        + "static here, share externally to confirm — some chat apps "
                        + "decode our GIF stricter than ImageDecoder does."
                : "Preview shows the first frame only on this device (needs API 28+ "
                        + "for in-app animation). Use the share button to view the "
                        + "animation in another app.");
        footer.setTextSize(12);
        footer.setTextColor(0xFF666666);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = dp(16);
        root.addView(footer, footerLp);

        return scroll;
    }

    private void addHeader(LinearLayout parent, String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(15);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(6);
        parent.addView(h, lp);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapH() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** EditText layout params for the grid-config row — narrow enough that
     *  three of them fit on a single line under the sheet info. */
    private LinearLayout.LayoutParams numberLp() {
        return new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private EditText makeNumberInput(String initial) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(initial);
        e.setTextSize(14);
        e.setMaxLines(1);
        e.setSingleLine(true);
        e.setPadding(dp(8), dp(4), dp(8), dp(4));
        return e;
    }

    /** Read the three inputs, apply them to the current grid state, and
     *  refresh the first-tile preview so the user can immediately see
     *  whether the manual override produces clean cells. Empty / non-numeric
     *  fields are tolerated — we just hold onto the previous valid value
     *  for that field so the user can clear and retype without us spamming
     *  preview redraws with degenerate {@code 0×0} grids. */
    private void applyGridFromInputs() {
        int newCols = parsePositive(colsInput, currentCols);
        int newRows = parsePositive(rowsInput, currentRows);
        int newDelay = parsePositive(delayInput, currentDelayCs);
        boolean changed = newCols != currentCols
                || newRows != currentRows
                || newDelay != currentDelayCs;
        currentCols = newCols;
        currentRows = newRows;
        currentDelayCs = newDelay;
        if (changed && currentSheet != null && !currentSheet.isRecycled()) {
            updateFirstTilePreview(currentSheet);
            // Refresh the info line too so the displayed slice config tracks
            // the manual override.
            double aspect = (double) currentSheet.getWidth() / currentSheet.getHeight();
            sheetInfo.setText(String.format(
                    "%d × %d px · aspect %.2f · %d × %d slice · %d cs/frame",
                    currentSheet.getWidth(), currentSheet.getHeight(), aspect,
                    currentCols, currentRows, currentDelayCs));
        }
    }

    /** Push the current grid state into the EditTexts, suppressing the
     *  TextWatcher round-trip so the act of programmatically setting text
     *  doesn't re-invoke {@link #applyGridFromInputs()} and clobber the
     *  values we just chose. */
    private void syncInputsFromState() {
        suppressInputWatch = true;
        try {
            colsInput.setText(String.valueOf(currentCols));
            rowsInput.setText(String.valueOf(currentRows));
            delayInput.setText(String.valueOf(currentDelayCs));
        } finally {
            suppressInputWatch = false;
        }
    }

    private static int parsePositive(EditText e, int fallback) {
        String s = e.getText() == null ? "" : e.getText().toString().trim();
        if (s.isEmpty()) return fallback;
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : fallback;
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    // -- pick ---------------------------------------------------------------

    private void launchPicker() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(pick, REQ_PICK_SHEET);
        } catch (Exception e) {
            toast("No image picker available");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_SHEET) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("openInputStream returned null");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                byte[] bytes = out.toByteArray();
                Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bm == null) throw new Exception("decode failed");
                main.post(() -> onSheetLoaded(bm, /*overrideGrid*/ false, "picked"));
            } catch (Exception e) {
                Log.w(TAG, "sheet read failed", e);
                main.post(() -> toast("Couldn't read sheet: " + e.getMessage()));
            }
        });
    }

    /** Lists every {@link ImageHistory} entry with command={@code "gif"} — these
     *  are the debug artifacts /gif writes per invocation (white-bg, black-bg,
     *  matte, chroma, raw). Lets you load any past sheet straight into the test
     *  pipeline without re-running the model. Aspect detection then applies
     *  the same 4×4 / 4×2 / 4×1 logic /gif uses in prod. */
    private void pickFromGifHistory() {
        io.execute(() -> {
            final java.util.List<ImageHistory.Entry> entries = filterGifEntries(
                    ImageHistory.list(SpriteToGifTestActivity.this));
            main.post(() -> {
                if (entries.isEmpty()) {
                    toast("No /gif history yet — run /gif from the keyboard first");
                    return;
                }
                String[] labels = new String[entries.size()];
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                        "MM-dd HH:mm", java.util.Locale.US);
                for (int i = 0; i < entries.size(); i++) {
                    ImageHistory.Entry e = entries.get(i);
                    String prompt = e.prompt == null ? "" : e.prompt;
                    labels[i] = fmt.format(new java.util.Date(e.ts)) + "  " + prompt;
                }
                new AlertDialog.Builder(SpriteToGifTestActivity.this)
                        .setTitle("Pick a /gif sheet")
                        .setItems(labels, (dialog, which) -> {
                            ImageHistory.Entry e = entries.get(which);
                            loadHistoryEntry(e);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private static java.util.List<ImageHistory.Entry> filterGifEntries(
            java.util.List<ImageHistory.Entry> all) {
        java.util.List<ImageHistory.Entry> out = new java.util.ArrayList<>(all.size());
        for (ImageHistory.Entry e : all) {
            // The /gif pipeline records two artifacts per run with the same
            // "gif" command: the source sprite-sheet PNG (this screen's
            // input) and the final encoded .gif (which the slicer can't
            // re-ingest as a sheet). Filter to PNGs so the picker only
            // surfaces things this test screen can actually feed back in.
            if ("gif".equals(e.command)
                    && e.file != null
                    && e.file.getName().endsWith(".png")) {
                out.add(e);
            }
        }
        return out;
    }

    private void loadHistoryEntry(ImageHistory.Entry entry) {
        io.execute(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inScaled = false; // history PNGs are already at native resolution
                Bitmap bm = BitmapFactory.decodeFile(entry.file.getAbsolutePath(), opts);
                if (bm == null) throw new Exception("decode failed for " + entry.file);
                main.post(() -> onSheetLoaded(bm, /*overrideGrid*/ false, "history"));
            } catch (Exception e) {
                Log.w(TAG, "history load failed", e);
                main.post(() -> toast("Couldn't load history entry: " + e.getMessage()));
            }
        });
    }

    /** Bundled oneko sprite sheet ({@code res/drawable/oneko.png}, 256×128 px).
     *  Eight columns × four rows of 32×32 pixel-art cat frames — pixel-perfect
     *  division means the slicer can be evaluated without any floor-division
     *  rounding artifacts. 15cs/frame ⇒ a ~5 s loop across all 32 cells. */
    private void loadBundledSheet() {
        io.execute(() -> {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inScaled = false; // keep native pixel dimensions
                Bitmap bm = BitmapFactory.decodeResource(getResources(),
                        R.drawable.oneko, opts);
                if (bm == null) throw new Exception("decodeResource returned null");
                main.post(() -> onSheetLoaded(bm, /*overrideGrid*/ true, "oneko"));
            } catch (Exception e) {
                Log.w(TAG, "bundled sheet load failed", e);
                main.post(() -> toast("Bundled load failed: " + e.getMessage()));
            }
        });
    }

    /** Single load-callback path used by both the picker and the bundled button.
     *  {@code overrideGrid=true} preseeds the bundled sheet's known
     *  8×4 layout; {@code false} runs the aspect-ratio heuristic. Either
     *  way the user can edit the cols/rows/delay inputs afterward — these
     *  values just populate the inputs as a starting point. */
    private void onSheetLoaded(Bitmap bm, boolean overrideGrid, String source) {
        // Recycle previous sheet so repeated loads don't pile up bitmap memory.
        if (currentSheet != null && !currentSheet.isRecycled()) currentSheet.recycle();
        currentSheet = bm;
        setPixelPerfectBitmap(sheetThumb, bm);
        double aspect = (double) bm.getWidth() / bm.getHeight();
        if (overrideGrid) {
            // oneko.png: 256×128 ⇒ 8 cols × 4 rows of 32×32 cells (32 frames).
            // 15 cs/frame ⇒ ~5 s loop. Pixel-perfect division, no rounding.
            currentCols = 8;
            currentRows = 4;
            currentDelayCs = 15;
        } else {
            int rows = rowsForAspect(aspect);
            currentCols = COLS;
            currentRows = rows;
            currentDelayCs = frameDelayForRows(rows);
        }
        sheetInfo.setText(String.format(
                "[%s] %d × %d px · aspect %.2f · %d × %d slice · %d cs/frame",
                source, bm.getWidth(), bm.getHeight(), aspect,
                currentCols, currentRows, currentDelayCs));
        // Push the auto-detected values into the EditTexts. Suppression
        // prevents the watcher from immediately re-applying them and
        // re-rendering the preview before this load path is done with it.
        syncInputsFromState();
        updateFirstTilePreview(bm);
        generateBtn.setEnabled(true);
    }

    /** Slices just frame 0 from {@code sheet} using the current grid config and
     *  displays it in the preview ImageView with NEAREST-neighbor scaling so
     *  pixel-art sheets like oneko stay crisp under upscale. Verifying frame 0
     *  visually is the fastest way to catch a mis-grided slicer — if this tile
     *  shows two half-cats fused at a seam, the cols/rows are wrong. */
    private void updateFirstTilePreview(Bitmap sheet) {
        int cellW = sheet.getWidth()  / currentCols;
        int cellH = sheet.getHeight() / currentRows;
        if (cellW <= 0 || cellH <= 0) {
            firstTilePreview.setImageDrawable(null);
            firstTileInfo.setText("Sheet too small for " + currentCols + "×"
                    + currentRows + " grid (cell would be " + cellW + "×" + cellH + ")");
            return;
        }
        // Copy out of the source so a later sheet reload (which recycles
        // currentSheet) can't invalidate the displayed tile.
        Bitmap raw = Bitmap.createBitmap(sheet, 0, 0, cellW, cellH);
        Bitmap tile = raw.copy(Bitmap.Config.ARGB_8888, false);
        if (raw != tile) raw.recycle();

        BitmapDrawable d = new BitmapDrawable(getResources(), tile);
        d.setFilterBitmap(false); // NEAREST upscale — keeps pixel-art crisp
        d.setAntiAlias(false);
        firstTilePreview.setImageDrawable(d);
        firstTileInfo.setText(String.format(
                "Frame 0 sliced as %d × %d px (sheet ÷ %d cols × %d rows). "
                        + "If this tile shows seams or partial neighbors, the grid is wrong.",
                cellW, cellH, currentCols, currentRows));
    }

    // -- generate -----------------------------------------------------------

    private void generate() {
        if (currentSheet == null || currentSheet.isRecycled()) return;
        generateBtn.setEnabled(false);
        resultInfo.setText("Encoding…");
        gifPreview.setImageDrawable(null);
        shareBtn.setEnabled(false);

        // Snapshot the load-time config so a later sheet reload mid-encode
        // can't desync rows/cols/delay.
        final int cols = currentCols;
        final int rows = currentRows;
        final int delayCs = currentDelayCs;
        final boolean keyOut = chromaKeyToggle.isChecked();

        // Copy the source bitmap so the (possible) chroma-key can recycle its
        // input without killing the on-screen thumbnail (which still references
        // currentSheet).
        final Bitmap source = currentSheet.copy(Bitmap.Config.ARGB_8888, false);

        io.execute(() -> {
            try {
                // Deterministic #FFFFFF mask. Opt-in because pixel-art sheets
                // like oneko have white INSIDE the subject too — masking would
                // hollow them out. Mirrors what /gif does in production.
                Bitmap sheet = keyOut
                        ? BackgroundChromaKey.applyForColor(source, 0xFFFFFF, 10)
                        : source;

                List<Bitmap> frames;
                try {
                    frames = SpriteSheetSlicer.slice(sheet, cols, rows);
                } finally {
                    sheet.recycle();
                }

                File outDir = new File(getCacheDir(), "shared_images");
                if (!outDir.exists() && !outDir.mkdirs()) {
                    throw new Exception("cannot create cache dir");
                }
                File outFile = new File(outDir,
                        "test_gif_" + System.currentTimeMillis() + ".gif");
                try (OutputStream out = new FileOutputStream(outFile)) {
                    GifEncoder.encodeAnimated(frames, delayCs, LOOP_FOREVER, out);
                }
                for (Bitmap f : frames) f.recycle();

                long size = outFile.length();
                main.post(() -> onGifEncoded(outFile, cols * rows, delayCs, size));
            } catch (Exception e) {
                Log.w(TAG, "encode failed", e);
                main.post(() -> {
                    resultInfo.setText("Failed: " + e.getMessage());
                    generateBtn.setEnabled(true);
                });
            }
        });
    }

    private void onGifEncoded(File gifFile, int frameCount, int delayCs, long sizeBytes) {
        lastGifFile = gifFile;
        resultInfo.setText(String.format(
                "✓ %d frames · %d cs/frame · %.1f KB · %s",
                frameCount, delayCs, sizeBytes / 1024.0, gifFile.getName()));
        showGifPreview(gifFile);
        generateBtn.setEnabled(true);
        shareBtn.setEnabled(true);
    }

    private void showGifPreview(File gifFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(gifFile);
                Drawable d = ImageDecoder.decodeDrawable(src);
                // NEAREST-neighbor upscale so pixel-art GIFs (oneko) render
                // crisp instead of bilinear-smudged. Drawable honors this on
                // AnimatedImageDrawable too — pixels stay sharp per frame.
                d.setFilterBitmap(false);
                gifPreview.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) d).start();
                }
                return;
            } catch (Exception e) {
                Log.w(TAG, "ImageDecoder failed; falling back to bitmap preview", e);
            }
        }
        // Pre-API-28 or decode failure: show the first frame as a static bitmap
        // so the user at least sees something. Encoded animation can still be
        // verified via the share button.
        Bitmap first = BitmapFactory.decodeFile(gifFile.getAbsolutePath());
        setPixelPerfectBitmap(gifPreview, first);
    }

    /** Sets {@code bm} on {@code iv} as a {@link BitmapDrawable} with bitmap
     *  filtering disabled — NEAREST-neighbor upscale. Required for crisp
     *  rendering of pixel-art and low-res sprite sheets; the default
     *  ImageView path uses bilinear filtering which smudges sub-pixel
     *  details into a blur. */
    private void setPixelPerfectBitmap(ImageView iv, Bitmap bm) {
        BitmapDrawable d = new BitmapDrawable(getResources(), bm);
        d.setFilterBitmap(false);
        d.setAntiAlias(false);
        iv.setImageDrawable(d);
    }

    // -- share --------------------------------------------------------------

    private void shareGif() {
        if (lastGifFile == null || !lastGifFile.exists()) return;
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", lastGifFile);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("image/gif");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "Share GIF"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Aspect-ratio → row count. Mirror of {@code GifIntegration.rowsForAspect}
     *  so the test screen behaves identically to the production pipeline for
     *  any sheet a user picks from gallery. */
    private static int rowsForAspect(double aspect) {
        if (aspect > STRIP_4X1_MIN_ASPECT) return 1;
        if (aspect > GRID_4X4_MAX_ASPECT)  return 2;
        return 4;
    }

    private static int frameDelayForRows(int rows) {
        switch (rows) {
            case 1:  return FRAME_DELAY_CS_4X1;
            case 2:  return FRAME_DELAY_CS_4X2;
            default: return FRAME_DELAY_CS_4X4;
        }
    }
}
