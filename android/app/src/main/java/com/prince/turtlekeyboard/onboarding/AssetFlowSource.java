package com.prince.turtlekeyboard.onboarding;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads bundled onboarding flows from assets/onboarding/{flowId}.json. Always
 * available offline — used as the safety net behind any remote source.
 */
public final class AssetFlowSource implements OnboardingFlowSource {
    private static final String TAG = "OnboardingAsset";
    private static final String DIR = "onboarding/";

    private final Context appContext;

    public AssetFlowSource(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public OnboardingFlow load(String flowId) {
        String path = DIR + flowId + ".json";
        try (InputStream is = appContext.getAssets().open(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return OnboardingFlow.fromJson(sb.toString());
        } catch (IOException e) {
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Malformed flow asset: " + path, e);
            return null;
        }
    }
}
