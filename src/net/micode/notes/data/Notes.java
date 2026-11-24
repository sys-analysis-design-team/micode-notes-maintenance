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

import android.net.Uri;
/**
 * 便签应用的数据契约（常量总表）。
 *
 * <p>职责：集中定义 ContentProvider 的 authority、内容 URI、笔记/文件夹/系统文件夹的
 * ID 与类型取值、Intent extra 的键名，以及 note、data 两张表的列名和两种数据子类型
 * （文本便签 {@link TextNote}、通话便签 {@link CallNote}）的 MIME 类型与列映射。</p>
 *
 * <p>架构位置：data 层的公共协议文件。{@link NotesProvider} 按这里的 URI 和列名对外提供
 * 增删改查，{@link NotesDatabaseHelper} 按列名建表与建触发器；model 层的
 * {@link net.micode.notes.model.Note}、{@link net.micode.notes.model.WorkingNote}
 * 和 UI 层都引用这些常量，避免字符串散落各处。</p>
 *
 * <p>关键协作类：{@link NotesProvider}、{@link NotesDatabaseHelper}、
 * {@link net.micode.notes.model.Note}、{@link net.micode.notes.model.WorkingNote}。</p>
 */
public class Notes {
    // ContentProvider 的唯一标识，必须与 AndroidManifest.xml 中 provider 的 android:authorities 一致
    public static final String AUTHORITY = "micode_notes";
    public static final String TAG = "Notes";
    // note 表中 type 列的取值：普通便签 / 用户文件夹 / 系统内置文件夹
    public static final int TYPE_NOTE     = 0;
    public static final int TYPE_FOLDER   = 1;
    public static final int TYPE_SYSTEM   = 2;

    // 系统文件夹的固定 ID：0 为根文件夹（默认位置），负数表示不对用户展示的内置文件夹。
    // 它们由 NotesDatabaseHelper.createSystemFolder() 在首次建库时插入，
    // 其中 ID 小于等于 0 的记录在 NotesProvider.delete() 中禁止删除。
    /**
     * Following IDs are system folders' identifiers
     * {@link Notes#ID_ROOT_FOLDER } is default folder
     * {@link Notes#ID_TEMPARAY_FOLDER } is for notes belonging no folder
     * {@link Notes#ID_CALL_RECORD_FOLDER} is to store call records
     */
    public static final int ID_ROOT_FOLDER = 0;
    public static final int ID_TEMPARAY_FOLDER = -1;
    public static final int ID_CALL_RECORD_FOLDER = -2;
    public static final int ID_TRASH_FOLER = -3;

    // 以下 extra 键用于 Activity / Service / Widget 之间传递“提醒时间、背景色、Widget 信息、
    // 所属文件夹、通话时间”等参数，统一带 net.micode.notes 前缀避免与系统或其他应用冲突
    public static final String INTENT_EXTRA_ALERT_DATE = "net.micode.notes.alert_date";
    public static final String INTENT_EXTRA_BACKGROUND_ID = "net.micode.notes.background_color_id";
    public static final String INTENT_EXTRA_WIDGET_ID = "net.micode.notes.widget_id";
    public static final String INTENT_EXTRA_WIDGET_TYPE = "net.micode.notes.widget_type";
    public static final String INTENT_EXTRA_FOLDER_ID = "net.micode.notes.folder_id";
    public static final String INTENT_EXTRA_CALL_DATE = "net.micode.notes.call_date";

    // 桌面 Widget 的尺寸类型：-1 表示无效/未绑定，0 为 2x 尺寸，1 为 4x 尺寸
    public static final int TYPE_WIDGET_INVALIDE      = -1;
    public static final int TYPE_WIDGET_2X            = 0;
    public static final int TYPE_WIDGET_4X            = 1;

    /**
     * data 表中 mime_type 列的两种业务取值。
     *
     * <p>NOTE 表示“便签正文”数据行（含文本内容与清单模式），
     * CALL_NOTE 表示“通话记录”数据行（含通话时间与号码）；
     * 两者的 MIME item 字符串分别复用 {@link TextNote}、{@link CallNote} 的定义。</p>
     */
    public static class DataConstants {
        public static final String NOTE = TextNote.CONTENT_ITEM_TYPE;
        public static final String CALL_NOTE = CallNote.CONTENT_ITEM_TYPE;
    }

    // note 表的集合 URI：查询全部便签与文件夹；单条记录用 “note/#”（见 NotesProvider 的 URI 匹配）
    /**
     * Uri to query all notes and folders
     */
    public static final Uri CONTENT_NOTE_URI = Uri.parse("content://" + AUTHORITY + "/note");

    // data 表的集合 URI：查询全部数据行；单条记录用 “data/#”
    /**
     * Uri to query data
     */
    public static final Uri CONTENT_DATA_URI = Uri.parse("content://" + AUTHORITY + "/data");

    /**
     * note 表的列定义。
     *
     * <p>note 表用一张表同时保存文件夹和便签：TYPE 列区分记录类型，PARENT_ID 指向同表 ID，
     * 从而构成“文件夹—子文件夹/便签”的树形结构。便签的正文、清单模式、通话信息不在这张表，
     * 而是拆到 data 表中，通过 data.note_id 关联。</p>
     */
    public interface NoteColumns {
        // 主键。系统文件夹使用固定 ID（0、-1、-2、-3），普通记录由 SQLite 自增分配
        /**
         * The unique ID for a row
         * <P> Type: INTEGER (long) </P>
         */
        public static final String ID = "_id";

