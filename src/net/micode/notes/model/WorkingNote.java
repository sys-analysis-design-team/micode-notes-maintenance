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

package net.micode.notes.model;

import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.tool.ResourceParser.NoteBgResources;


/**
 * 编辑页与列表页使用的“工作便签”模型。
 *
 * <p>职责：把 note 表的一行（文件夹、背景色、Widget、提醒等元数据）与 data 表的多行
 * （正文、通话信息）聚合成一个可编辑对象；所有修改先暂存在内部 {@link Note} 的差异集合里，
 * 调用 {@link #saveNote()} 时才统一提交。自身不直接执行 SQL，读写都走 ContentResolver。</p>
 *
 * <p>架构位置：model 层。NoteEditActivity 用它承载正在编辑的便签，NotesListActivity 用它
 * 新建便签；桌面 Widget 的绑定信息也挂在它的字段上。</p>
 *
 * <p>关键协作类：{@link Note}（脏值收集与提交）、{@link NoteSettingChangedListener}
 * （属性变化的 UI/Widget 通知）、{@link NoteBgResources}（背景色换算）。</p>
 */
public class WorkingNote {
    // Note for the working note
    // 内部脏值容器：属性改动先写进它，saveNote() 时才统一提交
    private Note mNote;
    // Note Id
    // 0 表示尚未落库的新便签，>0 才是数据库主键
    private long mNoteId;
    // Note content
    // 正文的内存副本，setWorkingText 用它比较是否真的发生改动
    private String mContent;
    // Note mode
    // 清单模式：0 普通、1 清单（最终写到 data1 列，即 TextNote.MODE）
    private int mMode;

    // 提醒时间（毫秒）；0 表示未设置
    private long mAlertDate;

    // 从库里读到的最后修改时间；新便签取构造时刻
    private long mModifiedDate;

    // 背景色索引（0~4），不是资源 ID，显示前要经 ResourceParser 换算
    private int mBgColorId;

    // 绑定的桌面 Widget ID；AppWidgetManager.INVALID_APPWIDGET_ID 表示未绑定
    private int mWidgetId;

    // Widget 尺寸类型：0=2x、1=4x、-1=未绑定
    private int mWidgetType;

    // 所属文件夹；0 是根文件夹，负数对应 Notes.ID_* 系统文件夹
    private long mFolderId;

    private Context mContext;

    private static final String TAG = "WorkingNote";

    // 内存态删除标记：只让 saveNote() 跳过写库，真正进回收站由调用方在写库前完成
    private boolean mIsDeleted;

    private NoteSettingChangedListener mNoteSettingStatusListener;

    // 查询列顺序必须与下面 DATA_*_COLUMN 下标严格一致；Cursor 全部按下标取值
    public static final String[] DATA_PROJECTION = new String[] {
            DataColumns.ID,
            DataColumns.CONTENT,
            DataColumns.MIME_TYPE,
            DataColumns.DATA1,
            DataColumns.DATA2,
            DataColumns.DATA3,
            DataColumns.DATA4,
    };

    // 同上：顺序与 NOTE_*_COLUMN 一一对应，改投影必须同步改下标
    public static final String[] NOTE_PROJECTION = new String[] {
            NoteColumns.PARENT_ID,
            NoteColumns.ALERTED_DATE,
            NoteColumns.BG_COLOR_ID,
            NoteColumns.WIDGET_ID,
            NoteColumns.WIDGET_TYPE,
            NoteColumns.MODIFIED_DATE
    };

    // 以下为数据行的投影下标：0=ID、1=正文、2=mime、3=data1（正文行存清单模式）
    private static final int DATA_ID_COLUMN = 0;

    private static final int DATA_CONTENT_COLUMN = 1;

    private static final int DATA_MIME_TYPE_COLUMN = 2;

    private static final int DATA_MODE_COLUMN = 3;

