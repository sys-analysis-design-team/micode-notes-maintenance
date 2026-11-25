package net.micode.notes.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NoteListSortTest {
    @Test
    public void fallsBackToLastEditedWhenNoSupportedPreferenceIsSaved() {
        assertEquals(NoteListSort.MODIFIED_NEWEST, NoteListSort.fromPreference(null));
        assertEquals(NoteListSort.MODIFIED_NEWEST, NoteListSort.fromPreference("unknown"));
    }

    @Test
    public void roundTripsEachPreference() {
        for (NoteListSort mode : NoteListSort.values()) {
            assertEquals(mode, NoteListSort.fromPreference(mode.name()));
        }
    }

    @Test
    public void sortsEachGroupBySelectedDateAndBreaksTiesWithId() {
        assertEquals("type DESC,pinned DESC,modified_date DESC,_id DESC",
                NoteListSort.MODIFIED_NEWEST.toSql());
        assertEquals("type DESC,pinned DESC,modified_date ASC,_id ASC",
                NoteListSort.MODIFIED_OLDEST.toSql());
        assertEquals("type DESC,pinned DESC,created_date DESC,_id DESC",
                NoteListSort.CREATED_NEWEST.toSql());
        assertEquals("type DESC,pinned DESC,created_date ASC,_id ASC",
                NoteListSort.CREATED_OLDEST.toSql());
    }
}
