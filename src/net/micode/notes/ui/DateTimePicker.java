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

import java.text.DateFormatSymbols;
import java.util.Calendar;

import net.micode.notes.R;


import android.content.Context;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.NumberPicker;

/**
 * 日期时间选择控件：日期、小时、分钟三个数值滚轮，外加 12 小时制下的 AM/PM 滚轮。
 *
 * <p>职责：只负责“选一个时间点”，把滚轮结果保存在内部的 {@link Calendar} 中，
 * 并在每次有效变化时通过 {@link OnDateTimeChangedListener} 通知外部；
 * 不负责提醒调度、不做“是否已过去”的校验、不直接读写数据库。</p>
 *
 * <p>架构位置：ui 包的自定义组合控件，被 {@link DateTimePickerDialog} 嵌入为对话框内容，
 * 便签编辑页再通过对话框间接使用。内部时间始终以 24 小时制的 {@code HOUR_OF_DAY} 保存，
 * 12/24 小时制只是显示层差异，切换显示模式不会改变已选中的时间点。</p>
 *
 * <p>关键协作类：{@link DateTimePickerDialog}（镜像本控件的时间、负责确认/取消）、
 * {@link NumberPicker}（滚轮交互；分钟与小时滚轮支持首尾循环滚动）。</p>
 */
public class DateTimePicker extends FrameLayout {

    // 控件默认启用；mIsEnabled 只是缓存，真正的 enabled 状态仍由 View 体系保存
    private static final boolean DEFAULT_ENABLE_STATE = true;

    // 滚轮边界：小时在 12 小时制下为 1~12，在 24 小时制下为 0~23；
    // 日期滚轮没有真实日历范围，固定展示以选中日为中心的 7 天窗口
    private static final int HOURS_IN_HALF_DAY = 12;
    private static final int HOURS_IN_ALL_DAY = 24;
    private static final int DAYS_IN_ALL_WEEK = 7;
    // 日期滚轮下标范围 0~6，其中下标 3（DAYS_IN_ALL_WEEK / 2）对应当前选中的那一天
    private static final int DATE_SPINNER_MIN_VAL = 0;
    private static final int DATE_SPINNER_MAX_VAL = DAYS_IN_ALL_WEEK - 1;
    private static final int HOUR_SPINNER_MIN_VAL_24_HOUR_VIEW = 0;
    private static final int HOUR_SPINNER_MAX_VAL_24_HOUR_VIEW = 23;
    private static final int HOUR_SPINNER_MIN_VAL_12_HOUR_VIEW = 1;
    private static final int HOUR_SPINNER_MAX_VAL_12_HOUR_VIEW = 12;
    private static final int MINUT_SPINNER_MIN_VAL = 0;
    private static final int MINUT_SPINNER_MAX_VAL = 59;
    // AM/PM 用 Calendar.AM(0) / Calendar.PM(1) 作为滚轮下标
    private static final int AMPM_SPINNER_MIN_VAL = 0;
    private static final int AMPM_SPINNER_MAX_VAL = 1;

    // 四个滚轮在构造时一次性绑定，之后只改取值范围与显示值，不重新查找
    private final NumberPicker mDateSpinner;
    private final NumberPicker mHourSpinner;
    private final NumberPicker mMinuteSpinner;
    private final NumberPicker mAmPmSpinner;
    // 时间的唯一事实源：所有滚轮的读写最终都折算到这个 Calendar 上
    private Calendar mDate;

    // 复用同一个数组承载 7 天的显示文案，updateDateControl 每次原地重建
    private String[] mDateDisplayValues = new String[DAYS_IN_ALL_WEEK];

    // 12 小时制下当前是上午还是下午；24 小时制下该字段不参与显示
    private boolean mIsAm;

    // 当前显示模式；来源是系统设置或显式调用 set24HourView
    private boolean mIs24HourView;

