package com.prince.turtlekeyboard.onboarding;

/** One screen in an onboarding flow. Pure data — rendered by FeatureOnboardingActivity. */
public final class OnboardingPage {
    public final String id;
    public final String heroKey;
    public final String title;
    public final String subtitle;
    public final String ctaLabel;

    public OnboardingPage(String id, String heroKey, String title, String subtitle, String ctaLabel) {
        this.id = id;
        this.heroKey = heroKey;
        this.title = title;
        this.subtitle = subtitle;
        this.ctaLabel = ctaLabel;
    }
}
