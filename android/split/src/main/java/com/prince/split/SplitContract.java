package com.prince.split;

/**
 * Identifiers shared between callers (the keyboard / host app) and the Split UI module.
 * Single point of agreement so a future standalone Split APK can integrate by depending on
 * this module alone.
 */
public final class SplitContract {

    /** Stepper bounds — kept here so every consumer agrees on what "valid" means. */
    public static final int MIN_PEOPLE = 1;
    public static final int MAX_PEOPLE = 99;
    public static final int DEFAULT_PEOPLE = 2;

    private SplitContract() {}
}
