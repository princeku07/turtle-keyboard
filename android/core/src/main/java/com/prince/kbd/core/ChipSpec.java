package com.prince.kbd.core;

import androidx.annotation.Nullable;

/**
 * Data the IME needs to render the integration chip above the keys. Either {@link #iconPackage}
 * is set (the IME loads the launcher icon via PackageManager) or both icon fields are null
 * (text-only chip).
 */
public final class ChipSpec {

    public final String label;
    @Nullable public final String iconPackage;

    private ChipSpec(String label, @Nullable String iconPackage) {
        this.label = label;
        this.iconPackage = iconPackage;
    }

    public static ChipSpec withHostIcon(String label, String iconPackage) {
        return new ChipSpec(label, iconPackage);
    }

    public static ChipSpec textOnly(String label) {
        return new ChipSpec(label, null);
    }
}
