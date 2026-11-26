package net.micode.notes.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * TagNames 的 JVM 单元测试：规范化、多标签解析去重、展示拼接。
 */
public class TagNamesTest {

    @Test
    public void normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("工作", TagNames.normalize("  工作  "));
        assertEquals("reading list", TagNames.normalize("reading   list"));
        assertEquals("a b", TagNames.normalize("a\tb\n"));
    }

    @Test
    public void normalizeRejectsBlankInput() {
        assertNull(TagNames.normalize(null));
        assertNull(TagNames.normalize(""));
        assertNull(TagNames.normalize("   \t\n "));
    }

    @Test
    public void normalizeTruncatesLongName() {
        String longName = "abcdefghijklmnopqrstuvwxyz";
        String normalized = TagNames.normalize(longName);

        assertEquals(TagNames.MAX_LENGTH, normalized.length());
        assertTrue(longName.startsWith(normalized));
    }

    @Test
    public void parseSplitsOnChineseAndEnglishSeparators() {
        List<String> names = TagNames.parse("工作，读书;运动, 读书\n学习");

        assertEquals(Arrays.asList("工作", "读书", "运动", "学习"), names);
    }

    @Test
    public void parseDropsBlankPieces() {
        List<String> names = TagNames.parse(" , 工作 ,, ");

        assertEquals(Arrays.asList("工作"), names);
    }

    @Test
    public void parseHandlesNull() {
        assertTrue(TagNames.parse(null).isEmpty());
    }

    @Test
    public void joinUsesMiddleDotSeparator() {
        assertEquals("工作 · 学习", TagNames.join(Arrays.asList("工作", "学习")));
        assertEquals("工作", TagNames.join(Arrays.asList("工作")));
    }

    @Test
    public void joinReturnsEmptyStringForNoTags() {
        assertEquals("", TagNames.join(null));
        assertEquals("", TagNames.join(Arrays.asList()));
    }
}
