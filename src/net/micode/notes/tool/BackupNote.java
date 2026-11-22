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

/**
 * 备份文件中的一条记录。
 *
 * <p>只包含用户可见的文件夹与文本便签，不包含系统文件夹、回收站和通话记录。
 * 字段使用原始数据库 ID，恢复时再重新映射。</p>
 */
public final class BackupNote {

    public static final int TYPE_NOTE = 0;

    public static final int TYPE_FOLDER = 1;

    public final long originalId;

    public final int type;

    public final long parentOriginalId;

    /** 文件夹名；对便签是标题摘要。 */
    public final String title;

    public final int bgColorId;

    public final long createdDate;

    public final long modifiedDate;

    /** 文本便签正文；文件夹为空串。 */
    public final String content;

    public BackupNote(long originalId, int type, long parentOriginalId, String title,
            int bgColorId, long createdDate, long modifiedDate, String content) {
        if (type != TYPE_NOTE && type != TYPE_FOLDER) {
            throw new IllegalArgumentException("Unsupported note type: " + type);
        }
        this.originalId = originalId;
        this.type = type;
        this.parentOriginalId = parentOriginalId;
        this.title = title == null ? "" : title;
        this.bgColorId = bgColorId;
        this.createdDate = createdDate;
        this.modifiedDate = modifiedDate;
        this.content = content == null ? "" : content;
    }

    public boolean isFolder() {
        return type == TYPE_FOLDER;
    }
}
