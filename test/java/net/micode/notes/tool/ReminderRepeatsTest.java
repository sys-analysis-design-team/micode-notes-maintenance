package net.micode.notes.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/**
 * M9：提醒重复规则的下一次触发时间计算。
 *
 * <p>2026-11-24 是周二，测试用例据此安排周一/周五/周末的边界。</p>
 */
public class ReminderRepeatsTest {

    /** 用系统默认时区构造时间戳，与被测代码保持同一口径。 */
    private static long time(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void nonRepeatingRuleReturnsZero() {
        long base = time(2026, 11, 24, 9, 0);
        assertEquals(0, ReminderRepeats.nextTrigger(base, ReminderRepeats.NONE, base));
        assertEquals(0, ReminderRepeats.nextTrigger(base, 99, base));
    }

    @Test
    public void dailyAdvancesExactlyOneDay() {
        long base = time(2026, 11, 24, 9, 0);
        assertEquals(time(2026, 11, 25, 9, 0),
                ReminderRepeats.nextTrigger(base, ReminderRepeats.DAILY, base));
    }

    @Test
    public void dailyCatchesUpPastMissedOccurrences() {
        // 基准已过去 3 天多，结果应是严格晚于 now 的第一个时刻
        long base = time(2026, 11, 20, 9, 0);
        long now = time(2026, 11, 23, 10, 0);
        assertEquals(time(2026, 11, 24, 9, 0),
                ReminderRepeats.nextTrigger(base, ReminderRepeats.DAILY, now));
    }

    @Test
    public void weeklyAdvancesExactlySevenDays() {
        long base = time(2026, 11, 24, 9, 0);
        assertEquals(time(2026, 12, 1, 9, 0),
                ReminderRepeats.nextTrigger(base, ReminderRepeats.WEEKLY, base));
    }

    @Test
    public void weekdaysSkipsWeekendFromFriday() {
        // 2026-11-27 是周五，下一次工作日提醒应是周一
        long base = time(2026, 11, 27, 9, 0);
        assertEquals(time(2026, 11, 30, 9, 0),
                ReminderRepeats.nextTrigger(base, ReminderRepeats.WEEKDAYS, base));
    }

    @Test
    public void weekdaysFromSaturdayMovesToMonday() {
        long base = time(2026, 11, 28, 9, 0);
        assertEquals(time(2026, 11, 30, 9, 0),
                ReminderRepeats.nextTrigger(base, ReminderRepeats.WEEKDAYS, base));
    }

    @Test
    public void weekdaysCatchesUpAcrossWeekend() {
        // 周五的提醒错过，周日才重新调度，应推进到周一
        long base = time(2026, 11, 27, 9, 0);
        long now = time(2026, 11, 29, 12, 0);
        long next = ReminderRepeats.nextTrigger(base, ReminderRepeats.WEEKDAYS, now);
        assertEquals(time(2026, 11, 30, 9, 0), next);
    }

    @Test
    public void resultIsAlwaysStrictlyAfterNow() {
        long base = time(2026, 11, 24, 9, 0);
        long now = time(2026, 11, 24, 9, 0);
        assertTrue(ReminderRepeats.nextTrigger(base, ReminderRepeats.DAILY, now) > now);
        assertTrue(ReminderRepeats.nextTrigger(base, ReminderRepeats.WEEKLY, now) > now);
        assertTrue(ReminderRepeats.nextTrigger(base, ReminderRepeats.WEEKDAYS, now) > now);
    }
}
