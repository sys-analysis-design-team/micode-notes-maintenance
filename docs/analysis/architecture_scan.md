# 小米便签（MiCode/Notes）架构与问题扫描

> 扫描对象：`upstream/`（GitHub MiCode/Notes master，81a6602）
> 规模：39 个 Java 文件 / 10,001 行；Eclipse ADT 老工程，无 Gradle、无测试、无 Application 子类。

## 1. 包结构

| 包（类数/行数） | 职责 |
|---|---|
| `data`（4/1019） | `Notes` 常量与 Uri 契约；`NotesProvider` ContentProvider CRUD/搜索；`NotesDatabaseHelper` SQLite 建表/9 个触发器/v1→v4 升级；`Contact` 号码查联系人 |
| `gtask/data`（6/1571） | `Node`/`Task`/`TaskList`/`MetaData`/`SqlData`/`SqlNote`，Google Tasks 同步节点与 JSON↔SQL 映射 |
| `gtask/remote`（4/1636） | `GTaskClient` HTTP+登录；`GTaskManager` 双向同步状态机（800 行）；`GTaskASyncTask` AsyncTask+通知；`GTaskSyncService` Service 入口 |
| `gtask/exception`（2/66） | 同步异常分类 |
| `model`（2/621） | `Note` 差量提交；`WorkingNote` 编辑期聚合根 |
| `tool`（4/933） | `DataUtils` 批量删/移；`BackupUtils` 导出 TXT；`ResourceParser` 资源映射；`GTaskStringUtils` JSON 键 |
| `ui`（14/3930） | 列表 `NotesListActivity`(954)、编辑 `NoteEditActivity`(873)、设置、闹钟、日期选择、适配器等 |
| `widget`（3/225） | 两种尺寸桌面小部件 |

核心数据流：UI → `WorkingNote` → `Note` 差量 → ContentResolver → `NotesProvider` → `NotesDatabaseHelper` → SQLite；触发器维护 `notes_count`/`snippet`。同步链路：设置页 → `GTaskSyncService` → `GTaskManager` → `GTaskClient` → `mail.google.com/tasks`（已下线）。

## 2. 现代 Android 风险（按严重度）

- **P0 装不上**：`AndroidManifest.xml:23` 只声明 minSdk 14、无 targetSdk；Android 14+ 拒装 target<23。
- **P0 数据泄露**：`NotesProvider`（Manifest:83-86）无 exported/readPermission/writePermission，旧 target 默认对外；任意 App 可读写全部便签。
- **P0 Android 12+ exported**：所有带 intent-filter 的 Activity/Receiver 未写 `android:exported`，target 31+ 构建/安装失败。
- **P0 PendingIntent 可变性**：`NoteEditActivity:626`、`AlarmInitReceiver:56`、`GTaskASyncTask:73/77`、`NoteWidgetProvider:113/117` 未指定 IMMUTABLE/MUTABLE，target 31+ 运行崩溃。
- **P0 GTask 编译不过/已死**：Apache HttpClient 在 API 28 移除（`GTaskClient:35-48`）；`Notification.setLatestEventInfo` 在 API 23 后移除（`GTaskASyncTask:80`）；旧端点与认证均下线。
- **P1 导出失效**：`BackupUtils:317` 用 `Environment.getExternalStorageDirectory()`；Android 10+ 分区存储无法写；`PrintStream` 默认字符集中文乱码。
- **P1 通知不可见**：无 NotificationChannel、无 POST_NOTIFICATIONS、`setLatestEventInfo` 废弃。
- **P1 联系人查询**：无运行时权限；`Contact` 用私有 `phone_lookup` 表；`NoteItemData:99` 主线程查联系人。
- **P1 提醒**：`AlarmManager.set()` 不精确；后台 startActivity 受限；`AlarmAlertActivity:94-100` 把铃声位掩码当 stream type。
- **P1 动态广播**：`NotesPreferenceActivity:84` 未指定 RECEIVER_EXPORTED/NOT_EXPORTED（target 34 崩溃）。
- **P2 深色模式缺失**：固定 `Theme.Holo.Light`，无 `values-night`。
- **P2 旧 API/线程**：AsyncTask、`getDefaultDisplay()`、`getColor()`、裸 Thread、静态持有 Activity（`GTaskManager:109`）。
- **P2 SQL 安全**：`NotesProvider:285-293` 手拼 SQL + `replaceFirst`；URI id 拼 SQL；`NotesDatabaseHelper:336-341` 升级直接 DROP 丢数据。

## 3. 质量观察

- 巨型类/方法：`NotesListActivity` 954 行、`NoteEditActivity` 873 行、`GTaskManager` 800 行；`syncFolder` 125 行、`SqlNote.setContent` 133 行。
- 重复：`DataUtils:40-112`、`Note:192-234`、`NotesProvider` notify 逻辑。
- 魔法数/硬编码：`NotesListActivity:375` 像素公式、通知 id `5234235`、URL/超时。
- 吞异常：`AlarmAlertActivity:107-119`、`GTaskClient:274-278`、`NotesProvider:137-139`。
- 拼写错误：`ID_TEMPARAY_FOLDER`、`ID_TRASH_FOLER`、`TYPE_WIDGET_INVALIDE`、`STATE_SD_CARD_UNMOUONTED`、`publishProgess`、`alermManager`。
- 可测性：零测试；静态单例多；`WorkingNote`/`Note` 直接依赖 Context/ContentResolver；纯逻辑 `Task`/`TaskList` JSON 映射可 JVM 单测。

## 4. 候选维护项

| 类型 | 项目 | 改动范围 | 难度 | 演示 |
|---|---|---|---|---|
| 缺陷 | 工程现代化 Gradle+target | build 文件、Manifest、GTask 编译障碍 | 高 | 强 |
| 缺陷 | Manifest/PendingIntent/通知渠道兼容 | Manifest、4 个类 | 中 | 强 |
| 缺陷 | 文本导出 SAF+UTF-8 | BackupUtils、NotesListActivity | 低-中 | 强 |
| 缺陷 | Provider SQL/选择条件修复+单测 | NotesProvider、DataUtils、test | 低-中 | 中 |
| 缺陷 | 提醒重构 setAlarmClock/setExact | NoteEditActivity、AlarmReceiver | 中 | 强 |
| 缺陷 | GTask 下线/替换 | gtask 包、设置页 | 高 | 中-强 |
| 缺陷 | 稳定性小修包 | 6 处 | 低 | 中 |
| 功能 | 深色模式 | styles/colors/values-night | 中 | 强 |
| 功能 | 回收站浏览与恢复 | 列表/适配器/DataUtils | 中 | 强 |
| 功能 | 备份导入/恢复 | BackupUtils、列表、设置 | 中 | 强 |
| 功能 | 标签/置顶+搜索增强 | DB v5、Provider、UI | 高 | 强 |
| 功能 | 清单拖拽/撤销 | 编辑页、布局 | 高 | 中-强 |

## 5. 标注工作量

类级 39 个可全覆盖；方法级覆盖 `data`/`model`/`gtask.data`；块级聚焦 DB/Provider/WorkingNote；语句级精选 300-500 行核心逻辑。推荐四级示例类：`NotesDatabaseHelper`、`NotesProvider`、`WorkingNote`、`Note`、`SqlNote`、`Task`、`NotesListActivity`、`NoteEditActivity`。
