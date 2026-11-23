# SonarQube 扫描摘要

| 指标 | 原始代码 (1098a1e) | 维护后 (3917b03) |
|---|---|---|
| files | 39 | 51 |
| classes | 65 | 80 |
| functions | 471 | 557 |
| ncloc | 7395 | 8696 |
| comment_lines | 401 | 2332 |
| comment_lines_density | 5.1 | 21.1 |
| complexity | 1325 | 1645 |
| cognitive_complexity | 1403 | 1622 |
| duplicated_lines_density | 2.9 | 2.3 |
| bugs | 2 | 2 |
| vulnerabilities | 51 | 40 |
| code_smells | 0 | 0 |
| sqale_index | 0 | 0 |
| reliability_rating | 3.0 | 3.0 |
| security_rating | 4.0 | 3.0 |
| sqale_rating | 1.0 | 1.0 |

## 问题明细

- 原始代码：53 个问题（漏洞 51、Bug 2）；S4507×42、S5320×7、S3923×2、S5322×1、S5324×1（2 个 CRITICAL）
- 维护后：42 个问题（漏洞 40、Bug 2）；S4507×33、S5320×7、S3923×2；0 个 CRITICAL
- 规则说明：S4507 调试输出保留；S5320 广播未限定接收方；S3923 条件分支相同；
  S5322 动态广播接收器未加权限（NotesPreferenceActivity:84）；
  S5324 外部存储保存敏感数据（BackupUtils:317）
