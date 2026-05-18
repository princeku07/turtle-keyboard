package com.prince.turtlekeyboard.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Transparent shim Activity that fires the system image picker on behalf of the IME
 * (which has no Activity context).
 *
 * <p>On API 33+ (Tiramisu) uses {@code MediaStore.ACTION_PICK_IMAGES} — the system
 * Photo Picker renders as a bottom sheet, avoiding the full-screen activity
 * transition that disrupts the IME window. Older devices fall back to the classic
 * {@code ACTION_GET_CONTENT} picker.
 *
 * <p>Two delivery modes:
 * <ul>
 *   <li><b>Legacy /edit</b> — no {@link #EXTRA_REQUEST_ID} in the intent. Picked bytes
 *       are staged via {@link LmStudioAiClient#stageEditImage}, consumed by the next
 *       {@code /edit} dispatch.</li>
 *   <li><b>SPI-routed</b> — {@link #EXTRA_REQUEST_ID} present. Picked bytes are
 *       published via {@link PickerResultBus#deliver}, which the IME consumes to fire
 *       the integration's {@code ImagePickCallback}.</li>
 * </ul>
 *
 * <p>Launched with {@code FLAG_ACTIVITY_NEW_TASK} since the IME starts it from an
 * application Context. {@code excludeFromRecents} keeps it out of the recents UI.
 */
public class ImagePickerActivity extends Activity {

    private static final String TAG = "ImagePickerActivity";
    private static final int REQ_PICK = 1;

    /** Optional int extra. Absent ⇒ legacy {@code /edit} staging. Present ⇒ result
     *  routes through {@link PickerResultBus}, keyed by this id. */
    public static final String EXTRA_REQUEST_ID = "request_id";

    private int requestId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent().getIntExtra(EXTRA_REQUEST_ID, -1);
        Intent pick = buildPickerIntent();
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Exception e) {
            Log.w(TAG, "no picker available", e);
            deliverCancel();
            finish();
        }
    }

    /** Photo Picker on API 33+, ACTION_GET_CONTENT below. The Photo Picker is a
     *  bottom-sheet system UI that doesn't require a full-screen activity
     *  transition — keeps the IME from glitching as the picker opens. */
    private Intent buildPickerIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
            return picker;
        }
        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
        fallback.setType("image/*");
        fallback.addCategory(Intent.CATEGORY_OPENABLE);
        return fallback;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            deliverCancel();
            finish();
            return;
        }
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                deliverCancel();
            } else {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                // Downsize before delivering — bytes sit in memory (either the static
                // staging ref or the SPI callback's closure) until consumed, and a
                // full-res selfie can be 5+ MB. Bounded delivery = bounded IME heap.
                byte[] downsized = ImageDownsizer.downsizeToJpegBytes(out.toByteArray());
                deliver(downsized, "image/jpeg");
            }
        } catch (Exception e) {
            Log.w(TAG, "read failed for " + uri, e);
            deliverCancel();
        }
        finish();
    }

    private void deliverCancel() {
        deliver(null, null);
    }

    private void deliver(byte[] bytes, String mime) {
        if (requestId < 0) {
            LmStudioAiClient.stageEditImage(bytes, mime);
        } else {
            PickerResultBus.deliver(requestId, bytes, mime);
        }
    }
}
