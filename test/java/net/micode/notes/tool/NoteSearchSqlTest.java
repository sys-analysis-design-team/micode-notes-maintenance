package net.micode.notes.tool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NoteSearchSql 的 JVM 单元测试。
 *
 * <p>重点：多关键词是 AND 且每词覆盖正文与标签；参数与占位符数量、顺序一致；
 * ORDER BY 里没有绑定占位符（ContentResolver 不支持），且关键词经过字面量转义。</p>
 */
public class NoteSearchSqlTest {

    @Test
    public void parseKeywordsSplitsTrimsAndDedupes() {
        assertEquals(Arrays.asList("工作", "报告"), NoteSearchSql.parseKeywords("  工作   报告  "));
        assertEquals(Arrays.asList("a", "b"), NoteSearchSql.parseKeywords("a b a"));
        assertTrue(NoteSearchSql.parseKeywords(null).isEmpty());
        assertTrue(NoteSearchSql.parseKeywords("   ").isEmpty());
    }

    @Test
    public void parseKeywordsCapsKeywordCount() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < NoteSearchSql.MAX_KEYWORDS + 5; i++) {
            raw.append("k").append(i).append(' ');
        }
        assertEquals(NoteSearchSql.MAX_KEYWORDS,
                NoteSearchSql.parseKeywords(raw.toString()).size());
    }

    @Test
    public void withKeywordsWrapsBaseAndAddsTwoPlaceholdersPerKeyword() {
        String base = "type=0 AND parent_id<>-3";
        String sql = NoteSearchSql.withKeywords(base, Arrays.asList("工作", "报告"));

        assertTrue(sql.startsWith("(" + base + ") AND ("));
        assertTrue(sql.contains("snippet LIKE ?"));
        assertTrue(sql.contains("note_tag"));
        // 两个关键词 → 两个分支 × 两个占位符
        assertEquals(4, countChar(sql, '?'));
        assertTrue(sql.contains(" AND ("));
    }

    @Test
    public void withKeywordArgsMatchesPlaceholderOrder() {
        assertArrayEquals(new String[] { "%工作%", "%工作%", "%报告%", "%报告%" },
                NoteSearchSql.withKeywordArgs(null, Arrays.asList("工作", "报告")));
        assertArrayEquals(new String[] { "0", "%工作%", "%工作%" },
                NoteSearchSql.withKeywordArgs(new String[] { "0" }, Collections.singletonList("工作")));
        assertArrayEquals(new String[] { "0" },
                NoteSearchSql.withKeywordArgs(new String[] { "0" }, Collections.emptyList()));
    }

    @Test
    public void likeWildcardsInUserInputAreEscaped() {
        String[] args = NoteSearchSql.withKeywordArgs(null, Collections.singletonList("100%_a"));
        assertEquals("%100\\%\\_a%", args[0]);
        // 转义后必须带 ESCAPE 子句，否则反斜杠不起作用
        assertTrue(NoteSearchSql.matchClause().contains("ESCAPE '\\'"));
    }

    @Test
    public void relevanceOrderHasNoBindPlaceholder() {
        String order = NoteSearchSql.relevanceOrder(Arrays.asList("工作"));
        assertFalse(order.contains("?"));
        assertTrue(order.endsWith(" DESC"));
        assertTrue(order.contains("THEN 4"));
        assertTrue(order.contains("THEN 2"));
        assertTrue(order.contains("THEN 1"));
    }

    @Test
    public void relevanceOrderEscapesQuotesInKeywords() {
        String order = NoteSearchSql.relevanceOrder(Collections.singletonList("a'b"));
        assertTrue(order.contains("'%a''b%'"));
        assertFalse(order.contains("'%a'b%'"));
    }

    @Test
    public void relevanceOrderWeightsTitleAboveBodyForEachKeyword() {
        List<String> keywords = Arrays.asList("工作", "报告");
        String order = NoteSearchSql.relevanceOrder(keywords);
        // 每个关键词一组 CASE，两组之间用 + 连接
        assertEquals(2, occurrences(order, "CASE WHEN snippet LIKE"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = value.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }

    private static int countChar(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }
}
