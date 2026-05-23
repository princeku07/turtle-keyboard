package com.prince.split;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import java.util.Locale;

/**
 * Inspects the host editor's {@link EditorInfo} to decide whether a numeric field is safe
 * to expose contextual chips on. Conservative on purpose — sensitive fields (PIN, OTP, CVV)
 * must never show a chip.
 */
public final class EditorFieldHeuristics {

    private EditorFieldHeuristics() {}

    /** True when the field accepts numeric input (with or without a decimal). */
    public static boolean isNumericField(EditorInfo info) {
        if (info == null) return false;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        return cls == InputType.TYPE_CLASS_NUMBER;
    }

    /** True when the field looks like a PIN, OTP, CVV, or password. */
    public static boolean looksSensitive(EditorInfo info) {
        if (info == null) return true;

        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true;
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD) return true;
        if (variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) return true;
        if (variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return true;

        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true;

        String haystack = (str(info.hintText) + " " + str(info.label) + " " + str(info.fieldName))
                .toLowerCase(Locale.ROOT);
        return haystack.contains("otp")
                || haystack.contains("pin")
                || haystack.contains("cvv")
                || haystack.contains("password")
                || haystack.contains("passcode")
                || haystack.contains("verification");
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString(); }
}
