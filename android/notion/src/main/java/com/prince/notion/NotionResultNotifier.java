package com.prince.notion;

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
 * Surfaces the result of a {@code /notion} dispatch via a system notification.
 * Tap opens the page; the "Copy link" action copies the URL to the clipboard.
 */
public final class NotionResultNotifier {

    private static final String CHANNEL_ID = "notion_results";
    private static final String CHANNEL_NAME = "Notion pages";
    private static final String ACTION_COPY = "com.prince.notion.action.COPY_LINK";
    public static final String EXTRA_URL = "url";

    public static void notifySuccess(Context context, String pageTitle, String pageUrl) {
        ensureChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        PendingIntent open = PendingIntent.getActivity(context, urlHash(pageUrl),
                new Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingFlags());

        Intent copyIntent = new Intent(context, NotionCopyLinkReceiver.class)
                .setAction(ACTION_COPY)
                .putExtra(EXTRA_URL, pageUrl);
        PendingIntent copy = PendingIntent.getBroadcast(context, urlHash(pageUrl) + 1,
                copyIntent, pendingFlags());

        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("📓 " + safe(pageTitle))
                .setContentText("Notion page created — tap to open")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Copy link", copy)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        nm.notify(idFor(pageUrl), n);
    }

    public static void notifyError(Context context, String userPrompt, String reason) {
        ensureChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Couldn't create Notion page")
                .setContentText(safe(reason))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Prompt: " + safe(userPrompt) + "\nReason: " + safe(reason)))
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
        ch.setDescription("Result of /notion commands fired from the keyboard");
        nm.createNotificationChannel(ch);
    }

    private static int urlHash(String url) {
        return url == null ? 0 : url.hashCode();
    }

    private static int idFor(String url) {
        return urlHash(url) & 0x7fffffff;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public static final class NotionCopyLinkReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url == null) return;
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Notion page", url));
        }
    }

    private NotionResultNotifier() {}
}
