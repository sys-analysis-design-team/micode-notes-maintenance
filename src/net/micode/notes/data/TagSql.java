package net.micode.notes.data;

/**
 * 标签功能的 SQL 片段与筛选条件拼装。
 *
 * <p>本类只产出字符串，不碰 Android API，因此可以在 JVM 单测里验证“标签筛选 + 原有条件”
 * 的组合结果是否仍然保持括号优先级、参数个数是否对得上。所有外部值（tag_id）一律走
 * {@code ?} 绑定参数，进 SQL 文本的只有列名与常量。</p>
 */
public final class TagSql {

    /** 标签字典表：一行一个标签名，name 唯一，避免同名重复。 */
    public static final String TABLE_TAG = "tag";

    /** 便签与标签的多对多关联表。 */
    public static final String TABLE_NOTE_TAG = "note_tag";

    public static final String COL_ID = "id";

    public static final String COL_NAME = "name";

    public static final String COL_NOTE_ID = "note_id";

    public static final String COL_TAG_ID = "tag_id";

    /**
     * 建标签表。用 IF NOT EXISTS 保证建库与升级两条路径都能安全重跑。
     */
    public static final String CREATE_TAG_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE_TAG + "("
            + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COL_NAME + " TEXT NOT NULL UNIQUE)";

    /**
     * 建关联表；复合主键天然去重，同一便签不会重复关联同一标签。
     */
    public static final String CREATE_NOTE_TAG_TABLE = "CREATE TABLE IF NOT EXISTS "
            + TABLE_NOTE_TAG + "("
            + COL_NOTE_ID + " INTEGER NOT NULL,"
            + COL_TAG_ID + " INTEGER NOT NULL,"
            + "PRIMARY KEY(" + COL_NOTE_ID + "," + COL_TAG_ID + "))";

    /**
     * 按标签反查便签（列表筛选）走的是 tag_id，必须有索引才不会全表扫。
     */
    public static final String CREATE_NOTE_TAG_INDEX = "CREATE INDEX IF NOT EXISTS note_tag_tag_id ON "
            + TABLE_NOTE_TAG + "(" + COL_TAG_ID + ")";

    /**
     * 便签被彻底删除时清掉它的标签关联。
     *
     * <p>删除便签走的是 note 表的 DELETE（回收站是软删除，只改 parent_id 不触发），
     * 交给触发器处理可以覆盖所有删除入口，不用在每个调用点补清理代码。</p>
     */
    public static final String DELETE_NOTE_TAG_ON_NOTE_DELETE_TRIGGER =
            "CREATE TRIGGER IF NOT EXISTS delete_note_tag_on_delete AFTER DELETE ON "
            + NotesDatabaseHelper.TABLE.NOTE + " BEGIN DELETE FROM " + TABLE_NOTE_TAG
            + " WHERE " + COL_NOTE_ID + " = old." + Notes.NoteColumns.ID + "; END";

    /**
     * 列表筛选片段：只保留带指定标签的便签。
     *
     * <p>外层查询的是 note 表，主键列名是 {@code _id}；{@code note_id} 只存在于关联表，
     * 所以只在子查询里用 {@link #COL_NOTE_ID}，外层必须用 {@link Notes.NoteColumns#ID}。</p>
     */
    public static final String NOTE_HAS_TAG = Notes.NoteColumns.ID + " IN (SELECT " + COL_NOTE_ID
            + " FROM " + TABLE_NOTE_TAG + " WHERE " + COL_TAG_ID + " = ?)";

    private TagSql() {
    }

    /**
     * 把“文件夹/搜索条件”和“标签条件”用 AND 连起来，两边都加括号。
     *
     * <p>原有条件里含 OR（例如根目录的 {@code (A AND B) OR C}），不加括号会被 AND 抢走优先级；
     * 这是标签筛选最容易出错的地方，因此组合逻辑单独抽出来做单测。</p>
     *
     * @param baseSelection 原有 WHERE 片段，不能为空
     * @return 组合后的 WHERE 片段，比原来多一个 {@code ?} 占位符
     */
    public static String withTag(String baseSelection) {
        return "(" + baseSelection + ") AND " + NOTE_HAS_TAG;
    }

    /**
     * 在原有参数末尾追加标签 ID，顺序与 {@link #withTag(String)} 里的占位符一致。
     *
     * @param baseArgs 原有参数，可为 null 或空数组
     * @param tagId    标签主键，调用方需保证是数字字符串
     */
    public static String[] withTagArgs(String[] baseArgs, String tagId) {
        if (baseArgs == null || baseArgs.length == 0) {
            return new String[] { tagId };
        }
        String[] merged = new String[baseArgs.length + 1];
        System.arraycopy(baseArgs, 0, merged, 0, baseArgs.length);
        merged[baseArgs.length] = tagId;
        return merged;
    }
}
