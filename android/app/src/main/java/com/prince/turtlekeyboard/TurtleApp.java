package com.prince.turtlekeyboard;

import android.app.Application;
import android.content.Context;

import com.prince.kbd.core.KeyboardIntegration;
import com.prince.kbd.core.SheetRouter;
import com.prince.notion.NotionIntegration;
import com.prince.slack.SlackIntegration;
import com.prince.split.SplitIntegration;
import com.prince.turtlekeyboard.integration.drive.DriveIntegration;
import com.prince.turtlekeyboard.integration.poll.PollIntegration;
import com.prince.turtlekeyboard.integration.wyr.WyrIntegration;
import com.prince.turtlekeyboard.overlay.SheetRouterImpl;
import com.prince.web.WebIntegration;

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

    private SheetRouterImpl sheetRouter;

    @Override
    public void onCreate() {
        super.onCreate();
        sheetRouter = new SheetRouterImpl();
        for (KeyboardIntegration integration : integrations()) {
            sheetRouter.registerAll(integration.sheetRoutes());
        }
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
                new WyrIntegration());
    }

    public SheetRouter sheetRouter() { return sheetRouter; }

    public static TurtleApp from(Context ctx) {
        return (TurtleApp) ctx.getApplicationContext();
    }
}
