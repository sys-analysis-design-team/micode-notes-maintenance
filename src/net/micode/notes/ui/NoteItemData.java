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
import android.text.TextUtils;

import net.micode.notes.data.Contact;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.tool.DataUtils;


/**
 * 便签列表行数据快照：把 note 查询结果游标的当前行读成普通 Java 对象。
 *
 * <p>职责：只消费 Cursor 当前行，不发起查询、不长期持有 Cursor。它把原始列值整理成
 * 列表渲染与批量操作需要的形式（去掉清单标记的摘要、通话记录的联系人/号码），
 * 并预先算好相邻行位置标志（首行、末行、唯一行、是否紧跟文件夹），
 * 供 {@link NotesListItem} 决定卡片背景的圆角拼接。</p>
 *
 * <p>架构位置：ui 包的轻量数据对象，位于 NotesListActivity 的查询结果与
 * {@link NotesListItem} 的视图绑定之间，也被 {@link NotesListAdapter}
 * 用于多选计数与桌面 Widget 收集。</p>
 *
 * <p>关键协作类：{@link NotesListAdapter}（每行绑定/统计时构造）、
 * {@link NotesListItem}（读取字段渲染）、{@link DataUtils}（通话记录行查询号码）。</p>
 */
public class NoteItemData {
    // 列顺序与下方 *_COLUMN 下标常量严格一一对应；调整任何一列都必须同步改常量，
    // 否则 getLong/getString 会静默取到错误的列
    static final String [] PROJECTION = new String [] {
        NoteColumns.ID,
        NoteColumns.ALERTED_DATE,
        NoteColumns.BG_COLOR_ID,
        NoteColumns.CREATED_DATE,
        NoteColumns.HAS_ATTACHMENT,
        NoteColumns.MODIFIED_DATE,
        NoteColumns.NOTES_COUNT,
        NoteColumns.PARENT_ID,
        NoteColumns.SNIPPET,
        NoteColumns.TYPE,
        NoteColumns.WIDGET_ID,
        NoteColumns.WIDGET_TYPE,
        NoteColumns.PINNED,
    };

    // 与上方 PROJECTION 顺序对应的列下标
    private static final int ID_COLUMN                    = 0;
    private static final int ALERTED_DATE_COLUMN          = 1;
    private static final int BG_COLOR_ID_COLUMN           = 2;
    private static final int CREATED_DATE_COLUMN          = 3;
    private static final int HAS_ATTACHMENT_COLUMN        = 4;
    private static final int MODIFIED_DATE_COLUMN         = 5;
    private static final int NOTES_COUNT_COLUMN           = 6;
    private static final int PARENT_ID_COLUMN             = 7;
    private static final int SNIPPET_COLUMN               = 8;
    private static final int TYPE_COLUMN                  = 9;
    private static final int WIDGET_ID_COLUMN             = 10;
    private static final int WIDGET_TYPE_COLUMN           = 11;
    private static final int PINNED_COLUMN                = 12;

    // note 表当前行的字段快照
    private long mId;
    private long mAlertDate;
    private int mBgColorId;
    private long mCreatedDate;
    private boolean mHasAttachment;
    private long mModifiedDate;
    private int mNotesCount;
    private long mParentId;
    private String mSnippet;
    private int mType;
    private int mWidgetId;
    private int mWidgetType;
    // 置顶标志（M8）：1 表示置顶；文件夹行恒为 0
    private boolean mPinned;
    // 仅通话记录相关行使用：号码来自 data 表，名称由联系人查询解析（失败时退回号码）
    private String mName;
    private String mPhoneNumber;

    // 列表位置标志：只影响卡片背景圆角的拼接，不代表数据关系
    private boolean mIsLastItem;
    private boolean mIsFirstItem;
    private boolean mIsOnlyOneItem;
    private boolean mIsOneNoteFollowingFolder;
    private boolean mIsMultiNotesFollowingFolder;

