package net.micode.notes.tool;

import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.TagSql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 站内搜索的 SQL 片段拼装（纯字符串，不依赖 Android API，可在 JVM 单测里验证）。
 *
 * <p>搜索范围是三处：便签正文（snippet 存的就是整篇正文，标题是正文首行）、
 * 标签名（tag.name）。多个关键词按空格切分，词与词之间是 AND，每个词自身是
 * “正文命中 OR 标签命中”。</p>
 *
 * <p>WHERE 里的关键词全部走 {@code ?} 绑定；只有 ORDER BY 无法绑定参数
 * （ContentResolver 的 sortOrder 是纯字符串），因此相关度表达式里的关键词要转成
 * SQL 字面量，统一经过 {@link #toLiteral(String)} 转义，单引号翻倍、LIKE 通配符转义。</p>
 */
public final class NoteSearchSql {

    /** 一次最多解析多少个关键词，防止长句拼出过深的 AND 嵌套。 */
    public static final int MAX_KEYWORDS = 8;

    /** LIKE 的转义符；模式与 ESCAPE 子句必须成对出现。 */
    private static final String LIKE_ESCAPE = " ESCAPE '\\'";

    private NoteSearchSql() {
    }

    /**
     * 把用户输入切成关键词：按空白切分、去空、按出现顺序去重，最多 {@link #MAX_KEYWORDS} 个。
     */
    public static List<String> parseKeywords(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String piece : raw.trim().split("\\s+")) {
            if (piece.isEmpty()) {
                continue;
            }
            seen.add(piece);
            if (seen.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        result.addAll(seen);
        return result;
    }

    /**
     * 在原条件（文件夹/回收站过滤）之上叠加关键词条件。
     *
     * @param baseSelection 原 WHERE 片段，不能为空
     * @param keywords      关键词，不能为空
     * @return 组合后的 WHERE，每个关键词贡献 2 个 {@code ?} 占位符
     */
    public static String withKeywords(String baseSelection, List<String> keywords) {
        StringBuilder sql = new StringBuilder("(").append(baseSelection).append(")");
        for (int i = 0; i < keywords.size(); i++) {
            sql.append(" AND (").append(matchClause()).append(")");
        }
        return sql.toString();
    }

    /**
     * 生成与 {@link #withKeywords(String, List)} 占位符顺序一致的参数：每个关键词两个
     * {@code %关键词%}（正文一次、标签名一次）。
     */
    public static String[] withKeywordArgs(String[] baseArgs, List<String> keywords) {
        int baseLength = baseArgs == null ? 0 : baseArgs.length;
        String[] merged = new String[baseLength + keywords.size() * 2];
        if (baseArgs != null) {
            System.arraycopy(baseArgs, 0, merged, 0, baseArgs.length);
        }
        int cursor = baseLength;
        for (String keyword : keywords) {
            String pattern = "%" + escapeLike(keyword) + "%";
            merged[cursor++] = pattern;
            merged[cursor++] = pattern;
        }
        return merged;
    }

    /**
     * 相关度排序表达式，调用方拼在原有排序之前：{@code relevanceOrder(kws) + "," + 原排序}。
     *
     * <p>权重设计：正文以关键词开头（等价于标题命中）4 分，正文包含 2 分，
     * 标签名命中 1 分，多关键词累加。分数相同时仍由原有排序（置顶优先、时间）决定。</p>
     *
     * @return 形如 {@code "(CASE ... + CASE ...) DESC"} 的 ORDER BY 片段
     */
    public static String relevanceOrder(List<String> keywords) {
        StringBuilder score = new StringBuilder("(");
        for (int i = 0; i < keywords.size(); i++) {
            String keyword = keywords.get(i);
            String prefix = toLiteral(escapeLike(keyword) + "%");
            String contains = toLiteral("%" + escapeLike(keyword) + "%");
            if (i > 0) {
                score.append(" + ");
            }
            score.append("CASE WHEN ").append(NoteColumns.SNIPPET).append(" LIKE ").append(prefix)
                    .append(LIKE_ESCAPE).append(" THEN 4")
                    .append(" WHEN ").append(NoteColumns.SNIPPET).append(" LIKE ").append(contains)
                    .append(LIKE_ESCAPE).append(" THEN 2 ELSE 0 END");
            score.append(" + CASE WHEN ").append(tagMatchLiteral(contains))
                    .append(" THEN 1 ELSE 0 END");
        }
        score.append(") DESC");
        return score.toString();
    }

    /**
     * 单个关键词的命中条件：正文包含，或标签名包含。
     */
    static String matchClause() {
        return NoteColumns.SNIPPET + " LIKE ?" + LIKE_ESCAPE + " OR " + tagMatchPlaceholder();
    }

    /**
     * 标签命中的子查询写法（占位符版）。
     */
    static String tagMatchPlaceholder() {
        return NoteColumns.ID + " IN (SELECT nt." + TagSql.COL_NOTE_ID + " FROM "
                + TagSql.TABLE_NOTE_TAG + " nt JOIN " + TagSql.TABLE_TAG + " t ON t."
                + TagSql.COL_ID + " = nt." + TagSql.COL_TAG_ID + " WHERE t." + TagSql.COL_NAME
                + " LIKE ?" + LIKE_ESCAPE + ")";
    }

    /**
     * 标签命中的子查询写法（字面量版，供 ORDER BY 使用）。
     */
    static String tagMatchLiteral(String patternLiteral) {
        return NoteColumns.ID + " IN (SELECT nt." + TagSql.COL_NOTE_ID + " FROM "
                + TagSql.TABLE_NOTE_TAG + " nt JOIN " + TagSql.TABLE_TAG + " t ON t."
                + TagSql.COL_ID + " = nt." + TagSql.COL_TAG_ID + " WHERE t." + TagSql.COL_NAME
                + " LIKE " + patternLiteral + LIKE_ESCAPE + ")";
    }

    /**
     * 转义 LIKE 模式里的元字符：先转义反斜杠，再转义 {@code %} 和 {@code _}。
     */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 把已转义的模式包成 SQL 字符串字面量，只负责单引号翻倍。
     */
    static String toLiteral(String pattern) {
        return "'" + pattern.replace("'", "''") + "'";
    }
}
