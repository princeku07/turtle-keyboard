package com.prince.turtlekeyboard.ai;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Transparent shim Activity that fires the system image picker on behalf of the IME
 * (which has no Activity context). On result it reads the picked image's bytes and
 * stages them via {@link LmStudioAiClient#stageEditImage}, so when the user finishes
 * typing the {@code /edit} prompt and hits Go the staged image is consumed.
 *
 * <p>Launched with {@code FLAG_ACTIVITY_NEW_TASK} since the IME starts it from an
 * application Context. {@code excludeFromRecents} keeps it out of the recents UI.
 */
public class ImagePickerActivity extends Activity {

    private static final String TAG = "ImagePickerActivity";
    private static final int REQ_PICK = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Exception e) {
            Log.w(TAG, "no picker available", e);
            LmStudioAiClient.stageEditImage(null, null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            LmStudioAiClient.stageEditImage(null, null);
            finish();
            return;
        }
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                LmStudioAiClient.stageEditImage(null, null);
            } else {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                // Downsize before staging — the staged bytes sit in a static
                // AtomicReference until /edit consumes them, and a full-res selfie can
                // be 5+ MB. Bounded staging = bounded IME heap pressure.
                byte[] downsized = ImageDownsizer.downsizeToJpegBytes(out.toByteArray());
                LmStudioAiClient.stageEditImage(downsized, "image/jpeg");
            }
        } catch (Exception e) {
            Log.w(TAG, "read failed for " + uri, e);
            LmStudioAiClient.stageEditImage(null, null);
        }
        finish();
    }
}
