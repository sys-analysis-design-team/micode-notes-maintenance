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

import android.content.Context;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser.NoteItemBgResources;


/**
 * 便签列表的单行视图。
 *
 * <p>职责：把 {@link NoteItemData} 渲染成标题、时间、提醒图标、通话联系人/号码与勾选框，
 * 并按相邻行位置选择卡片背景，使“文件夹后连续的多张便签”在视觉上拼成一张卡片。
 * 不处理点击/长按（由 ListView 与 Activity 负责），也不查询数据。</p>
 *
 * <p>架构位置：ui 包的行视图，由 {@link NotesListAdapter#newView} 创建、
 * {@link NotesListAdapter#bindView} 反复绑定；布局来自 note_item.xml。</p>
 *
 * <p>关键协作类：{@link NoteItemData}（行数据与位置标志）、
 * {@link NoteItemBgResources}（背景色与圆角资源映射）。</p>
 */
public class NotesListItem extends LinearLayout {
    // 子控件在构造时一次查找，之后只改文本、可见性与图片
    private ImageView mAlert;
    // M8：置顶标记，绑定数据时按 isPinned() 显示/隐藏
    private ImageView mPinned;
    private TextView mTitle;
    private TextView mTime;
    private TextView mCallName;
    // 最近一次绑定的行数据；供长按/上下文菜单读取当前行，首次绑定前为 null
    private NoteItemData mItemData;
    private CheckBox mCheckBox;

    /**
     * 构造行视图：inflate note_item 布局并缓存各子控件引用。
     */
    public NotesListItem(Context context) {
        super(context);
        inflate(context, R.layout.note_item, this);
        mAlert = (ImageView) findViewById(R.id.iv_alert_icon);
        mPinned = (ImageView) findViewById(R.id.iv_pinned);
        mTitle = (TextView) findViewById(R.id.tv_title);
        mTime = (TextView) findViewById(R.id.tv_time);
        mCallName = (TextView) findViewById(R.id.tv_name);
        mCheckBox = (CheckBox) findViewById(android.R.id.checkbox);
    }

    /**
     * 将一行数据绑定到视图。
     *
     * <p>按三种行形态分别渲染：通话记录系统文件夹本身、通话记录便签、普通文件夹/便签；
     * 复选框只在“多选模式且行类型为便签”时显示——文件夹不参与批量删除/移动。
     * 时间统一用相对时间，背景由 {@link #setBackground(NoteItemData)} 根据相邻行决定。</p>
     *
     * @param context 用于取资源文案
     * @param data 行数据快照
     * @param choiceMode 是否处于多选模式
     * @param checked 该行当前是否被勾选
     */
    public void bind(Context context, NoteItemData data, boolean choiceMode, boolean checked) {
        // 行复用：每次绑定都要显式设置可见性，否则会残留上一行的复选框状态
        if (choiceMode && data.getType() == Notes.TYPE_NOTE) {
            mCheckBox.setVisibility(View.VISIBLE);
            mCheckBox.setChecked(checked);
        } else {
            mCheckBox.setVisibility(View.GONE);
        }

        mItemData = data;
        // 置顶标记与行模板无关，每次绑定都显式刷新，避免行复用残留
        mPinned.setVisibility(data.isPinned() ? View.VISIBLE : View.GONE);
        // 形态一：通话记录系统文件夹本身，显示电话图标与“通话记录(N)”计数
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mCallName.setVisibility(View.GONE);
            mAlert.setVisibility(View.VISIBLE);
            mTitle.setTextAppearance(context, R.style.TextAppearancePrimaryItem);
            mTitle.setText(context.getString(R.string.call_record_folder_name)
                    + context.getString(R.string.format_folder_files_count, data.getNotesCount()));
            mAlert.setImageResource(R.drawable.call_record);
        } else if (data.getParentId() == Notes.ID_CALL_RECORD_FOLDER) {
            // 形态二：通话记录便签。联系人为单独一行，标题只放号码/摘要，有提醒时叠加时钟图标
            mCallName.setVisibility(View.VISIBLE);
            mCallName.setText(data.getCallName());
            mTitle.setTextAppearance(context,R.style.TextAppearanceSecondaryItem);
            mTitle.setText(DataUtils.getFormattedSnippet(data.getSnippet()));
            if (data.hasAlert()) {
                mAlert.setImageResource(R.drawable.clock);
                mAlert.setVisibility(View.VISIBLE);
            } else {
                mAlert.setVisibility(View.GONE);
            }
        } else {
            // 形态三：普通文件夹或普通便签，不显示联系人行
            mCallName.setVisibility(View.GONE);
            mTitle.setTextAppearance(context, R.style.TextAppearancePrimaryItem);

            // 文件夹：标题后追加由触发器维护的便签数量；文件夹没有提醒图标
            if (data.getType() == Notes.TYPE_FOLDER) {
                mTitle.setText(data.getSnippet()
                        + context.getString(R.string.format_folder_files_count,
                                data.getNotesCount()));
                mAlert.setVisibility(View.GONE);
            } else {
                mTitle.setText(DataUtils.getFormattedSnippet(data.getSnippet()));
                if (data.hasAlert()) {
                    mAlert.setImageResource(R.drawable.clock);
                    mAlert.setVisibility(View.VISIBLE);
                } else {
                    mAlert.setVisibility(View.GONE);
                }
            }
        }
        // 相对时间（如“3 分钟前”）只在绑定/刷新时计算，不会自动走秒；列表刷新即更新
        mTime.setText(DateUtils.getRelativeTimeSpanString(data.getModifiedDate()));

        // 背景依赖行类型与相邻行位置，必须在数据确定后再设置
        setBackground(data);
    }

    /**
     * 根据行类型与相邻位置选择背景资源。
     *
     * <p>文件夹固定使用文件夹背景。便签按“卡片拼接”规则：
     * 唯一一张或“文件夹后仅此一张”用四角全圆的 single；末行用下圆角的 last；
     * 首行或“文件夹后多张中的第一张”用上圆角的 first；其余用上下都平的 middle。</p>
     *
     * <p>判断顺序很重要：single 必须先于 last/first，否则列表末尾的独立便签
     * 会被画成只有一半圆角的卡片。</p>
     *
     * @param data 行数据，其中的位置标志由构造时的 checkPostion 预先算好
     */
    private void setBackground(NoteItemData data) {
        int id = data.getBgColorId();
        if (data.getType() == Notes.TYPE_NOTE) {
            // 唯一或“文件夹后唯一一张”优先判成 single，避免落到 last/first 分支丢圆角
            if (data.isSingle() || data.isOneFollowingFolder()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgSingleRes(id));
            } else if (data.isLast()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgLastRes(id));
            } else if (data.isFirst() || data.isMultiFollowingFolder()) {
                setBackgroundResource(NoteItemBgResources.getNoteBgFirstRes(id));
            } else {
                setBackgroundResource(NoteItemBgResources.getNoteBgNormalRes(id));
            }
        } else {
            // 文件夹不参与便签卡片拼接，统一使用文件夹背景
            setBackgroundResource(NoteItemBgResources.getFolderBgRes());
        }
    }

    /**
     * @return 最近一次绑定的行快照；长按/上下文菜单用它定位当前行，未绑定过时为 null
     */
    public NoteItemData getItemData() {
        return mItemData;
    }
}
