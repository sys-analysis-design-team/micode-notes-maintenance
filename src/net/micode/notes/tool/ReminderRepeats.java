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

import java.util.Calendar;

/**
 * M9：提醒重复规则与下一次触发时间计算。
 *
 * <p>职责：把“每天 / 每周 / 工作日”规则换算成下一次提醒时间戳。
 * 不读写数据库、不碰 AlarmManager——持久化与调度由
 * {@link ReminderScheduler#advanceRepeat} 与 AlarmReceiver 负责，
 * 因此本类可以直接在 JVM 单元测试里验证。</p>
 *
 * <p>计算口径：从基准时间出发按规则逐日（或逐周）推进，直到严格晚于当前时刻。
 * 工作日规则跳过周六/周日；如果设备关机错过了若干次，会一次推进到未来的第一个
 * 有效时间点，只补一次提醒，不补发历史。</p>
 */
public final class ReminderRepeats {
    /** 不重复：只提醒一次。 */
    public static final int NONE = 0;

    /** 每天同一时刻提醒。 */
    public static final int DAILY = 1;

    /** 每周同一天同一时刻提醒。 */
    public static final int WEEKLY = 2;

    /** 每个工作日（周一至周五）同一时刻提醒。 */
    public static final int WEEKDAYS = 3;

    // 追赶推进的保护上限：每天=36600 步足以覆盖 100 年，避免脏数据造成死循环
    private static final int MAX_STEPS = 36600;

    private ReminderRepeats() {
    }

    /**
     * @param rule 重复规则常量
     * @return true 表示是受支持的重复规则（每天/每周/工作日）
     */
    public static boolean isRepeating(int rule) {
        return rule == DAILY || rule == WEEKLY || rule == WEEKDAYS;
    }

    /**
     * 计算严格晚于当前时刻的下一次触发时间。
     *
     * @param base 上一次的计划触发时间（毫秒时间戳）；null 语义由调用方保证非 0
     * @param rule 重复规则；{@link #NONE} 或未知值返回 0
     * @param now  当前时刻（毫秒时间戳），结果一定大于它
     * @return 下一次触发时间；不重复或参数无效时返回 0
     */
    public static long nextTrigger(long base, int rule, long now) {
        if (base <= 0 || !isRepeating(rule)) {
            return 0;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(base);
        for (int i = 0; i < MAX_STEPS; i++) {
            advanceOneStep(calendar, rule);
            long candidate = calendar.getTimeInMillis();
            // 工作日规则命中的周末要再往前推，不算一次有效提醒
            if (rule == WEEKDAYS && isWeekend(calendar)) {
                continue;
            }
            if (candidate > now) {
                return candidate;
            }
        }
        return 0;
    }

    /** 按规则把日历推进一格：每天/工作日一天，每周七天。 */
    private static void advanceOneStep(Calendar calendar, int rule) {
        if (rule == WEEKLY) {
            calendar.add(Calendar.DAY_OF_YEAR, 7);
        } else {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private static boolean isWeekend(Calendar calendar) {
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        return dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY;
    }
}
