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
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;

import java.util.ArrayList;


/**
 * 单条便签的脏值容器与提交器。
 *
 * <p>职责：缓存 note 表字段（{@link #setNoteValue}）和 data 表字段（{@link #setTextData}、
 * {@link #setCallData}）的改动，在 {@link #syncNote} 时统一写入 ContentProvider；
 * 同时负责为新便签申请主键。不做查询，也不持有 Cursor。</p>
 *
 * <p>架构位置：model 层，被 {@link WorkingNote} 组合使用。WorkingNote 负责交互语义，
 * 本类只关心“哪些字段脏了、怎么落库”。</p>
 *
 * <p>关键协作类：{@link NotesProvider}（实际执行 insert/update）、
 * {@link WorkingNote}（唯一调用方）。</p>
 */
public class Note {
    // note 表待写字段；非空即表示 note 行有本地修改
    private ContentValues mNoteDiffValues;
    // data 表的待写字段，按正文/通话两类分开缓存
    private NoteData mNoteData;
    private static final String TAG = "Note";
    // 先插入一行只有元数据的便签换取自增 ID：data 行必须先有 note_id 才能写入；
    // LOCAL_MODIFIED=1 让同步模块知道这条新记录尚未上传
    /**
     * Create a new note id for adding a new note to databases
     */
    public static synchronized long getNewNoteId(Context context, long folderId) {
        // Create a new note in the database
        ContentValues values = new ContentValues();
        long createdTime = System.currentTimeMillis();
        values.put(NoteColumns.CREATED_DATE, createdTime);
        values.put(NoteColumns.MODIFIED_DATE, createdTime);
        values.put(NoteColumns.TYPE, Notes.TYPE_NOTE);
        values.put(NoteColumns.LOCAL_MODIFIED, 1);
        values.put(NoteColumns.PARENT_ID, folderId);
        Uri uri = context.getContentResolver().insert(Notes.CONTENT_NOTE_URI, values);

        long noteId = 0;
        // Provider 返回的 URI 形如 content://authority/note/{id}，第二段即自增主键
        try {
            noteId = Long.valueOf(uri.getPathSegments().get(1));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Get note id error :" + e.toString());
            noteId = 0;
        }
        // -1 不是合法主键，出现即说明插入结果异常，直接中止而不是继续写非法外键
        if (noteId == -1) {
            throw new IllegalStateException("Wrong note id:" + noteId);
        }
        return noteId;
    }

    /**
     * 创建空的差异容器；此时不触碰数据库，等 setXxx 积累改动后由 syncNote 提交。
     */
    public Note() {
        mNoteDiffValues = new ContentValues();
        mNoteData = new NoteData();
    }

    /**
     * 记录 note 表某列的改动。
     *
     * <p>除目标列外还会把 LOCAL_MODIFIED 置 1、MODIFIED_DATE 更新为当前时间：
     * 这两列是同步模块判断“本地有新改动”的依据，任何字段改动都必须一并带上。</p>
     */
    public void setNoteValue(String key, String value) {
        mNoteDiffValues.put(key, value);
        mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
        mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
    }

    /**
     * 记录正文 data 行某列的改动（内容或清单模式）。
     */
    public void setTextData(String key, String value) {
        mNoteData.setTextData(key, value);
    }

    /**
     * 回填正文 data 行主键；加载已有便签时用，必须 >0。
     */
    public void setTextDataId(long id) {
        mNoteData.setTextDataId(id);
    }

    /**
     * 正文 data 行主键；0 表示尚未创建。
     */
    public long getTextDataId() {
        return mNoteData.mTextDataId;
    }

    /**
     * 回填通话 data 行主键；加载已有通话便签时用，必须 >0。
     */
    public void setCallDataId(long id) {
        mNoteData.setCallDataId(id);
    }

    /**
     * 记录通话 data 行某列的改动（通话时间或号码）。
     */
    public void setCallData(String key, String value) {
        mNoteData.setCallData(key, value);
    }

    /**
     * 是否还有未提交的改动：note 字段或任一 data 字段非空即算脏。
     */
    public boolean isLocalModified() {
        return mNoteDiffValues.size() > 0 || mNoteData.isLocalModified();
    }

    /**
     * 把缓存的改动写回 ContentProvider。
     *
     * <p>顺序：先更新 note 行，再提交 data 行。note 行更新失败只记日志并继续——
     * 原注释的意图是“宁可 note 元数据丢一次，也不能让正文写不进去”；代价是
     * mNoteDiffValues 随后被 clear，失败的部分不会自动重试。</p>
     *
     * @param noteId 已落库的主键，必须 >0
     * @return true 表示无需写或 data 提交成功；false 表示 data 提交失败
     *         （首次保存只有 insert 时，pushIntoContentResolver 也会返回 null 得到 false，
     *         但 WorkingNote.saveNote 并未使用这个返回值）
     *
     * <p>异常：noteId ≤ 0 抛 IllegalArgumentException；Provider 级异常在
     * NoteData#pushIntoContentResolver 内被捕获并转成 null。</p>
     */
    public boolean syncNote(Context context, long noteId) {
        if (noteId <= 0) {
            throw new IllegalArgumentException("Wrong note id:" + noteId);
        }

        // 没有任何脏值时不发无意义的 update
        if (!isLocalModified()) {
            return true;
        }

        // note 行与 data 行分两次提交，不在同一事务里；note 更新失败仍继续，避免正文被挡下
        /**
         * In theory, once data changed, the note should be updated on {@link NoteColumns#LOCAL_MODIFIED} and
         * {@link NoteColumns#MODIFIED_DATE}. For data safety, though update note fails, we also update the
         * note data info
         */
        if (context.getContentResolver().update(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), mNoteDiffValues, null,
                null) == 0) {
            Log.e(TAG, "Update note error, should not happen");
            // Do not return, fall through
        }
        // 无论 update 是否成功都清空：差异值只服务本次提交，失败不重试（见方法注释）
        mNoteDiffValues.clear();

