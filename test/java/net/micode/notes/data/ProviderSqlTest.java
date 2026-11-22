package net.micode.notes.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * ProviderSql 的 JVM 单元测试。
 *
 * <p>重点验证两件事：调用方条件只能收窄主键查询；版本号自增语句的参数
 * 不会被拼进 SQL 文本。</p>
 */
public class ProviderSqlTest {

    @Test
    public void itemSelectionWithoutExtraCondition() {
        assertEquals("id=?", ProviderSql.buildItemSelection("id", null));
        assertEquals("id=?", ProviderSql.buildItemSelection("id", ""));
    }

    @Test
    public void itemSelectionWrapsCallerCondition() {
        assertEquals("id=? AND (type=?)",
                ProviderSql.buildItemSelection("id", "type=?"));
        assertEquals("id=? AND (type=? OR type=?)",
                ProviderSql.buildItemSelection("id", "type=? OR type=?"));
    }

    @Test
    public void mergeArgsPutsIdFirst() {
        assertArrayEquals(new String[] { "7" }, ProviderSql.mergeArgs("7", null));
        assertArrayEquals(new String[] { "7" }, ProviderSql.mergeArgs("7", new String[0]));
        assertArrayEquals(new String[] { "7", "note" },
                ProviderSql.mergeArgs("7", new String[] { "note" }));
    }

    @Test
    public void versionSqlWithIdOnly() {
        List<String> args = new ArrayList<String>();
        String sql = ProviderSql.buildVersionIncrementSql("note", "id", "version", 7, null, null,
                args);
        assertEquals("UPDATE note SET version=version+1 WHERE id=?", sql);
        assertEquals(1, args.size());
        assertEquals("7", args.get(0));
    }

    @Test
    public void versionSqlKeepsCallerArgumentOutOfSqlText() {
        List<String> args = new ArrayList<String>();
        String injection = "7 OR 1=1";
        String sql = ProviderSql.buildVersionIncrementSql("note", "id", "version", 7,
                "id=?", new String[] { injection }, args);

        assertEquals("UPDATE note SET version=version+1 WHERE id=? AND (id=?)", sql);
        assertFalse(sql.contains(injection));
        assertEquals(2, args.size());
        assertEquals("7", args.get(0));
        assertEquals(injection, args.get(1));
    }

    @Test
    public void versionSqlWithoutIdUsesSelection() {
        List<String> args = new ArrayList<String>();
        String sql = ProviderSql.buildVersionIncrementSql("note", "id", "version", -1,
                "type=?", new String[] { "0" }, args);
        assertEquals("UPDATE note SET version=version+1 WHERE (type=?)", sql);
        assertEquals(1, args.size());
        assertEquals("0", args.get(0));
    }

    @Test
    public void versionSqlWithoutAnyConditionHasNoWhere() {
        List<String> args = new ArrayList<String>();
        String sql = ProviderSql.buildVersionIncrementSql("note", "id", "version", -1, null, null,
                args);
        assertEquals("UPDATE note SET version=version+1", sql);
        assertTrue(args.isEmpty());
    }
}
