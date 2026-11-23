# 代码标注统计

> 基线：`master`；统计方法：`git diff -U0` 新增行中注释行（`//`、`/*`、`*`）。

## 原始代码（master 基线类）

| 包 | 类数 | 已标注类数 | 新增注释行 | Javadoc 块 |
|---|---|---|---|---|
| data | 4 | 4 | 225 | 19 |
| gtask | 12 | 12 | 1458 | 210 |
| model | 2 | 2 | 315 | 47 |
| tool | 4 | 4 | 123 | 25 |
| ui | 13 | 13 | 846 | 110 |
| widget | 3 | 3 | 113 | 18 |
| 合计 | 38 | 38 | 3080 | 429 |

## 按类明细（原始类）

| 类 | 包 | 新增注释行 | Javadoc 块 |
|---|---|---|---|
| GTaskManager | gtask | 295 | 35 |
| GTaskClient | gtask | 261 | 34 |
| WorkingNote | model | 195 | 29 |
| SqlNote | gtask | 193 | 18 |
| DateTimePicker | ui | 183 | 17 |
| NoteItemData | ui | 144 | 25 |
| TaskList | gtask | 137 | 20 |
| NotesDatabaseHelper | data | 134 | 13 |
| Task | gtask | 129 | 18 |
| NotesListAdapter | ui | 121 | 17 |
| Note | model | 120 | 18 |
| NoteEditText | ui | 114 | 11 |
| GTaskSyncService | gtask | 109 | 21 |
| GTaskASyncTask | gtask | 96 | 15 |
| ResourceParser | tool | 81 | 17 |
| SqlData | gtask | 77 | 8 |
| DateTimePickerDialog | ui | 69 | 8 |
| NoteWidgetProvider | widget | 69 | 8 |
| Node | gtask | 58 | 25 |
| MetaData | gtask | 57 | 8 |
| NotesListItem | ui | 54 | 5 |
| Notes | data | 45 | 3 |
| DropdownMenu | ui | 44 | 5 |
| FoldersListAdapter | ui | 41 | 8 |
| Contact | data | 38 | 2 |
| AlarmReceiver | ui | 26 | 3 |
| ActionFailureException | gtask | 23 | 4 |
| NetworkFailureException | gtask | 23 | 4 |
| NoteWidgetProvider_2x | widget | 23 | 5 |
| NoteWidgetProvider_4x | widget | 21 | 5 |
| BackupUtils | tool | 18 | 4 |
| NoteEditActivity | ui | 18 | 4 |
| NotesPreferenceActivity | ui | 16 | 4 |
| GTaskStringUtils | tool | 14 | 1 |
| AlarmInitReceiver | ui | 11 | 2 |
| DataUtils | tool | 10 | 3 |
| NotesProvider | data | 8 | 1 |
| NotesListActivity | ui | 5 | 1 |

## 维护新增类（不计入标注统计）

| 类 | 包 | 新增代码行 | 新增注释行 |
|---|---|---|---|
| ProviderSql | data | 51 | 35 |
| AiAnalysis | tool | 34 | 18 |
| AiClient | tool | 122 | 21 |
| AiFormatException | tool | 9 | 16 |
| AiResultParser | tool | 81 | 23 |
| AiSettings | tool | 51 | 21 |
| BackupNote | tool | 30 | 23 |
| LocalNoteAnalyzer | tool | 95 | 21 |
| NoteBackupCodec | tool | 103 | 27 |
| ReminderNotifier | tool | 119 | 27 |
| ReminderScheduler | tool | 95 | 30 |
| AiSettingsActivity | ui | 56 | 20 |
| TrashActivity | ui | 263 | 18 |

## 口径说明

- 新增注释行：注释文字行数，不含空行；许可证头不计（基线已有）。
- 新增代码行：维护功能新增的代码行，不属于标注统计。
- 已标注类数：相对基线至少新增 1 行注释的原始类。
- 新增类单独列出，避免把维护代码的注释算进标注工作量。
