package com.prince.turtlekeyboard.onboarding;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A complete onboarding flow — the ordered pages plus the flow id (used to attribute
 * which variant a user saw). Authored as JSON so the same schema works for the
 * local asset default and for any remote source (Firestore, Remote Config, HTTP).
 */
public final class OnboardingFlow {
    public final String id;
    public final int version;
    public final List<OnboardingPage> pages;

    public OnboardingFlow(String id, int version, List<OnboardingPage> pages) {
        this.id = id;
        this.version = version;
        this.pages = Collections.unmodifiableList(pages);
    }

    public static OnboardingFlow fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        String id = root.optString("id", "default");
        int version = root.optInt("version", 1);
        JSONArray pagesArr = root.getJSONArray("pages");
        List<OnboardingPage> pages = new ArrayList<>(pagesArr.length());
        for (int i = 0; i < pagesArr.length(); i++) {
            JSONObject p = pagesArr.getJSONObject(i);
            pages.add(new OnboardingPage(
                    p.optString("id", "page_" + i),
                    p.optString("heroKey", ""),
                    p.optString("title", ""),
                    p.optString("subtitle", ""),
                    p.optString("ctaLabel", "Continue")));
        }
        return new OnboardingFlow(id, version, pages);
    }
}
