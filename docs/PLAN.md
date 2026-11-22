# 小米便签维护大作业 — 实施计划

> 依据：任务书 + 四份模板 + GPT 两轮评审 + 本地源码扫描（2026-11-23）
> 截止：2026-12-13 交付；12-14 ~ 12-20 汇报。

## 一、维护项（6 必做 + 1 选做）

| 编号 | 类型 | 内容 | 验收标准 | 状态 |
|---|---|---|---|---|
| M1 | 缺陷 | 老工程现代化与 Android 12–17 兼容；GTask 退役 | assembleDebug 成功；Pixel_7 可安装启动；新建/编辑/保存/搜索/删除/Widget 正常；旧同步入口不误导 | ✅（Widget 回归待做） |
| M2 | 缺陷 | Provider 数据安全 + SQL 修复 + 测试 | 本应用 CRUD 正常；独立测试 App 无法读写 Provider；恶意 selection 不扩大结果；非法 URI 有确定行为 | ✅ |
| M3 | 功能 | SAF 导出 UTF-8 TXT + JSON 完整备份/恢复 | 中文/Emoji 导出正确；≥20 条不同类型便签可备份恢复；坏 JSON/取消/空文件不破坏现有数据 | ✅ |
| M4 | 缺陷 | 提醒与通知重构 | 可设置/修改/取消；通知可见、点击回跳；权限拒绝有说明；重启后恢复提醒 | ⬜ |
| M5 | 功能 | 回收站浏览/恢复/彻底删除 | 删除后普通列表不可见、回收站可见；恢复正文完整；彻底删除需确认；触发器计数正常 | ⬜ |
| M6 | 功能 | AI 摘要 + 待办提取 | 真实 LLM 至少成功一次；无 Key/断网/超时/坏 JSON 不崩溃；用户确认后才写入；Key 不入库 | ⬜ |
| M7 | 功能 | 深色模式（选做） | 系统切换后列表/编辑/回收站/AI/提醒可读；无白底白字 | ⬜ 12/6 决定 |

### M1 完成情况

- 全组件 `android:exported` 已检查并显式声明；`PendingIntent` 统一 `FLAG_IMMUTABLE`；动态广播 Android 13+ 使用 `RECEIVER_NOT_EXPORTED`。
- 发现并修复真实缺陷：原主题把 ActionBar 设为 `gone`，依赖实体 MENU 键；现代设备无菜单键导致设置/搜索/提醒入口不可达，现恢复标准 ActionBar 溢出菜单。
- GTask：列表菜单与设置页同步入口已移除；`GTaskSyncService` 保留但无调用方，报告中写明「退役」而非「修复」。
- 联系人查询捕获 `SecurityException`，未授权时退化为显示号码。
- 验证：构建、安装、启动、新建/编辑/保存、列表、搜索、设置页、提醒设置与触发均通过；Widget 待最终回归。
- 遗留：`NoteItemData` 主线程查询联系人可改异步（不影响验收）。

### M2 完成情况

- `NotesProvider` 设 `android:exported="false"`：安装后从 adb（UID 2000）查询 `content://micode_notes/note` 被 `SecurityException` 拒绝，应用内 CRUD 正常；证据 `logs/m2_external_denied.txt`。
- 新增 `ProviderSql` 纯 Java 构造器：单条 URI 的 ID 条件、删除附加条件、版本号自增全部改为 `?` 参数绑定，不再把 `selectionArgs` 字符串拼进 SQL。
- 修复 `delete(CONTENT_NOTE_URI, null, null)` 生成 `(null) AND ID>0` 的 SQL 错误；`insert` 失败返回 `null`；补齐 `getType` 的 dir/item MIME。
- 回归测试 `ProviderSqlTest` 7 条：ID 条件、条件包裹、参数顺序、注入字符串只进参数不进 SQL 文本、无 ID/无条件场景。

### M3 完成情况

- 新增 `BackupNote`（不可变模型）与 `NoteBackupCodec`（V1 JSON，严格校验版本、类型、ID 缺失/重复），`NoteBackupCodecTest` 9 条，含中文、引号、反斜杠、换行、Emoji 往返。
- `BackupUtils` 重写：文本导出改为调用方提供 `OutputStream`（UTF-8），JSON 备份收集用户文件夹与文本便签；恢复先建文件夹并做旧 ID→新 ID 映射，逐条写入，异常时回滚本次新增记录。
- 列表菜单新增「备份到 JSON 文件」「从 JSON 文件恢复」，保存/打开走 SAF，移除 `WRITE_EXTERNAL_STORAGE` 权限。
- 设备验证（Pixel_7 / Android 17）：22 条便签 + 2 个文件夹；文本导出 2.45 KB，中文与 🥛🥚🍞 无乱码；JSON 6.74 KB，含 24 条记录（22 便签 + 2 文件夹）；恢复后数据库 44 条便签 / 4 个文件夹，文件夹计数 10/6 保持正确；坏 JSON 与 0 字节文件均提示错误，数据库保持 22/2 不变。
- 证据：`logs/m3_seed_list.png`、`m3_full_text_saved.png`、`m3_full_restore_done.png`、`m3_bad_restore.png`、`m3_empty_restore.png`、`m3_full_backup.json`、`m3_full_export.txt`。

