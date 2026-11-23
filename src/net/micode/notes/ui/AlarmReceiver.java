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

package net.micode.notes.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ReminderNotifier;
import net.micode.notes.tool.ReminderScheduler;

/**
 * M4：提醒广播。
 *
 * <p>旧实现直接把 AlarmAlertActivity 拉起到前台，Android 10+ 后台启动 Activity 会被拦截。
 * 现在只发通知：点击通知回到编辑页，「知道了」或左滑清除通知。</p>
 */
public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
        /**
         * 处理提醒到点与「知道了」两类广播。
         *
         * <p>到点广播：校验便签仍可见后弹出通知；便签已删除或进回收站则静默清理。
         * ACTION_DISMISS 广播：取消对应通知。</p>
         *
         * @param context 广播上下文，用于查询便签与发通知
         * @param intent  携带便签 ID 的提醒意图
         */
    public void onReceive(Context context, Intent intent) {
        long noteId = resolveNoteId(intent);
        if (ReminderScheduler.ACTION_DISMISS.equals(intent.getAction())) {
            ReminderNotifier.cancel(context, noteId);
            return;
        }
        if (noteId <= 0) {
            Log.e(TAG, "Reminder without valid note id");
            return;
        }
        // 便签已删除或已进回收站时，静默丢弃这次提醒，并清掉可能残留的通知。
        if (!DataUtils.visibleInNoteDatabase(context.getContentResolver(), noteId,
                Notes.TYPE_NOTE)) {
            ReminderNotifier.cancel(context, noteId);
            return;
        }
        String snippet;
        try {
            snippet = DataUtils.getSnippetById(context.getContentResolver(), noteId);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Note not found: " + noteId, e);
            ReminderNotifier.cancel(context, noteId);
            return;
        }
        if (!ReminderNotifier.notify(context, noteId, snippet)) {
            // 通知权限被关闭时无法弹出提醒；编辑页会在设置提醒时给出说明。
            Log.w(TAG, "Notification permission denied, reminder dropped: " + noteId);
        }
    }

        /**
         * 解析广播中的便签 ID。
         *
         * <p>优先读取 EXTRA_NOTE_ID；兼容旧版以 URI 末段数字表示 ID 的形式。</p>
         *
         * @param intent 提醒广播意图
         * @return 有效便签 ID；两种方式都解析失败时返回 -1
         */
    private static long resolveNoteId(Intent intent) {
        long noteId = intent.getLongExtra(ReminderScheduler.EXTRA_NOTE_ID, -1);
        if (noteId > 0) {
            return noteId;
        }
                // 兼容旧格式：便签 ID 可能编码在 intent data 的 URI 路径末段
        Uri data = intent.getData();
        if (data == null) {
            return -1;
        }
        try {
            java.util.List<String> segments = data.getPathSegments();
            return Long.parseLong(segments.get(segments.size() - 1));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
