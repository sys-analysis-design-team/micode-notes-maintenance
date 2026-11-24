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
import android.database.Cursor;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;

import net.micode.notes.data.Notes;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;


/**
 * 便签列表的 CursorAdapter：把查询结果的每一行包装成 {@link NoteItemData}，
 * 再绑定到 {@link NotesListItem} 上，同时维护多选状态与可选便签计数。
 *
 * <p>职责：视图创建/绑定、按位置记录多选状态、全选与选中数量统计、
 * 批量操作所需的便签 ID 与桌面 Widget 信息收集。不负责发起查询——
 * Cursor 由 NotesListActivity 的异步查询通过 changeCursor 注入，适配器只消费。</p>
 *
 * <p>架构位置：ui 包中列表页的数据-视图桥。上层是 NotesListActivity 的多选 ActionMode
 * 与批量删除/移动逻辑，下层是 {@link NotesListItem} 与 {@link NoteItemData}。</p>
 *
 * <p>关键协作类：{@link NoteItemData}（行快照）、{@link NotesListItem}（行视图）、
 * {@link Notes}（类型常量与系统文件夹 ID）。</p>
 *
 * <p>边界：选中状态以“位置”为键，Cursor 内容刷新后同一位置可能对应别的便签，
 * 所以进入/退出选择模式时会清空；批量操作应在选择模式内、Cursor 未更换时完成。</p>
 */
public class NotesListAdapter extends CursorAdapter {
    private static final String TAG = "NotesListAdapter";
    private Context mContext;
    // 多选状态：key 为列表位置，value 为是否勾选；未勾选也保留 false，便于统计时过滤
    private HashMap<Integer, Boolean> mSelectedIndex;
    // 当前 Cursor 中 TYPE_NOTE 行数；全选判断的分母（文件夹不计入）
    private int mNotesCount;
    // 是否处于多选模式；只有便签行会显示复选框
    private boolean mChoiceMode;

    /**
     * 桌面 Widget 绑定信息：批量删除便签前先收集受影响的 Widget，
     * 删除后通知对应 Widget 刷新或移除。
     *
     * <p>注意：没有重写 equals/hashCode，放进 HashSet 时按对象身份去重，
     * 同一 Widget 绑定多张选中便签会出现多个相同条目；下游按 widgetId 更新是幂等的，
     * 因此不影响结果。</p>
     */
    public static class AppWidgetAttribute {
        public int widgetId;
        public int widgetType;
    };

    /**
     * 创建空适配器：初始没有 Cursor，等 Activity 查询完成后通过 changeCursor 注入。
     *
     * @param context 用于后续按位置取行构造 NoteItemData
     */
    public NotesListAdapter(Context context) {
        super(context, null);
        mSelectedIndex = new HashMap<Integer, Boolean>();
        mContext = context;
        mNotesCount = 0;
    }

