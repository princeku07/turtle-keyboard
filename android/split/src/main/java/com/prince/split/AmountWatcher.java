package com.prince.split;

import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure-logic watcher for amount-shaped input. Emits a normalized amount when the field
 * looks like one, {@code null} otherwise. Free of Android types so it's unit-testable.
 */
public class AmountWatcher {

    public interface Listener {
        void onAmountChanged(@Nullable String amount);
    }

    private static final Pattern AMOUNT = Pattern.compile("^\\d{1,7}(\\.\\d{1,2})?$");

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