## 二、排期（关键路径）

| 日期 | 工作 | 里程碑 |
|---|---|---|
| 11/23 | 仓库、基线、统计口径 | master/develop/成员分支 |
| 11/24–25 | Gradle、Manifest、GTask 退役、启动 | 构建 + 安装 |
| 11/26–27 | CRUD/搜索/Widget 回归 | **M1 验收；不过则暂停新功能** |
| 11/28–29 | Provider 风险复现、测试、基线冻结 | baseline-runnable tag |
| 11/30–12/3 | M2 安全、M3 导出/备份、M5 回收站 | 数据层先合并 |
| 12/4–12/6 | M4 提醒、M6 AI、集成验收 | 12/6 决定 M7 |
| 12/7–12/9 | 全流程测试、截图、补单测 | 第一版测试报告 |
| 12/10 | **功能冻结** | 禁止新增功能 |
| 12/11 | SonarQube 复测、统计、两份报告 | 报告基本完成 |
| 12/12 | PPT、演示录像、图表复核 | 可完整讲 8 分钟 |
| 12/13 | 最终回归、tag、成果校验 | 可提交 |

## 三、代码交付顺序

1. **M1**：Gradle/Manifest/兼容/GTask 退役 → 构建、安装、CRUD。
2. **M2**：`NotesProvider`、`NotesDatabaseHelper`、测试 → CRUD 回归 + 跨应用拒绝。
3. **M3**：`NoteBackupCodec`、`NoteBackupManager`、`BackupUtils`、入口 UI、测试 → JSON 往返 + 事务回滚。
4. **M5**：`TrashActivity`、`TrashAdapter`、`DataUtils`、最小入口 → 删除-恢复-彻底删除闭环。
5. **M4**：`ReminderScheduler`、`ReminderNotifier`、Receiver、编辑页调用 → 权限/改期/取消/回跳。
6. **M6**：`AiResultParser`、`LocalNoteAnalyzer` 纯逻辑测试 → `AiClient` → UI/设置 → 真实 API + 离线 + 错误分支。
7. **M7**：深色模式独立提交；风险高则取消。

交付协议：读原始文件 → 列准确包名/签名/调用方 → 改真实仓库文件 → 完整文件不省略 → 中文注释 → 编译 + 单测 + 回归 → 列修改清单。

## 四、PPT 10 页 / 8 分钟

| 页 | 时间 | 内容 |
|---|---|---|
| 1 | 20s | 项目背景与成果总览：39 文件/10,001 行；6+1 维护项 |
| 2 | 30s | 原始功能与核心架构（一页带过）：用例图 + 包图 + 数据链 |
| 3 | 45s | 代码阅读、四级标注、质量分析：统计 + 四个层级各一例 + Lint/Sonar |
| 4 | 50s | 问题一：老工程无法适配现代 Android → Gradle/Manifest/旧 API/同步退役 |
| 5 | 55s | 问题二：数据访问安全与导出失效 → Provider 收紧、SQL 参数化、SAF/UTF-8/JSON 备份 |
| 6 | 55s | 维护成果：可靠提醒 + 可恢复删除 |
| 7 | 55s | 创新拓展：AI 智能摘要 + 待办提取（真实 LLM + 离线兜底） |
| 8 | 55s | 维护后架构、类设计与关键代码（新增/修改分色） |
| 9 | 65s | 质量验证与成果演示：测试通过率、Lint/Sonar 前后、25 秒连续操作视频 |
| 10 | 50s | 总结与团队贡献：Git 提交证据、代码/标注/测试/文档统计、局限 |

演示纪律：不现场装 APK、不配 Key、不等精确闹钟；关键页用录屏；10 页讲 7 分 30 秒左右。

## 五、报告大纲

### 报告一：泛读-标注-维护

1. 代码泛读：功能描述 + 用例图；包图 + 类图 + 数据流；功能-类-方法对应表。
2. 代码标注：四级范围与原则；逐包统计表；类/方法/块/语句示例。
3. 维护：3.1 六项维护明细；3.2 维护后包图/类图（新增/修改分色）；**3.3 代码量 + 质量 + 单元测试结果**；3.4 软件原型截图。
4. 实践收获与体会。
5. 大模型辅助实践：工具、提示词、产出、纠错案例。

### 报告二：质量分析

1. 软件概述（来源、版本、语言、规模）。
2. 方法与工具（质量要素、人工+工具、SonarQube/Lint/JUnit）。
3. 人工分析：代码风格、代码设计、编程技能、质量问题。
4. 工具分析：Lint/SonarQube 结果、维护前后对比、测试与结论。

## 六、标注口径

- 类级：全部 39 个 Java 文件；写职责、关键依赖、与其他类关系。
- 方法级：`data`、`model`、`gtask.data` 全部方法；写参数/返回/副作用/异常。
- 块级：DB、Provider、WorkingNote 为主，30–50 个逻辑块；解释为什么。
- 语句级：8 个样板类，精读并注释 300–500 行；只标隐含约束/边界。
- 统计三列分开：注释物理行数、被解释代码行数、精读代码行数；提交前用脚本重新统计，不手抄。
