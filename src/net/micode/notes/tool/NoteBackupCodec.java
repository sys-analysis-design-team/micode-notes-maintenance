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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 便签 JSON 备份的编解码器。
 *
 * <p>格式固定为：版本号 + 导出时间 + 记录数组。解析时先完整校验，再交给恢复流程，
 * 保证坏文件不会写入数据库。</p>
 */
public final class NoteBackupCodec {

    public static final int VERSION = 1;

    private static final String KEY_VERSION = "version";

    private static final String KEY_EXPORTED_AT = "exportedAt";

    private static final String KEY_NOTES = "notes";

    private static final String KEY_ID = "id";

    private static final String KEY_TYPE = "type";

    private static final String KEY_PARENT = "parent";

    private static final String KEY_TITLE = "title";

    private static final String KEY_BG = "bg";

    private static final String KEY_CREATED = "created";

    private static final String KEY_MODIFIED = "modified";

    private static final String KEY_CONTENT = "content";

    private NoteBackupCodec() {
    }

    public static String encode(List<BackupNote> notes, long exportedAt) {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            for (BackupNote note : notes) {
                JSONObject item = new JSONObject();
                item.put(KEY_ID, note.originalId);
                item.put(KEY_TYPE, note.type);
                item.put(KEY_PARENT, note.parentOriginalId);
                item.put(KEY_TITLE, note.title);
                item.put(KEY_BG, note.bgColorId);
                item.put(KEY_CREATED, note.createdDate);
                item.put(KEY_MODIFIED, note.modifiedDate);
                item.put(KEY_CONTENT, note.content);
                array.put(item);
            }
            root.put(KEY_VERSION, VERSION);
            root.put(KEY_EXPORTED_AT, exportedAt);
            root.put(KEY_NOTES, array);
        } catch (JSONException e) {
            // 字段全是基本类型，正常不会发生
            throw new IllegalStateException("Failed to encode backup", e);
        }
        return root.toString();
    }

    /**
     * 解析并完整校验备份文件。
     *
     * @throws BackupFormatException 文件不是合法的 V1 备份
     */
    public static List<BackupNote> decode(String json) throws BackupFormatException {
        if (json == null || json.trim().isEmpty()) {
            throw new BackupFormatException("备份文件为空");
        }

        final JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new BackupFormatException("不是合法的 JSON 文件", e);
        }

        if (root.optInt(KEY_VERSION, -1) != VERSION) {
            throw new BackupFormatException("不支持的备份版本");
        }

        JSONArray array = root.optJSONArray(KEY_NOTES);
        if (array == null) {
            throw new BackupFormatException("缺少 notes 数组");
        }

        List<BackupNote> result = new ArrayList<BackupNote>(array.length());
        Set<Long> ids = new HashSet<Long>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                throw new BackupFormatException("第 " + (i + 1) + " 条记录不是对象");
            }
            long id = item.optLong(KEY_ID, Long.MIN_VALUE);
            if (id == Long.MIN_VALUE) {
                throw new BackupFormatException("第 " + (i + 1) + " 条记录缺少 id");
            }
            if (!ids.add(id)) {
                throw new BackupFormatException("记录 id 重复: " + id);
            }
            int type = item.optInt(KEY_TYPE, -1);
            if (type != BackupNote.TYPE_NOTE && type != BackupNote.TYPE_FOLDER) {
                throw new BackupFormatException("第 " + (i + 1) + " 条记录类型非法: " + type);
            }
            result.add(new BackupNote(
                    id,
                    type,
                    item.optLong(KEY_PARENT, 0),
                    item.optString(KEY_TITLE, ""),
                    item.optInt(KEY_BG, 0),
                    item.optLong(KEY_CREATED, 0),
                    item.optLong(KEY_MODIFIED, 0),
                    item.optString(KEY_CONTENT, "")));
        }
        return result;
    }

    public static final class BackupFormatException extends Exception {

        public BackupFormatException(String message) {
            super(message);
        }

        public BackupFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