        // 父节点 ID：文件夹指向其上级文件夹，便签指向所属文件夹；默认 0 表示根文件夹
        /**
         * The parent's id for note or folder
         * <P> Type: INTEGER (long) </P>
         */
        public static final String PARENT_ID = "parent_id";

        /**
         * Created data for note or folder
         * <P> Type: INTEGER (long) </P>
         */
        public static final String CREATED_DATE = "created_date";

        /**
         * Latest modified date
         * <P> Type: INTEGER (long) </P>
         */
        public static final String MODIFIED_DATE = "modified_date";


        /**
         * Alert date
         * <P> Type: INTEGER (long) </P>
         */
        public static final String ALERTED_DATE = "alert_date";

        // 文件夹中存名称；便签中存正文摘要。便签的摘要由 data 表触发器自动同步，
        // 不需要应用层在保存正文时手工更新（见 NotesDatabaseHelper 的 update_note_content 系列触发器）
        /**
         * Folder's name or text content of note
         * <P> Type: TEXT </P>
         */
        public static final String SNIPPET = "snippet";

        // 绑定到本条便签的桌面 Widget ID，未绑定为 0；Widget 类型见 WIDGET_TYPE
        /**
         * Note's widget id
         * <P> Type: INTEGER (long) </P>
         */
        public static final String WIDGET_ID = "widget_id";

        // Widget 类型：TYPE_WIDGET_2X / TYPE_WIDGET_4X，未绑定为 TYPE_WIDGET_INVALIDE(-1)
        /**
         * Note's widget type
         * <P> Type: INTEGER (long) </P>
         */
        public static final String WIDGET_TYPE = "widget_type";

        /**
         * Note's background color's id
         * <P> Type: INTEGER (long) </P>
         */
        public static final String BG_COLOR_ID = "bg_color_id";

        /**
         * For text note, it doesn't has attachment, for multi-media
         * note, it has at least one attachment
         * <P> Type: INTEGER </P>
         */
        public static final String HAS_ATTACHMENT = "has_attachment";

        /**
         * Folder's count of notes
         * <P> Type: INTEGER (long) </P>
         */
        public static final String NOTES_COUNT = "notes_count";

        /**
         * The file type: folder or note
         * <P> Type: INTEGER </P>
         */
        public static final String TYPE = "type";

        /**
         * The last sync id
         * <P> Type: INTEGER (long) </P>
         */
        public static final String SYNC_ID = "sync_id";

        /**
         * Sign to indicate local modified or not
         * <P> Type: INTEGER </P>
         */
        public static final String LOCAL_MODIFIED = "local_modified";

        /**
         * Original parent id before moving into temporary folder
         * <P> Type : INTEGER </P>
         */
        public static final String ORIGIN_PARENT_ID = "origin_parent_id";

        /**
         * The gtask id
         * <P> Type : TEXT </P>
         */
        public static final String GTASK_ID = "gtask_id";

        /**
         * The version code
         * <P> Type : INTEGER (long) </P>
         */
        public static final String VERSION = "version";

        /**
         * Whether the note is pinned to the top of the list
         * <P> Type : INTEGER (0 = normal, 1 = pinned) </P>
         *
         * <p>中文（M8 便签置顶）：置顶标志，数据库 v5 新增列。只对普通便签有意义，
         * 文件夹行恒为 0；列表查询按该列降序排序，置顶便签排在非置顶之前。</p>
         */
        public static final String PINNED = "pinned";
    }

    public interface DataColumns {
        /**
         * The unique ID for a row
         * <P> Type: INTEGER (long) </P>
         */
        public static final String ID = "_id";

        /**
         * The MIME type of the item represented by this row.
         * <P> Type: Text </P>
         */
        public static final String MIME_TYPE = "mime_type";

        /**
         * The reference id to note that this data belongs to
         * <P> Type: INTEGER (long) </P>
         */
        public static final String NOTE_ID = "note_id";

        /**
         * Created data for note or folder
         * <P> Type: INTEGER (long) </P>
         */
        public static final String CREATED_DATE = "created_date";

        /**
         * Latest modified date
         * <P> Type: INTEGER (long) </P>
         */
        public static final String MODIFIED_DATE = "modified_date";

        /**
         * Data's content
         * <P> Type: TEXT </P>
         */
        public static final String CONTENT = "content";


        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * integer data type
         * <P> Type: INTEGER </P>
         */
        public static final String DATA1 = "data1";

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * integer data type
         * <P> Type: INTEGER </P>
         */
        public static final String DATA2 = "data2";

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * TEXT data type
         * <P> Type: TEXT </P>
         */
        public static final String DATA3 = "data3";

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * TEXT data type
         * <P> Type: TEXT </P>
         */
        public static final String DATA4 = "data4";

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * TEXT data type
         * <P> Type: TEXT </P>
         */
        public static final String DATA5 = "data5";
    }

    public static final class TextNote implements DataColumns {
        /**
         * Mode to indicate the text in check list mode or not
         * <P> Type: Integer 1:check list mode 0: normal mode </P>
         */
        public static final String MODE = DATA1;

        public static final int MODE_CHECK_LIST = 1;

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/text_note";

        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/text_note";

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/text_note");
    }

    public static final class CallNote implements DataColumns {
        /**
         * Call date for this record
         * <P> Type: INTEGER (long) </P>
         */
        public static final String CALL_DATE = DATA1;

        /**
         * Phone number for this record
         * <P> Type: TEXT </P>
         */
        public static final String PHONE_NUMBER = DATA3;

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/call_note";

        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/call_note";

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/call_note");
    }
}
