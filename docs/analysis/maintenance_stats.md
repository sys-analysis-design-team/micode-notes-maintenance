# 维护代码量统计

> 口径：`git show --numstat`，只统计 `src/` 下的 `.java` 文件；
> 「受影响行数」= 新增行 + 删除行（代码改动 churn）。

| 维护项 | 名称 | 提交 | 新增 | 删除 | 受影响行数 | 文件数 |
|---|---|---|---|---|---|---|
| M1 | 老工程现代化与 Android 12–17 兼容；GTask 退役 | `51b3d28` + `84c4916` | 161 | 465 | 626 | 7 |
| M2 | Provider 数据安全 + SQL 参数化 | `0b77758` | 154 | 48 | 202 | 2 |
| M3 | SAF 导出 + JSON 备份/恢复 | `4220fa7` | 752 | 291 | 1043 | 4 |
| M5 | 回收站 | `5586889` | 477 | 22 | 499 | 4 |
| M4 | 提醒与通知重构 | `f6c79ea` | 486 | 219 | 705 | 7 |
| M6 | AI 摘要 + 待办提取 | `dddfddc` | 806 | 0 | 806 | 9 |
| 合计 | 6 项维护 | — | 2836 | 1045 | 3881 | 25 |

## 文件明细

| 文件 | 所属维护项 | 新增 | 删除 |
|---|---|---|---|
| `src/net/micode/notes/data/Contact.java` | M1 | 20 | 14 |
| `src/net/micode/notes/gtask/remote/GTaskASyncTask.java` | M1 | 28 | 10 |
| `src/net/micode/notes/ui/AlarmInitReceiver.java` | M1 | 2 | 1 |
| `src/net/micode/notes/ui/NoteEditActivity.java` | M1 | 35 | 43 |
| `src/net/micode/notes/ui/NotesListActivity.java` | M1 | 39 | 72 |
| `src/net/micode/notes/ui/NotesPreferenceActivity.java` | M1 | 34 | 323 |
| `src/net/micode/notes/widget/NoteWidgetProvider.java` | M1 | 3 | 2 |
| `src/net/micode/notes/data/NotesProvider.java` | M2 | 57 | 48 |
| `src/net/micode/notes/data/ProviderSql.java` | M2 | 97 | 0 |
| `src/net/micode/notes/tool/BackupNote.java` | M3 | 67 | 0 |
| `src/net/micode/notes/tool/BackupUtils.java` | M3 | 306 | 261 |
| `src/net/micode/notes/tool/NoteBackupCodec.java` | M3 | 156 | 0 |
| `src/net/micode/notes/ui/NotesListActivity.java` | M3 | 223 | 30 |
| `src/net/micode/notes/tool/DataUtils.java` | M5 | 141 | 0 |
| `src/net/micode/notes/ui/NoteEditActivity.java` | M5 | 3 | 8 |
| `src/net/micode/notes/ui/NotesListActivity.java` | M5 | 6 | 14 |
| `src/net/micode/notes/ui/TrashActivity.java` | M5 | 327 | 0 |
| `src/net/micode/notes/tool/ReminderNotifier.java` | M4 | 160 | 0 |
| `src/net/micode/notes/tool/ReminderScheduler.java` | M4 | 136 | 0 |
| `src/net/micode/notes/ui/AlarmAlertActivity.java` | M4 | 0 | 158 |
| `src/net/micode/notes/ui/AlarmInitReceiver.java` | M4 | 7 | 38 |
| `src/net/micode/notes/ui/AlarmReceiver.java` | M4 | 59 | 3 |
| `src/net/micode/notes/ui/NoteEditActivity.java` | M4 | 115 | 18 |
| `src/net/micode/notes/ui/NotesListActivity.java` | M4 | 9 | 2 |
| `src/net/micode/notes/tool/AiAnalysis.java` | M6 | 60 | 0 |
| `src/net/micode/notes/tool/AiClient.java` | M6 | 157 | 0 |
| `src/net/micode/notes/tool/AiFormatException.java` | M6 | 28 | 0 |
| `src/net/micode/notes/tool/AiResultParser.java` | M6 | 112 | 0 |
| `src/net/micode/notes/tool/AiSettings.java` | M6 | 85 | 0 |
| `src/net/micode/notes/tool/LocalNoteAnalyzer.java` | M6 | 128 | 0 |
| `src/net/micode/notes/ui/AiSettingsActivity.java` | M6 | 85 | 0 |
| `src/net/micode/notes/ui/NoteEditActivity.java` | M6 | 149 | 0 |
| `src/net/micode/notes/ui/NotesListActivity.java` | M6 | 2 | 0 |
