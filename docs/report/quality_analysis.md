# 开源软件质量分析报告

> 分析对象：MiCode/Notes（小米便签）原始代码，提交 `81a6602`
> 状态：草稿（第 4 章工具数据待补）

## 1 待分析开源软件概述

小米便签（MiCode/Notes）是小米开源的 Android 便签应用，采用 Java 编写，基于
Eclipse ADT 工程结构（`src/`、`res/`、`AndroidManifest.xml` 位于仓库根目录）。
原始代码共 39 个 Java 文件、10,001 行，组织为 7 个包：

| 包 | 类数/行数 | 主要职责 |
|---|---|---|
| `data` | 4 / 1,019 | 数据库契约、ContentProvider、SQLite 建表与触发器、联系人查询 |
| `gtask/data` | 6 / 1,571 | Google Tasks 同步节点模型与 JSON↔SQL 映射 |
| `gtask/remote` | 4 / 1,636 | HTTP 客户端、双向同步状态机、同步服务与异步任务 |
| `gtask/exception` | 2 / 66 | 同步失败异常分类 |
| `model` | 2 / 621 | 便签编辑期聚合（WorkingNote）与差量提交（Note） |
| `tool` | 4 / 933 | 批量数据操作、文本导出、资源映射、协议常量 |
| `ui` | 14 / 3,930 | 列表、编辑、设置、提醒、日期选择与适配器 |
| `widget` | 3 / 225 | 两种尺寸的桌面小部件 |

主要功能包括：便签与文件夹的增删改查、全文搜索、提醒闹钟、桌面微件、
Google Tasks 云同步。工程不含构建脚本、不含任何测试代码、无 Application 子类。

## 2 软件质量分析方法及工具

### 2.1 分析的软件质量要素

- **内部质量**：编码风格一致性、模块划分与设计原则（高内聚低耦合、信息隐藏）、
  可维护性（复杂度、重复代码、命名）、安全性（组件暴露、SQL 拼接、权限）。
- **外部质量**：缺陷与崩溃风险（新旧系统兼容性、生命周期、异常处理）、
  功能可用性（导出、提醒、通知在当代 Android 上是否仍工作）。

### 2.2 软件质量分析方法

1. **人工阅读**：按包逐类阅读，记录风格、设计、技能与问题四类观察；
   对核心链路（编辑→聚合→差量→Provider→SQLite）做数据流走查。
2. **静态分析**：Android Lint（AGP 9.0）检查 API 兼容性、Manifest、
   资源与代码缺陷；SonarQube Community Build 检查坏味道、复杂度与重复度。
3. **动态验证**：将工程迁移到可构建环境后，在 Pixel_7（Android 17）上
   逐功能回归，验证静态分析发现的问题是否真实发生。

### 2.3 软件质量分析工具

| 工具 | 版本 | 分析内容 |
|---|---|---|
| Android Lint | AGP 9.0.0 内置 | 兼容性、正确性、安全性、性能、可用性、国际化 |
| SonarQube Community Build | 26.11.0 | 代码坏味道、复杂度、重复率、可靠性、安全性规则 |
| JUnit | 4.13.2 | 维护代码的单元测试（报告 3.3 节口径） |

## 3 人工分析代码的质量情况

### 3.1 代码风格

原始代码整体遵循 Java 常规风格：4 空格缩进、类名大驼峰、方法与变量小驼峰、
常量全大写，包结构按职责划分。存在以下偏离：

- **拼写错误进入公共常量**：`ID_TEMPARAY_FOLDER`（应为 TEMPORARY）、
  `ID_TRASH_FOLER`（应为 FOLDER）、`TYPE_WIDGET_INVALIDE`、
  `STATE_SD_CARD_UNMOUONTED`、方法名 `publishProgess`、变量 `alermManager`。
  这些标识符被多处引用，修正会扩大改动面，属于典型的「风格债」。
- **魔法数与硬编码**：列表项高度公式直接写像素系数（`NotesListActivity`），
  通知 ID 写死 `5234235`，同步 URL、超时时间散落在客户端类中。
- **注释覆盖不均**：`gtask` 包几乎无注释；核心数据类只有许可证头，
  阅读成本高。
- **异常处理风格不统一**：部分位置记录日志后继续，部分位置静默吞掉。

