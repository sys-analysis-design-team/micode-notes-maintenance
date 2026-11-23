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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.NotesDatabaseHelper.TABLE;
import net.micode.notes.gtask.exception.ActionFailureException;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * 便签正文的一行数据（note 表 data 子表），承载正文或通话记录等条目。
 *
 * <p>职责：缓存 data 表一行（类型、内容、data1/data3），与 JSON 互转，
 * 并以差异方式提交到 ContentProvider。一个 {@link SqlNote} 可持有多个实例，
 * 分别对应正文分段或通话记录。</p>
 *
 * <p>架构位置：gtask.data 包的二级数据类，只被 {@link SqlNote} 创建和驱动；
 * 不参与远端协议，云端只看到一个整体便签。</p>
 *
 * <p>关键协作类：{@link SqlNote}（父行，提交时提供 noteId 与版本条件）；
 * {@link net.micode.notes.data.Notes.DataColumns} 与
 * {@link net.micode.notes.data.Notes.DataConstants}（列名与 mime 类型常量）。</p>
 */
public class SqlData {
    private static final String TAG = SqlData.class.getSimpleName();

    // 未落库哨兵 id；insert 前会从 ContentValues 移除，交给自增主键。
    private static final int INVALID_ID = -99999;

    // 查询 data 表的固定列集合，下方 *_COLUMN 常量是其 Cursor 下标，需同步维护。
    public static final String[] PROJECTION_DATA = new String[] {
            DataColumns.ID, DataColumns.MIME_TYPE, DataColumns.CONTENT, DataColumns.DATA1,
            DataColumns.DATA3
    };

    public static final int DATA_ID_COLUMN = 0;

    public static final int DATA_MIME_TYPE_COLUMN = 1;

    public static final int DATA_CONTENT_COLUMN = 2;

    public static final int DATA_CONTENT_DATA_1_COLUMN = 3;

    public static final int DATA_CONTENT_DATA_3_COLUMN = 4;

    private ContentResolver mContentResolver;

    private boolean mIsCreate;

    private long mDataId;

    private String mDataMimeType;

    private String mDataContent;

    // data1 语义随 mime 类型变化：文本便签未使用，通话记录存放通话时间。
    private long mDataContentData1;

    // data3：通话记录存放电话号码；文本便签为空串。
    private String mDataContentData3;

    // 仅登记真正变化的列，commit 时统一写入。
    private ContentValues mDiffDataValues;

    /**
     * 构造尚未落库的新数据行；默认类型为文本便签，内容为空串。
     *
     * <p>commit 前不会写库；note_id 由父 {@link SqlNote#commit(boolean)}
     * 在提交时注入。</p>
     */
    public SqlData(Context context) {
        mContentResolver = context.getContentResolver();
        mIsCreate = true;
        mDataId = INVALID_ID;
        mDataMimeType = DataConstants.NOTE;
        mDataContent = "";
        mDataContentData1 = 0;
        mDataContentData3 = "";
        mDiffDataValues = new ContentValues();
    }

    /**
     * 从已定位的 data 行构造对象（同步遍历时复用 Cursor）。
     *
     * @param c 已 moveTo 到有效行的 data 查询结果，由调用方负责关闭
     */
    public SqlData(Context context, Cursor c) {
        mContentResolver = context.getContentResolver();
        mIsCreate = false;
        loadFromCursor(c);
        mDiffDataValues = new ContentValues();
    }

    /**
     * 把 data 行列值复制到内存字段（列序见 {@link #PROJECTION_DATA}）。
     *
     * <p>不做类型转换与校验，调用方需保证 Cursor 位于有效行。</p>
     */
    private void loadFromCursor(Cursor c) {
        mDataId = c.getLong(DATA_ID_COLUMN);
        mDataMimeType = c.getString(DATA_MIME_TYPE_COLUMN);
        mDataContent = c.getString(DATA_CONTENT_COLUMN);
        mDataContentData1 = c.getLong(DATA_CONTENT_DATA_1_COLUMN);
        mDataContentData3 = c.getString(DATA_CONTENT_DATA_3_COLUMN);
    }

