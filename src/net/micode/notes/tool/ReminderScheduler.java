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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.ui.AlarmReceiver;

/**
 * M4：提醒统一调度入口。
 *
 * <p>旧实现直接 {@code AlarmManager.set} 并启动全屏 Activity，在 Android 12+ 上既不定时，
 * 也会被后台启动 Activity 限制拦截。这里改为：广播 + 通知，并在缺少精确闹钟权限时
 * 退化为 {@code setAndAllowWhileIdle}，调用方据返回值提示用户可能延迟。</p>
 */
public final class ReminderScheduler {
    private static final String TAG = "ReminderScheduler";

    public static final String ACTION_REMINDER = "net.micode.notes.action.REMINDER";
    public static final String ACTION_DISMISS = "net.micode.notes.action.DISMISS_REMINDER";
    public static final String EXTRA_NOTE_ID = "net.micode.notes.extra.NOTE_ID";

    private ReminderScheduler() {
    }

    /**
     * 设置或覆盖某条便签的提醒。
     *
     * @return true 表示使用精确闹钟；false 表示已降级为非精确闹钟。
     */
    public static boolean schedule(Context context, long noteId, long triggerAt) {
        if (noteId <= 0) {
            return false;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager unavailable");
            return false;
        }
        PendingIntent pendingIntent = buildPendingIntent(context, noteId, ACTION_REMINDER);
        boolean exact = canScheduleExactAlarms(context);
        if (exact) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
        return exact;
    }

    /** 取消某条便签的提醒；重复调用安全。 */
    public static void cancel(Context context, long noteId) {
        if (noteId <= 0) {
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = buildPendingIntent(context, noteId, ACTION_REMINDER);
        alarmManager.cancel(pendingIntent);
        pendingIntent.cancel();
    }

    /** 开机、应用升级、系统时间变化后重建所有未来提醒。 */
    public static void rescheduleAll(Context context) {
        long now = System.currentTimeMillis();
        Cursor cursor = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                new String[] { NoteColumns.ID, NoteColumns.ALERTED_DATE },
                NoteColumns.ALERTED_DATE + ">? AND " + NoteColumns.TYPE + "=? AND "
                        + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(now), String.valueOf(Notes.TYPE_NOTE),
                        String.valueOf(Notes.ID_TRASH_FOLER) },
                null);
        if (cursor == null) {
            Log.e(TAG, "Reschedule query failed");
            return;
        }
        int count = 0;
        try {
            while (cursor.moveToNext()) {
                long noteId = cursor.getLong(0);
                long alertDate = cursor.getLong(1);
                schedule(context, noteId, alertDate);
                count++;
            }
        } finally {
            cursor.close();
        }
        Log.i(TAG, "Rescheduled " + count + " reminders");
    }

    /** Android 12+ 需要 SCHEDULE_EXACT_ALARM 才能在 Doze 下准点触发。 */
    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    private static PendingIntent buildPendingIntent(Context context, long noteId, String action) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(action);
        intent.setData(android.content.ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId));
        intent.putExtra(EXTRA_NOTE_ID, noteId);
        int requestCode = (int) (noteId & 0x7fffffff);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
