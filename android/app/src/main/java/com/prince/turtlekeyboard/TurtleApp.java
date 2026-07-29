package com.prince.turtlekeyboard;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.emoji2.text.EmojiCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.FirebaseDatabase;
import com.prince.kbd.core.KeyboardIntegration;
import com.prince.kbd.core.SheetRouter;
import com.prince.notion.NotionIntegration;
import com.prince.slack.SlackIntegration;
import com.prince.split.SplitIntegration;
import com.prince.turtlekeyboard.ai.StagingPipeline;
import com.prince.turtlekeyboard.integration.drive.DriveIntegration;
import com.prince.turtlekeyboard.integration.poll.PollIntegration;
import com.prince.turtlekeyboard.integration.puzzle.PuzzleIntegration;
import com.prince.turtlekeyboard.integration.wyr.WyrIntegration;
import com.prince.turtlekeyboard.overlay.SheetRouterImpl;
import com.prince.web.WebIntegration;
import com.revenuecat.purchases.Purchases;
import com.revenuecat.purchases.PurchasesConfiguration;

import java.util.Arrays;
import java.util.List;

/**
 * Application class. Hoists the {@link SheetRouter} to process scope so the
 * {@code BottomSheetActivity} (triggered by an App Link from a foreign app) can resolve
 * routes without first inflating the IME. Slash-command dispatch still goes through the
 * IME's per-input-view {@code IntegrationRegistry}; this class only owns the sheet
 * route side of the SPI, which is pure data.
 *
 * <p>Same integration instances are instantiated here and again per {@code
 * onCreateInputView} in the IME — duplication is intentional. The integration objects
 * themselves are cheap to construct (no I/O, no state); a future cleanup could share
 * instances between both consumers, but that's invasive enough to defer until there's
 * a real reason.
 */
public class TurtleApp extends Application {

    private static final String TAG = "TurtleApp";

    private SheetRouterImpl sheetRouter;
    private final StagingPipeline stagingPipeline = new StagingPipeline();

    @Override
    public void onCreate() {
        super.onCreate();
        // Default config pulls Noto Color Emoji over Google's downloadable-fonts
        // provider, ensuring the emoji panel renders the same glyphs across
        // every Android 7+ device regardless of OEM font.
        EmojiCompat.init(this);
        initFirebase();
        initRevenueCat();
        sheetRouter = new SheetRouterImpl();
        for (KeyboardIntegration integration : integrations()) {
            sheetRouter.registerAll(integration.sheetRoutes());
        }
    }

    /** Firebase auto-initializes via {@code FirebaseInitProvider} (content-provider hop
     *  before {@code Application.onCreate}); calling explicitly is idempotent but makes
     *  the dependency visible at the entry point. Also opts the RTDB SDK into disk
     *  persistence so poll subscriptions survive app restarts inside the 47-minute
     *  window — must be called BEFORE any database reference is created (so this lives
     *  before integrations init below). */
    private void initFirebase() {
        FirebaseApp.initializeApp(this);
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true);
        } catch (Exception ignored) {
            // setPersistenceEnabled throws if any DB reference has already been taken;
            // safe to swallow on hot-reload during dev.
        }
    }

    /** RevenueCat is NOT auto-initialized — must be configured before any RC API call.
     *  Key comes from {@code local.properties} via {@link BuildConfig#REVENUECAT_SDK_KEY};
     *  empty key skips configuration so contributors without the secret can still run the
     *  app (purchases just won't work). */
    private void initRevenueCat() {
        String key = BuildConfig.REVENUECAT_SDK_KEY;
        if (key == null || key.isEmpty()) {
            Log.w(TAG, "REVENUECAT_SDK_KEY missing — Purchases left unconfigured");
            return;
        }
        Purchases.configure(new PurchasesConfiguration.Builder(this, key).build());
    }

    /** Master list of integrations that participate in the sheet registry. Kept in sync
     *  with the IME's {@code TurtleInputMethodService} list — both pull from the same
     *  module set, just for different SPIs. */
    private List<KeyboardIntegration> integrations() {
        return Arrays.asList(
                new SplitIntegration(),
                new NotionIntegration(),
                new SlackIntegration(),
                new WebIntegration(),
                new DriveIntegration(),
                new PollIntegration(),
                new WyrIntegration(),
                new PuzzleIntegration());
    }

    public SheetRouter sheetRouter() { return sheetRouter; }

    /** Cross-component image-staging bus (picker → IME / integrations). */
    public StagingPipeline stagingPipeline() { return stagingPipeline; }

    public static TurtleApp from(Context ctx) {
        return (TurtleApp) ctx.getApplicationContext();
    }
}
