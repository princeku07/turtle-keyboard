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
 * Transparent shim Activity that fires the system image picker on behalf of the IME.
 * API 33+ uses the bottom-sheet Photo Picker; older devices fall back to
 * {@code ACTION_GET_CONTENT}. Without {@link #EXTRA_REQUEST_ID} the picked bytes are
 * staged via {@link LmStudioAiClient#stageEditImage}; with it, they're delivered via
 * {@link PickerResultBus}.
 */
public class ImagePickerActivity extends Activity {

    private static final String TAG = "ImagePickerActivity";
    private static final int REQ_PICK = 1;

    /** Optional int extra; presence routes the result through {@link PickerResultBus}. */
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
                // Downsize before delivering — bytes sit in memory until consumed.
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
