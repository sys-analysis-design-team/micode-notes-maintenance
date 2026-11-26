package net.micode.notes.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import net.micode.notes.tool.TagNames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 标签的业务读写入口。
 *
 * <p>刻意不经过 {@link NotesProvider}：标签是应用内的附属数据，没有跨进程访问需求，
 * 直接用 {@link NotesDatabaseHelper} 的单例拿库可以减少对 Provider 的改动面。
 * 写操作后统一通知 note 的 ContentObserver，列表页会因此重新查询。</p>
 */
public final class TagStore {

    /** 表示“不按标签筛选”。 */
    public static final long NO_TAG = -1L;

    private TagStore() {
    }

    /**
     * 标签的只读快照，供列表对话框展示。
     */
    public static final class Tag {
        public final long id;
        public final String name;

        Tag(long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 建表 + 索引 + 删除清理触发器。建库与 v6→v7 升级共用这一份定义。
     */
    static void createTables(SQLiteDatabase db) {
        db.execSQL(TagSql.CREATE_TAG_TABLE);
        db.execSQL(TagSql.CREATE_NOTE_TAG_TABLE);
        db.execSQL(TagSql.CREATE_NOTE_TAG_INDEX);
        db.execSQL(TagSql.DELETE_NOTE_TAG_ON_NOTE_DELETE_TRIGGER);
    }

    /**
     * 写入预设标签；已存在同名标签时忽略，不会重复插入或覆盖用户数据。
     *
     * @param names 预设名（来自 string 资源，随系统语言变化）
     */
    static void seedPresets(SQLiteDatabase db, String... names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            String normalized = TagNames.normalize(name);
            if (normalized == null) {
                continue;
            }
            ContentValues values = new ContentValues();
            values.put(TagSql.COL_NAME, normalized);
            db.insertWithOnConflict(TagSql.TABLE_TAG, null, values,
                    SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    /**
     * 列出全部标签，预设先建、自定义后建，因此按 id 升序即可保持“预设在前”。
     */
    public static List<Tag> listTags(Context context) {
        List<Tag> tags = new ArrayList<>();
        try (Cursor cursor = db(context).query(TagSql.TABLE_TAG,
                new String[] { TagSql.COL_ID, TagSql.COL_NAME },
                null, null, null, null, TagSql.COL_ID + " ASC")) {
            while (cursor.moveToNext()) {
                tags.add(new Tag(cursor.getLong(0), cursor.getString(1)));
            }
        }
        return tags;
    }

    /**
     * 某个便签当前关联的标签 id。
     */
    public static List<Long> tagIdsOfNote(Context context, long noteId) {
        List<Long> ids = new ArrayList<>();
        if (noteId <= 0) {
            return ids;
        }
        try (Cursor cursor = db(context).query(TagSql.TABLE_NOTE_TAG,
                new String[] { TagSql.COL_TAG_ID },
                TagSql.COL_NOTE_ID + "=?", new String[] { String.valueOf(noteId) },
                null, null, TagSql.COL_TAG_ID + " ASC")) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        return ids;
    }

    /**
     * 某个便签的标签名，按标签 id 顺序返回，供编辑页顶部展示。
     */
    public static List<String> tagNamesOfNote(Context context, long noteId) {
        List<String> names = new ArrayList<>();
        if (noteId <= 0) {
            return names;
        }
        String sql = "SELECT t." + TagSql.COL_NAME
                + " FROM " + TagSql.TABLE_NOTE_TAG + " nt JOIN " + TagSql.TABLE_TAG + " t"
                + " ON nt." + TagSql.COL_TAG_ID + " = t." + TagSql.COL_ID
                + " WHERE nt." + TagSql.COL_NOTE_ID + " = ?"
                + " ORDER BY t." + TagSql.COL_ID + " ASC";
        try (Cursor cursor = db(context).rawQuery(sql,
                new String[] { String.valueOf(noteId) })) {
            while (cursor.moveToNext()) {
                names.add(cursor.getString(0));
            }
        }
        return names;
    }

    /**
     * 用给定集合整体替换某个便签的标签。
     *
     * <p>先删后插放在同一事务里，避免中途失败留下“删了一半”的状态；
     * 传入空集合等价于清空标签。</p>
     */
    public static void setTagsOfNote(Context context, long noteId, Collection<Long> tagIds) {
        if (noteId <= 0) {
            return;
        }
        SQLiteDatabase database = db(context);
        database.beginTransaction();
        try {
            database.delete(TagSql.TABLE_NOTE_TAG, TagSql.COL_NOTE_ID + "=?",
                    new String[] { String.valueOf(noteId) });
            if (tagIds != null) {
                for (Long tagId : tagIds) {
                    if (tagId == null || tagId <= 0) {
                        continue;
                    }
                    ContentValues values = new ContentValues();
                    values.put(TagSql.COL_NOTE_ID, noteId);
                    values.put(TagSql.COL_TAG_ID, tagId);
                    database.insertWithOnConflict(TagSql.TABLE_NOTE_TAG, null, values,
                            SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        notifyChanged(context);
    }

    /**
     * 按名字取标签 id，没有则返回 {@link #NO_TAG}。
     */
    public static long tagIdByName(Context context, String rawName) {
        String name = TagNames.normalize(rawName);
        if (name == null) {
            return NO_TAG;
        }
        try (Cursor cursor = db(context).query(TagSql.TABLE_TAG,
                new String[] { TagSql.COL_ID },
                TagSql.COL_NAME + "=?", new String[] { name },
                null, null, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return NO_TAG;
    }

    /**
     * 新建标签；同名标签已存在时直接返回已有的那条，避免 UNIQUE 冲突。
     *
     * @return 新建或命中的标签；输入为空时返回 null
     */
    public static Tag createTag(Context context, String rawName) {
        String name = TagNames.normalize(rawName);
        if (name == null) {
            return null;
        }
        long existing = tagIdByName(context, name);
        if (existing != NO_TAG) {
            return new Tag(existing, name);
        }
        ContentValues values = new ContentValues();
        values.put(TagSql.COL_NAME, name);
        long id = db(context).insert(TagSql.TABLE_TAG, null, values);
        if (id <= 0) {
            return null;
        }
        notifyChanged(context);
        return new Tag(id, name);
    }

    private static SQLiteDatabase db(Context context) {
        return NotesDatabaseHelper.getInstance(context).getWritableDatabase();
    }

    private static void notifyChanged(Context context) {
        context.getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
    }
}
