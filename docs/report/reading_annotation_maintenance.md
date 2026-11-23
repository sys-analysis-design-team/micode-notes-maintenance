# 开源软件泛读、标注和维护报告

> 项目：MiCode/Notes（小米便签）
> 状态：初稿完成（五章齐，第 5 章可再按实际使用补充）
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
| `data` | 4 / 1,019 | 数据契约 `Notes`、`ContentProvider`、SQLite 建表与 10 个触发器、联系人查询 |
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

小组对 `data`、`gtask`、`model`、`tool`、`ui`、`widget` 六个包中共 38 个类
（现存全部原始类）的代码进行了标注，新增注释 3,080 行，覆盖代码 5,135 行。
标注按四级口径执行：类级（职责/架构位置/协作类）、方法级（参数/返回/副作用/异常）、
块级（数据库、Provider、同步逻辑的关键决策）、语句级（隐含约束与边界）。
统计由 `tools/count_annotations.py` 与 `tools/count_annotation_coverage.py` 生成，
不手工抄写。

| 包 | 已标注类数 | 注释行 | 覆盖代码行 |
|---|---|---|---|
| `data` | 4 | 225 | 363 |
| `gtask`（含 data/remote/exception） | 12 | 1,458 | 2,763 |
| `model` | 2 | 315 | 444 |
| `tool` | 4 | 123 | 263 |
| `ui` | 13 | 846 | 1,181 |
| `widget` | 3 | 113 | 121 |
| 合计 | 38 | 3,080 | 5,135 |

标注示例：

- **类级**（`NotesDatabaseHelper`）：说明该类负责建表、10 个触发器与 v1→v4 升级，
  是 `NotesProvider` 的唯一数据库入口，外部不得直接持有 `SQLiteDatabase`。
- **方法级**（`WorkingNote.saveNote`）：写明返回值语义、空便签不落库、
  `isWorthSaving` 的三类拦截，以及 `Note.syncNote` 失败不抛异常只返回 false。
- **块级**（触发器 SQL）：逐个说明 `UPDATE OF parent_id` 即使值不变也会触发、
  snippet 触发器只看旧 `mime_type`、级联删除不递归等盲区。
- **语句级**（`Contact.getContact`）：标注缓存懒初始化、`replace` 字面量替换
  两处 `+` 占位符、游标只取首行的原因。

原始类 `AlarmAlertActivity` 已因 M4 提醒重构删除，不计入标注统计。

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

| 维护项 | 新增行 | 删除行 | 受影响行数 | 文件数 |
|---|---|---|---|---|
| M1 工程现代化与兼容 | 161 | 465 | 626 | 7 |
| M2 Provider 安全 | 154 | 48 | 202 | 2 |
| M3 导出与备份 | 752 | 291 | 1,043 | 4 |
| M5 回收站 | 477 | 22 | 499 | 4 |
| M4 提醒与通知 | 486 | 219 | 705 | 7 |
| M6 AI 摘要与待办 | 806 | 0 | 806 | 9 |
| 合计 | 2,836 | 1,045 | 3,881 | 25 |

新增 13 个类、1,109 行代码，类内注释 300 行（注释率 27%）。
维护代码的质量检查：

- **单元测试**：32 条 JVM 单测全部通过（`ProviderSqlTest` 7、`NoteBackupCodecTest` 9、
  `AiResultParserTest` 8、`LocalNoteAnalyzerTest` 8），覆盖 SQL 构造、备份编解码、
  AI 结果解析与离线分析四个纯逻辑模块。
- **Android Lint**（AGP 9.0，`lintDebug`）：首轮 180 个问题（6 Error / 174 Warning），
  修复 6 个 Error 后为 0 Error / 174 Warning。3 个 Error 是原始代码遗留
  （`-View.VISIBLE` 异常写法、布局缺 orientation、4 条字符串缺中文翻译），
  已在维护中修复；剩余 Warning 以资源与国际化建议为主
  （`docs/analysis/lint/`）。
- **SonarQube**（Community Build 26.11）：维护后 42 个问题（漏洞 40、Bug 2、坏味道 0），
  安全性评级由原始代码的 D 升到 C；与原始代码对比注释密度 5.1% → 21.1%、
  漏洞 51 → 40，两个 CRITICAL（S5322/S5324）随维护修复消失
  （`docs/analysis/sonar/`）。

### 3.4 维护后的软件原型

维护版在 Pixel_7 模拟器（Android 17 / API 37）上逐项验证，关键界面截图存于
`logs/`，可作为附录材料：

