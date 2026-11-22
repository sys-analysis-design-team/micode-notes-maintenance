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

package net.micode.notes.tool;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 便签导出与备份。
 *
 * <p>M3 维护说明：原实现直接写 {@code /sdcard} 并在主线程 IO，在 Android 10+ 分区存储下
 * 必然失败。这里改为由调用方通过 SAF 提供 {@link OutputStream}，全部 IO 放在后台线程，
 * 文本固定 UTF-8。</p>
 */
public final class BackupUtils {

    private static final String TAG = "BackupUtils";

    private static final int MAX_BG_COLOR_ID = 4;

    private static final String[] NOTE_PROJECTION = {
            NoteColumns.ID,
            NoteColumns.MODIFIED_DATE,
            NoteColumns.CREATED_DATE,
            NoteColumns.SNIPPET,
            NoteColumns.BG_COLOR_ID,
            NoteColumns.TYPE,
            NoteColumns.PARENT_ID
    };

    private static final int COL_ID = 0;

    private static final int COL_MODIFIED = 1;

    private static final int COL_CREATED = 2;

    private static final int COL_SNIPPET = 3;

    private static final int COL_BG = 4;

    private static final int COL_TYPE = 5;

    private static final int COL_PARENT = 6;

    private BackupUtils() {
    }

    /**
     * 导出人类可读的纯文本，UTF-8，不依赖外部存储权限。
     */
    public static void writeText(Context context, OutputStream out) throws IOException {
        PrintStream ps = new PrintStream(out, false, "UTF-8");
        String[] formats = context.getResources().getStringArray(R.array.format_for_exported_note);
        ContentResolver resolver = context.getContentResolver();

        Cursor folderCursor = resolver.query(Notes.CONTENT_NOTE_URI, NOTE_PROJECTION,
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(Notes.TYPE_FOLDER),
                        String.valueOf(Notes.ID_TRASH_FOLER) }, null);
        if (folderCursor != null) {
            try {
                while (folderCursor.moveToNext()) {
                    String folderName = folderCursor.getString(COL_SNIPPET);
                    if (!TextUtils.isEmpty(folderName)) {
                        ps.println(String.format(formats[0], folderName));
                    }
                    writeFolderNotes(context, ps, formats,
                            folderCursor.getLong(COL_ID));
                }
            } finally {
                folderCursor.close();
            }
        }

