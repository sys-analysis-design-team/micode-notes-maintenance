# 开源软件泛读、标注和维护报告

> 项目：MiCode/Notes（小米便签）
> 状态：草稿（第 2 章标注统计、3.3 节工具数据待补）
> 小组：袁晨昕（组长）、刘嘉、罗奕峰

## 1 开源软件的代码泛读

### 1.1 开源软件的功能描述

小米便签是小米开源的 Android 便签应用，面向「快速记录 + 轻量整理」场景，
核心功能如下：

1. **便签管理**：新建、编辑、保存、删除便签；支持普通文本与勾选清单两种模式，
   可设置字号与背景色；列表按修改时间排序，支持多选批量删除与移动。
2. **文件夹管理**：新建、重命名、删除文件夹；便签可在文件夹间移动；
   文件夹项显示便签数量与摘要。
3. **搜索**：按标题与正文摘要进行全文搜索，结果高亮显示。
4. **提醒**：为便签设置提醒时间，到点弹出闹钟提醒；支持修改与取消提醒。
5. **桌面微件**：提供 2x2 与 4x4 两种尺寸，绑定某条便签后在桌面显示摘要，
   点击进入编辑，正文修改后微件同步刷新。
6. **通话记录便签**：从通话记录创建便签时，按号码查询系统联系人显示姓名。
7. **数据导出**：把全部文本便签导出为 TXT 文件。
8. **云同步**：通过 Google Tasks 协议在本地便签与云端任务之间双向同步
   （原功能，Google 已关闭该端点，维护版将其退役）。
9. **设置**：默认文件夹、列表字体大小、联系人同步开关、关于信息。

用例模型：使用者（本地用户）与系统闹钟、桌面微件、联系人库、云端任务服务
四类外部参与者交互；用例包括上表 9 组功能，其中「提醒」与「微件」依赖系统
服务，「云同步」依赖网络服务。

### 1.2 开源软件的软件架构及各个包的作用

原始代码 39 个 Java 文件、10,001 行，按职责组织为 7 个包：

| 包 | 类数/行数 | 作用 |
|---|---|---|
| `data` | 4 / 1,019 | 数据契约 `Notes`、`ContentProvider`、SQLite 建表与 9 个触发器、联系人查询 |
| `gtask/data` | 6 / 1,571 | 同步节点模型（`Node`/`Task`/`TaskList`/`MetaData`）与 JSON↔SQL 映射 |
| `gtask/remote` | 4 / 1,636 | HTTP 客户端、双向同步状态机（`GTaskManager`）、同步服务与异步任务 |
| `gtask/exception` | 2 / 66 | 同步失败异常分类 |
| `model` | 2 / 621 | 编辑期聚合根 `WorkingNote`、差量提交 `Note` |
| `tool` | 4 / 933 | 批量数据操作 `DataUtils`、文本导出 `BackupUtils`、资源映射、协议常量 |
| `ui` | 14 / 3,930 | 列表、编辑、设置、提醒、日期选择、适配器与自定义控件 |
| `widget` | 3 / 225 | 两种尺寸的桌面小部件 |

核心数据流：

```
UI（列表/编辑）
  → WorkingNote（编辑期聚合，记录原值/新值/差量）
  → Note（差量提交）
  → ContentResolver
  → NotesProvider（CRUD/搜索）
  → NotesDatabaseHelper（建表/触发器/升级）
  → SQLite（note 表 + data 表）
```

数据库触发器在写入后维护便签摘要 `snippet` 与文件夹计数 `notes_count`，
保证批量操作后列表显示与计数一致。同步链路独立于主数据流：
设置页 → `GTaskSyncService` → `GTaskManager` → `GTaskClient` → 云端 Tasks API。

### 1.3 软件功能与类间的对应关系

