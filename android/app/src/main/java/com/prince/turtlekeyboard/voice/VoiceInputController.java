package com.prince.turtlekeyboard.voice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Wraps {@link SpeechRecognizer} for the IME mic key. Streams partials via
 * {@link Sink#onPartial} and final transcript via {@link Sink#onFinal}.
 *
 * <p>IMEs cannot request runtime permissions, so callers must check
 * {@link #hasMicPermission} and route the user to the host app to grant RECORD_AUDIO.
 */
public class VoiceInputController {

    private static final String TAG = "VoiceInputController";

    public interface Sink {
        void onPartial(String text);
        /** Called once when recognition locks in; {@code text} may be empty. */
        void onFinal(String text);
        void onError(String userVisibleMessage);
        void onListeningStarted();
        void onListeningStopped();
        /** Latest mic loudness in dB (roughly -2..10 from the platform recognizer). */
        default void onRms(float dB) {}
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean listening;
    private Sink activeSink;

    public VoiceInputController(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public static boolean hasMicPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isListening() { return listening; }

    /** Tap-to-toggle. If already listening, stops and emits the final transcript. */
    public void toggle(Sink sink) {
        if (listening) {
            stop();
        } else {
            start(sink);
        }
    }

    public void start(Sink sink) {
        if (listening) return;
        if (!hasMicPermission(appContext)) {
            sink.onError("Microphone permission required");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            sink.onError("Speech recognition not available on this device");
            return;
        }
        this.activeSink = sink;
        // SpeechRecognizer must be created on the main thread.
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
            recognizer.setRecognitionListener(listener);
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // Prefer offline; recognizer silently falls back to online if no offline pack is installed.
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.getPackageName());

        try {
            recognizer.startListening(intent);
            listening = true;
            sink.onListeningStarted();
        } catch (Exception e) {
            Log.w(TAG, "startListening failed", e);
            sink.onError("Could not start mic");
            activeSink = null;
        }
    }

    public void stop() {
        if (!listening) return;
        try { recognizer.stopListening(); } catch (Exception ignored) {}
        // onResults / onError fires next and calls finishListening().
    }

    public void cancel() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        finishListening();
    }

    public void destroy() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
        listening = false;
        activeSink = null;
    }

    private void finishListening() {
        listening = false;
        Sink s = activeSink;
        activeSink = null;
        if (s != null) main.post(s::onListeningStopped);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { Log.d(TAG, "onReadyForSpeech"); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {
            Sink s = activeSink;
            if (s != null) s.onRms(rmsdB);
        }
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { Log.d(TAG, "onEndOfSpeech"); }
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override public void onPartialResults(Bundle partial) {
            ArrayList<String> hyp = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (hyp != null && !hyp.isEmpty() && activeSink != null) {
                String top = hyp.get(0);
                Log.d(TAG, "partial: " + top);
                activeSink.onPartial(top);
            }
        }

        @Override public void onResults(Bundle results) {
            ArrayList<String> hyp = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String top = (hyp == null || hyp.isEmpty()) ? "" : hyp.get(0);
            Log.d(TAG, "final: " + top);
            Sink s = activeSink;
            finishListening();
            if (s != null) main.post(() -> s.onFinal(top));
        }

        @Override public void onError(int code) {
            String msg = describeError(code);
            Log.w(TAG, "recognizer error " + code + ": " + msg);
            Sink s = activeSink;
            finishListening();
            if (s != null) main.post(() -> s.onError(msg));
        }
    };

    private static String describeError(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO:                return "Audio error";
            case SpeechRecognizer.ERROR_CLIENT:               return "Client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Mic permission missing";
            case SpeechRecognizer.ERROR_NETWORK:              return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:      return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:             return "Didn't catch that";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:      return "Recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:               return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:       return "No speech detected";
            default:                                          return "Mic error " + code;
        }
    }
}
