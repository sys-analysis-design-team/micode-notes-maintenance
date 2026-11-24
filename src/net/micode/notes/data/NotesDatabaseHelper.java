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

package net.micode.notes.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;


/**
 * 便签数据库的建库、建表、触发器与版本升级入口。
 *
 * <p>职责：继承 {@link SQLiteOpenHelper} 管理 note.db；创建 note/data 两张表、note_id 索引
 * 和一组维护派生数据的 SQLite 触发器，并在首次建库时写入四个系统文件夹。
 * 不承载业务查询，增删改查都由 {@link NotesProvider} 转交。</p>
 *
 * <p>架构位置：data 层最底层。{@link NotesProvider} 持有它的单例并通过
 * getWritableDatabase() 取库；onCreate 只在数据库文件缺失时执行，onUpgrade 按版本号
 * 逐级迁移，迁移不完整时抛异常而不是带旧结构继续运行。</p>
 *
 * <p>关键协作类：{@link NotesProvider}（唯一使用者）、{@link Notes}（列名与系统文件夹 ID）。</p>
 */
public class NotesDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "note.db";

    // 结构版本：1 初始结构、2 删表重建、3 增加 gtask_id 与回收站文件夹、4 增加 version 列。
    // 改表结构必须同步 upgradeToVx()，否则老用户会停留在旧结构上。
    private static final int DB_VERSION = 5;

    // 表名常量：建表 SQL、触发器、Provider 都从这里拼接，避免表名字符串散落各处
    public interface TABLE {
        // 便签与文件夹共用一张表，用 type 列区分（TYPE_NOTE / TYPE_FOLDER / TYPE_SYSTEM）
        public static final String NOTE = "note";

        // 便签正文、通话信息等数据行，通过 note_id 关联 note 表
        public static final String DATA = "data";
    }

    private static final String TAG = "NotesDatabaseHelper";

    // 进程内单例：SQLiteOpenHelper 每个实例各持一条连接，多实例会引入并发写锁与重复升级问题
    private static NotesDatabaseHelper mInstance;

    // note 表：便签行与文件夹行共用，靠 type 列区分。parent_id 默认 0 即根文件夹；
    // created/modified 用 strftime('%s','now')*1000 由 SQLite 生成毫秒时间戳，保证与 Java 侧同一口径；
    // widget_type 默认 -1 表示未绑定任何桌面微件。表里没有“路径”字段，层级完全由 parent_id 表达。
    private static final String CREATE_NOTE_TABLE_SQL =
        "CREATE TABLE " + TABLE.NOTE + "(" +
            NoteColumns.ID + " INTEGER PRIMARY KEY," +
            NoteColumns.PARENT_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.ALERTED_DATE + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.BG_COLOR_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            NoteColumns.HAS_ATTACHMENT + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            NoteColumns.NOTES_COUNT + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.SNIPPET + " TEXT NOT NULL DEFAULT ''," +
            NoteColumns.TYPE + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.WIDGET_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.WIDGET_TYPE + " INTEGER NOT NULL DEFAULT -1," +
            NoteColumns.SYNC_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.LOCAL_MODIFIED + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.ORIGIN_PARENT_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.GTASK_ID + " TEXT NOT NULL DEFAULT ''," +
            NoteColumns.VERSION + " INTEGER NOT NULL DEFAULT 0," +
            // v5 起：置顶标志，0 普通 / 1 置顶；老数据默认 0
            NoteColumns.PINNED + " INTEGER NOT NULL DEFAULT 0" +
        ")";

    // data 表：一条便签可有多行数据，用 mime_type 区分业务类型；data1~data5 是多态列——
    // 正文行的 data1 存清单模式（TextNote.MODE），通话行的 data1/data3 存通话时间与号码。
    // 列类型故意放宽，具体含义由上层按 mime_type 解析，数据库层不做约束。
    private static final String CREATE_DATA_TABLE_SQL =
        "CREATE TABLE " + TABLE.DATA + "(" +
            DataColumns.ID + " INTEGER PRIMARY KEY," +
            DataColumns.MIME_TYPE + " TEXT NOT NULL," +
            DataColumns.NOTE_ID + " INTEGER NOT NULL DEFAULT 0," +
            NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
            DataColumns.CONTENT + " TEXT NOT NULL DEFAULT ''," +
            DataColumns.DATA1 + " INTEGER," +
            DataColumns.DATA2 + " INTEGER," +
            DataColumns.DATA3 + " TEXT NOT NULL DEFAULT ''," +
            DataColumns.DATA4 + " TEXT NOT NULL DEFAULT ''," +
            DataColumns.DATA5 + " TEXT NOT NULL DEFAULT ''" +
        ")";

    // 便签详情页每次都按 note_id 拉取该便签的全部 data 行，属于高频查询；
    // 不建索引时随着便签量增长会退化为全表扫描
    private static final String CREATE_DATA_NOTE_ID_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS note_id_index ON " +
        TABLE.DATA + "(" + DataColumns.NOTE_ID + ");";

    // 仅当 UPDATE 的 SET 子句里出现 parent_id 时触发，即使值没变也会加 1；
    // 原样写回同一个 parent_id 会虚增计数，移动便签时必须确保新旧文件夹确实不同。
    /**
     * Increase folder's note count when move note to the folder
     */
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER increase_folder_count_on_update "+
        " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
        "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
        " END";

    // 与上一条成对出现：改 parent_id 时新父 +1、旧父 -1；NOTES_COUNT>0 防止计数减成负数。
    /**
     * Decrease folder's note count when move note from folder
     */
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER decrease_folder_count_on_update " +
        " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
        "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
        "  AND " + NoteColumns.NOTES_COUNT + ">0" + ";" +
        " END";

    // 新增行时给父文件夹计数 +1；新建一条根级便签同样会把根文件夹的计数加 1。
    /**
     * Increase folder's note count when insert new note to the folder
     */
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER =
        "CREATE TRIGGER increase_folder_count_on_insert " +
        " AFTER INSERT ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
        "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
        " END";

    // 硬删除行时给旧父减 1；正常删除走回收站软删除，只有彻底清除时才经过这里。
    /**
     * Decrease folder's note count when delete note from the folder
     */
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER =
        "CREATE TRIGGER decrease_folder_count_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN " +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
        "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
        "  AND " + NoteColumns.NOTES_COUNT + ">0;" +
        " END";

    // 正文实际存在 data 表，插入 NOTE 类型的 data 行后回写 note.snippet，
    // 列表页只查 note 表就能显示摘要，不必逐条去 join data。
    /**
     * Update note's content when insert data with type {@link DataConstants#NOTE}
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER =
        "CREATE TRIGGER update_note_content_on_insert " +
        " AFTER INSERT ON " + TABLE.DATA +
        " WHEN new." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT +
        "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
        " END";

    // 条件只看 old.mime_type：若一行从文本便签改成其他类型，仍会按旧类型更新摘要。
    /**
     * Update note's content when data with {@link DataConstants#NOTE} type has changed
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER =
        "CREATE TRIGGER update_note_content_on_update " +
        " AFTER UPDATE ON " + TABLE.DATA +
        " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT +
        "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
        " END";

    // 删除正文行时清空 snippet，避免列表页残留已删除的内容。
    /**
     * Update note's content when data with {@link DataConstants#NOTE} type has deleted
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER =
        "CREATE TRIGGER update_note_content_on_delete " +
        " AFTER delete ON " + TABLE.DATA +
        " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.SNIPPET + "=''" +
        "  WHERE " + NoteColumns.ID + "=old." + DataColumns.NOTE_ID + ";" +
        " END";

    // 删除 note 行时级联清掉它的 data 行，由数据库保证不留孤儿数据。
    /**
     * Delete datas belong to note which has been deleted
     */
    private static final String NOTE_DELETE_DATA_ON_DELETE_TRIGGER =
        "CREATE TRIGGER delete_data_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN" +
        "  DELETE FROM " + TABLE.DATA +
        "   WHERE " + DataColumns.NOTE_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    // 删除文件夹行时级联删除其直接子行；SQLite 默认不开启递归触发器，
    // 孙级节点不会被这一条带走，所以彻底删除文件夹前要先把子便签移走。
    /**
     * Delete notes belong to folder which has been deleted
     */
    private static final String FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER =
        "CREATE TRIGGER folder_delete_notes_on_delete " +
        " AFTER DELETE ON " + TABLE.NOTE +
        " BEGIN" +
        "  DELETE FROM " + TABLE.NOTE +
        "   WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    // 文件夹被移入回收站（parent_id 改为 -3）时，把它的直接子行也改为 -3；
    // 同样只处理一层：嵌套子文件夹内部的便签不会跟着走。
    /**
     * Move notes belong to folder which has been moved to trash folder
     */
    private static final String FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER =
        "CREATE TRIGGER folder_move_notes_on_trash " +
        " AFTER UPDATE ON " + TABLE.NOTE +
        " WHEN new." + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
        " BEGIN" +
        "  UPDATE " + TABLE.NOTE +
        "   SET " + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
        "  WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
        " END";

    /**
     * 创建 helper 实例；数据库文件实际在第一次 getWritableDatabase() 时才建立。
     *
     * @param context 用于定位应用私有 databases 目录，实际传入的是 Provider 的 Context
     */
    public NotesDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    /**
     * 建 note 表并补齐它的触发器与系统文件夹。
     *
     * <p>顺序有依赖：先建表，触发器才能引用表；系统文件夹依赖表已存在。
     * 声明为 public 是为了让 {@link #upgradeToV2} 复用，正常业务代码不应调用。</p>
     *
     * @param db 已打开的可写库；SQLiteOpenHelper 会在事务中回调 onCreate/onUpgrade
     *
     * <p>副作用：执行 DDL 并写入 4 条系统文件夹记录。</p>
     */
    public void createNoteTable(SQLiteDatabase db) {
        db.execSQL(CREATE_NOTE_TABLE_SQL);
        reCreateNoteTableTriggers(db);
        createSystemFolder(db);
        Log.d(TAG, "note table has been created");
    }

    /**
     * 先删后建 note 表相关的 7 个触发器。
     *
     * <p>用 DROP TRIGGER IF EXISTS 保证幂等：建库、v1→v2 重建表、v2→v3 升级后都能安全重跑，
     * 不会因同名触发器已存在而报错。触发器只影响之后的写入，不会回补已有数据。</p>
     *
     * <p>副作用：修改库结构；调用方应保证此时没有并发写入。</p>
     */
    private void reCreateNoteTableTriggers(SQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS increase_folder_count_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS decrease_folder_count_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS decrease_folder_count_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS delete_data_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS increase_folder_count_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS folder_delete_notes_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS folder_move_notes_on_trash");

        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_DELETE_DATA_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER);
        db.execSQL(FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER);
        db.execSQL(FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER);
    }

    /**
     * 写入四个系统文件夹，ID 固定为 0/-1/-2/-3，类型统一为 {@link Notes#TYPE_SYSTEM}。
     *
     * <p>0 根文件夹（默认归属）、-1 临时文件夹（移动便签的中转站）、-2 通话记录、
     * -3 回收站。只在首次建库或 v1→v2 重建时执行一次；重复执行会因主键冲突失败，
     * 因此不能拿它当“确保存在”的幂等操作。</p>
     *
     * <p>副作用：向 note 表插入 4 行。</p>
     */
    private void createSystemFolder(SQLiteDatabase db) {
        // 四个文件夹共用一份 ContentValues；每次插入前必须 clear()，否则上一行的 ID 会残留导致主键冲突
        ContentValues values = new ContentValues();

        // ID 由常量写死（0/-1/-2/-3），不能交给自增：移动便签、回收站、通话记录的代码直接引用这些值
        /**
         * call record foler for call notes
         */
        values.put(NoteColumns.ID, Notes.ID_CALL_RECORD_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * root folder which is default folder
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_ROOT_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * temporary folder which is used for moving note
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TEMPARAY_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * create trash folder
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    /**
     * 建 data 表、重建它的 3 个触发器，并为 note_id 建索引。
     *
     * @param db 已打开的可写库
     *
     * <p>副作用：执行 DDL；不写入业务数据。</p>
     */
    public void createDataTable(SQLiteDatabase db) {
        db.execSQL(CREATE_DATA_TABLE_SQL);
        reCreateDataTableTriggers(db);
        db.execSQL(CREATE_DATA_NOTE_ID_INDEX_SQL);
        Log.d(TAG, "data table has been created");
    }

    /**
     * 重建 data 表上维护 note.snippet 的 3 个触发器（插入/更新/删除各一个）。
     */
    private void reCreateDataTableTriggers(SQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_delete");

        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER);
    }

    /**
     * 取进程内单例。
     *
     * <p>synchronized 保证多线程首次取用时只创建一个 helper，避免两份连接各自触发
     * onCreate/onUpgrade 造成竞争。</p>
     *
     * @param context 任意 Context；helper 只取数据库路径，实际传入的是 Provider 的 Context
     */
    static synchronized NotesDatabaseHelper getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NotesDatabaseHelper(context);
        }
        return mInstance;
    }

    /**
     * 数据库文件首次创建时回调：建两张表。
     *
     * <p>SQLiteOpenHelper 会把本次回调包在事务里，中途抛异常整体回滚，不会留下半张表。</p>
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        createNoteTable(db);
        createDataTable(db);
    }

    /**
     * 按版本号逐级迁移数据库结构。
     *
     * <p>每个分支只处理一个版本并手动推进 oldVersion，最后统一校验是否到达 newVersion；
     * v1 的迁移逻辑重建出的是当前版本的完整结构，因此用 skipV2 跳过重复执行。</p>
     *
     * @param oldVersion 当前库的结构版本；同一份代码可能面对 1~3 任意起点
     * @param newVersion 目标版本，即 {@link #DB_VERSION}
     *
     * <p>异常：迁移没有推进到目标版本时抛 IllegalStateException，宁可启动失败也不带着
     * 旧结构继续运行；具体 ALTER/DROP 语句也可能抛 SQLiteException。</p>
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 两个开关控制迁移路径：reCreateTriggers 表示升级后要重建触发器，
        // skipV2 表示 v1 分支已经顺带完成了 v3 的结构变更
        boolean reCreateTriggers = false;
        boolean skipV2 = false;

        if (oldVersion == 1) {
            upgradeToV2(db);
            // v1→v2 是删表重建，重建结果已包含 gtask_id、version 列和回收站，
            // 相当于顺带完成了 V3 的变更，所以后面必须跳过 V3 分支
            skipV2 = true; // this upgrade including the upgrade from v2 to v3
            oldVersion++;
        }

        // v2→v3 动了表结构（新增列），升级完成后统一重建触发器，保证定义与最新列一致
        if (oldVersion == 2 && !skipV2) {
            upgradeToV3(db);
            reCreateTriggers = true;
            oldVersion++;
        }

        if (oldVersion == 3) {
            upgradeToV4(db);
            oldVersion++;
        }

        if (oldVersion == 4) {
            upgradeToV5(db);
            oldVersion++;
        }

        if (reCreateTriggers) {
            reCreateNoteTableTriggers(db);
            reCreateDataTableTriggers(db);
        }

        // 版本号没有推进到 newVersion 说明迁移分支缺失，直接抛异常而不是带病运行；
        // 注意：v1 分支只把 oldVersion 推到 2 且跳过后续分支，在 DB_VERSION=5 下无法走到这里，
        // 即 v1 直升 v5 的路径实际不可用，属于历史遗留分支
        if (oldVersion != newVersion) {
            throw new IllegalStateException("Upgrade notes database to version " + newVersion
                    + "fails");
        }
    }

    /**
     * v1→v2：删表重建（v1 结构无法平滑迁移，接受数据丢失）。
     *
     * <p>重建直接使用当前版本的 CREATE_*_SQL，升上来的库已包含 gtask_id、version 列
     * 和回收站文件夹；调用方据此跳过 V3 分支，避免对已存在的列重复 ALTER。</p>
     */
    private void upgradeToV2(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.NOTE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.DATA);
        createNoteTable(db);
        createDataTable(db);
    }

    /**
     * v2→v3：清掉废弃的 modified_date 触发器，补 gtask_id 列，并插入回收站系统文件夹。
     */
    private void upgradeToV3(SQLiteDatabase db) {
        // drop unused triggers
        // v3 起 modified_date 改由上层写值，旧触发器必须清掉，避免同一列被两处维护
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_update");
        // add a column for gtask id
        // ALTER TABLE 只能追加带默认值的列；GTASK_ID 默认空串表示尚未关联 Google Task
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.GTASK_ID
                + " TEXT NOT NULL DEFAULT ''");
        // add a trash system folder
        // 老库没有回收站文件夹，这里补一行；新库由 createSystemFolder 建好，不会走到这里
        ContentValues values = new ContentValues();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    /**
     * v3→v4：追加 version 列，记录同步版本号（Google Task 侧冲突检测用）。
     */
    private void upgradeToV4(SQLiteDatabase db) {
        // 默认 0 表示从未同步过；列只增不改，老数据自然取默认值
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.VERSION
                + " INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * v4→v5：追加 pinned 列，支持便签置顶（M8）。
     *
     * <p>纯新增列，不改动任何触发器定义；老数据默认 0（未置顶），升级后行为与升级前一致。</p>
     */
    private void upgradeToV5(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.PINNED
                + " INTEGER NOT NULL DEFAULT 0");
    }
}
