package com.prince.turtlekeyboard.keyboard;

import android.content.Context;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;

import com.prince.turtlekeyboard.R;

/**
 * Owns the three layout objects (qwerty, symbols, symbols-shift) and switches the
 * active layout on the bound KeyboardView. Knows nothing about input or shift state.
 */
public class KeyboardController {

    public enum Layout { QWERTY, SYMBOLS, SYMBOLS_SHIFT }

    private final Keyboard qwerty;
    private final Keyboard symbols;
    private final Keyboard symbolsShift;
    private KeyboardView view;
    private Layout active = Layout.QWERTY;

    public KeyboardController(Context context) {
        this.qwerty = new Keyboard(context, R.xml.qwerty);
        this.symbols = new Keyboard(context, R.xml.symbols);
        this.symbolsShift = new Keyboard(context, R.xml.symbols_shift);
    }

    public void attach(KeyboardView view) {
        this.view = view;
        apply();
    }

    public Layout active() { return active; }

    public boolean isQwerty() { return active == Layout.QWERTY; }

    public void setLayout(Layout layout) {
        if (this.active == layout) return;
        this.active = layout;
        apply();
    }

    public void toggleLetterSymbol() {
        setLayout(active == Layout.QWERTY ? Layout.SYMBOLS : Layout.QWERTY);
    }

    public void toggleSymbolShift() {
        if (active == Layout.SYMBOLS) setLayout(Layout.SYMBOLS_SHIFT);
        else if (active == Layout.SYMBOLS_SHIFT) setLayout(Layout.SYMBOLS);
    }

    private void apply() {
        if (view == null) return;
        switch (active) {
            case QWERTY: view.setKeyboard(qwerty); break;
            case SYMBOLS: view.setKeyboard(symbols); break;
            case SYMBOLS_SHIFT: view.setKeyboard(symbolsShift); break;
        }
    }
}
