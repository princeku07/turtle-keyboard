package com.prince.slack;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Surfaces the result of a {@code /slack} dispatch via a system notification.
 * Tap opens the message; the "Copy link" action copies the permalink.
 */
public final class SlackResultNotifier {

    private static final String CHANNEL_ID = "slack_results";
    private static final String CHANNEL_NAME = "Slack messages";
    private static final String ACTION_COPY = "com.prince.slack.action.COPY_LINK";
    public static final String EXTRA_URL = "url";

    public static void notifySuccess(Context context, String channelName, String permalink) {
        ensureChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || permalink == null) return;

        PendingIntent open = PendingIntent.getActivity(context, urlHash(permalink),
                new Intent(Intent.ACTION_VIEW, Uri.parse(permalink))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingFlags());

        Intent copyIntent = new Intent(context, SlackCopyLinkReceiver.class)
                .setAction(ACTION_COPY)
                .putExtra(EXTRA_URL, permalink);
        PendingIntent copy = PendingIntent.getBroadcast(context, urlHash(permalink) + 1,
                copyIntent, pendingFlags());

        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle("💬 Posted to #" + safe(channelName))
                .setContentText("Slack message sent — tap to open")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Copy link", copy)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(idFor(permalink), n);
    }

    public static void notifyError(Context context, String userPrompt, String reason) {
        ensureChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Couldn't send Slack message")
                .setContentText(safe(reason))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Message: " + safe(userPrompt) + "\nReason: " + safe(reason)))
                .setAutoCancel(true)
                .build();
        nm.notify(("err" + System.currentTimeMillis()).hashCode(), n);
    }

    private static int pendingFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Result of /slack commands fired from the keyboard");
        nm.createNotificationChannel(ch);
    }

    private static int urlHash(String url) { return url == null ? 0 : url.hashCode(); }
    private static int idFor(String url) { return urlHash(url) & 0x7fffffff; }
    private static String safe(String s) { return s == null ? "" : s; }

    public static final class SlackCopyLinkReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url == null) return;
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Slack message", url));
        }
    }

    private SlackResultNotifier() {}
}