| 原型界面 | 截图 | 验证内容 |
|---|---|---|
| 便签列表（维护后） | `logs/m1_final_list.png` | M1 迁移后正常启动、列表渲染与菜单可达 |
| 便签编辑 | `logs/app_editor_typed.png` | 编辑、字号/背景色、AI 与提醒入口 |
| 提醒设置 | `logs/m4_reminder_set.png` | 精确闹钟授权后的提醒时间选择 |
| 通知提醒 | `logs/m4_notification.png` | 通知渠道生效，点击回到便签 |
| 回收站 | `logs/m5_trash_list.png` | 删除的便签进入回收站而非直接消失 |
| 回收站操作 | `logs/m5_trash_actions.png` | 恢复/彻底删除/清空三个入口 |
| 恢复后列表 | `logs/m5_after_restore_list.png` | 恢复后便签回到原文件夹 |
| SAF 备份位置 | `logs/m6_backup_picker.png` | 系统文件选择器选择备份位置 |
| 恢复确认 | `logs/m3_restore_confirm.png` | 恢复前确认、坏文件不改动数据 |
| AI 分析结果 | `logs/m6_ai_result.png` | 摘要与待办列表，确认后写入 |
| AI 写入后 | `logs/m6_inserted.png` | 确认后摘要/待办出现在便签中 |

完整操作流程：新建便签 → 输入正文 → 设置提醒 → 到点收到通知 → 删除便签
（进回收站）→ 从回收站恢复 → 导出 txt / 备份 JSON → 从 JSON 恢复 →
AI 摘要与待办提取。每一步均以截图记录，异常场景（坏 JSON、空备份）单独留证。

## 4 实践收获和体会

**读懂老代码要从「跑起来」开始。** 原始工程用 Eclipse ADT 结构且没有构建脚本，
直接读代码很容易迷路。先把工程迁到 Gradle/AGP 9、在 Android 17 模拟器上跑通，
再回头读，才分得清哪些是设计意图、哪些是死代码（如已下线的 Google Tasks 链路）。

**标注是理解的倒逼机制。** 给每个类写「职责、协作类」，给每个方法写
「参数、返回、副作用」，逼着我们不放过任何一条隐含约束；本报告 3.4 节外的
一批上游缺陷（`TaskList` 前驱指向自身、`ResourceParser` 兜底返回索引常量、
`DateTimePicker` 24 小时制折算等）都是标注时发现的，纯浏览很难看出来。

**维护要跟着平台演进的真实约束走。** targetSdk 缺失、`exported` 必填、
PendingIntent 可变性、通知渠道、精确闹钟、分区存储，每一项都是 Android
近几年新增的硬性要求，逐项修复的过程基本覆盖了 Android 应用兼容适配的主要知识点。

**测试是维护的安全网。** 把 SQL 构造、备份编解码、AI 结果解析、离线分析
四块纯逻辑抽成不依赖 Android 框架的类后，32 条 JVM 单测可以在秒级给出反馈；
后续改动的回归成本大幅下降。

**分工与接口先行。** 三人并行时先约定接口（类名、方法签名、数据格式），
各自实现再合并，冲突和返工明显减少。

## 5 大模型辅助实践的认识

### 5.1 使用的大模型 CASE 工具

- Claude Code：代码阅读与标注、维护实现、单元测试、Gradle 构建、
  ADB 设备验证与截图采集。
- ChatGPT 网页版：维护方案评审、报告结构与 PPT 初稿讨论。

### 5.2 大模型辅助的实践工作

实际使用中形成了几个固定套路：

1. **分层阅读**：不让模型一次读整个工程，而是按「包 → 类 → 方法」逐层提问，
   每次只给一个包或一个链路（如「编辑 → WorkingNote → Note → Provider」）。
2. **标注规范先行**：先写一份标注规范（四级口径与分档表），再让模型按规范
   逐类产出；产出后用脚本校验「只加注释、不改代码、不删除行」。
3. **维护项拆分**：每个维护项（M1–M6）单独开工、单独提交、单独验证，
   不让模型一次改多个维护项。
4. **验证闭环**：模型的每个产出都必须经过「编译 → 单测 → 模拟器操作截图」
   三步，任一步失败就回到提示词修正。

纠错案例：模型曾在 `AlarmInitReceiver` 中把 Javadoc 写在 `@Override` 之后
（编译通过但不符合规范），由校验脚本发现后修正；标注统计口径也经过一次
纠偏——初版只统计注释行数，后来补充了「被注释覆盖的代码行数」这一列，
避免注释行数虚高。

### 5.3 大模型辅助实践的收获和问题

**收获**：阅读速度明显提升，跨文件追踪调用链（如 `DataUtils` 与 Provider 的
批量操作配合）几乎不需要人工翻页；重复性工作（逐类标注、样板代码、单测）可以
批量产出；报告与 PPT 的结构化初稿可以在几分钟内成型。

**问题**：模型会「自信地」声称已完成但实际未验证——例如说「已修复」但构建
没跑过、说「已覆盖全部类」但漏掉被重构删除的类；上下文有限，大工程必须拆小
任务；对平台新约束（如 AGP 9 的 `exported` 要求）偶尔给出过时建议，需要以
官方文档为准复核。结论：把模型当作高效的执行者与初稿作者，但验收必须由人
用构建、测试与设备操作来做。
