# STATUS — 小米便签开源维护大作业

> 唯一当前状态入口。更新日期：2026-11-24

## 一、任务硬指标

- 3 人组：袁晨昕（组长）、刘嘉、罗奕峰。
- 第 16 周汇报：2026-12-14 ~ 12-20；PPT 10 页、8 分钟；老师口径：已有功能一页带过，重点讲使用中发现的问题 + 解决 + 拓展功能 + 团队贡献。
- 维护项 ≥3（组员一半向上取整），实际做 6 项必做 + 1 项选做。
- 交付：泛读-标注-维护报告、质量分析报告、成果 PPT、源码版本库（src/doc；master/develop/成员分支）。

## 二、当前进度

| 项 | 状态 | 证据 |
|---|---|---|
| 任务书与四份模板解析 | ✅ | `docs/_extract/` |
| 上游代码扫描（39 Java / 10,001 行 / 7 包） | ✅ | `docs/analysis/architecture_scan.md` |
| GPT 方案评审（两轮） | ✅ | `logs/gpt_round1_reply.md`、`logs/gpt_round1b_reply.md` |
| Gradle 9.2.1 + AGP 9.0.0 构建 | ✅ | `sh gradlew assembleDebug` → `build/outputs/apk/debug/micode-notes-debug.apk` |
| Pixel_7（Android 17）安装启动 | ✅ | `logs/app_first_launch.png` |
| 新建/输入/保存/列表显示 | ✅ | `logs/app_editor_typed.png`、`logs/app_list_after_save.png` |
| 本地 Git：master 原始基线 / develop 维护基线 / 3 个成员分支 | ✅ | `git branch` |
| M1 兼容清理与 GTask 退役 | ✅ | 提交 `84c4916`；构建/启动/CRUD/搜索/设置/提醒通过；Widget 添加/绑定/更新回归通过 |
| M2 Provider 安全与 SQL 参数化 | ✅ | 提交 `0b77758`；adb 跨应用查询被拒；`ProviderSqlTest` 7/7 |
| M3 SAF 导出 + JSON 备份恢复 | ✅ | 22 便签/2 文件夹；中文+Emoji 往返正确；恢复 44/4；坏/空 JSON 不改数据 |
| M5 回收站 | ✅ | 软删除/恢复/彻底删除；文件夹计数 10→9→10；触发器删正文 |
| M4 提醒与通知 | ✅ | 通知弹出/点击回跳/改期/取消；精确权限 window=0；重启 `Rescheduled 1` |
| M6 AI 摘要 + 待办 | ✅ | 真实 DeepSeek 在线成功；离线/断网回退；插入需确认；Key 不入库/备份/日志 |
| JVM 单元测试 | ✅ 40/40 | `ProviderSqlTest` 7 + `NoteBackupCodecTest` 9 + `AiResultParserTest` 8 + `LocalNoteAnalyzerTest` 8 + `ReminderRepeatsTest` 8 |
| 远端小组仓库 | ✅ | `https://github.com/sys-analysis-design-team/micode-notes-maintenance`，五个分支已推送 |
| 代码标注（38 个原始类） | ✅ 提交 `3917b03` | 3,080 行注释 / 覆盖 5,135 行代码；`docs/analysis/annotation_stats.md`、`annotation_coverage.md` |
| 维护代码量统计 | ✅ | 9 项维护、3,451 新增 / 1,068 删除 / 4,519 行受影响、33 文件；`docs/analysis/maintenance_stats.md` |
| 两份报告 | ✅ 初稿完成（第 3 章已同步 M7–M9） | `docs/report/quality_analysis.md`（4 章齐）、`reading_annotation_maintenance.md`（1–5 章齐）；9 项维护表、4,519 churn、15 类/1,259 行、40 单测、Lint 178、Sonar 重扫 |
| Android Lint | ✅ 0 Error / 178 Warning | `docs/analysis/lint/`（txt + html + summary，2026-11-24 重扫）；M6 时 174，M7–M9 新增 4 条 |
| SonarQube 扫描（前后对比） | ✅ 2026-11-24 重扫 | `docs/analysis/sonar/`；原始 53 问题/安全 D → 维护后 42 问题/安全 C；注释密度 5.1%→21.4%、重复率 2.9%→2.2%；脚本 `tools/scan_sonar.py` |
| UML 图（PlantUML） | ✅ 已补 M7–M9 | `docs/uml/`：用例图×2（新增 3 个用例）、包图×2、维护后类图（含 ThemeHelper/ReminderRepeats），PNG 已重渲染 |
| 成果 PPT 10 页 | ✅ v2 初版（2026-11-24） | `dist/小米便签开源软件维护.pptx` + PDF；源在 `dist/ppt_src/`；10 页重排：M9 并入第 6 页、新增第 8 页（M7/M8 + 刘嘉/罗奕峰占位卡）、验证与总结并为第 10 页；第 9 页已换新类图、第 10 页数字刷新；视觉评审 PASS 0 错误 |

## 三、维护项