        Cursor rootCursor = resolver.query(Notes.CONTENT_NOTE_URI, NOTE_PROJECTION,
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "=?",
                new String[] { String.valueOf(Notes.TYPE_NOTE), "0" }, null);
        if (rootCursor != null) {
            try {
                while (rootCursor.moveToNext()) {
                    writeOneNote(context, ps, formats, rootCursor);
                }
            } finally {
                rootCursor.close();
            }
        }
        ps.flush();
    }

    private static void writeFolderNotes(Context context, PrintStream ps, String[] formats,
            long folderId) {
        Cursor cursor = context.getContentResolver().query(Notes.CONTENT_NOTE_URI,
                NOTE_PROJECTION, NoteColumns.PARENT_ID + "=?",
                new String[] { String.valueOf(folderId) }, null);
        if (cursor == null) {
            return;
        }
        try {
            while (cursor.moveToNext()) {
                if (cursor.getInt(COL_TYPE) == Notes.TYPE_NOTE) {
                    writeOneNote(context, ps, formats, cursor);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private static void writeOneNote(Context context, PrintStream ps, String[] formats,
            Cursor noteCursor) {
        long modified = noteCursor.getLong(COL_MODIFIED);
        ps.println(String.format(formats[1], DateFormat.format(
                context.getString(R.string.format_datetime_mdhm), modified)));

        Cursor dataCursor = context.getContentResolver().query(Notes.CONTENT_DATA_URI,
                new String[] { DataColumns.CONTENT, DataColumns.MIME_TYPE },
                DataColumns.NOTE_ID + "=? AND " + DataColumns.MIME_TYPE + "=?",
                new String[] { String.valueOf(noteCursor.getLong(COL_ID)),
                        DataConstants.NOTE }, null);
        if (dataCursor != null) {
            try {
                if (dataCursor.moveToFirst()) {
                    String content = dataCursor.getString(0);
                    if (!TextUtils.isEmpty(content)) {
                        ps.println(String.format(formats[2], content));
                    }
                }
            } finally {
                dataCursor.close();
            }
        }
        // 便签之间用 Unicode 行分隔符隔开，与旧版导出格式保持一致
        ps.println(' ');
    }

    /**
     * 读取用户可见的文件夹与文本便签，生成 JSON 备份及条目数。
     */
    public static BackupData buildBackup(Context context) throws IOException {
        List<BackupNote> notes = collectNotes(context);
        int folderCount = 0;
        for (BackupNote note : notes) {
            if (note.isFolder()) {
                folderCount++;
            }
        }
        String json = NoteBackupCodec.encode(notes, System.currentTimeMillis());
        return new BackupData(json, folderCount, notes.size() - folderCount);
    }

    private static List<BackupNote> collectNotes(Context context) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        List<BackupNote> result = new ArrayList<BackupNote>();

        Cursor folderCursor = resolver.query(Notes.CONTENT_NOTE_URI, NOTE_PROJECTION,
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>?",
                new String[] { String.valueOf(Notes.TYPE_FOLDER),
                        String.valueOf(Notes.ID_TRASH_FOLER) }, null);
        if (folderCursor == null) {
            throw new IOException("无法读取便签数据");
        }
        try {
            while (folderCursor.moveToNext()) {
                result.add(new BackupNote(
                        folderCursor.getLong(COL_ID),
                        BackupNote.TYPE_FOLDER,
                        0,
                        folderCursor.getString(COL_SNIPPET),
                        clampBg(folderCursor.getInt(COL_BG)),
                        folderCursor.getLong(COL_CREATED),
                        folderCursor.getLong(COL_MODIFIED),
                        ""));
            }
        } finally {
            folderCursor.close();
        }

        Cursor noteCursor = resolver.query(Notes.CONTENT_NOTE_URI, NOTE_PROJECTION,
                NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + " NOT IN (?,?)",
                new String[] { String.valueOf(Notes.TYPE_NOTE),
                        String.valueOf(Notes.ID_TRASH_FOLER),
                        String.valueOf(Notes.ID_CALL_RECORD_FOLDER) }, null);
        if (noteCursor == null) {
            throw new IOException("无法读取便签数据");
        }
        try {
            while (noteCursor.moveToNext()) {
                long id = noteCursor.getLong(COL_ID);
                result.add(new BackupNote(
                        id,
                        BackupNote.TYPE_NOTE,
                        noteCursor.getLong(COL_PARENT),
                        noteCursor.getString(COL_SNIPPET),
                        clampBg(noteCursor.getInt(COL_BG)),
                        noteCursor.getLong(COL_CREATED),
                        noteCursor.getLong(COL_MODIFIED),
                        readTextContent(resolver, id)));
            }
        } finally {
            noteCursor.close();
        }
        return result;
    }

    private static String readTextContent(ContentResolver resolver, long noteId) {
        Cursor cursor = resolver.query(Notes.CONTENT_DATA_URI,
                new String[] { DataColumns.CONTENT },
                DataColumns.NOTE_ID + "=? AND " + DataColumns.MIME_TYPE + "=?",
                new String[] { String.valueOf(noteId), DataConstants.NOTE }, null);
        if (cursor == null) {
            return "";
        }
        try {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        } finally {
            cursor.close();
        }
    }

    /**
     * 恢复 JSON 备份。解析失败时不会写入任何数据；写入中途失败则回滚本次新增的记录。
     */
    public static RestoreResult restoreJson(Context context, String json)
            throws NoteBackupCodec.BackupFormatException, IOException {
        List<BackupNote> notes = NoteBackupCodec.decode(json);
        ContentResolver resolver = context.getContentResolver();

        Map<Long, Long> folderIdMap = new HashMap<Long, Long>();
        List<Long> insertedNotes = new ArrayList<Long>();
        List<Long> insertedFolders = new ArrayList<Long>();
        int noteCount = 0;

        try {
            // 先建文件夹，保证便签能挂到新 ID
            for (BackupNote note : notes) {
                if (!note.isFolder()) {
                    continue;
                }
                long newId = insertNoteRow(resolver, note, 0, BackupNote.TYPE_FOLDER);
                folderIdMap.put(note.originalId, newId);
                insertedFolders.add(newId);
            }

            for (BackupNote note : notes) {
                if (note.isFolder()) {
                    continue;
                }
                Long mappedParent = folderIdMap.get(note.parentOriginalId);
                long parentId = mappedParent == null ? 0 : mappedParent;
                long newId = insertNoteRow(resolver, note, parentId, BackupNote.TYPE_NOTE);
                insertedNotes.add(newId);
                if (!TextUtils.isEmpty(note.content)) {
                    insertTextData(resolver, newId, note);
                }
                noteCount++;
            }
        } catch (RuntimeException | IOException e) {
            rollback(resolver, insertedNotes, insertedFolders);
            Log.e(TAG, "Restore failed, rolled back", e);
            throw new IOException("恢复失败，已回滚本次写入", e);
        }

        return new RestoreResult(insertedFolders.size(), noteCount);
    }

    private static long insertNoteRow(ContentResolver resolver, BackupNote note, long parentId,
            int type) throws IOException {
        ContentValues values = new ContentValues();
        values.put(NoteColumns.TYPE, type);
        values.put(NoteColumns.PARENT_ID, parentId);
        values.put(NoteColumns.SNIPPET, note.title);
        values.put(NoteColumns.BG_COLOR_ID, clampBg(note.bgColorId));
        values.put(NoteColumns.CREATED_DATE, note.createdDate > 0
                ? note.createdDate : System.currentTimeMillis());
        values.put(NoteColumns.MODIFIED_DATE, note.modifiedDate > 0
                ? note.modifiedDate : System.currentTimeMillis());
        values.put(NoteColumns.LOCAL_MODIFIED, 1);

        Uri uri = resolver.insert(Notes.CONTENT_NOTE_URI, values);
        if (uri == null) {
            throw new IOException("写入便签失败");
        }
        return ContentUris.parseId(uri);
    }

    private static void insertTextData(ContentResolver resolver, long noteId, BackupNote note)
            throws IOException {
        ContentValues values = new ContentValues();
        values.put(DataColumns.MIME_TYPE, DataConstants.NOTE);
        values.put(DataColumns.NOTE_ID, noteId);
        values.put(DataColumns.CONTENT, note.content);
        values.put(DataColumns.CREATED_DATE, note.createdDate > 0
                ? note.createdDate : System.currentTimeMillis());
        values.put(DataColumns.MODIFIED_DATE, note.modifiedDate > 0
                ? note.modifiedDate : System.currentTimeMillis());
        if (resolver.insert(Notes.CONTENT_DATA_URI, values) == null) {
            throw new IOException("写入便签正文失败");
        }
    }

    private static void rollback(ContentResolver resolver, List<Long> noteIds,
            List<Long> folderIds) {
        for (int i = noteIds.size() - 1; i >= 0; i--) {
            try {
                resolver.delete(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI,
                        noteIds.get(i)), null, null);
            } catch (RuntimeException e) {
                Log.e(TAG, "Rollback note failed: " + noteIds.get(i), e);
            }
        }
        for (int i = folderIds.size() - 1; i >= 0; i--) {
            try {
                resolver.delete(ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI,
                        folderIds.get(i)), null, null);
            } catch (RuntimeException e) {
                Log.e(TAG, "Rollback folder failed: " + folderIds.get(i), e);
            }
        }
    }

    private static int clampBg(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, MAX_BG_COLOR_ID);
    }

    public static final class BackupData {

        public final String json;

        public final int folderCount;

        public final int noteCount;

        BackupData(String json, int folderCount, int noteCount) {
            this.json = json;
            this.folderCount = folderCount;
            this.noteCount = noteCount;
        }
    }

    public static final class RestoreResult {

        public final int folderCount;

        public final int noteCount;

        RestoreResult(int folderCount, int noteCount) {
            this.folderCount = folderCount;
            this.noteCount = noteCount;
        }
    }
}