    /**
     * 创建列表行视图；行复用由 CursorAdapter/ListView 负责，这里只负责新建。
     */
    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return new NotesListItem(context);
    }

    /**
     * 把 Cursor 当前行绑定到行视图。
     *
     * <p>每次绑定都新建一个轻量 NoteItemData 快照（同时算出卡片圆角所需的相邻位置标志），
     * 并把当前是否勾选传给行视图。Cursor 归适配器所有，绑定过程中不得关闭。</p>
     */
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        if (view instanceof NotesListItem) {
            NoteItemData itemData = new NoteItemData(context, cursor);
            ((NotesListItem) view).bind(context, itemData, mChoiceMode,
                    isSelectedItem(cursor.getPosition()));
        }
    }

    /**
     * 设置某个位置的勾选状态并刷新列表。
     *
     * <p>取消勾选时保留 false 而不是删除键：统计时只数 true，删不删都不影响结果，
     * 保留可以让重复取消成为幂等操作。notifyDataSetChanged 是必要的——
     * ListView 行会复用，只有整表重绘才能让所有可见行的复选框状态一致。</p>
     *
     * @param position 列表位置
     * @param checked 是否勾选
     */
    public void setCheckedItem(final int position, final boolean checked) {
        mSelectedIndex.put(position, checked);
        notifyDataSetChanged();
    }

    /**
     * @return 当前是否处于多选模式
     */
    public boolean isInChoiceMode() {
        return mChoiceMode;
    }

    /**
     * 切换多选模式。
     *
     * <p>切换时清空选择表：选中状态按位置保存，退出再进入或 Cursor 刷新后，
     * 旧位置可能对应到别的便签，必须丢弃以免误删、误移动。</p>
     *
     * @param mode true 进入多选，false 退出多选
     */
    public void setChoiceMode(boolean mode) {
        mSelectedIndex.clear();
        mChoiceMode = mode;
    }

    /**
     * 全选或全不选当前列表中的便签。
     *
     * <p>只处理 {@link Notes#TYPE_NOTE}：文件夹行不参与批量删除/移动，也不显示复选框。
     * 逐位置调用 setCheckedItem 会为每一行触发一次整表刷新，列表较大时存在重复刷新，
     * 但实现简单、状态一致，原逻辑保留。</p>
     *
     * @param checked true 全选，false 全不选
     */
    public void selectAll(boolean checked) {
        Cursor cursor = getCursor();
        for (int i = 0; i < getCount(); i++) {
            if (cursor.moveToPosition(i)) {
                if (NoteItemData.getNoteType(cursor) == Notes.TYPE_NOTE) {
                    setCheckedItem(i, checked);
                }
            }
        }
    }

    /**
     * 收集已勾选便签的数据库 ID，供批量删除/移动使用。
     *
     * <p>通过 getItemId(position) 把位置映射为 note 表主键。根文件夹 ID（0）
     * 不应出现在选择集中；一旦出现说明选择状态与数据错位，只记日志并跳过，
     * 避免把根文件夹当成便签操作。</p>
     *
     * @return 已选便签 ID 集合；无选中时返回空集合
     */
    public HashSet<Long> getSelectedItemIds() {
        HashSet<Long> itemSet = new HashSet<Long>();
        for (Integer position : mSelectedIndex.keySet()) {
            if (mSelectedIndex.get(position) == true) {
                Long id = getItemId(position);
                if (id == Notes.ID_ROOT_FOLDER) {
                    Log.d(TAG, "Wrong item id, should not happen");
                } else {
                    itemSet.add(id);
                }
            }
        }

        return itemSet;
    }

    /**
     * 收集已勾选便签关联的桌面 Widget 信息，供删除后刷新/移除 Widget。
     *
     * <p>对每个选中位置重新取行并构造 NoteItemData 读取 widget 列。Cursor 属于适配器，
     * 这里不能关闭（关闭会让后续绑定与统计失效）。取不到行时返回 null，
     * 调用方必须先判空再遍历。</p>
     *
     * @return Widget 属性集合；Cursor 状态异常时返回 null
     */
    public HashSet<AppWidgetAttribute> getSelectedWidget() {
        HashSet<AppWidgetAttribute> itemSet = new HashSet<AppWidgetAttribute>();
        for (Integer position : mSelectedIndex.keySet()) {
            if (mSelectedIndex.get(position) == true) {
                Cursor c = (Cursor) getItem(position);
                if (c != null) {
                    AppWidgetAttribute widget = new AppWidgetAttribute();
                    NoteItemData item = new NoteItemData(mContext, c);
                    widget.widgetId = item.getWidgetId();
                    widget.widgetType = item.getWidgetType();
                    itemSet.add(widget);
                    /**
                     * Don't close cursor here, only the adapter could close it
                     *
                     * <p>中文：Cursor 归适配器所有，方法内关闭会让后续 bindView/统计失效。</p>
                     */
                } else {
                    Log.e(TAG, "Invalid cursor");
                    return null;
                }
            }
        }
        return itemSet;
    }

    /**
     * @return 已勾选数量；只统计 value 为 true 的条目，未选中时返回 0
     */
    public int getSelectedCount() {
        Collection<Boolean> values = mSelectedIndex.values();
        if (null == values) {
            return 0;
        }
        Iterator<Boolean> iter = values.iterator();
        int count = 0;
        while (iter.hasNext()) {
            if (true == iter.next()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 已勾选的便签是否全部处于置顶状态（M8）。
     *
     * <p>批量置顶按“全已置顶则取消、否则置顶”的切换语义工作，
     * 需要一个整体判断。selectedCount 为 0 时返回 false，由调用方先拦截“未选择”提示。</p>
     *
     * @return true 表示至少选中一条且全部已置顶
     */
    public boolean isAllSelectedPinned() {
        int selectedCount = 0;
        for (Integer position : mSelectedIndex.keySet()) {
            if (mSelectedIndex.get(position) == true) {
                Cursor c = (Cursor) getItem(position);
                if (c == null) {
                    Log.e(TAG, "Invalid cursor");
                    return false;
                }
                if (!NoteItemData.isPinned(c)) {
                    return false;
                }
                selectedCount++;
            }
        }
        return selectedCount > 0;
    }

    /**
     * 是否所有“可选便签”都已勾选。
     *
     * <p>checkedCount != 0 是必要保护：空列表时 0 == mNotesCount 也成立，
     * 没有该判断会把“没有便签”误判为“全选”。分母 mNotesCount 只统计便签，不含文件夹。</p>
     *
     * @return true 表示至少选中一个且已选中全部便签
     */
    public boolean isAllSelected() {
        int checkedCount = getSelectedCount();
        return (checkedCount != 0 && checkedCount == mNotesCount);
    }

    /**
     * @param position 列表位置
     * @return 该位置是否被勾选；没有记录视为未选中
     */
    public boolean isSelectedItem(final int position) {
        if (null == mSelectedIndex.get(position)) {
            return false;
        }
        return mSelectedIndex.get(position);
    }

    /**
     * Cursor 内容变化时重新统计便签数量，保证全选判断的分母与数据同步。
     */
    @Override
    protected void onContentChanged() {
        super.onContentChanged();
        calcNotesCount();
    }

    /**
     * 替换 Cursor 后重新统计；初次加载与数据刷新都会走这里。
     * 本方法不自动清空选择表，选择模式的生命周期由 Activity 控制。
     */
    @Override
    public void changeCursor(Cursor cursor) {
        super.changeCursor(cursor);
        calcNotesCount();
    }

    /**
     * 遍历当前 Cursor 统计 {@link Notes#TYPE_NOTE} 行数，作为“全选”的分母。
     *
     * <p>getItem(i) 会移动 Cursor 位置，这是本方法的副作用；只在数据变化后调用，
     * 复杂度 O(n)。某位置取不到行说明适配器与 Cursor 状态不一致，
     * 记错误日志并中止，避免用错误计数做全选判断。</p>
     */
    private void calcNotesCount() {
        mNotesCount = 0;
        for (int i = 0; i < getCount(); i++) {
            Cursor c = (Cursor) getItem(i);
            if (c != null) {
                if (NoteItemData.getNoteType(c) == Notes.TYPE_NOTE) {
                    mNotesCount++;
                }
            } else {
                Log.e(TAG, "Invalid cursor");
                return;
            }
        }
    }
}
