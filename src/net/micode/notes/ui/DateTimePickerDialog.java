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

import java.util.Calendar;

import net.micode.notes.R;
import net.micode.notes.ui.DateTimePicker;
import net.micode.notes.ui.DateTimePicker.OnDateTimeChangedListener;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

/**
 * 提醒时间选择对话框：用 {@link AlertDialog} 包装 {@link DateTimePicker}。
 *
 * <p>职责：展示日期/时间滚轮，把每次选择镜像到内部 Calendar，并实时更新标题；
 * 点击“确定”时通过 {@link OnDateTimeSetListener} 把最终时间交给调用方。
 * 不校验时间是否已过去，也不负责调度提醒——这些由 NoteEditActivity 处理。</p>
 *
 * <p>架构位置：ui 包的对话框封装，只被便签编辑页的“设置提醒”流程使用。
 * 与 DateTimePicker 的分工是：控件负责选值，对话框负责确认/取消与结果回调。</p>
 *
 * <p>关键协作类：{@link DateTimePicker}（内容控件）、
 * {@link NoteEditActivity}（调用方，负责过去时间校验与提醒落库）。</p>
 */
public class DateTimePickerDialog extends AlertDialog implements OnClickListener {

    // 与 DateTimePicker 同步的时间副本；点“确定”时以它为准，秒在构造时已清零
    private Calendar mDate = Calendar.getInstance();
    // 标题是否按 24 小时制格式化；只影响标题，不改变已创建选择器的显示模式
    private boolean mIs24HourView;
    // 确认回调，可为 null
    private OnDateTimeSetListener mOnDateTimeSetListener;
    private DateTimePicker mDateTimePicker;

    /**
     * 确认回调：只在点击“确定”按钮时触发，取消不会触发。
     */
    public interface OnDateTimeSetListener {
        /**
         * @param dialog 当前对话框，便于调用方关闭或复用
         * @param date 用户确认的时间戳（毫秒），秒已清零
         */
        void OnDateTimeSet(AlertDialog dialog, long date);
    }

    /**
     * 创建对话框并绑定选择器。
     *
     * <p>选择器自身的监听器把每次滚轮变化镜像到 mDate 并刷新标题；
     * 秒在这里清零，保证提醒时间精确到分钟。按钮通过 AlertDialog 注入：
     * “确定”走本类的 onClick，“取消”传 null 监听器，点击后仅关闭对话框。</p>
     *
     * @param context 对话框上下文
     * @param date 初始时间，通常为已有提醒时间或当前时间
     */
    public DateTimePickerDialog(Context context, long date) {
        super(context);
        // 选择器构造时按系统设置决定 12/24 小时显示
        mDateTimePicker = new DateTimePicker(context);
        setView(mDateTimePicker);
        mDateTimePicker.setOnDateTimeChangedListener(new OnDateTimeChangedListener() {
            public void onDateTimeChanged(DateTimePicker view, int year, int month,
                    int dayOfMonth, int hourOfDay, int minute) {
                // 每次滚轮变化都完整镜像五个字段，保证标题与“确定”结果始终一致
                mDate.set(Calendar.YEAR, year);
                mDate.set(Calendar.MONTH, month);
                mDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                mDate.set(Calendar.HOUR_OF_DAY, hourOfDay);
                mDate.set(Calendar.MINUTE, minute);
                updateTitle(mDate.getTimeInMillis());
            }
        });
        mDate.setTimeInMillis(date);
        // 提醒精度只到分钟：秒清零；毫秒仍保留输入值中的毫秒，对调度与展示没有影响
        mDate.set(Calendar.SECOND, 0);
        mDateTimePicker.setCurrentDate(mDate.getTimeInMillis());
        // “确定”绑定本类 onClick；“取消”不设监听器，点击只关闭对话框
        setButton(context.getString(R.string.datetime_dialog_ok), this);
        setButton2(context.getString(R.string.datetime_dialog_cancel), (OnClickListener)null);
        // 标题制式与选择器一致：两者在构造时都读取系统 12/24 小时设置
        set24HourView(DateFormat.is24HourFormat(this.getContext()));
        updateTitle(mDate.getTimeInMillis());
    }

    /**
     * 设置标题的时间制式。
     *
     * <p>边界：只写 mIs24HourView，供 updateTitle 使用；不会改变已经创建好的
     * DateTimePicker 的显示模式（它在构造时按系统设置初始化）。构造流程中两者取值一致；
     * 若在创建后再调用，可能出现标题制式与选择器不一致。</p>
     *
     * @param is24HourView true 表示标题用 24 小时制
     */
    public void set24HourView(boolean is24HourView) {
        mIs24HourView = is24HourView;
    }

    /**
     * 注入“确定”回调；传 null 表示不关心结果（对话框仍可正常关闭）。
     *
     * @param callBack 确认后的回调
     */
    public void setOnDateTimeSetListener(OnDateTimeSetListener callBack) {
        mOnDateTimeSetListener = callBack;
    }

    /**
     * 按当前时间刷新对话框标题。
     *
     * <p>注意：三元表达式两个分支都是 FORMAT_24HOUR，mIs24HourView 实际不影响结果，
     * 标题恒为 24 小时制。原逻辑保留，仅做标注，不改行为。</p>
     *
     * @param date 用于格式化标题的时间戳
     */
    private void updateTitle(long date) {
        int flag =
            DateUtils.FORMAT_SHOW_YEAR |
            DateUtils.FORMAT_SHOW_DATE |
            DateUtils.FORMAT_SHOW_TIME;
        flag |= mIs24HourView ? DateUtils.FORMAT_24HOUR : DateUtils.FORMAT_24HOUR;
        setTitle(DateUtils.formatDateTime(this.getContext(), date, flag));
    }

    /**
     * “确定”按钮回调（通过 setButton 绑定到本类）。
     *
     * <p>只把 mDate 的毫秒值交给监听器，不校验时间是否已过去；对话框的关闭
     * 由 AlertDialog 默认按钮行为完成。是否允许过去时间由调用方决定。</p>
     *
     * @param arg0 触发事件的对话框
     * @param arg1 被点击按钮的标识
     */
    public void onClick(DialogInterface arg0, int arg1) {
        if (mOnDateTimeSetListener != null) {
            mOnDateTimeSetListener.OnDateTimeSet(this, mDate.getTimeInMillis());
        }
    }

}