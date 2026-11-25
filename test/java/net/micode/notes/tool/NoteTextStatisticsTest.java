package net.micode.notes.tool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NoteTextStatisticsTest {
    @Test
    public void emptyTextHasNoCharactersOrLines() {
        assertStatistics("", 0, 0, 0);
    }

    @Test
    public void whitespaceOnlyTextHasNoNonBlankLines() {
        assertStatistics(" \t\r\n\u3000\u00A0", 6, 0, 0);
    }

    @Test
    public void countsChineseAndSupplementaryCharactersByCodePoint() {
        assertStatistics("你好 😀\nA", 6, 4, 2);
    }

    @Test
    public void skipsBlankLinesAndHandlesWindowsLineEndings() {
        assertStatistics("a\r\n \r\nb\r\n", 9, 2, 2);
    }

    @Test
    public void recognizesUnicodeLineSeparators() {
        assertStatistics("a\u0085b\u2028c\u2029d", 7, 4, 4);
    }

    @Test
    public void preservesLiteralCheckboxSymbolsInText() {
        assertStatistics("□ √", 3, 2, 1);
    }

    private static void assertStatistics(String text, int characters, int nonWhitespace, int lines) {
        NoteTextStatistics counts = NoteTextStatistics.from(text);
        assertEquals(characters, counts.characters);
        assertEquals(nonWhitespace, counts.nonWhitespaceCharacters);
        assertEquals(lines, counts.nonBlankLines);
    }
}