    // 以下为 note 行的投影下标：0=父文件夹、1=提醒时间、2=背景色、3=Widget ID、4=Widget 类型、5=修改时间
    private static final int NOTE_PARENT_ID_COLUMN = 0;

    private static final int NOTE_ALERTED_DATE_COLUMN = 1;

    private static final int NOTE_BG_COLOR_ID_COLUMN = 2;

    private static final int NOTE_WIDGET_ID_COLUMN = 3;

    private static final int NOTE_WIDGET_TYPE_COLUMN = 4;

    private static final int NOTE_MODIFIED_DATE_COLUMN = 5;

    // New note construct
    // 新建便签：不读库、不占主键；mNoteId 保持 0，首次 saveNote() 时才插入占位行换 ID。
    // 提醒时间默认 0（无提醒），Widget 类型置 -1（未绑定），由工厂方法再覆盖
    private WorkingNote(Context context, long folderId) {
        mContext = context;
        mAlertDate = 0;
        mModifiedDate = System.currentTimeMillis();
        mFolderId = folderId;
        mNote = new Note();
        mNoteId = 0;
        mIsDeleted = false;
        mMode = 0;
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;
    }

    // Existing note construct
    // 已有便签：先按 ID 从库里读回字段；folderId 只是初始值，随即被库里的 parent_id 覆盖，
    // 所以 load() 传 0 不会影响结果。ID 不存在时 loadNote() 会抛异常，对象构造失败
    private WorkingNote(Context context, long noteId, long folderId) {
        mContext = context;
        mNoteId = noteId;
        mFolderId = folderId;
        mIsDeleted = false;
        mNote = new Note();
        loadNote();
    }

