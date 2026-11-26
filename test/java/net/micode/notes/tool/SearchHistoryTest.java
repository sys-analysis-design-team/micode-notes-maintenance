package net.micode.notes.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SearchHistory 的 JVM 单元测试：顺序、去重、上限、序列化容错。
 *
 * <p>只测静态逻辑部分；SharedPreferences 的读写是薄封装，由设备上的集成验证覆盖。</p>
 */
public class SearchHistoryTest {

    @Test
    public void insertPutsNewKeywordFirst() {
        List<String> history = SearchHistory.insert(Arrays.asList("b", "a"), "c");
        assertEquals(Arrays.asList("c", "b", "a"), history);
    }

    @Test
    public void insertMovesDuplicatedKeywordToFrontWithoutDuplicating() {
        List<String> history = SearchHistory.insert(Arrays.asList("b", "a", "c"), "a");
        assertEquals(Arrays.asList("a", "b", "c"), history);
    }

    @Test
    public void insertKeepsAtMostTenEntries() {
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < SearchHistory.MAX_SIZE; i++) {
            existing.add("k" + i);
        }
        List<String> history = SearchHistory.insert(existing, "new");

        assertEquals(SearchHistory.MAX_SIZE, history.size());
        assertEquals("new", history.get(0));
        // 最旧的一条（k9）被挤掉，其余保持原有顺序
        assertFalse(history.contains("k9"));
        assertEquals("k8", history.get(history.size() - 1));
    }

    @Test
    public void insertIgnoresBlankKeyword() {
        assertTrue(SearchHistory.insert(Arrays.asList("a"), "").isEmpty());
        assertTrue(SearchHistory.insert(Arrays.asList("a"), null).isEmpty());
    }

    @Test
    public void encodeDecodeRoundTripKeepsSpecialCharacters() {
        List<String> history = Arrays.asList("a,b", "引号\"测试", "空格 关键词");
        assertEquals(history, SearchHistory.decode(SearchHistory.encode(history)));
    }

    @Test
    public void decodeToleratesBrokenStorage() {
        assertTrue(SearchHistory.decode(null).isEmpty());
        assertTrue(SearchHistory.decode("").isEmpty());
        assertTrue(SearchHistory.decode("not-json").isEmpty());
        assertTrue(SearchHistory.decode("[1,2]").contains("1"));
    }

    @Test
    public void decodeCapsLegacyOversizedHistory() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tooMany.add("k" + i);
        }
        assertEquals(SearchHistory.MAX_SIZE,
                SearchHistory.decode(SearchHistory.encode(tooMany)).size());
    }
}
