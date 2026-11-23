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

package net.micode.notes.gtask.data;

import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.exception.ActionFailureException;
import net.micode.notes.tool.GTaskStringUtils;
import net.micode.notes.tool.ResourceParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;


/**
 * 本地便签/文件夹在同步层的数据快照与读写代理。
 *
 * <p>职责：把 Notes ContentProvider 中的一行 note（及若干 data 子行）暂存到内存，
 * 负责与 Google Tasks 的 meta JSON 互相转换，并把变更以差异方式写回 SQLite。
 * 本类不发起网络请求，也不处理 UI，只在“本地数据库”与“远端节点”之间做映射。</p>
 *
 * <p>架构位置：gtask.data 包的核心数据类。GTaskManager 同步时用它读取本地行、
 * 生成推送内容（{@link #getContent()}），或把远端 meta JSON 合并进本地
 * （{@link #setContent(JSONObject)}），最后调用 {@link #commit(boolean)} 落库。
 * 便签正文按 data 行拆分，由 {@link SqlData} 承载。</p>
 *
 * <p>关键协作类：{@link SqlData}（正文/通话记录等数据子行）；
 * {@link net.micode.notes.gtask.data.Task} / {@link net.micode.notes.gtask.data.TaskList}
 * （远端表示，其 meta JSON 由本类产生）；{@link net.micode.notes.data.Notes}
 * （表列名与类型常量）。</p>
 *
 * <p>线程模型：非线程安全。实例只在同步线程内短生命周期使用；字段是数据库行缓存，
 * commit 后会整体重新加载，避免持有过期值。</p>
 */
public class SqlNote {
    private static final String TAG = SqlNote.class.getSimpleName();

    // 本地未落库的哨兵 id：SQLite 自增 id 从 1 开始，负数不会与真实 id 冲突；
    // commit() 插入前会把这个值从 ContentValues 中移除，交给数据库分配。
    private static final int INVALID_ID = -99999;

    // 查询 note 表的固定列集合；下方 *_COLUMN 常量是它在 Cursor 中的下标。
    // 两者必须同步维护：任何列顺序调整都会让按位置取值的代码整体错位。
    public static final String[] PROJECTION_NOTE = new String[] {
            NoteColumns.ID, NoteColumns.ALERTED_DATE, NoteColumns.BG_COLOR_ID,
            NoteColumns.CREATED_DATE, NoteColumns.HAS_ATTACHMENT, NoteColumns.MODIFIED_DATE,
            NoteColumns.NOTES_COUNT, NoteColumns.PARENT_ID, NoteColumns.SNIPPET, NoteColumns.TYPE,
            NoteColumns.WIDGET_ID, NoteColumns.WIDGET_TYPE, NoteColumns.SYNC_ID,
            NoteColumns.LOCAL_MODIFIED, NoteColumns.ORIGIN_PARENT_ID, NoteColumns.GTASK_ID,
            NoteColumns.VERSION
    };

    // Cursor 列下标：与 PROJECTION_NOTE 一一对应。用常量而非字面量，
    // 让 GTaskManager 等调用方按语义取列，避免散落的魔法数字。
    public static final int ID_COLUMN = 0;

    public static final int ALERTED_DATE_COLUMN = 1;

    public static final int BG_COLOR_ID_COLUMN = 2;

    public static final int CREATED_DATE_COLUMN = 3;

    public static final int HAS_ATTACHMENT_COLUMN = 4;

    public static final int MODIFIED_DATE_COLUMN = 5;

    public static final int NOTES_COUNT_COLUMN = 6;

    public static final int PARENT_ID_COLUMN = 7;

    public static final int SNIPPET_COLUMN = 8;

    public static final int TYPE_COLUMN = 9;

    public static final int WIDGET_ID_COLUMN = 10;

    public static final int WIDGET_TYPE_COLUMN = 11;

    public static final int SYNC_ID_COLUMN = 12;

    public static final int LOCAL_MODIFIED_COLUMN = 13;

    public static final int ORIGIN_PARENT_ID_COLUMN = 14;

    public static final int GTASK_ID_COLUMN = 15;

