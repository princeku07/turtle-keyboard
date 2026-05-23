package com.prince.turtlekeyboard.onboarding;

/**
 * Pluggable source of onboarding flows. The default ships with the APK; a remote
 * source (Firestore doc, Remote Config parameter, signed HTTP payload) can be
 * added by implementing this interface and registering it in OnboardingFlowRepository.
 */
public interface OnboardingFlowSource {

    /** Resolve a flow by id. Returns null when the source has nothing for that id. */
    OnboardingFlow load(String flowId);
}
