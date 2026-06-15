package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.prince.kbd.core.GeminiService;
import com.prince.turtlekeyboard.BuildConfig;
import com.prince.turtlekeyboard.R;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * {@link GeminiService} that returns the bundled {@code R.drawable.gg} fixture
 * after a configured delay instead of calling Nano Banana. Active when
 * {@link BuildConfig#MOCK_AI} is true. Phase 1 honors only SUCCESS; error /
 * fragmented / degenerate scenarios ship in phases 3+4.
 *
 * <p>Sits next to {@link MockAiClient} but operates one layer lower: MockAiClient
 * stubs the dispatcher for loader-timing tests; this stubs the AI primitive itself
 * so every command (sticker, gif, gift, cap, edit) gets the fixture back through
 * its normal pipeline.</p>
 */
public final class MockGeminiService implements GeminiService {

    private static final String TAG = "MockGeminiService";

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable private volatile byte[] cachedFixturePng;

    public MockGeminiService(Context appContext) {
        this.appContext = appContext.getApplicationContext();
        Log.i(TAG, "active scenario=" + BuildConfig.MOCK_SCENARIO
                + " delays img=" + BuildConfig.MOCK_DELAY_IMAGE_MS
                + " edit=" + BuildConfig.MOCK_DELAY_IMAGE_EDIT_MS
                + " text=" + BuildConfig.MOCK_DELAY_TEXT_MS);
    }

    @Override
    public void text(@Nullable String systemPrompt, String userPrompt, TextCallback cb) {
        main.postDelayed(() -> cb.onText("mock text reply for: " + userPrompt),
                BuildConfig.MOCK_DELAY_TEXT_MS);
    }

    @Override
    public void image(@Nullable String systemPrompt, String userPrompt, ImageCallback cb) {
        deliverFixture(cb, BuildConfig.MOCK_DELAY_IMAGE_MS);
    }

    @Override
    public void imageEdit(@Nullable String systemPrompt, String userPrompt,
                          List<InlineImage> references, ImageCallback cb) {
        deliverFixture(cb, BuildConfig.MOCK_DELAY_IMAGE_EDIT_MS);
    }

    @Override
    public void imageEditPro(@Nullable String systemPrompt, String userPrompt,
                             List<InlineImage> references, ImageCallback cb) {
        deliverFixture(cb, BuildConfig.MOCK_DELAY_IMAGE_EDIT_MS);
    }

    private void deliverFixture(ImageCallback cb, int delayMs) {
        main.postDelayed(() -> {
            byte[] png = loadFixturePng();
            if (png == null) cb.onError("mock fixture unavailable");
            else cb.onImage(png);
        }, delayMs);
    }

    /** AAPT may re-encode drawable PNGs at build time, so decode-then-recompress
     *  guarantees a valid PNG byte array regardless of crunch settings. */
    @Nullable
    private byte[] loadFixturePng() {
        byte[] cached = cachedFixturePng;
        if (cached != null) return cached;
        synchronized (this) {
            if (cachedFixturePng != null) return cachedFixturePng;
            try {
                Bitmap bmp = BitmapFactory.decodeResource(
                        appContext.getResources(), R.drawable.gg);
                if (bmp == null) {
                    Log.w(TAG, "R.drawable.gg decoded to null");
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                bmp.recycle();
                cachedFixturePng = out.toByteArray();
                Log.d(TAG, "fixture loaded; " + cachedFixturePng.length + " bytes");
                return cachedFixturePng;
            } catch (Exception e) {
                Log.w(TAG, "fixture load failed", e);
                return null;
            }
        }
    }
}