        if (mNoteData.isLocalModified()
                && (mNoteData.pushIntoContentResolver(context, noteId) == null)) {
            return false;
        }

        return true;
    }

    /**
     * data 表的差异缓存：正文与通话记录分开存，因为两者 mime_type 不同、创建时机也不同。
     *
     * <p>data 行 ID 在第一次提交前为 0，插入成功后回填；之后的修改走批量 update。</p>
     */
    private class NoteData {
        // 正文 data 行主键；0 表示尚未创建
        private long mTextDataId;

        // 正文行的待写字段（内容、清单模式）
        private ContentValues mTextDataValues;

        // 通话 data 行主键；0 表示尚未创建
        private long mCallDataId;

        // 通话行的待写字段（通话时间、号码）
        private ContentValues mCallDataValues;

        private static final String TAG = "NoteData";

        /**
         * 初始两个主键都为 0，即两条 data 行都还不存在。
         */
        public NoteData() {
            mTextDataValues = new ContentValues();
            mCallDataValues = new ContentValues();
            mTextDataId = 0;
            mCallDataId = 0;
        }

        /**
         * 是否有任一 data 行待写；不关心 note 表字段。
         */
        boolean isLocalModified() {
            return mTextDataValues.size() > 0 || mCallDataValues.size() > 0;
        }

        /**
         * 回填正文 data 行主键。
         *
         * @param id 数据库主键，必须 >0
         *
         * <p>异常：id ≤ 0 抛 IllegalArgumentException，防止把非法主键写进后续 update。</p>
         */
        void setTextDataId(long id) {
            if(id <= 0) {
                throw new IllegalArgumentException("Text data id should larger than 0");
            }
            mTextDataId = id;
        }

        /**
         * 回填通话 data 行主键。
         *
         * @param id 数据库主键，必须 >0
         *
         * <p>异常：id ≤ 0 抛 IllegalArgumentException。</p>
         */
        void setCallDataId(long id) {
            if (id <= 0) {
                throw new IllegalArgumentException("Call data id should larger than 0");
            }
            mCallDataId = id;
        }

        /**
         * 缓存通话行字段改动。
         *
         * <p>同时把父 Note 标记为本地修改，否则 syncNote 会因无脏值直接跳过。</p>
         */
        void setCallData(String key, String value) {
            mCallDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        /**
         * 缓存正文行字段改动，并同步标记父 Note 为本地修改。
         */
        void setTextData(String key, String value) {
            mTextDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        /**
         * 提交 data 差异：主键为 0 的走 insert（立即执行），已有主键的走批量 update
         * （applyBatch 一次提交）。
         *
         * <p>非原子：正文 insert 成功后通话 insert 失败会返回 null，此时正文已落库、
         * 两个 ContentValues 也都已清空，本次保存的其余字段不会重试。</p>
         *
         * @return 有 update 批次成功时返回该便签的 URI；无事可做、全部只做了 insert
         *         或任一步失败都返回 null
         *
         * <p>异常：noteId ≤ 0 抛 IllegalArgumentException；Provider 的
         * RemoteException / OperationApplicationException 被捕获并转成 null。</p>
         */
        Uri pushIntoContentResolver(Context context, long noteId) {
            /**
             * Check for safety
             */
            if (noteId <= 0) {
                throw new IllegalArgumentException("Wrong note id:" + noteId);
            }

            ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
            ContentProviderOperation.Builder builder = null;

            // 正文行：主键为 0 说明还没建过，先 insert 再回填 ID；已有 ID 则攒成 update 操作
            if(mTextDataValues.size() > 0) {
                // 补上 note_id 与 mime_type 才构成一条完整的 data 行
                mTextDataValues.put(DataColumns.NOTE_ID, noteId);
                if (mTextDataId == 0) {
                    mTextDataValues.put(DataColumns.MIME_TYPE, TextNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mTextDataValues);
                    // 只捕获解析异常；若解析出的 ID ≤0，setTextDataId 会抛 IllegalArgumentException
                    try {
                        setTextDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new text data fail with noteId" + noteId);
                        mTextDataValues.clear();
                        return null;
                    }
                } else {
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mTextDataId));
                    builder.withValues(mTextDataValues);
                    operationList.add(builder.build());
                }
                mTextDataValues.clear();
            }

            // 通话行同理：没有主键先插入并回填，已有主键攒进同一批 update
            if(mCallDataValues.size() > 0) {
                mCallDataValues.put(DataColumns.NOTE_ID, noteId);
                if (mCallDataId == 0) {
                    mCallDataValues.put(DataColumns.MIME_TYPE, CallNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mCallDataValues);
                    try {
                        setCallDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new call data fail with noteId" + noteId);
                        mCallDataValues.clear();
                        return null;
                    }
                } else {
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mCallDataId));
                    builder.withValues(mCallDataValues);
                    operationList.add(builder.build());
                }
                mCallDataValues.clear();
            }

            // 只有 update 会进批次；本次若全部是 insert，operationList 为空
            if (operationList.size() > 0) {
                try {
                    ContentProviderResult[] results = context.getContentResolver().applyBatch(
                            Notes.AUTHORITY, operationList);
                    return (results == null || results.length == 0 || results[0] == null) ? null
                            : ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId);
                } catch (RemoteException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                } catch (OperationApplicationException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                }
            }
            // 批次失败、或没有 update 可执行时返回 null；注意“全部 insert 成功”也走这一行
            return null;
        }
    }
}
