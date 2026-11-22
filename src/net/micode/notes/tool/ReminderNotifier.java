/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.ui.NoteEditActivity;

/**
 * M4：提醒通知。
 *
 * <p>Android 8+ 必须使用渠道；Android 13+ 需要 POST_NOTIFICATIONS 运行时权限。
 * 通知正文为便签摘要，点击回到编辑页，「知道了」只清除通知。</p>
 */
public final class ReminderNotifier {
    public static final String CHANNEL_ID = "note_reminder";

    private ReminderNotifier() {
    }

    /**
     * 发出提醒通知。
     *
     * @return false 表示通知权限被关闭，通知未发出。
     */
    public static boolean notify(Context context, long noteId, String snippet) {
        if (!canPostNotifications(context)) {
            return false;
        }
        NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return false;
        }
        ensureChannel(context, manager);

        String text = TextUtils.isEmpty(snippet)
                ? context.getString(R.string.reminder_notification_no_content) : snippet;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.reminder_notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(buildOpenIntent(context, noteId))
                .setDeleteIntent(buildDismissIntent(context, noteId));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_HIGH);
            builder.setDefaults(Notification.DEFAULT_ALL);
        }
        builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                context.getString(R.string.notealert_enter),
                buildOpenIntent(context, noteId)).build());
        builder.addAction(new Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                context.getString(R.string.notealert_ok),
                buildDismissIntent(context, noteId)).build());

        manager.notify(notificationId(noteId), builder.build());
        return true;
    }

    public static void cancel(Context context, long noteId) {
        NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(notificationId(noteId));
        }
    }

    /** 通知既能被系统权限关闭，也能被用户单独关闭，两者都要检查。 */
    public static boolean canPostNotifications(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        return manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                || manager.areNotificationsEnabled();
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.reminder_channel_description));
        channel.enableVibration(true);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) {
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if (sound != null) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            channel.setSound(sound, attributes);
        }
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent buildOpenIntent(Context context, long noteId) {
        Intent intent = new Intent(context, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_UID, noteId);
        return PendingIntent.getActivity(context, notificationId(noteId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent buildDismissIntent(Context context, long noteId) {
        Intent intent = new Intent(context, net.micode.notes.ui.AlarmReceiver.class);
        intent.setAction(ReminderScheduler.ACTION_DISMISS);
        intent.setData(android.content.ContentUris.withAppendedId(
                Notes.CONTENT_NOTE_URI, noteId));
        intent.putExtra(ReminderScheduler.EXTRA_NOTE_ID, noteId);
        return PendingIntent.getBroadcast(context, notificationId(noteId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int notificationId(long noteId) {
        return (int) (noteId & 0x7fffffff);
    }
}