| 编号 | 内容 | 状态 |
|---|---|---|
| M1 | 老工程现代化与 Android 12–17 兼容；GTask 退役 | ✅ 构建/启动/CRUD/搜索/设置/提醒/Widget 全部通过 |
| M2 | Provider 数据安全 + SQL 修复 + 跨应用拒绝访问测试 | ✅ adb 访问被拒；应用内 CRUD 正常；7 条 SQL 回归测试 |
| M3 | SAF 导出（UTF-8 TXT）+ JSON 备份/恢复 | ✅ 22 条往返；中文/Emoji 正确；坏/空文件不写入 |
| M4 | 提醒与通知机制重构 | ✅ 设置/改期/取消；通知可见可点击；权限说明；开机重建 |
| M5 | 回收站浏览/恢复/彻底删除 | ✅ 便签软删除；恢复回原文件夹；彻底删除二次确认 |
| M6 | AI 智能摘要 + 待办提取（真实 LLM + 离线兜底） | ✅ 在线/离线/断网三分支；确认后写入；Key 泄漏扫描通过 |
| M7 | 深色模式（跟随系统/浅色/深色三态） | ✅ 提交 b789c50；三态切换与列表/编辑/设置/回收站截图通过 |
| M8 | 便签置顶/取消置顶（批量切换，数据库 v5） | ✅ 置顶/取消/批量/重启持久化/子文件夹均通过；DB 校验 user_version=5、pinned 列正确 |
| M9 | 提醒重复（每天/每周/工作日，数据库 v6） | ✅ DB v6 迁移；重复方式对话框；三档规则；触发后自动推进（含 TIME_SET 双推进竞态修复）；重启显示；删除提醒清空规则；单测 40/40 |

## 四、环境与构建

- Android Studio 2026.1：`D:\Projects\Android Studio`
- SDK：`D:\Projects\Android\Sdk`（platform 34/35/36/36.1；build-tools 36/37）
- AVD：`Pixel_7`，实际系统 Android 17（API 37）
- 构建：Gradle 9.2.1 + AGP 9.0.0，compileSdk 36.1，minSdk 23，targetSdk 34（基线）
- 工程结构：`src/`、`res/`、`AndroidManifest.xml` 保持在根目录，符合作业要求
- 构建命令：`sh gradlew assembleDebug`
- 安装启动：`adb install -r build/outputs/apk/debug/micode-notes-debug.apk`
- SonarQube：`D:\AI\Tools\sonarqube-26.11.0.134175`，数据目录 `D:\AI\Tools\sq-data-3`
  （2026-11-24 重置，旧库 sq-data-2 保留），启动 `bin\windows-x86-64\StartSonar.bat`
  （需 JAVA_HOME=JDK 21），端口 9000；扫描器在 `D:\AI\Tools\sonar-scanner-8.5.0.16571-windows-x64`；
  一键重扫 `python tools/scan_sonar.py --base D:/AI/Temp/mipnote-base`（先
  `git worktree add --detach D:/AI/Temp/mipnote-base 1098a1e` 检出原始基线）。
  扫描结果落盘 `docs/analysis/sonar/`

## 五、关键决策

1. 不做全面重写；保留原生 Java + SQLite + ContentProvider 架构。
2. GTask 同步不重写协议：保留源码在 master，维护版从 UI/构建链路退役，报告中明确说明。
3. targetSdk 先 34 建立可运行基线，不把 target 36 作为交付门槛。
4. LLM 功能双路径：真实 OpenAI 兼容接口 + 明确标识的离线规则分析；Key 不入库。
5. 代码按模块交付与验证，不一次生成整包。
6. 深色模式只改窗口/控件跟随主题，便签纸始终浅色、文字固定黑（纸质感是产品特征）。
7. 置顶标志视为设备本地 UI 状态（同 Widget 绑定），不写入 JSON 备份/恢复。
8. 重复规则只支持三档（每天/每周/工作日），不做自定义间隔；重复推进写回 alerted_date 但不改 modified_date。

## 六、下一步

1. M7–M9 已全部完成（深色模式/置顶/提醒重复）；12/10 功能冻结，之前可再评估是否补新功能。
2. 报告收尾：第 3 章与 Sonar/Lint 数字已同步（2026-11-24 重扫）；剩通读定稿与第 5 章按实际使用补充（M9 的 TIME_SET 竞态可作「使用中发现的问题 → 解决」素材）。
3. PPT：已换新类图与数字（本次收尾提交）；剩演示录屏（25 秒连续操作）、组员功能定后回填第 8 页占位卡与第 10 页分工。
4. 组员新功能提名：11/26 前收「功能名 + 做什么 + 怎么做」三行，汇总查重；成员功能合并后重跑
   `python tools/count_maintenance.py`、`python tools/scan_sonar.py --skip-base`、`sh gradlew lintDebug`，重渲染类图再回填 PPT。
5. 推送状态（2026-11-24）：github.com 的 git 传输被网络重置，改走 GitHub API 通道推送成功
   —— 远端 develop head = `10b0a060`（树与本地 `572779b` 一致；中间 `a96e97db` 是首次 API
   推送的 CRLF 版本，已被后一个提交完全覆盖）。**本地与远端历史分叉（内容一致、SHA 不同），
   网络恢复后执行 `git fetch origin && git reset --hard origin/develop` 对齐。**
   兜底脚本：`tools/gh_api_push.py`（提交里的精确 blob，不读工作区）；本次收尾提交（UML/报告/PPT 同步）尚未推送。
