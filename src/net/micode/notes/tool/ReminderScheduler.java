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
import android.content.ContentUris;
import android.content.ContentValues;
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

    /**
     * 开机、应用升级、系统时间变化后重建所有未来提醒。
     *
     * <p>M9：重复提醒如果已过期（关机期间错过一次或多次），这里把它推进到未来的第一个
     * 有效时间点并写回数据库，只补一次调度、不补发历史通知；一次性提醒过期则跳过。</p>
     */
    public static void rescheduleAll(Context context) {
        long now = System.currentTimeMillis();
        Cursor cursor = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                new String[] { NoteColumns.ID, NoteColumns.ALERTED_DATE, NoteColumns.REPEAT_RULE },
                NoteColumns.ALERTED_DATE + ">0 AND " + NoteColumns.TYPE + "=? AND "
                        + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(Notes.TYPE_NOTE),
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
                int repeatRule = cursor.getInt(2);
                if (alertDate > now) {
                    schedule(context, noteId, alertDate);
                    count++;
                } else if (ReminderRepeats.isRepeating(repeatRule)) {
                    long next = ReminderRepeats.nextTrigger(alertDate, repeatRule, now);
                    if (next > 0) {
                        updateAlertDate(context, noteId, next);
                        schedule(context, noteId, next);
                        count++;
                    }
                }
            }
        } finally {
            cursor.close();
        }
        Log.i(TAG, "Rescheduled " + count + " reminders");
    }

    /**
     * M9：重复提醒触发后，把 alerted_date 推进到下一次并重新调度。
     *
     * <p>由 AlarmReceiver 在发出通知后调用。只更新 ALERTED_DATE（系统行为，不标记
     * LOCAL_MODIFIED、不改 MODIFIED_DATE），与设置提醒时的落库口径一致。</p>
     *
     * @return 下一次触发时间；便签不存在、不重复或计算失败时返回 0
     */
    public static long advanceRepeat(Context context, long noteId) {
        Cursor cursor = context.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId),
                new String[] { NoteColumns.ALERTED_DATE, NoteColumns.REPEAT_RULE },
                NoteColumns.TYPE + "=?", new String[] { String.valueOf(Notes.TYPE_NOTE) }, null);
        if (cursor == null) {
            Log.e(TAG, "Advance repeat query failed: " + noteId);
            return 0;
        }
        long alertDate;
        int repeatRule;
        try {
            if (!cursor.moveToFirst()) {
                return 0;
            }
            alertDate = cursor.getLong(0);
            repeatRule = cursor.getInt(1);
        } finally {
            cursor.close();
        }
        long now = System.currentTimeMillis();
        if (alertDate > now) {
            // 数据库里的时间已被其它路径推进过（典型场景：改系统时间时 rescheduleAll
            // 先补偿了下一次，随后过期闹钟才派发到这里）。此时只确保调度存在，
            // 不再推进，避免一次触发跳掉一整天。
            schedule(context, noteId, alertDate);
            return alertDate;
        }
        long next = ReminderRepeats.nextTrigger(alertDate, repeatRule, now);
        if (next <= 0) {
            return 0;
        }
        updateAlertDate(context, noteId, next);
        schedule(context, noteId, next);
        return next;
    }

    private static void updateAlertDate(Context context, long noteId, long alertDate) {
        ContentValues values = new ContentValues();
        values.put(NoteColumns.ALERTED_DATE, alertDate);
        context.getContentResolver().update(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), values, null, null);
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