| 序号 | 功能名称 | 涉及的类 | 涉及的类方法 |
|---|---|---|---|
| 1 | 新建便签 | `NotesListActivity`、`NoteEditActivity`、`WorkingNote` | `createNewNote`、`onCreate`、`createEmptyNote` |
| 2 | 编辑与保存 | `NoteEditActivity`、`WorkingNote`、`Note`、`NotesProvider` | `onPause`、`saveNote`、`commit`、`insert`/`update` |
| 3 | 便签列表 | `NotesListActivity`、`NotesListAdapter`、`NotesListItem`、`NoteItemData` | `startAsyncNotesListQuery`、`getView`、`getSnippet` |
| 4 | 搜索 | `NotesListActivity`、`NotesProvider` | `onSearchRequested`、`search` |
| 5 | 文件夹管理 | `NotesListActivity`、`FoldersListAdapter`、`DataUtils`、`NotesProvider` | `onMenuItemClick`、`moveNotesToFolder`、`delete` |
| 6 | 提醒 | `NoteEditActivity`、`AlarmReceiver`、`AlarmInitReceiver` | `setAlertDate`、`onReceive` |
| 7 | 桌面微件 | `NoteWidgetProvider`（及 2x/4x 子类）、`NoteEditActivity` | `onUpdate`、`updateWidget` |
| 8 | 通话记录便签 | `NoteItemData`、`Contact`、`Notes` | `getCallDate`、`getContact` |
| 9 | 数据导出 | `BackupUtils`、`NotesListActivity` | `exportToExternal`、`onMenuItemClick` |
| 10 | 云同步 | `NotesPreferenceActivity`、`GTaskSyncService`、`GTaskManager`、`GTaskClient` | `startSync`、`onStartCommand`、`sync`、`login` |
| 11 | 设置 | `NotesPreferenceActivity` | `onCreate`、`onPreferenceChange` |

## 2 开源软件的代码标注

> 待补：标注类数、标注行数、按包分布表（由 `tools/count_annotations.py`
> 与 `tools/count_annotation_coverage.py` 生成）。

## 3 开源软件的维护

### 3.1 维护的内容

共完成 6 项必做维护 + 1 项选做（待定），覆盖缺陷修复与功能新增：

| 序号 | 维护类别 | 名称 | 描述 |
|---|---|---|---|
| M1 | 修改缺陷 | 老工程现代化与 Android 12–17 兼容 | Gradle 9.2.1 + AGP 9 迁移；修复 ActionBar 隐藏导致菜单不可达；GTask 同步入口退役 |
| M2 | 修改缺陷 | Provider 数据安全与 SQL 参数化 | Provider 收紧为应用内访问；SQL 条件改为参数绑定；补齐 MIME 与删除条件 |
| M3 | 新增功能 | SAF 导出与 JSON 备份/恢复 | UTF-8 TXT 导出；JSON 全量备份与恢复；坏文件不改动数据 |
| M4 | 修改缺陷 | 提醒与通知重构 | 精确闹钟权限适配；通知渠道与动作；开机重建提醒 |
| M5 | 新增功能 | 回收站 | 软删除、恢复、彻底删除、清空回收站 |
| M6 | 新增功能 | AI 摘要与待办提取 | OpenAI 兼容接口 + 离线规则兜底；确认后写入；Key 不入库 |

维护代码量：6 项共 2,836 行新增、1,045 行删除，受影响 3,881 行、25 个文件
（`docs/analysis/maintenance_stats.md`）。

### 3.2 开源软件维护所产生的设计

维护不改变原始分层，在原有包内扩展：

- `data`：新增 `ProviderSql`（纯 Java，可单测的 SQL 构造器）；修改 `NotesProvider`。
- `tool`：新增 `BackupNote`、`NoteBackupCodec`（备份编解码）、`ReminderScheduler`、
  `ReminderNotifier`（提醒调度与通知）、`AiAnalysis`/`AiClient`/`AiResultParser`/
  `AiSettings`/`LocalNoteAnalyzer`（AI 分析链路），重写 `BackupUtils`。
- `ui`：新增 `TrashActivity`（回收站）、`AiSettingsActivity`（AI 配置）；
  重构 `NoteEditActivity` 的提醒与 AI 入口；列表页新增备份/恢复/回收站菜单。
- `widget`：`NoteWidgetProvider` 适配新版 PendingIntent 与导出。
- 退役：删除 `AlarmAlertActivity`（全屏提醒页），`GTaskSyncService` 等保留源码但不接入 UI。

维护后包图与设计类图待绘制（区分新增/修改类）。

### 3.3 维护代码数量及其质量情况

> 待补：维护代码量、注释覆盖率、Android Lint 与 SonarQube 结果、单元测试 32/32。

### 3.4 维护后的软件原型

> 待补：界面截图与操作流程（复用 `logs/` 证据）。

## 4 实践收获和体会

> 待补。

## 5 大模型辅助实践的认识

### 5.1 使用的大模型 CASE 工具

- Claude Code：代码阅读、维护实现、单元测试、构建与设备验证。
- ChatGPT 网页版：方案评审与 PPT/报告初稿讨论。

### 5.2 大模型辅助的实践工作

> 待补：提示词、产出、纠错案例。

### 5.3 大模型辅助实践的收获和问题

> 待补。
