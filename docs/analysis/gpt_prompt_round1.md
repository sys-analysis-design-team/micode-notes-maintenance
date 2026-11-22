我在做本科课程大作业《阅读、分析和维护开源软件》，选定小米便签 MiCode/Notes（GitHub: MiCode/Notes）作为统一题目。我这边用 Claude Code CLI 在本地落地、编译、跑 Pixel_7 模拟器、生成文档和 PPT。请你作为项目导师 + 严苛评审人，帮我把方案定下来。

【任务硬要求】
- 3 人组：袁晨昕（组长）、刘嘉、罗奕峰。
- 第 16 周汇报（12/14-12/20），PPT 约 10 页，8 分钟。
- 老师口径：重点讲大家在使用过程中发现的问题、如何解决、拓展的功能；小米便签已有功能一页带过；不用说分工。官方规则又写「重点汇报团队贡献」，新增功能「结合 LLM 的功能更好」。
- 交付物：①泛读-标注-维护报告（模板见附件，单元测试写入 3.3 节）②质量分析报告（模板见附件，要求人工分析 + SonarQube 等工具）③成果 PPT ④源码版本库（src、doc 两个目录；master 放原始代码，develop 和各成员分支）。
- 维护项至少 3 项（不少于组员一半向上取整），鼓励多做；要求新功能完成度高、集成度好、体验好，缺陷修复完成度高；代码风格、注释、静态质量问题全部处理。
- 需绘制准确的用例图、类图、包图、维护后架构图。

【代码现状（已扫描）】
- Eclipse 老工程，无 Gradle：根目录 AndroidManifest.xml，src/ 下 7 个包、39 个 Java 文件、10001 行；res/ 为旧资源。
- 包：data（Provider/SQLite/契约）、gtask（Google Tasks 同步，4+6+2 类）、model（Note/WorkingNote）、tool（导出/工具）、ui（14 类，列表 954 行、编辑 873 行）、widget（2 个桌面小部件）。
- 现代环境致命问题：manifest 只有 minSdk 14 无 targetSdk；所有带 intent-filter 组件没写 android:exported；PendingIntent 未标 IMMUTABLE/MUTABLE；Apache HttpClient 在 API 28 移除导致 GTaskClient 编译失败；Notification.setLatestEventInfo 已移除；GTask 的 mail.google.com/tasks 端点与旧认证已下线；Provider 无权限，任意 App 可读写全部便签；导出用 Environment.getExternalStorageDirectory() + 默认字符集；无通知渠道/无 POST_NOTIFICATIONS；AlarmManager.set() 不精确；动态广播未指定 RECEIVER_EXPORTED；固定 Theme.Holo.Light 无深色模式。
- 本机环境：Android Studio 2026.1、SDK platform 34/35/36/36.1、build-tools 36/37、Pixel_7 AVD（Android 36）、Gradle 9.2.1 缓存、AGP 9.0.0 已验证可用的同机项目。

【我的初步方案，请逐条评审并给出删改】
A. 工程现代化：Gradle 9.2.1 + AGP 9.0.0，compileSdk 36.1，minSdk 23，targetSdk 先 34 跑通基线，再升 35/36 作为维护项；保留 src/res 根目录以符合作业「src/doc」结构；处理 GTask 编译障碍（下线同步入口或改为可选模块）。
B. 维护项候选（想控制在 6-7 项，含至少 1 个 LLM 功能）：
  1) Android 12-16 兼容修复（exported、PendingIntent、通知渠道、动态广播）
  2) 文本导出改 SAF + UTF-8，并新增备份导入/恢复
  3) Provider 权限加固 + SQL 注入/选择条件修复 + 单元测试
  4) 提醒重构（精确闹钟、锁屏全屏、Android 13 通知权限）
  5) 回收站浏览与恢复（原代码有 trash 文件夹但无 UI）
  6) 深色模式适配
  7) LLM 功能：便签 AI 摘要 / 自动标签 / 待办提取（OpenAI 兼容接口，Key 由用户在设置页填，仓库不落 Key；演示时用本地缓存或离线规则兜底）
C. 标注：类级 39 类全覆盖；方法级覆盖 data/model/gtask.data；块级聚焦 DB/Provider/WorkingNote；语句级精选 300-500 行，做 8 个样板类。
D. 质量分析：人工阅读 + Android Lint + PMD/SpotBugs + SonarQube（Windows 本地，是否值得装？）。
E. 测试：JUnit 纯逻辑（Task/TaskList JSON、snippet、SQL 构造）+ Robolectric（Provider/DB）是否可行。
F. 排期：11/23-12/13 完成，12/14-12/20 汇报。

【请输出】
1. 方案评审：最大的 5 个风险与对应规避；哪些维护项该删/该换/该合并；LLM 功能在「演示机可能没网/没 Key」的前提下怎样设计才稳。
2. 最终维护项清单（6-7 项）：每项写清用户痛点、技术方案、涉及文件/类、验收标准、测试点、演示脚本（30 秒内能讲清）、难度与工作量。
3. 分阶段排期：按 11/23-12/13 给出周计划与缓冲，标明关键路径。
4. 两份报告的逐章大纲（严格对齐附件模板），以及 10 页 PPT 的逐页标题 + 每页要点 + 8 分钟讲稿节奏。
5. 代码标注与统计口径：四级标注各做到什么程度、行数怎么统计才经得起老师核对、如何避免「注释注水」。
6. 代码交付方式：你建议按模块给我完整 Java 文件，还是一次给整包？先做哪个模块？文件必须完整、包名正确、中文注释、不省略。
7. Git 分支与提交方案：master/develop/3 个成员分支怎么组织，维护项怎么落到分支，最后怎么合并。
8. 质量分析工具链的可执行建议（Windows 本机、时间有限）。

附件：作业要求、两份报告模板、PPT 模板、架构扫描、AndroidManifest.xml。请直接给结论和可执行清单，不要泛泛而谈。