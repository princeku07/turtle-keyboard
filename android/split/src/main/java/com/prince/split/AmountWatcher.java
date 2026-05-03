package com.prince.split;

import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure-logic watcher for amount-shaped input. The IME feeds it the field's current text
 * (before + after cursor); the watcher emits a normalized amount when the field looks like
 * an amount and {@code null} when it doesn't.
 *
 * <p>Kept free of Android types so it can be unit-tested in isolation.
 */
public class AmountWatcher {

    public interface Listener {
        void onAmountChanged(@Nullable String amount);
    }

    /** 1–7 digits, optionally followed by .[1-2 digits]. ₹9,999,999 ceiling is plenty. */
    private static final Pattern AMOUNT = Pattern.compile("^\\d{1,7}(\\.\\d{1,2})?$");

    /** Drops everything that isn't a digit or decimal point — strips ₹, commas, spaces,
     *  and any other formatting payment apps inject into the field as the user types. */
    private static final Pattern STRIP = Pattern.compile("[^\\d.]");

    private final Listener listener;
    private boolean armed;
    @Nullable private String lastEmitted;

    public AmountWatcher(Listener listener) {
        this.listener = listener;
    }

    public void arm() {
        armed = true;
    }

    public void disarm() {
        armed = false;
        emit(null);
    }

    public void onTextChanged(CharSequence before, CharSequence after) {
        if (!armed) { emit(null); return; }
        String raw = (before == null ? "" : before).toString()
                + (after == null ? "" : after).toString();
        String cleaned = STRIP.matcher(raw).replaceAll("");
        emit(isAmount(cleaned) ? cleaned : null);
    }

    private void emit(@Nullable String value) {
        if (Objects.equals(value, lastEmitted)) return;
        lastEmitted = value;
        listener.onAmountChanged(value);
    }

    /** A non-zero numeric value that fits the amount pattern. */
    static boolean isAmount(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!AMOUNT.matcher(s).matches()) return false;
        try {
            return Double.parseDouble(s) > 0d;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
