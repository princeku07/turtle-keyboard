package com.prince.turtlekeyboard.ime;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.prince.turtlekeyboard.R;

public class TurtleInputMethodService extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener {

    private KeyboardView keyboardView;
    private TextView banner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideBanner = new Runnable() {
        @Override public void run() {
            if (banner != null) banner.setVisibility(View.GONE);
        }
    };
    private Keyboard qwertyKeyboard;
    private Keyboard symbolsKeyboard;
    private Keyboard symbolsShiftKeyboard;

    private boolean capsLock = false;
    private long lastShiftTapMs = 0L;
    private long lastSpaceTapMs = 0L;
    private static final long DOUBLE_TAP_MS = 300L;
    private static final int KEYCODE_SPACE = 32;

    @Override
    public View onCreateInputView() {
        View root = View.inflate(this, R.layout.keyboard_view, null);
        keyboardView = root.findViewById(R.id.keyboard_view);
        banner = root.findViewById(R.id.banner);
        qwertyKeyboard = new Keyboard(this, R.xml.qwerty);
        symbolsKeyboard = new Keyboard(this, R.xml.symbols);
        symbolsShiftKeyboard = new Keyboard(this, R.xml.symbols_shift);
        keyboardView.setKeyboard(qwertyKeyboard);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        capsLock = false;
        if (keyboardView != null) {
            keyboardView.setKeyboard(qwertyKeyboard);
            keyboardView.setShifted(false);
        }
    }

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        Keyboard current = keyboardView.getKeyboard();

        switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                handleBackspace(ic);
                break;
            case Keyboard.KEYCODE_SHIFT:
                handleShift(current);
                break;
            case Keyboard.KEYCODE_DONE:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                break;
            case Keyboard.KEYCODE_MODE_CHANGE:
                if (current == qwertyKeyboard) {
                    keyboardView.setKeyboard(symbolsKeyboard);
                } else {
                    keyboardView.setKeyboard(qwertyKeyboard);
                    keyboardView.setShifted(capsLock);
                }
                break;
            default:
                char code = (char) primaryCode;
                if (Character.isLetter(code) && (keyboardView.isShifted() || capsLock)) {
                    code = Character.toUpperCase(code);
                }
                ic.commitText(String.valueOf(code), 1);
                if (primaryCode == KEYCODE_SPACE) {
                    detectSpaceDoubleTap();
                }
                if (keyboardView.isShifted() && !capsLock && current == qwertyKeyboard) {
                    keyboardView.setShifted(false);
                }
                break;
        }
    }

    private void handleBackspace(InputConnection ic) {
        CharSequence selected = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(selected)) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void handleShift(Keyboard current) {
        if (current == qwertyKeyboard) {
            long now = System.currentTimeMillis();
            if (now - lastShiftTapMs < DOUBLE_TAP_MS) {
                capsLock = !capsLock;
                keyboardView.setShifted(true);
            } else {
                capsLock = false;
                keyboardView.setShifted(!keyboardView.isShifted());
            }
            lastShiftTapMs = now;
        } else if (current == symbolsKeyboard) {
            keyboardView.setKeyboard(symbolsShiftKeyboard);
        } else {
            keyboardView.setKeyboard(symbolsKeyboard);
        }
    }

    private void detectSpaceDoubleTap() {
        long now = System.currentTimeMillis();
        if (now - lastSpaceTapMs < DOUBLE_TAP_MS) {
            showBanner("🐢 Double-tap detected");
            lastSpaceTapMs = 0L;
        } else {
            lastSpaceTapMs = now;
        }
    }

    private void showBanner(String text) {
        if (banner == null) return;
        banner.setText(text);
        banner.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideBanner);
        mainHandler.postDelayed(hideBanner, 1500L);
    }

    @Override public void onPress(int primaryCode) {}
    @Override public void onRelease(int primaryCode) {}
    @Override public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}
