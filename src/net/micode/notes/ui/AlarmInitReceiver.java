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

import net.micode.notes.tool.ReminderScheduler;

/**
 * M4：开机、应用升级、系统时间/时区变化后重建提醒。
 *
 * <p>AlarmManager 中的闹钟不会跨重启保留，必须从数据库重新登记。</p>
 */
public class AlarmInitReceiver extends BroadcastReceiver {
    /**
     * 接收开机、应用升级、系统时间或时区变化广播，重建全部提醒。
     *
     * @param context 广播上下文，供提醒调度器查询数据库
     * @param intent  系统广播意图，本类不读取其内容
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        ReminderScheduler.rescheduleAll(context);
    }
}
