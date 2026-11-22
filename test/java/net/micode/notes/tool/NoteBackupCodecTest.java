package net.micode.notes.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NoteBackupCodecTest {

    @Test
    public void roundTripKeepsTextExactly() throws Exception {
        String content = "第一行\n第二行 \"quoted\" \\ slash 😀";
        BackupNote note = new BackupNote(12, BackupNote.TYPE_NOTE, 0, "标题", 3,
                1700000000000L, 1700000001000L, content);
        BackupNote folder = new BackupNote(5, BackupNote.TYPE_FOLDER, 0, "工作", 0,
                1700000002000L, 1700000003000L, "");

        String json = NoteBackupCodec.encode(Arrays.asList(folder, note), 1700000004000L);
        List<BackupNote> decoded = NoteBackupCodec.decode(json);

        assertEquals(2, decoded.size());
        assertTrue(decoded.get(0).isFolder());
        assertEquals("工作", decoded.get(0).title);
        assertEquals("标题", decoded.get(1).title);
        assertEquals(content, decoded.get(1).content);
        assertEquals(3, decoded.get(1).bgColorId);
        assertEquals(1700000001000L, decoded.get(1).modifiedDate);
    }

    @Test
    public void emptyBackupRoundTrip() throws Exception {
        String json = NoteBackupCodec.encode(new ArrayList<BackupNote>(), 1L);
        assertEquals(0, NoteBackupCodec.decode(json).size());
    }

    @Test
    public void emptyTextIsRejected() {
        assertDecodeFails("");
        assertDecodeFails("   ");
        assertDecodeFails(null);
    }

    @Test
    public void malformedJsonIsRejected() {
        assertDecodeFails("{not json");
        assertDecodeFails("[]");
    }

    @Test
    public void unsupportedVersionIsRejected() {
        assertDecodeFails("{\"version\":2,\"notes\":[]}");
    }

    @Test
    public void missingNotesArrayIsRejected() {
        assertDecodeFails("{\"version\":1}");
    }

    @Test
    public void unknownTypeIsRejected() {
        assertDecodeFails("{\"version\":1,\"notes\":[{\"id\":1,\"type\":9}]}");
    }

    @Test
    public void duplicateIdsAreRejected() {
        assertDecodeFails("{\"version\":1,\"notes\":["
                + "{\"id\":1,\"type\":0},{\"id\":1,\"type\":0}]}");
    }

    @Test
    public void missingIdIsRejected() {
        assertDecodeFails("{\"version\":1,\"notes\":[{\"type\":0}]}");
    }

    private static void assertDecodeFails(String json) {
        try {
            NoteBackupCodec.decode(json);
            fail("expected BackupFormatException for: " + json);
        } catch (NoteBackupCodec.BackupFormatException expected) {
            // expected
        }
    }
}
