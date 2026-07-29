package com.prince.turtlekeyboard.ai;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.ai.edge.aicore.Content;
import com.google.ai.edge.aicore.DownloadCallback;
import com.google.ai.edge.aicore.DownloadConfig;
import com.google.ai.edge.aicore.GenerateContentResponse;
import com.google.ai.edge.aicore.GenerationConfig;
import com.google.ai.edge.aicore.GenerativeAIException;
import com.google.ai.edge.aicore.GenerativeModel;
import com.google.ai.edge.aicore.java.GenerativeModelFutures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemini Nano (on-device) backend for the AI assist panel.
 * Same surface as {@link TurtleAiClient#rewrite(String, String, TurtleAiClient.RewriteCallback)}
 * — the IME routes here first and falls back to cloud on {@link Availability#UNAVAILABLE}.
 *
 * Requires a device with AICore + Gemini Nano (Pixel 9, S24+, etc.); the aicore aar declares
 * minSdk 31 so we short-circuit older devices before any AICore class is resolved.
 */
public class OnDeviceAiClient {

    private static final String TAG = "OnDeviceAiClient";

    /** Free-form text-rewrite budget; Nano is fast enough that 256 covers assist-panel use cases. */
    private static final int MAX_OUTPUT_TOKENS = 256;
    private static final float TEMPERATURE = 0.2f;
    private static final int TOP_K = 16;

    public enum Availability { UNKNOWN, AVAILABLE, DOWNLOADING, UNAVAILABLE }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Availability> availability =
            new AtomicReference<>(Availability.UNKNOWN);

    @Nullable private GenerativeModelFutures model;

    public OnDeviceAiClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public Availability availability() {
        return availability.get();
    }

    /**
     * Constructs the model and fires a tiny probe so the DownloadCallback lifecycle flips
     * availability to AVAILABLE / DOWNLOADING / UNAVAILABLE. Safe to call from
     * {@code onCreate}; all work hops off the main thread internally.
     */
    public void warmup() {
        if (availability.get() != Availability.UNKNOWN) return;
        final long t0 = System.currentTimeMillis();
        Log.i(TAG, "warmup: probing AICore / Gemini Nano on "
                + Build.MANUFACTURER + " " + Build.MODEL
                + " (sdk " + Build.VERSION.SDK_INT + ")");
        // AICore aar declares minSdk 31; touching its classes on older devices risks
        // NoClassDefFoundError. Short-circuit before any GenerativeModel reference is resolved.
        if (Build.VERSION.SDK_INT < 31) {
            Log.i(TAG, "warmup: SDK " + Build.VERSION.SDK_INT + " < 31, skipping AICore");
            availability.set(Availability.UNAVAILABLE);
            return;
        }
        try {
            GenerationConfig.Builder cfgB = new GenerationConfig.Builder();
            cfgB.setContext(appContext);
            cfgB.setTemperature(TEMPERATURE);
            cfgB.setTopK(TOP_K);
            cfgB.setMaxOutputTokens(MAX_OUTPUT_TOKENS);
            GenerationConfig cfg = cfgB.build();

            DownloadConfig dc = new DownloadConfig(new DownloadCallback() {
                long totalBytes = 0;
                long lastLoggedPct = -1;
                @Override public void onDownloadPending() {
                    Log.i(TAG, "Nano download pending");
                    availability.set(Availability.DOWNLOADING);
                }
                @Override public void onDownloadStarted(long bytesToDownload) {
                    totalBytes = bytesToDownload;
                    availability.set(Availability.DOWNLOADING);
                    Log.i(TAG, "Nano download started: " + (bytesToDownload / 1_048_576) + " MiB");
                }
                @Override public void onDownloadProgress(long bytesDownloaded) {
                    if (totalBytes <= 0) return;
                    long pct = (bytesDownloaded * 100) / totalBytes;
                    if (pct >= lastLoggedPct + 10) {
                        lastLoggedPct = pct;
                        Log.i(TAG, "Nano download " + pct + "%");
                    }
                }
                @Override public void onDownloadCompleted() {
                    long dt = System.currentTimeMillis() - t0;
                    Log.i(TAG, "Nano download complete (" + dt + " ms)");
                    availability.set(Availability.AVAILABLE);
                }
                @Override public void onDownloadFailed(String message, GenerativeAIException e) {
                    Log.w(TAG, "Nano UNAVAILABLE (download failed): " + message, e);
                    availability.set(Availability.UNAVAILABLE);
                }
                @Override public void onDownloadDidNotStart(GenerativeAIException e) {
                    Log.w(TAG, "Nano UNAVAILABLE (download didn't start)", e);
                    availability.set(Availability.UNAVAILABLE);
                }
            });

            GenerativeModel m = new GenerativeModel(cfg, dc);
            GenerativeModelFutures futures = GenerativeModelFutures.from(m);
            model = futures;

            // Tiny probe: triggers the DownloadCallback lifecycle if a download is needed,
            // otherwise succeeds quickly and flips us to AVAILABLE without any callback events.
            Content probe = new Content.Builder().addText("hi").build();
            ListenableFuture<GenerateContentResponse> f = futures.generateContent(probe);
            Futures.addCallback(f, new FutureCallback<GenerateContentResponse>() {
                @Override public void onSuccess(GenerateContentResponse r) {
                    if (availability.get() != Availability.AVAILABLE) {
                        long dt = System.currentTimeMillis() - t0;
                        Log.i(TAG, "Nano READY (probe " + dt + " ms, no download needed)");
                        availability.set(Availability.AVAILABLE);
                    }
                }
                @Override public void onFailure(Throwable t) {
                    // Only mark UNAVAILABLE if the download callback hasn't already classified
                    // this state — DOWNLOADING is a legitimate transient.
                    Availability cur = availability.get();
                    if (cur != Availability.UNAVAILABLE && cur != Availability.DOWNLOADING) {
                        Log.w(TAG, "warmup probe failed", t);
                        availability.set(Availability.UNAVAILABLE);
                    }
                }
            }, MoreExecutors.directExecutor());
        } catch (Throwable t) {
            Log.w(TAG, "warmup threw — Nano UNAVAILABLE", t);
            availability.set(Availability.UNAVAILABLE);
        }
    }

    /**
     * Free-form rewrite identical in shape to {@link TurtleAiClient#rewrite}. Caller is
     * responsible for checking {@link #availability()} == AVAILABLE first; if it isn't,
     * we surface an error so the router can fall back to cloud without retrying here.
     */
    public void rewrite(String systemPrompt, String userText,
                        TurtleAiClient.RewriteCallback cb) {
        if (cb == null) return;
        GenerativeModelFutures m = model;
        if (m == null || availability.get() != Availability.AVAILABLE) {
            main.post(() -> cb.onError("On-device AI unavailable"));
            return;
        }
        String prompt = (systemPrompt == null ? "" : systemPrompt.trim())
                + "\n\n" + (userText == null ? "" : userText);
        Content content = new Content.Builder().addText(prompt).build();
        final long t0 = System.currentTimeMillis();
        Log.i(TAG, "rewrite: on-device, " + (userText == null ? 0 : userText.length()) + " chars in");
        ListenableFuture<GenerateContentResponse> f = m.generateContent(content);
        Futures.addCallback(f, new FutureCallback<GenerateContentResponse>() {
            @Override public void onSuccess(GenerateContentResponse result) {
                String out = result == null ? "" : result.getText();
                long dt = System.currentTimeMillis() - t0;
                if (out == null || out.isEmpty()) {
                    Log.w(TAG, "rewrite: empty result (" + dt + " ms)");
                    main.post(() -> cb.onError("Empty result"));
                } else {
                    String trimmed = out.trim();
                    Log.i(TAG, "rewrite: ok " + trimmed.length() + " chars (" + dt + " ms)");
                    main.post(() -> cb.onSuccess(trimmed));
                }
            }
            @Override public void onFailure(Throwable t) {
                long dt = System.currentTimeMillis() - t0;
                Log.w(TAG, "rewrite: failed (" + dt + " ms)", t);
                main.post(() -> cb.onError("On-device AI failed"));
            }
        }, MoreExecutors.directExecutor());
    }

    public void destroy() {
        model = null;
        availability.set(Availability.UNKNOWN);
    }
}
