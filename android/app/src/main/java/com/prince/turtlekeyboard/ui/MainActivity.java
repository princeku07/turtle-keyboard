package com.prince.turtlekeyboard.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Pair;

import androidx.core.view.ContentInfoCompat;
import androidx.core.view.OnReceiveContentListener;
import androidx.core.view.ViewCompat;

import com.prince.turtlekeyboard.ai.AttachedHtmlRenderer;
import com.prince.turtlekeyboard.databinding.ActivityMainBinding;

/**
 * Host app entry point — onboarding plus a playground EditText wired with
 * {@link ViewCompat#setOnReceiveContentListener} so we can verify the keyboard's
 * image / sticker / GIF share types end-to-end without a third-party app.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    /** Set on the launching Intent when the IME bounces the user here to grant
     *  the RECORD_AUDIO permission. {@link #onCreate} reads it and triggers the
     *  request immediately, then finishes once the OS dialog returns. */
    public static final String EXTRA_REQUEST_MIC = "extra_request_mic";
    private static final int REQ_MIC = 4242;
    private static final String[] ACCEPTED_MIMES = {
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/*",
            "video/mp4", "video/*"
    };

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnEnable.setOnClickListener(v -> openInputMethodSettings());
        binding.btnChoose.setOnClickListener(v -> showInputMethodPicker());
        binding.btnClearReceived.setOnClickListener(v -> clearReceived());
        binding.btnLoadSample.setOnClickListener(v -> binding.htmlInput.setText(nextSample()));
        binding.btnRenderHtml.setOnClickListener(v -> renderHtml());

        ViewCompat.setOnReceiveContentListener(binding.playground, ACCEPTED_MIMES, receiveListener);
        updateEmptyState();

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            requestMicPermission();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) requestMicPermission();
    }

    private void requestMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mic already enabled", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "Mic enabled — switch back to keyboard" : "Mic denied",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private final OnReceiveContentListener receiveListener = (view, payload) -> {
        // Split: handle items with a URI ourselves, defer the rest (plain text, etc.)
        Pair<ContentInfoCompat, ContentInfoCompat> split =
                payload.partition(item -> item.getUri() != null);
        ContentInfoCompat uriContent = split.first;
        ContentInfoCompat remaining = split.second;

        if (uriContent != null) {
            ClipData clip = uriContent.getClip();
            String[] mimes = mimes(uriContent);
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) addReceivedItem(uri, primaryMime(mimes));
            }
        }
        return remaining;
    };

    private static String[] mimes(ContentInfoCompat info) {
        android.content.ClipDescription d = info.getClip().getDescription();
        String[] arr = new String[d.getMimeTypeCount()];
        for (int i = 0; i < arr.length; i++) arr[i] = d.getMimeType(i);
        return arr;
    }

    private static String primaryMime(String[] mimes) {
        return mimes.length > 0 ? mimes[0] : "application/octet-stream";
    }

    private void addReceivedItem(Uri uri, String mime) {
        int sizePx = (int) (96 * getResources().getDisplayMetrics().density);
        int marginPx = (int) (6 * getResources().getDisplayMetrics().density);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cellLp.rightMargin = marginPx;
        cell.setLayoutParams(cellLp);

        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sizePx, sizePx));
        iv.setBackgroundColor(0x11000000);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

        if (mime != null && mime.startsWith("video/")) {
            iv.setImageResource(android.R.drawable.ic_media_play);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        } else {
            loadImage(iv, uri);
        }

        TextView label = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (2 * getResources().getDisplayMetrics().density);
        label.setLayoutParams(lp);
        label.setTextSize(10f);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setText(mime != null ? mime : "?");

        cell.addView(iv);
        cell.addView(label);
        binding.receivedItems.addView(cell);
        updateEmptyState();

        Log.d(TAG, "received uri=" + uri + " mime=" + mime);
    }

    private void loadImage(ImageView iv, Uri uri) {
        // ImageDecoder (API 28+) handles GIF / animated WebP animation natively.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Drawable d = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getContentResolver(), uri));
                iv.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "ImageDecoder failed for " + uri, t);
            }
        }
        try {
            iv.setImageURI(uri);
        } catch (Throwable t) {
            Log.w(TAG, "setImageURI failed for " + uri, t);
            iv.setImageResource(android.R.drawable.ic_menu_report_image);
        }
    }

    private void clearReceived() {
        binding.receivedItems.removeAllViews();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = binding.receivedItems.getChildCount() == 0;
        binding.receivedEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // Cycles through the design-system primitives so each tap of "Sample"
    // shows a different layout. Useful for eyeballing all the styles fit.
    private static final String[] SAMPLES = {
            // table
            "<h3>Split of ₹987 across 3</h3>"
          + "<table>"
          + "  <thead><tr><th>Person</th><th>Share</th></tr></thead>"
          + "  <tbody>"
          + "    <tr><td>Person 1</td><td>₹329.00</td></tr>"
          + "    <tr><td>Person 2</td><td>₹329.00</td></tr>"
          + "    <tr><td>Person 3</td><td>₹329.00</td></tr>"
          + "  </tbody>"
          + "  <tfoot><tr><td><b>Total</b></td><td><b>₹987.00</b></td></tr></tfoot>"
          + "</table>",
            // stat
            "<div class=\"stat\">"
          + "  <div class=\"num\">₹810.00</div>"
          + "  <div class=\"label\">18% GST on ₹4500</div>"
          + "</div>",
            // checklist
            "<h3>Filter coffee</h3>"
          + "<ul class=\"checklist\">"
          + "  <li>Boil water</li>"
          + "  <li>Add 2 tbsp coffee to filter</li>"
          + "  <li>Pour hot water, drip 10 min</li>"
          + "  <li>Mix with hot milk</li>"
          + "  <li>Add sugar to taste</li>"
          + "</ul>",
            // grid of cards
            "<div class=\"grid\">"
          + "  <div class=\"card\"><div class=\"title\">iPhone 17</div>"
          +     "<div class=\"body\">A19 · 8GB · 6.1\"</div></div>"
          + "  <div class=\"card\"><div class=\"title\">Pixel 10</div>"
          +     "<div class=\"body\">Tensor G5 · 12GB · 6.3\"</div></div>"
          + "</div>",
            // key-value
            "<h3>AI 308</h3>"
          + "<dl>"
          + "  <dt>Date</dt><dd>21 May</dd>"
          + "  <dt>Departs</dt><dd>BLR · 8:30 am</dd>"
          + "  <dt>Arrives</dt><dd>DEL</dd>"
          + "</dl>",
            // callout + badge
            "<div class=\"callout\">"
          + "  Heads up — flight <span class=\"badge pink\">DELAYED</span> by 45 min."
          + "</div>",
    };
    private int sampleIndex = 0;
    private String nextSample() {
        String s = SAMPLES[sampleIndex % SAMPLES.length];
        sampleIndex++;
        return s;
    }

    private void renderHtml() {
        String html = binding.htmlInput.getText().toString().trim();
        if (html.isEmpty()) {
            binding.renderStatus.setText("Paste an HTML fragment first.");
            return;
        }
        binding.renderStatus.setText("Rendering…");
        binding.renderResult.setImageDrawable(null);
        long t0 = System.currentTimeMillis();
        android.view.ViewGroup root = findViewById(android.R.id.content);
        AttachedHtmlRenderer.render(this, root, html, new AttachedHtmlRenderer.Callback() {
            @Override public void onRendered(android.graphics.Bitmap bitmap) {
                long ms = System.currentTimeMillis() - t0;
                binding.renderResult.setImageBitmap(bitmap);
                binding.renderStatus.setText("OK — " + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " in " + ms + "ms");
            }
            @Override public void onError(String message) {
                binding.renderStatus.setText("Error: " + message);
            }
        });
    }

    private void openInputMethodSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open keyboard settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void showInputMethodPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }
}
