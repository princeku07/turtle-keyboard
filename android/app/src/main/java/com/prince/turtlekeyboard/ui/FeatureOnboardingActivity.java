package com.prince.turtlekeyboard.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.onboarding.HeroBinders;
import com.prince.turtlekeyboard.onboarding.OnboardingFlow;
import com.prince.turtlekeyboard.onboarding.OnboardingFlowRepository;
import com.prince.turtlekeyboard.onboarding.OnboardingPage;

/**
 * First-impression marketing onboarding. Swipeable pages defined by a remote-
 * configurable {@link OnboardingFlow} so the screens can be repersonalised
 * post-install (e.g. a /puzzle deeplink picks a different flow id than an
 * organic Play Store install).
 *
 * <p>Caller passes {@link #EXTRA_CAMPAIGN_ID}; the repository resolves which
 * flow to render. Always renders something even if every source returns null.
 */
public final class FeatureOnboardingActivity extends AppCompatActivity {

    public static final String EXTRA_CAMPAIGN_ID = "campaign_id";

    public static Intent intentFor(Context context, String campaignId) {
        Intent i = new Intent(context, FeatureOnboardingActivity.class);
        if (campaignId != null) i.putExtra(EXTRA_CAMPAIGN_ID, campaignId);
        return i;
    }

    private ViewPager2 pager;
    private LinearLayout dots;
    private Button btnContinue;
    private OnboardingFlow flow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Draw the hero artwork edge-to-edge behind the transparent status bar.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_feature_onboarding);

        String campaignId = getIntent().getStringExtra(EXTRA_CAMPAIGN_ID);
        flow = new OnboardingFlowRepository(this).resolve(campaignId);

        pager = findViewById(R.id.pager);
        dots = findViewById(R.id.dots);
        btnContinue = findViewById(R.id.btn_continue);

        View root = findViewById(R.id.onboarding_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });

        if (flow.pages.isEmpty()) { finish(); return; }

        pager.setAdapter(new PagesAdapter(flow));
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { syncForPage(position); }
        });

        buildDots(flow.pages.size());
        btnContinue.setOnClickListener(v -> advance());
        syncForPage(0);
    }

    private void advance() {
        int next = pager.getCurrentItem() + 1;
        if (next >= flow.pages.size()) {
            finishOnboarding();
            return;
        }
        pager.setCurrentItem(next, true);
    }

    private void finishOnboarding() {
        setResult(RESULT_OK);
        finish();
    }

    private void syncForPage(int position) {
        OnboardingPage page = flow.pages.get(position);
        btnContinue.setText(page.ctaLabel != null && !page.ctaLabel.isEmpty() ? page.ctaLabel : "Continue");
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            boolean active = i == position;
            dot.setBackgroundResource(active
                    ? R.drawable.bg_onboard_dot_active
                    : R.drawable.bg_onboard_dot_inactive);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            lp.width = dp(active ? 22 : 6);
            dot.setLayoutParams(lp);
        }
    }

    private void buildDots(int count) {
        dots.removeAllViews();
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.setMarginStart(i == 0 ? 0 : dp(6));
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_onboard_dot_inactive);
            dots.addView(dot);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // -- Pager adapter -------------------------------------------------------

    private static final class PagesAdapter extends RecyclerView.Adapter<PageHolder> {
        private final OnboardingFlow flow;

        PagesAdapter(OnboardingFlow flow) { this.flow = flow; }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding_page, parent, false);
            return new PageHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.bind(flow.pages.get(position));
        }

        @Override
        public int getItemCount() { return flow.pages.size(); }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        private final View heroSlot;
        private final TextView title;
        private final TextView subtitle;

        PageHolder(@NonNull View itemView) {
            super(itemView);
            heroSlot = itemView.findViewById(R.id.hero_slot);
            title = itemView.findViewById(R.id.page_title);
            subtitle = itemView.findViewById(R.id.page_subtitle);
        }

        void bind(OnboardingPage page) {
            title.setText(page.title);
            subtitle.setText(page.subtitle);
            HeroBinders.bind(heroSlot, page);
        }
    }
}
