package net.micode.notes.data;

import net.micode.notes.data.Notes.NoteColumns;

/** 定义便签列表允许的排序方式。偏好只保存枚举名称，查询使用固定的列名。 */
public enum NoteListSort {
    MODIFIED_NEWEST(NoteColumns.MODIFIED_DATE, "DESC"),
    MODIFIED_OLDEST(NoteColumns.MODIFIED_DATE, "ASC"),
    CREATED_NEWEST(NoteColumns.CREATED_DATE, "DESC"),
    CREATED_OLDEST(NoteColumns.CREATED_DATE, "ASC");

    private final String dateColumn;
    private final String direction;

    NoteListSort(String dateColumn, String direction) {
        this.dateColumn = dateColumn;
        this.direction = direction;
    }

    public static NoteListSort fromPreference(String value) {
        for (NoteListSort mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        return MODIFIED_NEWEST;
    }

    public String toSql() {
        // 先按条目类型和置顶状态分组，再按选定时间排序。同一时间用 ID 决定顺序。
        return NoteColumns.TYPE + " DESC," + NoteColumns.PINNED + " DESC,"
                + dateColumn + " " + direction + "," + NoteColumns.ID + " " + direction;
    }
}