    /**
     * 从 Cursor 当前行构造快照。
     *
     * <p>先按固定下标读取 note 表各列；摘要会去掉清单模式的已勾选/未勾选前缀，
     * 避免列表预览里出现控制标记。若该行属于通话记录文件夹，还会查询 data 表拿号码，
     * 并尝试解析联系人名称（解析不到时退回号码本身）。</p>
     *
     * <p>副作用：通话记录行会触发一次 ContentResolver 查询；checkPostion 会临时移动 Cursor，
     * 但最终恢复到原位置。</p>
     *
     * @param context 用于 ContentResolver 与联系人查询
     * @param cursor 已定位在有效行的查询结果，列集合必须是 {@link #PROJECTION}
     */
    public NoteItemData(Context context, Cursor cursor) {
        mId = cursor.getLong(ID_COLUMN);
        mAlertDate = cursor.getLong(ALERTED_DATE_COLUMN);
        mBgColorId = cursor.getInt(BG_COLOR_ID_COLUMN);
        mCreatedDate = cursor.getLong(CREATED_DATE_COLUMN);
        mHasAttachment = (cursor.getInt(HAS_ATTACHMENT_COLUMN) > 0) ? true : false;
        mModifiedDate = cursor.getLong(MODIFIED_DATE_COLUMN);
        mNotesCount = cursor.getInt(NOTES_COUNT_COLUMN);
        mParentId = cursor.getLong(PARENT_ID_COLUMN);
        mSnippet = cursor.getString(SNIPPET_COLUMN);
        // 数据库 schema 保证 snippet 非空（NOT NULL DEFAULT ''）；这里只做展示清洗，
        // 不去除换行，换行截断由 NotesListItem 调 DataUtils.getFormattedSnippet 完成
        mSnippet = mSnippet.replace(NoteEditActivity.TAG_CHECKED, "").replace(
                NoteEditActivity.TAG_UNCHECKED, "");
        mType = cursor.getInt(TYPE_COLUMN);
        mWidgetId = cursor.getInt(WIDGET_ID_COLUMN);
        mWidgetType = cursor.getInt(WIDGET_TYPE_COLUMN);
        mPinned = (cursor.getInt(PINNED_COLUMN) > 0);

        mPhoneNumber = "";
        // 只有“通话记录”系统文件夹下的便签才需要号码/联系人；
        // 这是额外一次 Provider 查询，普通便签行不会进入
        if (mParentId == Notes.ID_CALL_RECORD_FOLDER) {
            mPhoneNumber = DataUtils.getCallNumberByNoteId(context.getContentResolver(), mId);
            if (!TextUtils.isEmpty(mPhoneNumber)) {
                mName = Contact.getContact(context, mPhoneNumber);
                if (mName == null) {
                    mName = mPhoneNumber;
                }
            }
        }

        if (mName == null) {
            mName = "";
        }
        // 位置标志放在最后计算：它会临时移动 Cursor，不影响上面已读取的字段
        checkPostion(cursor);
    }

    /**
     * 计算当前行在列表中的相邻位置标志。
     *
     * <p>列表把“文件夹后连续的多张便签”视为一个视觉卡片：首行需要上方圆角、
     * 末行需要下方圆角、只有一张时四角全圆。判断方式是偷看上一行的 type：
     * 若上一行是文件夹或系统文件夹，当前行就是卡片首行；再根据后面是否还有行，
     * 区分“卡片只有这一张”与“卡片还有后续多张”。</p>
     *
     * <p>副作用：会把 Cursor 移到上一行再移回来。若移回失败则抛 IllegalStateException，
     * 因为继续执行会让后续行绑定错位，属于应当立即暴露的适配器契约破坏。</p>
     *
     * @param cursor 已定位在当前行的查询结果
     * @throws IllegalStateException Cursor 移到上一行后无法移回原位
     */
    private void checkPostion(Cursor cursor) {
        // 末行/首行/唯一行直接由 Cursor 位置得出，不移动 Cursor
        mIsLastItem = cursor.isLast() ? true : false;
        mIsFirstItem = cursor.isFirst() ? true : false;
        mIsOnlyOneItem = (cursor.getCount() == 1);
        mIsMultiNotesFollowingFolder = false;
        mIsOneNoteFollowingFolder = false;

        // 只有便签需要和上一行拼接卡片；首行不可能“紧跟文件夹”，直接跳过
        if (mType == Notes.TYPE_NOTE && !mIsFirstItem) {
            int position = cursor.getPosition();
            // moveToPrevious 返回 false 表示当前已是第一行，保持“非卡片首行”的默认值
            if (cursor.moveToPrevious()) {
                // 上一行是文件夹（用户文件夹或系统文件夹）时，当前便签才开始一张新卡片
                if (cursor.getInt(TYPE_COLUMN) == Notes.TYPE_FOLDER
                        || cursor.getInt(TYPE_COLUMN) == Notes.TYPE_SYSTEM) {
                    // position + 1 是当前行的 0 基“下一行”位置；总数更大说明后面还有行，
                    // 当前行只是卡片首行而不是整张卡片
                    if (cursor.getCount() > (position + 1)) {
                        mIsMultiNotesFollowingFolder = true;
                    } else {
                        mIsOneNoteFollowingFolder = true;
                    }
                }
                // 必须恢复原位置，否则下一行的类型判断会用到错误行；
                // 恢复失败说明 Cursor 行为异常，直接抛出而不是静默继续
                if (!cursor.moveToNext()) {
                    throw new IllegalStateException("cursor move to previous but can't move back");
                }
            }
        }
    }

    /**
     * 当前便签是否紧跟文件夹且是卡片里唯一一张。
     *
     * @return true 表示应绘制成四角全圆的单独卡片
     */
    public boolean isOneFollowingFolder() {
        return mIsOneNoteFollowingFolder;
    }

    /**
     * 当前便签是否紧跟文件夹且卡片后面还有其它便签。
     *
     * @return true 表示是卡片首行，需要上方圆角
     */
    public boolean isMultiFollowingFolder() {
        return mIsMultiNotesFollowingFolder;
    }

