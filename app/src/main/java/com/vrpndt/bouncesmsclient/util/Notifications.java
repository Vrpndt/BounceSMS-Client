package com.vrpndt.bouncesmsclient.util;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.app.NotificationCompat;

import com.vrpndt.bouncesmsclient.MainActivity;
import com.vrpndt.bouncesmsclient.R;

public final class Notifications {
    public static void sendDefaultNotif(Context context, int icon, String title, String content){
        Intent notificationIntent = new Intent(context, MainActivity.class);
        notificationIntent.addCategory(Intent.CATEGORY_DEFAULT);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
        builder.setSmallIcon(icon);
        builder.setContentTitle(title);
        builder.setContentText(content);
        builder.setDefaults(Notification.DEFAULT_VIBRATE);
        builder.setAutoCancel(true);
        builder.setContentIntent(contentIntent);
        Notification notif = builder.build();

        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, notif);
    }

    public static void sendMessageNotif(Context context, String title, String content, String threadId){
        Intent notificationIntent = new Intent(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_DEFAULT);
        notificationIntent.setType("vnd.android-dir/mms-sms");
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context);
        builder.setSmallIcon(R.drawable.notifc_icon_bubble);
        builder.setContentTitle(title);
        builder.setContentText(content);
        builder.setDefaults(Notification.DEFAULT_VIBRATE);
        builder.setAutoCancel(true);
        builder.setContentIntent(contentIntent);
        Notification notif = builder.build();

        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, notif);
    }
}