结论：风格基线合格，但命名拼写、魔法数和注释覆盖三项不达标，属于可维护性扣分项。

### 3.2 代码设计

**做得好的部分**：

- 分层清晰：UI → model（WorkingNote/Note）→ ContentProvider → SQLite，
  同步链路独立在 `gtask` 子包，UI 不直接拼 SQL。
- 信息隐藏：数据库表名、列名、Uri 集中在 `Notes` 契约类；
  便签状态（正常/删除/文件夹）用文件夹 ID 表达，避免多套状态字段。
- 数据库触发器承担 `snippet` 与 `notes_count` 的维护，减少应用层重复计算。

**设计问题**：

- **巨型类**：`NotesListActivity` 954 行、`NoteEditActivity` 873 行、
  `GTaskManager` 800 行；`syncFolder` 单方法 125 行、`SqlNote.setContent`
  133 行，职责过多。
- **重复代码**：`DataUtils` 的批量删除/移动、`Note` 的提交逻辑、
  Provider 的 notify 调用存在成段重复。
- **静态状态与全局耦合**：`GTaskManager` 以静态单例持有 Activity 引用，
  存在泄漏风险；`Contact` 用静态 HashMap 做无上限缓存。
- **可测性差**：`WorkingNote`、`Note` 直接依赖 `Context`/`ContentResolver`，
  纯逻辑（JSON 映射、SQL 构造）与 Android 框架耦合，原始工程零测试。

结论：模块划分合理，但类粒度失控、重复与静态耦合拉低了设计质量。

### 3.3 编程技能

- **SQLite 触发器**：用 9 个触发器在数据库层维护便签摘要与文件夹计数，
  保证批量操作后计数仍一致，体现了把不变量下沉到存储层的设计技巧。
- **编辑态聚合**：`WorkingNote` 把「原值/新值/差量」封装在一个对象里，
  只在保存时计算 INSERT/UPDATE/DELETE，减少无效写库。
- **同步状态机**：`GTaskManager` 用 `Node`/`Task`/`TaskList` 抽象服务端对象，
  通过 `MetaData` 记录本地与远端的映射，支持双向增量同步（虽然后端已下线）。
- **号码匹配**：`Contact` 复用系统 `phone_lookup.min_match` 规则查联系人，
  兼容各种号码格式。

结论：原作者在数据一致性、增量提交与协议抽象上有较高水平。

### 3.4 代码质量问题

按严重度整理（详细复现见维护报告）：

| 级别 | 问题 | 位置 |
|---|---|---|
| P0 | 无 targetSdk，Android 14+ 拒装 | `AndroidManifest.xml` |
| P0 | Provider 未设 exported/权限，任意应用可读写全部便签 | `AndroidManifest.xml`、`NotesProvider` |
| P0 | 带 intent-filter 组件缺 `android:exported`，target 31+ 构建失败 | Manifest 多处 |
| P0 | PendingIntent 未指定可变性，target 31+ 运行崩溃 | 编辑页、闹钟、微件、同步通知 |
| P0 | Apache HttpClient 与 `setLatestEventInfo` 已移除，同步模块无法编译/运行 | `GTaskClient`、`GTaskASyncTask` |
| P1 | 导出写外部存储根目录且用默认字符集，Android 10+ 失败、中文乱码 | `BackupUtils` |
| P1 | 无通知渠道与通知权限，提醒通知不可见 | `AlarmAlertActivity` 等 |
| P1 | 提醒用 `AlarmManager.set` 且直接启动 Activity，后台受限 | 编辑页、`AlarmReceiver` |
| P1 | 动态广播未指定导出标志，target 34 崩溃 | `NotesPreferenceActivity` |
| P1 | 联系人查询在主线程执行且无运行时权限 | `NoteItemData`、`Contact` |
| P2 | SQL 手拼与 `replaceFirst` 构造条件，存在注入面 | `NotesProvider` |
| P2 | 数据库升级直接 DROP 表，升级丢数据 | `NotesDatabaseHelper` |
| P2 | 固定浅色主题，无深色模式资源 | `res/values` |
| P2 | 吞异常、`AsyncTask`、`getDefaultDisplay()` 等过时 API | 多处 |

## 4 工具分析代码的质量情况

> 待补：Android Lint 与 SonarQube 的原始报告、问题分布与前后对比。