    public static final int VERSION_COLUMN = 16;

    // 本地数据库访问入口；构造后固定不变，不随同步过程切换。
    private Context mContext;

    private ContentResolver mContentResolver;

    // true 表示该对象尚未在 note 表中存在：所有字段都会进入差异集合，
    // commit() 走 insert 分支；落库成功后置 false。
    private boolean mIsCreate;

    // 本地 note 表主键；INVALID_ID 表示尚未插入。
    private long mId;

    private long mAlertDate;

    private int mBgColorId;

    private long mCreatedDate;

    private int mHasAttachment;

    private long mModifiedDate;

    // 父文件夹 id：0 为根文件夹（Notes.ID_ROOT_FOLDER），-2 为通话记录文件夹。
    private long mParentId;

    private String mSnippet;

    private int mType;

    private int mWidgetId;

    private int mWidgetType;

    private long mOriginParent;

    // 本地乐观锁版本号：每次成功提交差异后自增，并作为子 data 行提交时的父版本条件。
    private long mVersion;

    // 只记录与当前值不同的列，commit() 时一次性写入；
    // 空对象意味着无需 update，可避免无谓的 version 自增。
    private ContentValues mDiffNoteValues;

    // 便签正文的数据行缓存；文件夹/系统文件夹不会加载该列表。
    private ArrayList<SqlData> mDataList;

    /**
     * 构造一个尚未落库的新便签（默认类型为普通便签）。
     *
     * <p>所有字段取内存默认值并标记为待创建：背景色取主题默认值，
     * widget 相关字段取无效值，父文件夹暂为 0（根文件夹）。
     * 真正写库发生在 {@link #commit(boolean)}。</p>
     *
     * @param context 用于获取 ContentResolver 与默认背景色，不能为 null
     */
    public SqlNote(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mIsCreate = true;
        mId = INVALID_ID;
        mAlertDate = 0;
        mBgColorId = ResourceParser.getDefaultBgId(context);
        mCreatedDate = System.currentTimeMillis();
        mHasAttachment = 0;
        mModifiedDate = System.currentTimeMillis();
        mParentId = 0;
        mSnippet = "";
        mType = Notes.TYPE_NOTE;
        mWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;
        mOriginParent = 0;
        mVersion = 0;
        mDiffNoteValues = new ContentValues();
        mDataList = new ArrayList<SqlData>();
    }