    /**
     * 用 JSON 更新数据行字段，并把变化的列登记到差异集合。
     *
     * <p>与 {@link SqlNote#setContent(JSONObject)} 相同的模式：字段缺失时取
     * 默认值（文本类型、空内容、data1=0、data3 空串），仅在变化或尚未落库时登记。
     * 调用方（{@link SqlNote}）负责按 id 复用对象。</p>
     *
     * @param js 远端 meta 中的单条 data JSON
     *
     * <p>异常：字段类型不合法时向上抛 {@link JSONException}，由 SqlNote 统一处理。</p>
     */
    public void setContent(JSONObject js) throws JSONException {
        long dataId = js.has(DataColumns.ID) ? js.getLong(DataColumns.ID) : INVALID_ID;
        if (mIsCreate || mDataId != dataId) {
            mDiffDataValues.put(DataColumns.ID, dataId);
        }
        mDataId = dataId;

        String dataMimeType = js.has(DataColumns.MIME_TYPE) ? js.getString(DataColumns.MIME_TYPE)
                : DataConstants.NOTE;
        if (mIsCreate || !mDataMimeType.equals(dataMimeType)) {
            mDiffDataValues.put(DataColumns.MIME_TYPE, dataMimeType);
        }
        mDataMimeType = dataMimeType;

        String dataContent = js.has(DataColumns.CONTENT) ? js.getString(DataColumns.CONTENT) : "";
        if (mIsCreate || !mDataContent.equals(dataContent)) {
            mDiffDataValues.put(DataColumns.CONTENT, dataContent);
        }
        mDataContent = dataContent;

        long dataContentData1 = js.has(DataColumns.DATA1) ? js.getLong(DataColumns.DATA1) : 0;
        if (mIsCreate || mDataContentData1 != dataContentData1) {
            mDiffDataValues.put(DataColumns.DATA1, dataContentData1);
        }
        mDataContentData1 = dataContentData1;

        String dataContentData3 = js.has(DataColumns.DATA3) ? js.getString(DataColumns.DATA3) : "";
        if (mIsCreate || !mDataContentData3.equals(dataContentData3)) {
            mDiffDataValues.put(DataColumns.DATA3, dataContentData3);
        }
        mDataContentData3 = dataContentData3;
    }

    /**
     * 把数据行导出为 meta JSON 中的 data 元素。
     *
     * <p>未落库对象没有稳定 id，返回 null（父便签导出时会跳过该条）。</p>
     *
     * @return data JSON；未落库时返回 null
     *
     * <p>异常：{@link JSONException} 由调用方（{@link SqlNote#getContent()}）捕获。</p>
     */
    public JSONObject getContent() throws JSONException {
        if (mIsCreate) {
            Log.e(TAG, "it seems that we haven't created this in database yet");
            return null;
        }
        JSONObject js = new JSONObject();
        js.put(DataColumns.ID, mDataId);
        js.put(DataColumns.MIME_TYPE, mDataMimeType);
        js.put(DataColumns.CONTENT, mDataContent);
        js.put(DataColumns.DATA1, mDataContentData1);
        js.put(DataColumns.DATA3, mDataContentData3);
        return js;
    }

    /**
     * 把数据行差异写入本地数据库。
     *
     * <p>创建分支：去掉占位 id、注入父 noteId 后 insert，并从返回 Uri 解析新 id。
     * 更新分支：按 validateVersion 决定是否附加“父便签版本匹配”条件——
     * 条件通过子查询检查父 note 的 version，父行在同步期间被改过时更新会被跳过。</p>
     *
     * <p>正常完成后会清空差异并把对象标记为已存在；更新 0 行只告警，
     * 中途抛异常则不会走到清空这一步。</p>
     *
     * @param noteId          父便签 id（创建时写入 note_id，更新时作为版本校验条件）
     * @param validateVersion true 时校验父便签版本
     * @param version         父便签当前版本（仅在 validateVersion 为 true 时使用）
     *
     * <p>异常：insert 返回的 Uri 无法解析出 id 时抛 {@link ActionFailureException}。</p>
     */
    public void commit(long noteId, boolean validateVersion, long version) {

        if (mIsCreate) {
            if (mDataId == INVALID_ID && mDiffDataValues.containsKey(DataColumns.ID)) {
                // 占位 id 不能写入自增主键列，移除后由数据库分配。
                mDiffDataValues.remove(DataColumns.ID);
            }

            // 子行必须挂在父便签下；note_id 只能在父行 commit 拿到 id 后注入。
            mDiffDataValues.put(DataColumns.NOTE_ID, noteId);
            Uri uri = mContentResolver.insert(Notes.CONTENT_DATA_URI, mDiffDataValues);
            try {
                mDataId = Long.valueOf(uri.getPathSegments().get(1));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Get note id error :" + e.toString());
                throw new ActionFailureException("create note failed");
            }
        } else {
            if (mDiffDataValues.size() > 0) {
                int result = 0;
                if (!validateVersion) {
                    result = mContentResolver.update(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mDataId), mDiffDataValues, null, null);
                } else {
                    // 版本条件通过子查询落到父 note 行：父行版本等于期望值才允许写子行，
                    // 这样一次同步写的 note 与 data 保持同一版本快照。
                    result = mContentResolver.update(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mDataId), mDiffDataValues,
                            " ? in (SELECT " + NoteColumns.ID + " FROM " + TABLE.NOTE
                                    + " WHERE " + NoteColumns.VERSION + "=?)", new String[] {
                                    String.valueOf(noteId), String.valueOf(version)
                            });
                }
                if (result == 0) {
                    // 0 行更新通常意味着父便签在同步期间被 UI 改过，跳过本行即可。
                    Log.w(TAG, "there is no update. maybe user updates note when syncing");
                }
            }
        }

        mDiffDataValues.clear();
        mIsCreate = false;
    }

    /** 返回数据行主键；新对象在 commit 前为 INVALID_ID。 */
    public long getId() {
        return mDataId;
    }
}