    // 整体启用状态缓存；与传入值相同时直接返回，避免重复下发到子滚轮
    private boolean mIsEnabled = DEFAULT_ENABLE_STATE;

    // 构造期标志：为 true 时各 setter 跳过“值未变则返回”的短路，确保每个控件都被完整初始化；
    // 构造结束后置 false，避免滚轮监听器内部再次 setValue 时产生冗余回调
    private boolean mInitialising;

    // 可为 null（构造期间通常尚未设置），触发回调前必须判空
    private OnDateTimeChangedListener mOnDateTimeChangedListener;

    /**
     * 日期滚轮监听：界面展示的是“选中日前后各 3 天”，并没有真实日期列表可滚动，
     * 因此用 {@code newVal - oldVal} 的差值平移 mDate，再重建 7 天窗口。
     * 重建后窗口重新以新日期居中，但差值语义不受影响。
     */
    private NumberPicker.OnValueChangeListener mOnDateChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            mDate.add(Calendar.DAY_OF_YEAR, newVal - oldVal);
            updateDateControl();
            onDateTimeChanged();
        }
    };

    /**
     * 小时滚轮监听：处理跨日临界值并同步 AM/PM。
     *
     * <p>12 小时制下 11↔12 有两种含义，必须结合 mIsAm 判断：
     * PM 11→12 是跨到次日凌晨，AM 12→11 是退回前一天深夜；这两种情况才加减一天，
     * 其余 11↔12 只是上下午切换。24 小时制下则只有 23→0（次日）和 0→23（前一日）跨日。</p>
     *
     * <p>无论显示模式如何，最终都把 24 小时制的 HOUR_OF_DAY 写回 mDate，
     * 保证时间戳语义唯一；跨日时再用临时 Calendar 的日期回填日期滚轮。</p>
     */
    private NumberPicker.OnValueChangeListener mOnHourChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            boolean isDateChanged = false;
            Calendar cal = Calendar.getInstance();
            // 12 小时制：结合旧值/新值与当前 AM/PM 判断是否跨日，并处理上下午翻转
            if (!mIs24HourView) {
                if (!mIsAm && oldVal == HOURS_IN_HALF_DAY - 1 && newVal == HOURS_IN_HALF_DAY) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                    isDateChanged = true;
                } else if (mIsAm && oldVal == HOURS_IN_HALF_DAY && newVal == HOURS_IN_HALF_DAY - 1) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, -1);
                    isDateChanged = true;
                }
                if (oldVal == HOURS_IN_HALF_DAY - 1 && newVal == HOURS_IN_HALF_DAY ||
                        oldVal == HOURS_IN_HALF_DAY && newVal == HOURS_IN_HALF_DAY - 1) {
                    mIsAm = !mIsAm;
                    updateAmPmControl();
                }
            } else {
                // 24 小时制：只有 23→0 与 0→23 两个方向会跨日
                if (oldVal == HOURS_IN_ALL_DAY - 1 && newVal == 0) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, 1);
                    isDateChanged = true;
                } else if (oldVal == 0 && newVal == HOURS_IN_ALL_DAY - 1) {
                    cal.setTimeInMillis(mDate.getTimeInMillis());
                    cal.add(Calendar.DAY_OF_YEAR, -1);
                    isDateChanged = true;
                }
            }
            // 把滚轮显示值折算回 24 小时制：12 小时制下 12 折算为 0，PM 再加 12；24 小时制下取模自身。
            // 注意：24 小时制下 mIsAm 不会随小时滚动更新（仅在构造、分钟进位与
            // 12 小时制切换时改写），因此这段折算在 24 小时制直接滚动小时时可能得到
            // 错误小时；原逻辑保留，仅做标注。
            int newHour = mHourSpinner.getValue() % HOURS_IN_HALF_DAY + (mIsAm ? 0 : HOURS_IN_HALF_DAY);
            mDate.set(Calendar.HOUR_OF_DAY, newHour);
            onDateTimeChanged();
            if (isDateChanged) {
                setCurrentYear(cal.get(Calendar.YEAR));
                setCurrentMonth(cal.get(Calendar.MONTH));
                setCurrentDay(cal.get(Calendar.DAY_OF_MONTH));
            }
        }
    };

    /**
     * 分钟滚轮监听：滚轮在 0/59 处循环，仅改分钟会丢掉进位，
     * 因此把 ±1 的进位作用到小时上，并刷新小时滚轮、日期窗口与 AM/PM；
     * 普通变化只更新分钟。所有分支都会触发一次时间变化回调。
     */
    private NumberPicker.OnValueChangeListener mOnMinuteChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            int minValue = mMinuteSpinner.getMinValue();
            int maxValue = mMinuteSpinner.getMaxValue();
            int offset = 0;
            // 59→0 视为向上进位一小时，0→59 视为借位一小时；其余变化只改分钟
            if (oldVal == maxValue && newVal == minValue) {
                offset += 1;
            } else if (oldVal == minValue && newVal == maxValue) {
                offset -= 1;
            }
            if (offset != 0) {
                // 小时变化可能跨日，因此同步刷新日期窗口；setValue 会再次进入小时监听器，
                // 由小时监听器负责 AM/PM 的翻转与 mDate 的最终小时值
                mDate.add(Calendar.HOUR_OF_DAY, offset);
                mHourSpinner.setValue(getCurrentHour());
                updateDateControl();
                int newHour = getCurrentHourOfDay();
                if (newHour >= HOURS_IN_HALF_DAY) {
                    mIsAm = false;
                    updateAmPmControl();
                } else {
                    mIsAm = true;
                    updateAmPmControl();
                }
            }
            mDate.set(Calendar.MINUTE, newVal);
            onDateTimeChanged();
        }
    };

    /**
     * AM/PM 滚轮监听：翻转上下午等价于对 24 小时制的 HOUR_OF_DAY 加/减 12 小时，
     * 这样 mDate 始终保存唯一的时间点，显示层状态不会污染时间数据。
     */
    private NumberPicker.OnValueChangeListener mOnAmPmChangedListener = new NumberPicker.OnValueChangeListener() {
        @Override
        public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
            mIsAm = !mIsAm;
            if (mIsAm) {
                mDate.add(Calendar.HOUR_OF_DAY, -HOURS_IN_HALF_DAY);
            } else {
                mDate.add(Calendar.HOUR_OF_DAY, HOURS_IN_HALF_DAY);
            }
            updateAmPmControl();
            onDateTimeChanged();
        }
    };

    /**
     * 时间变化回调。滚轮的每次有效变化都会触发；构造期间监听器通常尚未设置，因此不会外泄。
     */
    public interface OnDateTimeChangedListener {
        /**
         * @param view 触发变化的控件本身
         * @param year 目标年份
         * @param month 目标月份，取值为 Calendar 的 0~11（0 表示一月），不是日常的 1~12
         * @param dayOfMonth 目标日，1~31
         * @param hourOfDay 24 小时制小时，0~23
         * @param minute 分钟，0~59
         */
        void onDateTimeChanged(DateTimePicker view, int year, int month,
                int dayOfMonth, int hourOfDay, int minute);
    }

    /**
     * 以“当前时间”和系统 12/24 小时设置创建控件。
     */
    public DateTimePicker(Context context) {
        this(context, System.currentTimeMillis());
    }

    /**
     * 以指定时间创建控件；显示模式仍跟随系统设置。
     *
     * @param date 初始时间，毫秒时间戳
     */
    public DateTimePicker(Context context, long date) {
        this(context, date, DateFormat.is24HourFormat(context));
    }

    /**
     * 完整构造：指定初始时间与显示模式。
     *
     * <p>流程：inflate 布局并初始化四个滚轮 → 刷新日期/小时/AMPM 显示 → 应用 24 小时制
     * → 写入初始日期。mInitialising 在构造期间为 true，使各 setter 跳过“值未变则返回”的短路，
     * 保证所有控件都按传入时间完整初始化；此时监听器尚未设置，期间产生的回调不会外泄。</p>
     *
     * <p>边界：setCurrentDate(date) 只设置到分钟，秒/毫秒沿用构造时 mDate 的值；
     * 需要精确到分钟的调用方（如 {@link DateTimePickerDialog}）应先把秒清零。</p>
     *
     * @param date 初始时间，毫秒时间戳
     * @param is24HourView true 为 24 小时制；false 为 12 小时制并显示 AM/PM 滚轮
     */
    public DateTimePicker(Context context, long date, boolean is24HourView) {
        super(context);
        mDate = Calendar.getInstance();
        // 构造期间全量刷新各控件，不做“值未变则跳过”的优化
        mInitialising = true;
        // 先按系统当前时刻推定 AM/PM，后续 setCurrentDate 会在 12 小时制下重新校准
        mIsAm = getCurrentHourOfDay() >= HOURS_IN_HALF_DAY;
        inflate(context, R.layout.datetime_picker, this);

        // 日期滚轮固定 0~6 共 7 个位置，显示文案由 updateDateControl 动态生成
        mDateSpinner = (NumberPicker) findViewById(R.id.date);
        mDateSpinner.setMinValue(DATE_SPINNER_MIN_VAL);
        mDateSpinner.setMaxValue(DATE_SPINNER_MAX_VAL);
        mDateSpinner.setOnValueChangedListener(mOnDateChangedListener);

        mHourSpinner = (NumberPicker) findViewById(R.id.hour);
        mHourSpinner.setOnValueChangedListener(mOnHourChangedListener);
        mMinuteSpinner =  (NumberPicker) findViewById(R.id.minute);
        mMinuteSpinner.setMinValue(MINUT_SPINNER_MIN_VAL);
        mMinuteSpinner.setMaxValue(MINUT_SPINNER_MAX_VAL);
        // 长按滚动时每 100ms 才更新一次，避免按住不放时分钟跳得过快
        mMinuteSpinner.setOnLongPressUpdateInterval(100);
        mMinuteSpinner.setOnValueChangedListener(mOnMinuteChangedListener);

        // AM/PM 文案跟随系统区域设置，不硬编码英文
        String[] stringsForAmPm = new DateFormatSymbols().getAmPmStrings();
        mAmPmSpinner = (NumberPicker) findViewById(R.id.amPm);
        mAmPmSpinner.setMinValue(AMPM_SPINNER_MIN_VAL);
        mAmPmSpinner.setMaxValue(AMPM_SPINNER_MAX_VAL);
        mAmPmSpinner.setDisplayedValues(stringsForAmPm);
        mAmPmSpinner.setOnValueChangedListener(mOnAmPmChangedListener);

        // update controls to initial state
        // 先按 mDate 当前值建立滚轮显示与取值范围，再应用显示模式
        updateDateControl();
        updateHourControl();
        updateAmPmControl();

        // 24 小时制会隐藏 AM/PM 并切换小时范围；12 小时制下还会重新校准 mIsAm
        set24HourView(is24HourView);

        // set to current time
        // 写入调用方指定的初始时间；此时 mInitialising 仍为 true，各 setter 会强制刷新
        setCurrentDate(date);

        // 初次调用时 enabled 与默认值相同，会在 setEnabled 内直接返回；
        // 这里主要是把默认状态显式走一遍，真正生效的是外部后续的启用/禁用调用
        setEnabled(isEnabled());

        // set the content descriptions
        mInitialising = false;
    }

    /**
     * 整体启用或禁用控件。
     *
     * <p>FrameLayout 不会把 enabled 自动下发给子 View，所以需要逐个设置四个滚轮；
     * 用 mIsEnabled 做缓存，值未变时直接返回，避免重复下发。</p>
     */
    @Override
    public void setEnabled(boolean enabled) {
        if (mIsEnabled == enabled) {
            return;
        }
        super.setEnabled(enabled);
        mDateSpinner.setEnabled(enabled);
        mMinuteSpinner.setEnabled(enabled);
        mHourSpinner.setEnabled(enabled);
        mAmPmSpinner.setEnabled(enabled);
        mIsEnabled = enabled;
    }

    /**
     * 返回缓存的整体启用状态，保证对外状态与四个子滚轮一致。
     */
    @Override
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * Get the current date in millis
     *
     * @return the current date in millis
     *
     * <p>中文：直接读取 mDate，秒/毫秒保持内部 Calendar 的原值。</p>
     */
    public long getCurrentDateInTimeMillis() {
        return mDate.getTimeInMillis();
    }

    /**
     * Set the current date
     *
     * @param date The current date in millis
     *
     * <p>中文：按字段拆分后逐个设置，只精确到分钟；秒与毫秒不会被清零，
     * 需要分钟精度时间戳的调用方应自行处理。</p>
     */
    public void setCurrentDate(long date) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(date);
        setCurrentDate(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    /**
     * Set the current date
     *
     * @param year The current year
     * @param month The current month
     * @param dayOfMonth The current dayOfMonth
     * @param hourOfDay The current hourOfDay
     * @param minute The current minute
     *
     * <p>中文：依次调用 5 个 setter，每个都可能触发一次 onDateTimeChanged，
     * 因此一次调用最多对应 5 次回调，监听方需要幂等。</p>
     */
    public void setCurrentDate(int year, int month,
            int dayOfMonth, int hourOfDay, int minute) {
        setCurrentYear(year);
        setCurrentMonth(month);
        setCurrentDay(dayOfMonth);
        setCurrentHour(hourOfDay);
        setCurrentMinute(minute);
    }

    /**
     * Get current year
     *
     * @return The current year
     *
     * <p>中文：读取 mDate 的 YEAR 字段。</p>
     */
    public int getCurrentYear() {
        return mDate.get(Calendar.YEAR);
    }

    /**
     * Set current year
     *
     * @param year The current year
     *
     * <p>中文：构造期强制刷新；构造完成后年份未变直接返回，避免冗余回调。
     * 同样模式适用于下面所有 setCurrentXxx。</p>
     */
    public void setCurrentYear(int year) {
        if (!mInitialising && year == getCurrentYear()) {
            return;
        }
        mDate.set(Calendar.YEAR, year);
        updateDateControl();
        onDateTimeChanged();
    }

    /**
     * Get current month in the year
     *
     * @return The current month in the year
     *
     * <p>中文：返回 Calendar 的 0~11 月份下标，0 表示一月。</p>
     */
    public int getCurrentMonth() {
        return mDate.get(Calendar.MONTH);
    }

    /**
     * Set current month in the year
     *
     * @param month The month in the year
     *
     * <p>中文：month 使用 Calendar 的 0~11 下标；日期溢出（如 31 日改到 2 月）
     * 由 Calendar 自行滚动，本方法不额外校正。</p>
     */
    public void setCurrentMonth(int month) {
        if (!mInitialising && month == getCurrentMonth()) {
            return;
        }
        mDate.set(Calendar.MONTH, month);
        updateDateControl();
        onDateTimeChanged();
    }

    /**
     * Get current day of the month
     *
     * @return The day of the month
     *
     * <p>中文：读取 DAY_OF_MONTH，即日常所说的 1~31 日。</p>
     */
    public int getCurrentDay() {
        return mDate.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * Set current day of the month
     *
     * @param dayOfMonth The day of the month
     *
     * <p>中文：只改“日”，月份与年份不变；非法日值由 Calendar 按宽松规则滚动。</p>
     */
    public void setCurrentDay(int dayOfMonth) {
        if (!mInitialising && dayOfMonth == getCurrentDay()) {
            return;
        }
        mDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        updateDateControl();
        onDateTimeChanged();
    }

    /**
     * Get current hour in 24 hour mode, in the range (0~23)
     * @return The current hour in 24 hour mode
     *
     * <p>中文：这是内部时间的规范形式，12 小时制下也返回 0~23。</p>
     */
    public int getCurrentHourOfDay() {
        return mDate.get(Calendar.HOUR_OF_DAY);
    }

    /**
     * 把 24 小时制小时折算成小时滚轮的显示值。
     *
     * <p>24 小时制直接返回 0~23；12 小时制下 0 点显示为 12，13~23 点显示为 1~11，
     * 12 点保持 12（由 AM/PM 滚轮区分中午与午夜）。</p>
     *
     * @return 当前显示模式下的小时滚轮值
     */
    private int getCurrentHour() {
        if (mIs24HourView){
            return getCurrentHourOfDay();
        } else {
            int hour = getCurrentHourOfDay();
            if (hour > HOURS_IN_HALF_DAY) {
                return hour - HOURS_IN_HALF_DAY;
            } else {
                return hour == 0 ? HOURS_IN_HALF_DAY : hour;
            }
        }
    }

    /**
     * Set current hour in 24 hour mode, in the range (0~23)
     *
     * @param hourOfDay
     *
     * <p>中文：先把 24 小时制小时写入 mDate，再在 12 小时制下换算显示值并校准 mIsAm；
     * 12 点保持 12（PM），0 点显示为 12（AM）。24 小时制下不会更新 mIsAm。</p>
     */
    public void setCurrentHour(int hourOfDay) {
        if (!mInitialising && hourOfDay == getCurrentHourOfDay()) {
            return;
        }
        mDate.set(Calendar.HOUR_OF_DAY, hourOfDay);
        // 12 小时制：mDate 已保存 24 小时制值，这里只折算滚轮显示值并校准 AM/PM；
        // 12 点保持 12 且属于 PM，0 点显示为 12 且属于 AM
        if (!mIs24HourView) {
            if (hourOfDay >= HOURS_IN_HALF_DAY) {
                mIsAm = false;
                if (hourOfDay > HOURS_IN_HALF_DAY) {
                    hourOfDay -= HOURS_IN_HALF_DAY;
                }
            } else {
                mIsAm = true;
                if (hourOfDay == 0) {
                    hourOfDay = HOURS_IN_HALF_DAY;
                }
            }
            updateAmPmControl();
        }
        mHourSpinner.setValue(hourOfDay);
        onDateTimeChanged();
    }

    /**
     * Get currentMinute
     *
     * @return The Current Minute
     *
     * <p>中文：读取 mDate 的 MINUTE，范围 0~59。</p>
     */
    public int getCurrentMinute() {
        return mDate.get(Calendar.MINUTE);
    }

    /**
     * Set current minute
     *
     * <p>中文：先设置滚轮值（可能经滚轮监听器触发一次回调与进位处理），
     * 再显式写回 mDate 并再次回调；监听方需要幂等。</p>
     */
    public void setCurrentMinute(int minute) {
        if (!mInitialising && minute == getCurrentMinute()) {
            return;
        }
        mMinuteSpinner.setValue(minute);
        mDate.set(Calendar.MINUTE, minute);
        onDateTimeChanged();
    }

    /**
     * @return true if this is in 24 hour view else false.
     *
     * <p>中文：只反映显示模式，不影响内部 mDate 的 24 小时制取值。</p>
     */
    public boolean is24HourView () {
        return mIs24HourView;
    }

    /**
     * Set whether in 24 hour or AM/PM mode.
     *
     * @param is24HourView True for 24 hour mode. False for AM/PM mode.
     *
     * <p>中文：切换显示模式只影响 AM/PM 可见性与小时滚轮范围，不改已选时间。
     * 切换前先记下 24 小时制小时，改完范围后用 setCurrentHour 重新折算显示值，
     * 避免滚轮范围变化导致旧值被钳制或错位。</p>
     */
    public void set24HourView(boolean is24HourView) {
        if (mIs24HourView == is24HourView) {
            return;
        }
        mIs24HourView = is24HourView;
        mAmPmSpinner.setVisibility(is24HourView ? View.GONE : View.VISIBLE);
        int hour = getCurrentHourOfDay();
        updateHourControl();
        // 范围变化后重新应用当前小时，顺带在 12 小时制下校准 mIsAm
        setCurrentHour(hour);
        updateAmPmControl();
    }

    /**
     * 重建日期滚轮的 7 天显示窗口，并把选中位置固定回下标 3。
     *
     * <p>窗口为 mDate 前 3 天到后 3 天：先回退 4 天，再在循环里每天前进一格。
     * 由于日期监听器按 newVal - oldVal 平移，窗口重新居中不会影响后续滚动的语义。</p>
     *
     * <p>先 setDisplayedValues(null) 再整体重建：避免复用同一个数组时控件内部缓存不刷新，
     * 也规避部分 ROM 上直接替换显示值导致的越界或错位。</p>
     */
    private void updateDateControl() {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(mDate.getTimeInMillis());
        cal.add(Calendar.DAY_OF_YEAR, -DAYS_IN_ALL_WEEK / 2 - 1);
        mDateSpinner.setDisplayedValues(null);
        for (int i = 0; i < DAYS_IN_ALL_WEEK; ++i) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            mDateDisplayValues[i] = (String) DateFormat.format("MM.dd EEEE", cal);
        }
        mDateSpinner.setDisplayedValues(mDateDisplayValues);
        mDateSpinner.setValue(DAYS_IN_ALL_WEEK / 2);
        mDateSpinner.invalidate();
    }

    /**
     * 刷新 AM/PM 滚轮：24 小时制下直接隐藏；12 小时制下按下标 0=AM、1=PM 显示。
     */
    private void updateAmPmControl() {
        if (mIs24HourView) {
            mAmPmSpinner.setVisibility(View.GONE);
        } else {
            int index = mIsAm ? Calendar.AM : Calendar.PM;
            mAmPmSpinner.setValue(index);
            mAmPmSpinner.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 按显示模式设置小时滚轮的取值范围：24 小时制 0~23，12 小时制 1~12。
     * 只改范围，不改当前值；调用方需在范围变化后重新应用小时。
     */
    private void updateHourControl() {
        if (mIs24HourView) {
            mHourSpinner.setMinValue(HOUR_SPINNER_MIN_VAL_24_HOUR_VIEW);
            mHourSpinner.setMaxValue(HOUR_SPINNER_MAX_VAL_24_HOUR_VIEW);
        } else {
            mHourSpinner.setMinValue(HOUR_SPINNER_MIN_VAL_12_HOUR_VIEW);
            mHourSpinner.setMaxValue(HOUR_SPINNER_MAX_VAL_12_HOUR_VIEW);
        }
    }

    /**
     * Set the callback that indicates the 'Set' button has been pressed.
     * @param callback the callback, if null will do nothing
     *
     * <p>中文：实际触发时机是任意滚轮的有效变化，而不仅是确认按钮；
     * 确认/取消的语义由 {@link DateTimePickerDialog} 负责。传 null 表示取消监听。</p>
     */
    public void setOnDateTimeChangedListener(OnDateTimeChangedListener callback) {
        mOnDateTimeChangedListener = callback;
    }

    /**
     * 把当前 mDate 拆成 年/月/日/时/分 通知监听器；未设置监听器时什么也不做。
     */
    private void onDateTimeChanged() {
        if (mOnDateTimeChangedListener != null) {
            mOnDateTimeChangedListener.onDateTimeChanged(this, getCurrentYear(),
                    getCurrentMonth(), getCurrentDay(), getCurrentHourOfDay(), getCurrentMinute());
        }
    }
}