    /**
     * @return 当前行是否是查询结果最后一行；影响卡片下方圆角
     */
    public boolean isLast() {
        return mIsLastItem;
    }

    /**
     * @return 通话记录的联系人名称；解析不到时为号码，再没有号码时为空字符串
     */
    public String getCallName() {
        return mName;
    }

    /**
     * @return 当前行是否是查询结果第一行；影响卡片上方圆角
     */
    public boolean isFirst() {
        return mIsFirstItem;
    }

    /**
     * @return 查询结果是否只有当前这一行；影响卡片四角全圆
     */
    public boolean isSingle() {
        return mIsOnlyOneItem;
    }

    /**
     * @return note 表主键；批量删除/移动时作为目标 ID
     */
    public long getId() {
        return mId;
    }

    /**
     * @return 提醒时间戳；0 表示未设置提醒
     */
    public long getAlertDate() {
        return mAlertDate;
    }

    /**
     * @return 创建时间戳，用于排序/展示（当前列表主要使用修改时间）
     */
    public long getCreatedDate() {
        return mCreatedDate;
    }

    /**
     * @return 该便签是否带附件（has_attachment 列大于 0）
     */
    public boolean hasAttachment() {
        return mHasAttachment;
    }

    /**
     * @return 最后修改时间戳，列表右侧的相对时间由它计算
     */
    public long getModifiedDate() {
        return mModifiedDate;
    }

    /**
     * @return 背景色索引；由 ResourceParser.NoteItemBgResources 映射为具体 drawable，
     *         文件夹固定使用文件夹背景，不使用该值
     */
    public int getBgColorId() {
        return mBgColorId;
    }

    /**
     * @return 所属文件夹 ID；系统文件夹 ID 为 0 或负数
     */
    public long getParentId() {
        return mParentId;
    }

    /**
     * @return 文件夹内的便签数量；该列由数据库触发器维护，普通便签行没有意义
     */
    public int getNotesCount() {
        return mNotesCount;
    }

    /**
     * @return 与 {@link #getParentId()} 相同；保留该命名是为了兼容调用方语义
     */
    public long getFolderId () {
        return mParentId;
    }

    /**
     * @return 行类型：{@link Notes#TYPE_NOTE}、{@link Notes#TYPE_FOLDER} 或 {@link Notes#TYPE_SYSTEM}
     */
    public int getType() {
        return mType;
    }

    /**
     * @return 绑定的桌面 Widget 尺寸类型；未绑定为 {@link Notes#TYPE_WIDGET_INVALIDE}
     */
    public int getWidgetType() {
        return mWidgetType;
    }

    /**
     * @return 绑定的桌面 Widget ID；0 表示未绑定
     */
    public int getWidgetId() {
        return mWidgetId;
    }

    /**
     * @return 便签正文摘要（原始内容，已去掉清单勾选标记，但未截断换行）；
     *         列表展示前还会经 DataUtils.getFormattedSnippet 取第一行
     */
    public String getSnippet() {
        return mSnippet;
    }

    /**
     * @return 是否设置了提醒；以提醒时间戳大于 0 判断（0 表示无提醒）
     */
    public boolean hasAlert() {
        return (mAlertDate > 0);
    }

    /**
     * @return 便签是否置顶（M8）；列表中的置顶便签会显示图钉标记并排在非置顶便签之前
     */
    public boolean isPinned() {
        return mPinned;
    }

    /**
     * 是否是一条有效的通话记录便签。
     *
     * <p>除父文件夹必须是通话记录文件夹外，还要求号码非空：实际数据中可能存在
     * 号码已丢失的残留行，这类行不应按通话记录展示。</p>
     *
     * @return true 表示可按通话记录渲染联系人名称/号码
     */
    public boolean isCallRecord() {
        return (mParentId == Notes.ID_CALL_RECORD_FOLDER && !TextUtils.isEmpty(mPhoneNumber));
    }

    /**
     * 直接读取 Cursor 当前行的类型，不构造快照、不移动 Cursor。
     *
     * <p>供适配器在全选/计数时快速过滤文件夹行。前提是 Cursor 已定位在有效行，
     * 且列集合与 {@link #PROJECTION} 一致（TYPE_COLUMN 下标才有效）。</p>
     *
     * @param cursor 已定位在当前行的查询结果
     * @return 行类型常量
     */
    public static int getNoteType(Cursor cursor) {
        return cursor.getInt(TYPE_COLUMN);
    }

    /**
     * 直接读取 Cursor 当前行的置顶标志，不构造快照、不移动 Cursor。
     *
     * <p>供适配器判断“选中的便签是否已全部置顶”，从而决定批量操作是置顶还是取消置顶。
     * 前提同 {@link #getNoteType(Cursor)}：列集合必须与 {@link #PROJECTION} 一致。</p>
     *
     * @param cursor 已定位在当前行的查询结果
     * @return true 表示该行已置顶
     */
    public static boolean isPinned(Cursor cursor) {
        return cursor.getInt(PINNED_COLUMN) > 0;
    }
}
