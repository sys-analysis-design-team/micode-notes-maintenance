package net.micode.notes.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TagSql 的 JVM 单元测试。
 *
 * <p>重点验证三件事：标签条件不会破坏原有 OR 条件的优先级；tag_id 只出现在绑定参数里，
 * 不进入 SQL 文本；参数顺序与占位符顺序一致。</p>
 */
public class TagSqlTest {

    @Test
    public void withTagKeepsBaseConditionInParentheses() {
        // 根目录条件本身含 OR，不加括号会被 AND 抢走优先级
        String base = "(type<>2 AND parent_id=?) OR (id=-2 AND notes_count>0)";
        String combined = TagSql.withTag(base);

        assertEquals("(" + base + ") AND " + TagSql.NOTE_HAS_TAG, combined);
        assertTrue(combined.startsWith("("));
        assertTrue(combined.contains(") AND "));
    }

    @Test
    public void withTagAddsExactlyOnePlaceholder() {
        // 原有条件里的占位符保留，标签条件再追加一个
        assertEquals(2, countChar(TagSql.withTag("parent_id=?"), '?'));
        assertEquals(1, countChar(TagSql.withTag("type<>2"), '?'));
    }

    @Test
    public void withTagArgsAppendsTagIdAtEnd() {
        assertArrayEquals(new String[] { "0", "3" },
                TagSql.withTagArgs(new String[] { "0" }, "3"));
        assertArrayEquals(new String[] { "3" }, TagSql.withTagArgs(null, "3"));
        assertArrayEquals(new String[] { "3" }, TagSql.withTagArgs(new String[0], "3"));
    }

    @Test
    public void tagIdIsNeverInterpolatedIntoSqlText() {
        String tagId = "3 OR 1=1";
        String combined = TagSql.withTag("parent_id=?");

        assertFalse(combined.contains(tagId));
        assertTrue(combined.contains("?"));
        assertArrayEquals(new String[] { "0", tagId },
                TagSql.withTagArgs(new String[] { "0" }, tagId));
    }

    @Test
    public void noteHasTagUsesSubqueryOnLinkTable() {
        // 外层是 note 表，主键列叫 _id；note_id 只存在于子查询里
        assertTrue(TagSql.NOTE_HAS_TAG.startsWith("_id IN (SELECT note_id FROM note_tag"));
        assertTrue(TagSql.NOTE_HAS_TAG.contains("tag_id = ?"));
    }

    @Test
    public void schemaStatementsAreIdempotentAndCascadeOnDelete() {
        // 建库与升级都会执行同一份语句，必须带 IF NOT EXISTS 才能重跑
        assertTrue(TagSql.CREATE_TAG_TABLE.contains("IF NOT EXISTS"));
        assertTrue(TagSql.CREATE_NOTE_TAG_TABLE.contains("IF NOT EXISTS"));
        assertTrue(TagSql.CREATE_NOTE_TAG_INDEX.contains("IF NOT EXISTS"));
        assertTrue(TagSql.CREATE_NOTE_TAG_TABLE.contains("PRIMARY KEY(note_id,tag_id)"));
        // 便签彻底删除时由触发器清掉关联，覆盖所有删除入口
        assertTrue(TagSql.DELETE_NOTE_TAG_ON_NOTE_DELETE_TRIGGER.contains("AFTER DELETE ON note"));
        assertTrue(TagSql.DELETE_NOTE_TAG_ON_NOTE_DELETE_TRIGGER.contains("old._id"));
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