    /**
     * 从已定位到目标行的 Cursor 构造对象（同步遍历时复用查询结果，避免按 id 再查一次）。
     *
     * <p>只复制 note 表字段；若该行是普通便签，还会按 id 载入其 data 子行。
     * Cursor 由调用方负责关闭，本构造器不持有它。</p>
     *
     * @param context 上下文
     * @param c       已 moveTo 到有效行的 note 查询结果
     */
    public SqlNote(Context context, Cursor c) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mIsCreate = false;
        loadFromCursor(c);
        mDataList = new ArrayList<SqlData>();
        if (mType == Notes.TYPE_NOTE)
            loadDataContent();
        mDiffNoteValues = new ContentValues();
    }

    /**
     * 按本地 note id 从数据库加载；id 必须已存在（含 0 根文件夹、-2 通话记录文件夹）。
     *
     * <p>查询无对应行时构造器不会兜底：{@link #loadFromCursor(long)} 仍会对空 Cursor
     * 按列取值，可能抛 CursorIndexOutOfBoundsException，因此该入口只用于确定存在的行。</p>
     *
     * @param context 上下文
     * @param id      本地 note 主键
     */
    public SqlNote(Context context, long id) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mIsCreate = false;
        loadFromCursor(id);
        mDataList = new ArrayList<SqlData>();
        if (mType == Notes.TYPE_NOTE)
            loadDataContent();
        mDiffNoteValues = new ContentValues();

    }

    /**
     * 按 id 查询 note 表并委托 {@link #loadFromCursor(Cursor)} 复制字段。
     *
     * <p>副作用：发起一次 ContentProvider 查询；Cursor 在 finally 中关闭。
     * 查询返回 null 只记警告，对象字段保持原值（调用方随后可能读到陈旧或默认数据）。</p>
     *
     * @param id 本地 note 主键
     */
    private void loadFromCursor(long id) {
        Cursor c = null;
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI, PROJECTION_NOTE, "(_id=?)",
                    new String[] {
                        String.valueOf(id)
                    }, null);
            if (c != null) {
                // 约定查询结果最多一行；这里不检查 moveToNext 的返回值，
                // 假定 id 有效（不存在的 id 会因 Cursor 未定位而抛异常）。
                c.moveToNext();
                loadFromCursor(c);
            } else {
                Log.w(TAG, "loadFromCursor: cursor = null");
            }
        } finally {
            if (c != null)
                c.close();
        }
    }

    /**
     * 把 note 行字段复制到内存，供 getter 与同步决策使用。
     *
     * <p>注意：GTASK_ID、LOCAL_MODIFIED 不缓存为字段，只在需要时经 Cursor
     * 或差异对象访问；同步相关判断仍以数据库为准。</p>
     *
     * @param c 已定位到有效行的 note 查询结果
     */
    private void loadFromCursor(Cursor c) {
        mId = c.getLong(ID_COLUMN);
        mAlertDate = c.getLong(ALERTED_DATE_COLUMN);
        mBgColorId = c.getInt(BG_COLOR_ID_COLUMN);
        mCreatedDate = c.getLong(CREATED_DATE_COLUMN);
        mHasAttachment = c.getInt(HAS_ATTACHMENT_COLUMN);
        mModifiedDate = c.getLong(MODIFIED_DATE_COLUMN);
        mParentId = c.getLong(PARENT_ID_COLUMN);
        mSnippet = c.getString(SNIPPET_COLUMN);
        mType = c.getInt(TYPE_COLUMN);
        mWidgetId = c.getInt(WIDGET_ID_COLUMN);
        mWidgetType = c.getInt(WIDGET_TYPE_COLUMN);
        mVersion = c.getLong(VERSION_COLUMN);
    }

    /**
     * 载入当前便签的 data 子行（正文、通话记录等）。
     *
     * <p>只应在类型为 {@link Notes#TYPE_NOTE} 时调用：文件夹没有 data 行。
     * 先清空缓存再逐行构造 {@link SqlData}，保证对象与数据库一致。
     * 无 data 行不算错误（空便签），仅记日志后返回。</p>
     *
     * <p>副作用：发起查询；Cursor 在 finally 中关闭。</p>
     */
    private void loadDataContent() {
        Cursor c = null;
        mDataList.clear();
        try {
            c = mContentResolver.query(Notes.CONTENT_DATA_URI, SqlData.PROJECTION_DATA,
                    "(note_id=?)", new String[] {
                        String.valueOf(mId)
                    }, null);
            if (c != null) {
                if (c.getCount() == 0) {
                    // 便签存在但正文行缺失：可能是远端刚建的空便签，按空列表处理。
                    Log.w(TAG, "it seems that the note has not data");
                    return;
                }
                while (c.moveToNext()) {
                    SqlData data = new SqlData(mContext, c);
                    mDataList.add(data);
                }
            } else {
                Log.w(TAG, "loadDataContent: cursor = null");
            }
        } finally {
            if (c != null)
                c.close();
        }
    }

    /**
     * 用远端 meta JSON 更新内存字段，并把真正变化的列登记到差异集合。
     *
     * <p>协议约定：JSON 含 {@code meta_note}（note 属性）与可选的
     * {@code meta_data}（正文行数组）。系统文件夹只记日志、不接受更新；
     * 普通文件夹只允许同步 snippet 与 type；普通便签逐字段合并，
     * 远端缺字段时回落到本地默认值（而不是保留旧值）。</p>
     *
     * <p>只有字段值发生变化（或对象尚未落库）才写入 mDiffNoteValues，
     * 这样 commit() 不会因“回写相同值”而白白抬升版本号。</p>
     *
     * @param js 远端 meta JSON
     * @return true 解析成功；false 表示 JSON 结构不合法（已记错误日志），调用方应视为同步失败
     */
    public boolean setContent(JSONObject js) {
        try {
            JSONObject note = js.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
            if (note.getInt(NoteColumns.TYPE) == Notes.TYPE_SYSTEM) {
                // 系统文件夹（根文件夹、通话记录文件夹）是本地固定结构，远端无权改写。
                Log.w(TAG, "cannot set system folder");
            } else if (note.getInt(NoteColumns.TYPE) == Notes.TYPE_FOLDER) {
                // for folder we can only update the snnipet and type
                // 文件夹没有正文、颜色、时间等属性，只同步名称（snippet）与类型，
                // 避免用远端缺省值覆盖本地文件夹的其它列。
                String snippet = note.has(NoteColumns.SNIPPET) ? note
                        .getString(NoteColumns.SNIPPET) : "";
                if (mIsCreate || !mSnippet.equals(snippet)) {
                    mDiffNoteValues.put(NoteColumns.SNIPPET, snippet);
                }
                mSnippet = snippet;

                int type = note.has(NoteColumns.TYPE) ? note.getInt(NoteColumns.TYPE)
                        : Notes.TYPE_NOTE;
                if (mIsCreate || mType != type) {
                    mDiffNoteValues.put(NoteColumns.TYPE, type);
                }
                mType = type;
            } else if (note.getInt(NoteColumns.TYPE) == Notes.TYPE_NOTE) {
                // 普通便签：逐字段合并。每个字段的模式一致——
                // 取远端值（缺失则取默认值），与当前值比较，变了才登记差异。
                JSONArray dataArray = js.getJSONArray(GTaskStringUtils.META_HEAD_DATA);
                // 远端 meta 里的 id 就是上次同步携带的本地主键，用于识别“同一条便签”。
                long id = note.has(NoteColumns.ID) ? note.getLong(NoteColumns.ID) : INVALID_ID;
                if (mIsCreate || mId != id) {
                    mDiffNoteValues.put(NoteColumns.ID, id);
                }
                mId = id;

                long alertDate = note.has(NoteColumns.ALERTED_DATE) ? note
                        .getLong(NoteColumns.ALERTED_DATE) : 0;
                if (mIsCreate || mAlertDate != alertDate) {
                    mDiffNoteValues.put(NoteColumns.ALERTED_DATE, alertDate);
                }
                mAlertDate = alertDate;

                int bgColorId = note.has(NoteColumns.BG_COLOR_ID) ? note
                        .getInt(NoteColumns.BG_COLOR_ID) : ResourceParser.getDefaultBgId(mContext);
                if (mIsCreate || mBgColorId != bgColorId) {
                    mDiffNoteValues.put(NoteColumns.BG_COLOR_ID, bgColorId);
                }
                mBgColorId = bgColorId;

                long createDate = note.has(NoteColumns.CREATED_DATE) ? note
                        .getLong(NoteColumns.CREATED_DATE) : System.currentTimeMillis();
                if (mIsCreate || mCreatedDate != createDate) {
                    mDiffNoteValues.put(NoteColumns.CREATED_DATE, createDate);
                }
                mCreatedDate = createDate;

                int hasAttachment = note.has(NoteColumns.HAS_ATTACHMENT) ? note
                        .getInt(NoteColumns.HAS_ATTACHMENT) : 0;
                if (mIsCreate || mHasAttachment != hasAttachment) {
                    mDiffNoteValues.put(NoteColumns.HAS_ATTACHMENT, hasAttachment);
                }
                mHasAttachment = hasAttachment;

                long modifiedDate = note.has(NoteColumns.MODIFIED_DATE) ? note
                        .getLong(NoteColumns.MODIFIED_DATE) : System.currentTimeMillis();
                if (mIsCreate || mModifiedDate != modifiedDate) {
                    mDiffNoteValues.put(NoteColumns.MODIFIED_DATE, modifiedDate);
                }
                mModifiedDate = modifiedDate;

                long parentId = note.has(NoteColumns.PARENT_ID) ? note
                        .getLong(NoteColumns.PARENT_ID) : 0;
                if (mIsCreate || mParentId != parentId) {
                    mDiffNoteValues.put(NoteColumns.PARENT_ID, parentId);
                }
                mParentId = parentId;

                String snippet = note.has(NoteColumns.SNIPPET) ? note
                        .getString(NoteColumns.SNIPPET) : "";
                if (mIsCreate || !mSnippet.equals(snippet)) {
                    mDiffNoteValues.put(NoteColumns.SNIPPET, snippet);
                }
                mSnippet = snippet;

                int type = note.has(NoteColumns.TYPE) ? note.getInt(NoteColumns.TYPE)
                        : Notes.TYPE_NOTE;
                if (mIsCreate || mType != type) {
                    mDiffNoteValues.put(NoteColumns.TYPE, type);
                }
                mType = type;

                int widgetId = note.has(NoteColumns.WIDGET_ID) ? note.getInt(NoteColumns.WIDGET_ID)
                        : AppWidgetManager.INVALID_APPWIDGET_ID;
                if (mIsCreate || mWidgetId != widgetId) {
                    mDiffNoteValues.put(NoteColumns.WIDGET_ID, widgetId);
                }
                mWidgetId = widgetId;

                int widgetType = note.has(NoteColumns.WIDGET_TYPE) ? note
                        .getInt(NoteColumns.WIDGET_TYPE) : Notes.TYPE_WIDGET_INVALIDE;
                if (mIsCreate || mWidgetType != widgetType) {
                    mDiffNoteValues.put(NoteColumns.WIDGET_TYPE, widgetType);
                }
                mWidgetType = widgetType;

                long originParent = note.has(NoteColumns.ORIGIN_PARENT_ID) ? note
                        .getLong(NoteColumns.ORIGIN_PARENT_ID) : 0;
                if (mIsCreate || mOriginParent != originParent) {
                    mDiffNoteValues.put(NoteColumns.ORIGIN_PARENT_ID, originParent);
                }
                mOriginParent = originParent;

                // 正文行按 data id 对齐：能匹配到既有 SqlData 就原地更新，
                // 匹配不到（远端新增）则新建对象追加。这里不删除本地多余行，
                // 删除语义由调用方根据完整列表另行处理。
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject data = dataArray.getJSONObject(i);
                    SqlData sqlData = null;
                    if (data.has(DataColumns.ID)) {
                        long dataId = data.getLong(DataColumns.ID);
                        for (SqlData temp : mDataList) {
                            if (dataId == temp.getId()) {
                                sqlData = temp;
                            }
                        }
                    }

                    if (sqlData == null) {
                        // 远端新增的正文行：先建临时对象承接字段，commit 时由父便签触发 insert。
                        sqlData = new SqlData(mContext);
                        mDataList.add(sqlData);
                    }

                    sqlData.setContent(data);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 把内存状态导出为远端 meta JSON（推送或刷新 meta 用）。
     *
     * <p>普通便签输出完整的 note 属性与全部 data 行；文件夹/系统文件夹只输出
     * id、type、snippet 三个字段，因为远端 group 只有名称和类型。
     * 尚未落库的对象没有稳定的本地 id，导出无意义，直接返回 null。</p>
     *
     * @return meta JSON；对象未落库或序列化异常时返回 null
     */
    public JSONObject getContent() {
        try {
            JSONObject js = new JSONObject();

            if (mIsCreate) {
                // 未落库对象没有本地 id，远端 meta 无法与本地行建立映射。
                Log.e(TAG, "it seems that we haven't created this in database yet");
                return null;
            }

            JSONObject note = new JSONObject();
            if (mType == Notes.TYPE_NOTE) {
                note.put(NoteColumns.ID, mId);
                note.put(NoteColumns.ALERTED_DATE, mAlertDate);
                note.put(NoteColumns.BG_COLOR_ID, mBgColorId);
                note.put(NoteColumns.CREATED_DATE, mCreatedDate);
                note.put(NoteColumns.HAS_ATTACHMENT, mHasAttachment);
                note.put(NoteColumns.MODIFIED_DATE, mModifiedDate);
                note.put(NoteColumns.PARENT_ID, mParentId);
                note.put(NoteColumns.SNIPPET, mSnippet);
                note.put(NoteColumns.TYPE, mType);
                note.put(NoteColumns.WIDGET_ID, mWidgetId);
                note.put(NoteColumns.WIDGET_TYPE, mWidgetType);
                note.put(NoteColumns.ORIGIN_PARENT_ID, mOriginParent);
                js.put(GTaskStringUtils.META_HEAD_NOTE, note);

                // 只导出已落库的正文行；getContent 返回 null 的临时数据行直接跳过。
                JSONArray dataArray = new JSONArray();
                for (SqlData sqlData : mDataList) {
                    JSONObject data = sqlData.getContent();
                    if (data != null) {
                        dataArray.put(data);
                    }
                }
                js.put(GTaskStringUtils.META_HEAD_DATA, dataArray);
            } else if (mType == Notes.TYPE_FOLDER || mType == Notes.TYPE_SYSTEM) {
                // 文件夹在远端是 group：只需携带名称（存放在 snippet）与类型。
                note.put(NoteColumns.ID, mId);
                note.put(NoteColumns.TYPE, mType);
                note.put(NoteColumns.SNIPPET, mSnippet);
                js.put(GTaskStringUtils.META_HEAD_NOTE, note);
            }

            return js;
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 修改父文件夹并登记差异（便签移动、从回收站恢复都走这里）。
     *
     * <p>只改内存与差异集合，不立即写库；commit 后才生效。</p>
     *
     * @param id 目标文件夹 id
     */
    public void setParentId(long id) {
        mParentId = id;
        mDiffNoteValues.put(NoteColumns.PARENT_ID, id);
    }

    /**
     * 记录该本地行对应的远端 gid（首次同步创建或拉取后回填）。
     *
     * <p>只写差异集合，不更新内存字段——本类没有 gid getter，
     * 后续判断一律以数据库列或 {@link Node} 对象为准。</p>
     *
     * @param gid 远端任务/任务组 id
     */
    public void setGtaskId(String gid) {
        mDiffNoteValues.put(NoteColumns.GTASK_ID, gid);
    }

    /**
     * 记录最近一次成功同步时远端的 last_modified 时间戳。
     *
     * <p>sync_id 是本地保存的“远端版本水位”，getSyncAction 用它判断
     * 远端随后是否又发生了变化；这里仅登记差异，commit 后落库。</p>
     *
     * @param syncId 远端 last_modified
     */
    public void setSyncId(long syncId) {
        mDiffNoteValues.put(NoteColumns.SYNC_ID, syncId);
    }

    /**
     * 清除本地修改标记（local_modified=0），表示本地变更已推送完毕。
     *
     * <p>同步流程在远端更新成功后调用；仅登记差异，需再 commit 才生效。</p>
     */
    public void resetLocalModified() {
        mDiffNoteValues.put(NoteColumns.LOCAL_MODIFIED, 0);
    }

    /** 返回本地 note 主键；新对象在 commit 前为 {@link #INVALID_ID}。 */
    public long getId() {
        return mId;
    }

    /** 返回父文件夹 id（0 根文件夹，-2 通话记录文件夹）。 */
    public long getParentId() {
        return mParentId;
    }

    /** 返回便签摘要或文件夹名称（note 表的 snippet 列）。 */
    public String getSnippet() {
        return mSnippet;
    }

    /** 是否为普通便签；false 表示文件夹或系统文件夹，二者不携带正文数据行。 */
    public boolean isNoteType() {
        return mType == Notes.TYPE_NOTE;
    }

    /**
     * 把累积的差异写入本地数据库，并刷新内存快照。
     *
     * <p>创建分支：insert note 行（必要时去掉占位 id，交由 SQLite 分配），
     * 从返回 Uri 解析新 id，再逐条提交 data 子行；id 非法会抛异常终止同步。
     * 更新分支：note 行差异非空时先自增本地版本，再按 validateVersion 决定是否
     * 附加版本条件更新；随后 data 子行以父版本为条件提交。</p>
     *
     * <p>正常完成后会重新从数据库加载 note 与 data，并清空差异集合，
     * 保证对象不会带着过期字段被复用；中途抛异常则不会走到这一步。</p>
     *
     * @param validateVersion true 时附加本地版本条件（同步写回本地场景），
     *                        false 时无条件更新（新建或已知独占场景）
     *
     * <p>副作用：写 note/data 表、自增 version、重新查询数据库。</p>
     * <p>异常：插入失败或解析 id 失败抛 {@link ActionFailureException}；
     * 更新时 id 非法抛 IllegalStateException。</p>
     */
    public void commit(boolean validateVersion) {
        if (mIsCreate) {
            if (mId == INVALID_ID && mDiffNoteValues.containsKey(NoteColumns.ID)) {
                // 占位 id 不能写进自增主键列，否则会占用 -99999 或让后续 id 分配异常；
                // 移除后由 SQLite 分配真实 id。
                mDiffNoteValues.remove(NoteColumns.ID);
            }

            Uri uri = mContentResolver.insert(Notes.CONTENT_NOTE_URI, mDiffNoteValues);
            try {
                // ContentResolver.insert 返回 content://.../note/<id>，第二段即新主键。
                mId = Long.valueOf(uri.getPathSegments().get(1));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Get note id error :" + e.toString());
                throw new ActionFailureException("create note failed");
            }
            if (mId == 0) {
                // id 0 被根文件夹占用，新便签拿到 0 说明插入结果异常。
                throw new IllegalStateException("Create thread id failed");
            }

            if (mType == Notes.TYPE_NOTE) {
                // 只有普通便签有正文行；父 id 已确定，子行以非校验模式落库。
                for (SqlData sqlData : mDataList) {
                    sqlData.commit(mId, false, -1);
                }
            }
        } else {
            if (mId <= 0 && mId != Notes.ID_ROOT_FOLDER && mId != Notes.ID_CALL_RECORD_FOLDER) {
                // 除根文件夹(0)与通话记录文件夹(-2)这两个内置 id 外，
                // 更新目标必须是已存在的正数 id。
                Log.e(TAG, "No such note");
                throw new IllegalStateException("Try to update note with invalid id");
            }
            if (mDiffNoteValues.size() > 0) {
                // 有差异才自增版本；空差异不写库也不抬版本，避免无意义冲突。
                mVersion ++;
                int result = 0;
                if (!validateVersion) {
                    // 不做版本校验：仅用于调用方已确认独占写的场景（如初次回填）。
                    result = mContentResolver.update(Notes.CONTENT_NOTE_URI, mDiffNoteValues, "("
                            + NoteColumns.ID + "=?)", new String[] {
                        String.valueOf(mId)
                    });
                } else {
                    // 版本闸门：NotesProvider 在每次 update note 前会先自增 version，
                    // 所以这里比较的是“本次提交推进后的版本”。若加载之后已有其它写者
                    // （UI 编辑或另一轮同步）提交过，数据库版本会超过期望值，本次写回被跳过。
                    result = mContentResolver.update(Notes.CONTENT_NOTE_URI, mDiffNoteValues, "("
                            + NoteColumns.ID + "=?) AND (" + NoteColumns.VERSION + "<=?)",
                            new String[] {
                                    String.valueOf(mId), String.valueOf(mVersion)
                            });
                }
                if (result == 0) {
                    // 更新 0 行说明条件未命中（通常是同步线程与 UI 线程并发写），
                    // 只告警不中断，后续以重新加载的数据为准。
                    Log.w(TAG, "there is no update. maybe user updates note when syncing");
                }
            }

            if (mType == Notes.TYPE_NOTE) {
                // 子行携带父 note 的版本作为条件：父行没被并发改过才允许更新。
                for (SqlData sqlData : mDataList) {
                    sqlData.commit(mId, validateVersion, mVersion);
                }
            }
        }

        // refresh local info
        // 重新读库：字段以数据库最终值为准（包括并发写入的结果与自增后的版本）。
        loadFromCursor(mId);
        if (mType == Notes.TYPE_NOTE)
            loadDataContent();

        // 差异已落库，清空避免重复写入；此后对象视为“已存在”。
        mDiffNoteValues.clear();
        mIsCreate = false;
    }
}
