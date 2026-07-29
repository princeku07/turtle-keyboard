package com.prince.turtlekeyboard.ui;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.anyOf;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.prince.turtlekeyboard.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Smoke test for the host-app entry point. Catches startup crashes (Firebase
 * init, layout inflation, theme application) before they reach a real device.
 *
 * <p>Onboarding launches another activity from {@code MainActivity.onCreate}
 * via {@code startActivity}, so the visible UI under test may be either
 * MainActivity or the onboarding activity stacked on top — assertions accept
 * either's primary affordance.</p>
 */
@RunWith(AndroidJUnit4.class)
public class MainActivitySmokeTest {

    @Test
    public void mainActivity_launches_without_crashing() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.moveToState(Lifecycle.State.RESUMED);
            // Either the MainActivity onboarding CTA or the FeatureOnboardingActivity
            // first-step affordance should be visible. Locked text drifts; tolerate both.
            onView(anyOf(
                    withId(R.id.btn_enable),
                    withText("Enable Turtle Keyboard")))
                    .check(matches(isDisplayed()));
        }
    }
}
