package com.prince.turtlekeyboard.onboarding;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Single entry point for resolving which onboarding flow to render. Order of
 * resolution: campaign-specific remote → campaign-specific asset → default
 * remote → default asset. The asset fallback guarantees first-launch always
 * paints something even with no network.
 *
 * Adding a remote source: implement {@link OnboardingFlowSource} (e.g. wrap
 * Firebase Remote Config's {@code getString("onboarding_flow_" + flowId)}) and
 * call {@link #addRemoteSource}. The repository will try remote sources first
 * and silently fall through to the asset default on any miss.
 */
public final class OnboardingFlowRepository {
    public static final String DEFAULT_FLOW_ID = "default";

    private final List<OnboardingFlowSource> remoteSources = new ArrayList<>();
    private final OnboardingFlowSource assetSource;

    public OnboardingFlowRepository(Context context) {
        this.assetSource = new AssetFlowSource(context);
    }

    public void addRemoteSource(OnboardingFlowSource source) {
        remoteSources.add(source);
    }

    /**
     * Resolve a flow for the given campaign id (e.g. "puzzle", "poll", null for
     * organic installs). Always returns a non-null flow — falls back to the
     * default asset if nothing else matches.
     */
    public OnboardingFlow resolve(String campaignId) {
        String wanted = campaignId == null || campaignId.isEmpty() ? DEFAULT_FLOW_ID : campaignId;

        for (OnboardingFlowSource src : remoteSources) {
            OnboardingFlow f = safeLoad(src, wanted);
            if (f != null) return f;
        }
        OnboardingFlow asset = assetSource.load(wanted);
        if (asset != null) return asset;

        if (!DEFAULT_FLOW_ID.equals(wanted)) {
            for (OnboardingFlowSource src : remoteSources) {
                OnboardingFlow f = safeLoad(src, DEFAULT_FLOW_ID);
                if (f != null) return f;
            }
            OnboardingFlow def = assetSource.load(DEFAULT_FLOW_ID);
            if (def != null) return def;
        }
        return new OnboardingFlow(DEFAULT_FLOW_ID, 0, new ArrayList<>());
    }

    private static OnboardingFlow safeLoad(OnboardingFlowSource src, String id) {
        try { return src.load(id); } catch (Throwable t) { return null; }
    }
}
