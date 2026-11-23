# 标注覆盖统计

> 基线：`master`；覆盖行 = 注释块后紧邻声明的代码行（去重）。

| 包 | 已标注类数 | 注释行 | 覆盖代码行 |
|---|---|---|---|
| data | 4 | 225 | 363 |
| gtask | 12 | 1458 | 2763 |
| model | 2 | 315 | 444 |
| tool | 4 | 123 | 263 |
| ui | 13 | 846 | 1181 |
| widget | 3 | 113 | 121 |
| 合计 | 38 | 3080 | 5135 |

## 按类明细

| 类 | 包 | 注释行 | 覆盖代码行 |
|---|---|---|---|
| GTaskManager | gtask | 295 | 811 |
| GTaskClient | gtask | 261 | 510 |
| SqlNote | gtask | 193 | 415 |
| DateTimePicker | ui | 183 | 342 |
| Task | gtask | 129 | 309 |
| TaskList | gtask | 137 | 303 |
| NotesDatabaseHelper | data | 134 | 281 |
| WorkingNote | model | 195 | 244 |
| Note | model | 120 | 200 |
| NotesListAdapter | ui | 121 | 165 |
| NoteItemData | ui | 144 | 154 |
| NoteEditText | ui | 114 | 147 |
| SqlData | gtask | 77 | 123 |
| BackupUtils | tool | 18 | 99 |
| GTaskASyncTask | gtask | 96 | 97 |
| NoteWidgetProvider | widget | 69 | 95 |
| ResourceParser | tool | 81 | 92 |
| NoteEditActivity | ui | 18 | 90 |
| NotesListItem | ui | 54 | 90 |
| GTaskSyncService | gtask | 109 | 85 |
| DataUtils | tool | 10 | 70 |
| Contact | data | 38 | 55 |
| DateTimePickerDialog | ui | 69 | 53 |
| Node | gtask | 58 | 49 |
| AlarmReceiver | ui | 26 | 47 |
| MetaData | gtask | 57 | 39 |
| FoldersListAdapter | ui | 41 | 33 |
| DropdownMenu | ui | 44 | 29 |
| Notes | data | 45 | 15 |
| NotesListActivity | ui | 5 | 15 |
| NoteWidgetProvider_2x | widget | 23 | 13 |
| NoteWidgetProvider_4x | widget | 21 | 13 |
| NotesProvider | data | 8 | 12 |
| NotesPreferenceActivity | ui | 16 | 12 |
| ActionFailureException | gtask | 23 | 11 |
| NetworkFailureException | gtask | 23 | 11 |
| AlarmInitReceiver | ui | 11 | 4 |
| GTaskStringUtils | tool | 14 | 2 |
