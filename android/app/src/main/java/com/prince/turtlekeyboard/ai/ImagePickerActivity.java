package com.prince.turtlekeyboard.ai;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import com.prince.turtlekeyboard.TurtleApp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent shim Activity that fires the system image picker on behalf of the IME.
 * API 33+ uses the bottom-sheet Photo Picker; older devices fall back to
 * {@code ACTION_GET_CONTENT}.
 *
 * <p>Single-pick: bytes are staged on the app's {@link StagingPipeline} or, with
 * {@link #EXTRA_REQUEST_ID}, delivered via {@link PickerResultBus}.
 *
 * <p>{@link #EXTRA_PICK_COUNT}>1: multi-select; must pick exactly that many or the result is
 * treated as cancel. Multi-pick results are staged via {@link StagingPipeline#stageUsImages}.
 */
public class ImagePickerActivity extends Activity {

    private static final String TAG = "ImagePickerActivity";
    private static final int REQ_PICK = 1;

    /** Optional int extra; presence routes the result through {@link PickerResultBus}. */
    public static final String EXTRA_REQUEST_ID = "request_id";

    /** Optional int extra; >1 enables multi-select with that exact count required. */
    public static final String EXTRA_PICK_COUNT = "pick_count";

    private int requestId = -1;
    private int pickCount = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent().getIntExtra(EXTRA_REQUEST_ID, -1);
        pickCount = Math.max(1, getIntent().getIntExtra(EXTRA_PICK_COUNT, 1));
        Intent pick = buildPickerIntent();
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Exception e) {
            Log.w(TAG, "no picker available", e);
            deliverCancel();
            finish();
        }
    }

    private Intent buildPickerIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
            if (pickCount > 1) {
                picker.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, pickCount);
            }
            return picker;
        }
        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
        fallback.setType("image/*");
        fallback.addCategory(Intent.CATEGORY_OPENABLE);
        if (pickCount > 1) {
            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return fallback;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            deliverCancel();
            finish();
            return;
        }
        if (pickCount > 1) {
            List<Uri> uris = collectUris(data);
            if (uris.size() != pickCount) {
                if (!uris.isEmpty()) {
                    Toast.makeText(this, "Pick exactly " + pickCount + " photos",
                            Toast.LENGTH_SHORT).show();
                }
                deliverCancel();
                finish();
                return;
            }
            List<byte[]> images = new ArrayList<>(uris.size());
            List<String> mimes = new ArrayList<>(uris.size());
            for (Uri uri : uris) {
                byte[] bytes = readBytes(uri);
                if (bytes == null) {
                    deliverCancel();
                    finish();
                    return;
                }
                images.add(bytes);
                mimes.add("image/jpeg");
            }
            TurtleApp.from(this).stagingPipeline().stageUsImages(images, mimes);
            finish();
            return;
        }
        if (data.getData() == null) {
            deliverCancel();
            finish();
            return;
        }
        byte[] bytes = readBytes(data.getData());
        if (bytes == null) {
            deliverCancel();
        } else {
            deliver(bytes, "image/jpeg");
        }
        finish();
    }

    /** Returns up to {@code pickCount} URIs from either ClipData (multi) or getData (single). */
    private List<Uri> collectUris(Intent data) {
        List<Uri> out = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            int n = Math.min(clip.getItemCount(), pickCount);
            for (int i = 0; i < n; i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) out.add(u);
            }
        } else if (data.getData() != null) {
            out.add(data.getData());
        }
        return out;
    }

    /** Returns downsized JPEG bytes for {@code uri}, or null on failure. */
    private byte[] readBytes(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return ImageDownsizer.downsizeToJpegBytes(out.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "read failed for " + uri, e);
            return null;
        }
    }

    private void deliverCancel() {
        if (pickCount > 1) {
            TurtleApp.from(this).stagingPipeline().stageUsImages(null, null);
        } else {
            deliver(null, null);
        }
    }

    private void deliver(byte[] bytes, String mime) {
        if (requestId < 0) {
            TurtleApp.from(this).stagingPipeline().stageEditImage(bytes, mime);
        } else {
            PickerResultBus.deliver(requestId, bytes, mime);
        }
    }
}
