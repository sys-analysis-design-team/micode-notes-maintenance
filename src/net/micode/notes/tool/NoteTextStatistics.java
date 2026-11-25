package net.micode.notes.tool;

/** 以 Unicode 码点计数字符，以实际换行符分隔行。 */
public final class NoteTextStatistics {
    public final int characters;
    public final int nonWhitespaceCharacters;
    public final int nonBlankLines;

    private NoteTextStatistics(int characters, int nonWhitespaceCharacters, int nonBlankLines) {
        this.characters = characters;
        this.nonWhitespaceCharacters = nonWhitespaceCharacters;
        this.nonBlankLines = nonBlankLines;
    }

    public static NoteTextStatistics from(CharSequence text) {
        int characters = 0;
        int nonWhitespaceCharacters = 0;
        int nonBlankLines = 0;
        boolean hasTextOnLine = false;

        for (int offset = 0; offset < text.length();) {
            int point = Character.codePointAt(text, offset);
            offset += Character.charCount(point);
            characters++;

            if (point == '\n' || point == '\r' || point == 0x85
                    || point == 0x2028 || point == 0x2029) {
                hasTextOnLine = false;
            } else if (!Character.isWhitespace(point) && !Character.isSpaceChar(point)) {
                nonWhitespaceCharacters++;
                if (!hasTextOnLine) {
                    nonBlankLines++;
                    hasTextOnLine = true;
                }
            }
        }

        return new NoteTextStatistics(characters, nonWhitespaceCharacters, nonBlankLines);
    }
}