    /**
     * 按 ID 读回 note 行元数据，再加载 data 行。
     *
     * <p>文件夹、背景色、Widget、提醒时间、修改时间都以数据库为准，覆盖构造时的默认值。</p>
     *
     * <p>异常：查询返回空游标（便签已被并发删除）时抛 IllegalArgumentException，
     * 而不是静默返回空对象，避免编辑页继续操作一条不存在的便签。</p>
     *
     * <p>副作用：两次 ContentResolver 查询；游标在正常与异常数据两个分支都手动 close。</p>
     */
    private void loadNote() {
        Cursor cursor = mContext.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId), NOTE_PROJECTION, null,
                null, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                mFolderId = cursor.getLong(NOTE_PARENT_ID_COLUMN);
                mBgColorId = cursor.getInt(NOTE_BG_COLOR_ID_COLUMN);
                mWidgetId = cursor.getInt(NOTE_WIDGET_ID_COLUMN);
                mWidgetType = cursor.getInt(NOTE_WIDGET_TYPE_COLUMN);
                mAlertDate = cursor.getLong(NOTE_ALERTED_DATE_COLUMN);
                mModifiedDate = cursor.getLong(NOTE_MODIFIED_DATE_COLUMN);
            }
            cursor.close();
        } else {
            Log.e(TAG, "No note with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note with id " + mNoteId);
        }
        loadNoteData();
    }

    /**
     * 加载便签的 data 行：正文行填充 mContent/mMode 并记住 data 主键，通话行只记录 data 主键。
     *
     * <p>边界：一条便签理论上只有一行正文；若库里存在重复正文行，后读到的会覆盖前面的。
     * 查询返回 null 游标（Provider 异常）时抛 IllegalArgumentException；返回空结果不抛，
     * 此时正文保持 null，等同空便签。</p>
     *
     * <p>未知 mime 类型只记日志跳过，不影响其余行加载。</p>
     */
    private void loadNoteData() {
        Cursor cursor = mContext.getContentResolver().query(Notes.CONTENT_DATA_URI, DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?", new String[] {
                    String.valueOf(mNoteId)
                }, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(DATA_MIME_TYPE_COLUMN);
                    if (DataConstants.NOTE.equals(type)) {
                        mContent = cursor.getString(DATA_CONTENT_COLUMN);
                        mMode = cursor.getInt(DATA_MODE_COLUMN);
                        mNote.setTextDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else if (DataConstants.CALL_NOTE.equals(type)) {
                        mNote.setCallDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else {
                        Log.d(TAG, "Wrong note type with type:" + type);
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        } else {
            Log.e(TAG, "No data with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note's data with id " + mNoteId);
        }
    }

    /**
     * 工厂：创建尚未落库的空便签，并写入背景色与 Widget 绑定信息。
     *
     * @param folderId 新便签所属文件夹；传 0 即根文件夹
     * @param widgetId 绑定的桌面 Widget；无绑定传 {@link AppWidgetManager#INVALID_APPWIDGET_ID}
     * @param widgetType 0=2x、1=4x；无绑定传 {@link Notes#TYPE_WIDGET_INVALIDE}
     * @param defaultBgColorId 背景色索引（0~4），来自用户偏好或随机色
     */
    public static WorkingNote createEmptyNote(Context context, long folderId, int widgetId,
            int widgetType, int defaultBgColorId) {
        WorkingNote note = new WorkingNote(context, folderId);
        note.setBgColorId(defaultBgColorId);
        note.setWidgetId(widgetId);
        note.setWidgetType(widgetType);
        return note;
    }

    /**
     * 工厂：按主键加载已有便签。
     *
     * @param id note 表主键；不存在时由内部 loadNote 抛 IllegalArgumentException
     */
    public static WorkingNote load(Context context, long id) {
        return new WorkingNote(context, id, 0);
    }

    /**
     * 把内存中的改动提交到数据库，必要时顺带刷新桌面 Widget。
     *
     * <p>流程：先判断是否值得写（见 {@link #isWorthSaving()}）；新便签先向 note 表插入
     * 占位行换取自增 ID（data 行必须先有 note_id 才能写入）；随后由 {@link Note#syncNote}
     * 统一写 note 与 data；最后只在确实绑定了 Widget 时通知监听者刷新。</p>
     *
     * <p>注意：这里没有检查 syncNote 的返回值——data 行写入失败时本次仍返回 true，
     * 错误只留在 NoteData 的日志里；已清空的差异值不会在下次保存时自动重试。</p>
     *
     * @return true 表示本次提交完成或本来无需写库；false 表示没有可保存的内容，
     *         或新便签申请 ID 失败
     *
     * <p>副作用：写数据库、触发监听回调；synchronized 防止 onPause 与点击保存并发执行，
     * 导致同一条新便签插入两次。</p>
     */
    public synchronized boolean saveNote() {
        if (isWorthSaving()) {
            if (!existInDatabase()) {
                // 新便签没有主键：先向 note 表插一行占位记录，由数据库分配自增 ID；
                // 返回 0 表示插入失败，本次直接放弃，不留下半条数据
                if ((mNoteId = Note.getNewNoteId(mContext, mFolderId)) == 0) {
                    Log.e(TAG, "Create new note fail with id:" + mNoteId);
                    return false;
                }
            }

            mNote.syncNote(mContext, mNoteId);

            // 只有仍绑定且类型有效时才通知刷新，避免已删除的 Widget 收到无意义更新
            /**
             * Update widget content if there exist any widget of this note
             */
            if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                    && mWidgetType != Notes.TYPE_WIDGET_INVALIDE
                    && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * 是否已有数据库主键；0 表示新建未保存。
     */
    public boolean existInDatabase() {
        return mNoteId > 0;
    }

    /**
     * 是否有必要写库，挡掉三类无意义写入：已标记删除、新建但正文为空、
     * 已有便签且没有任何脏值。
     *
     * <p>空正文的新便签不落库，避免用户点开又退出时留下空记录。</p>
     */
    private boolean isWorthSaving() {
        if (mIsDeleted || (!existInDatabase() && TextUtils.isEmpty(mContent))
                || (existInDatabase() && !mNote.isLocalModified())) {
            return false;
        } else {
            return true;
        }
    }

    // 只保留一个监听者；编辑页重建时会重新注册，这里不做多播列表
    public void setOnSettingStatusChangedListener(NoteSettingChangedListener l) {
        mNoteSettingStatusListener = l;
    }

    /**
     * 设置或取消提醒时间，并把新值记进 note 表差异。
     *
     * @param date 毫秒时间戳；0 表示取消提醒
     * @param set  true=设置、false=取消，仅透传给监听者用于提示文案
     *
     * <p>边界：即使 date 与当前值相同也会回调监听者（回调在 if 之外），
     * 保证 UI 状态与用户操作一致。这里不触发写库，需调用方再 saveNote()。</p>
     */
    public void setAlertDate(long date, boolean set) {
        if (date != mAlertDate) {
            mAlertDate = date;
            mNote.setNoteValue(NoteColumns.ALERTED_DATE, String.valueOf(mAlertDate));
        }
        if (mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onClockAlertChanged(date, set);
        }
    }

    /**
     * 标记便签已删除，使后续 {@link #saveNote()} 跳过写库。
     *
     * <p>只改内存标记：真正的软删除（移动到回收站文件夹 -3）由调用方在写库前完成。
     * 这里额外刷新 Widget，避免桌面微件还显示已删除便签的内容。</p>
     */
    public void markDeleted(boolean mark) {
        mIsDeleted = mark;
        if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && mWidgetType != Notes.TYPE_WIDGET_INVALIDE && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
        }
    }

    /**
     * 切换背景色索引。
     *
     * @param id 颜色索引 0~4；越界值这里不校验，取资源时才会数组越界
     *
     * <p>值没变时直接返回，不产生差异值；变化时先更新内存字段再回调监听者，
     * 监听方读 {@link #getBgColorId()} 已经是新值。</p>
     */
    public void setBgColorId(int id) {
        if (id != mBgColorId) {
            mBgColorId = id;
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onBackgroundColorChanged();
            }
            mNote.setNoteValue(NoteColumns.BG_COLOR_ID, String.valueOf(id));
        }
    }

    /**
     * 切换普通/清单模式，并写入正文 data 行的 data1 列。
     *
     * @param mode 0=普通、1=清单（{@link TextNote#MODE_CHECK_LIST}）
     *
     * <p>边界：回调发生在 mMode 赋值之前，参数已带旧/新模式，监听方应以参数为准，
     * 此时调用 {@link #getCheckListMode()} 读到的还是旧值。</p>
     */
    public void setCheckListMode(int mode) {
        if (mMode != mode) {
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onCheckListModeChanged(mMode, mode);
            }
            mMode = mode;
            mNote.setTextData(TextNote.MODE, String.valueOf(mMode));
        }
    }

    /**
     * 记录绑定 Widget 的尺寸类型。
     *
     * @param type 0=2x、1=4x、-1=未绑定；只写入 note 表差异，不触发刷新
     */
    public void setWidgetType(int type) {
        if (type != mWidgetType) {
            mWidgetType = type;
            mNote.setNoteValue(NoteColumns.WIDGET_TYPE, String.valueOf(mWidgetType));
        }
    }

    /**
     * 记录绑定的桌面 Widget ID。
     *
     * @param id 系统分配的 AppWidget ID；解绑时写回 INVALID_APPWIDGET_ID
     */
    public void setWidgetId(int id) {
        if (id != mWidgetId) {
            mWidgetId = id;
            mNote.setNoteValue(NoteColumns.WIDGET_ID, String.valueOf(mWidgetId));
        }
    }

    /**
     * 更新正文内容；内容不变时不产生差异值。
     *
     * <p>只改内存与 Note 的差异缓存，真正写 data 表要等 {@link #saveNote()}；
     * 传空串会如实写入，传 null 且原值为 null 时视为无改动。</p>
     */
    public void setWorkingText(String text) {
        if (!TextUtils.equals(mContent, text)) {
            mContent = text;
            mNote.setTextData(DataColumns.CONTENT, mContent);
        }
    }

    /**
     * 把当前便签转成通话记录：写入通话时间与号码，并把所属文件夹改为 -2（通话记录文件夹）。
     *
     * <p>边界：只改差异值，需再调用 {@link #saveNote()} 才落库；号码不做非空校验，
     * 由调用方（来电界面）保证。</p>
     *
     * @param phoneNumber 对方号码，原样存入 data3
     * @param callDate 通话时间（毫秒），存入 data1
     */
    public void convertToCallNote(String phoneNumber, long callDate) {
        mNote.setCallData(CallNote.CALL_DATE, String.valueOf(callDate));
        mNote.setCallData(CallNote.PHONE_NUMBER, phoneNumber);
        mNote.setNoteValue(NoteColumns.PARENT_ID, String.valueOf(Notes.ID_CALL_RECORD_FOLDER));
    }

    /**
     * 是否设置了提醒。
     *
     * <p>约定提醒时间 >0 才算有提醒，0 表示未设置。</p>
     */
    public boolean hasClockAlert() {
        return (mAlertDate > 0 ? true : false);
    }

    /**
     * 当前正文内容；新建且从未编辑过的便签返回 null。
     */
    public String getContent() {
        return mContent;
    }

    /**
     * 提醒时间（毫秒）；0 表示未设置。
     */
    public long getAlertDate() {
        return mAlertDate;
    }

    /**
     * 最后修改时间（毫秒）；新便签为创建该对象的时刻，已有便签为库里读回的值。
     */
    public long getModifiedDate() {
        return mModifiedDate;
    }

    /**
     * 正文区域背景 drawable 资源 ID。
     *
     * <p>边界：直接用背景色索引查数组，调用方需保证索引在 0~4，否则数组越界。</p>
     */
    public int getBgColorResId() {
        return NoteBgResources.getNoteBgResource(mBgColorId);
    }

    /**
     * 背景色索引（0~4）；数据库里存的就是这个值，不是资源 ID。
     */
    public int getBgColorId() {
        return mBgColorId;
    }

    /**
     * 标题栏背景 drawable 资源 ID；越界风险同 {@link #getBgColorResId()}。
     */
    public int getTitleBgResId() {
        return NoteBgResources.getNoteTitleBgResource(mBgColorId);
    }

    /**
     * 清单模式：0=普通、1=清单。
     */
    public int getCheckListMode() {
        return mMode;
    }

    /**
     * note 表主键；0 表示尚未落库。
     */
    public long getNoteId() {
        return mNoteId;
    }

    /**
     * 所属文件夹 ID；0 为根文件夹，负数对应系统文件夹。
     */
    public long getFolderId() {
        return mFolderId;
    }

    /**
     * 绑定桌面 Widget 的 ID；{@link AppWidgetManager#INVALID_APPWIDGET_ID} 表示未绑定。
     */
    public int getWidgetId() {
        return mWidgetId;
    }

    /**
     * Widget 尺寸类型：0=2x、1=4x、-1=未绑定。
     */
    public int getWidgetType() {
        return mWidgetType;
    }

    /**
     * 便签属性变化回调，编辑页实现它来同步界面与桌面 Widget。
     *
     * <p>回调都在属性变更的同一调用栈里触发，此时数据库尚未写入（saveNote 才落库），
     * 监听方只应刷新 UI/触发微件更新，不要假设数据已持久化。</p>
     */
    public interface NoteSettingChangedListener {
        /**
         * Called when the background color of current note has just changed
         */
        void onBackgroundColorChanged();

        /**
         * Called when user set clock
         */
        void onClockAlertChanged(long date, boolean set);

        /**
         * Call when user create note from widget
         */
        void onWidgetChanged();

        /**
         * Call when switch between check list mode and normal mode
         * @param oldMode is previous mode before change
         * @param newMode is new mode
         */
        void onCheckListModeChanged(int oldMode, int newMode);
    }
}
