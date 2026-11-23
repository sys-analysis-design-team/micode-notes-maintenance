#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按维护项统计 M1–M6 的代码变更量（仅统计 src/ 下的 Java 文件）。

用法：
    python tools/count_maintenance.py [--out docs/analysis/maintenance_stats.md]

维护项与提交的对应关系写死在 ITEMS 中；如需增减，改这里并重跑。
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

# 维护项 → 提交序列（不含纯文档提交）
ITEMS = [
    ("M1", "老工程现代化与 Android 12–17 兼容；GTask 退役", ["51b3d28", "84c4916"]),
    ("M2", "Provider 数据安全 + SQL 参数化", ["0b77758"]),
    ("M3", "SAF 导出 + JSON 备份/恢复", ["4220fa7"]),
    ("M5", "回收站", ["5586889"]),
    ("M4", "提醒与通知重构", ["f6c79ea"]),
    ("M6", "AI 摘要 + 待办提取", ["dddfddc"]),
]


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        raise SystemExit(result.returncode)
    return result.stdout


def stat_commit(commit: str) -> tuple[int, int, set[str]]:
    """返回（新增行, 删除行, 受影响 Java 文件集合）。"""
    added = deleted = 0
    files: set[str] = set()
    out = git("show", "--numstat", "--format=", commit, "--", "src")
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        a, d, path = parts
        if not path.endswith(".java"):
            continue
        files.add(path)
        added += int(a) if a.isdigit() else 0
        deleted += int(d) if d.isdigit() else 0
    return added, deleted, files


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="docs/analysis/maintenance_stats.md")
    args = parser.parse_args()

    lines = [
        "# 维护代码量统计",
        "",
        "> 口径：`git show --numstat`，只统计 `src/` 下的 `.java` 文件；",
        "> 「受影响行数」= 新增行 + 删除行（代码改动 churn）。",
        "",
        "| 维护项 | 名称 | 提交 | 新增 | 删除 | 受影响行数 | 文件数 |",
        "|---|---|---|---|---|---|---|",
    ]
    total_added = total_deleted = 0
    total_files: set[str] = set()
    for key, name, commits in ITEMS:
        added = deleted = 0
        files: set[str] = set()
        for commit in commits:
            a, d, f = stat_commit(commit)
            added += a
            deleted += d
            files |= f
        total_added += added
        total_deleted += deleted
        total_files |= files
        lines.append(
            f"| {key} | {name} | {' + '.join('`%s`' % c for c in commits)} "
            f"| {added} | {deleted} | {added + deleted} | {len(files)} |"
        )
    lines.append(
        f"| 合计 | 6 项维护 | — | {total_added} | {total_deleted} "
        f"| {total_added + total_deleted} | {len(total_files)} |"
    )
    lines += [
        "",
        "## 文件明细",
        "",
        "| 文件 | 所属维护项 | 新增 | 删除 |",
        "|---|---|---|---|",
    ]
    for key, _name, commits in ITEMS:
        per_file: dict[str, list[int]] = {}
        for commit in commits:
            out = git("show", "--numstat", "--format=", commit, "--", "src")
            for line in out.splitlines():
                parts = line.split("\t")
                if len(parts) != 3 or not parts[2].endswith(".java"):
                    continue
                a = int(parts[0]) if parts[0].isdigit() else 0
                d = int(parts[1]) if parts[1].isdigit() else 0
                row = per_file.setdefault(parts[2], [0, 0])
                row[0] += a
                row[1] += d
        for path in sorted(per_file):
            a, d = per_file[path]
            lines.append(f"| `{path}` | {key} | {a} | {d} |")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"items={len(ITEMS)} added={total_added} deleted={total_deleted} "
          f"files={len(total_files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
