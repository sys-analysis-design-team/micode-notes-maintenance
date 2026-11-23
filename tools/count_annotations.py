#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计代码标注（相对原始基线的注释行增量），输出 Markdown 与 CSV。

用法：
    python tools/count_annotations.py [--base master] [--out docs/analysis/annotation_stats.md]
"""

from __future__ import annotations

import argparse
import csv
import subprocess
import sys
from collections import defaultdict
from pathlib import Path


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


def is_comment_line(text: str) -> bool:
    stripped = text.strip()
    return bool(stripped) and (
        stripped.startswith("//")
        or stripped.startswith("/*")
        or stripped.startswith("*")
        or stripped.startswith("*/")
    )


def package_of(path: str) -> str:
    prefix = "src/net/micode/notes/"
    if not path.startswith(prefix):
        return "其他"
    rest = path[len(prefix):]
    return rest.split("/", 1)[0] if "/" in rest else "根"


def collect(base: str) -> list[dict]:
    files = [line for line in git("ls-files", "src").splitlines() if line.endswith(".java")]
    base_files = set(
        line for line in git("ls-tree", "-r", "--name-only", base, "--", "src").splitlines()
        if line.endswith(".java")
    )
    rows = []
    for path in files:
        diff = git("diff", "-U0", base, "--", path)
        added_comments = 0
        added_code = 0
        javadoc_markers = 0
        for line in diff.splitlines():
            if not line.startswith("+") or line.startswith("+++"):
                continue
            text = line[1:]
            if not text.strip():
                continue
            if is_comment_line(text):
                added_comments += 1
                if "/**" in text:
                    javadoc_markers += 1
            else:
                added_code += 1
        rows.append(
            {
                "path": path,
                "package": package_of(path),
                "in_base": path in base_files,
                "added_comments": added_comments,
                "added_code": added_code,
                "javadoc_blocks": javadoc_markers,
            }
        )
    return rows


def render(rows: list[dict], base: str) -> str:
    base_rows = [row for row in rows if row["in_base"]]
    new_rows = [row for row in rows if not row["in_base"]]
    total_comments = sum(row["added_comments"] for row in base_rows)
    total_files = sum(1 for row in base_rows if row["added_comments"] > 0)
    by_pkg: dict[str, list[dict]] = defaultdict(list)
    for row in base_rows:
        by_pkg[row["package"]].append(row)

    lines = [
        "# 代码标注统计",
        "",
        f"> 基线：`{base}`；统计方法：`git diff -U0` 新增行中注释行（`//`、`/*`、`*`）。",
        "",
        "## 原始代码（master 基线类）",
        "",
        "| 包 | 类数 | 已标注类数 | 新增注释行 | Javadoc 块 |",
        "|---|---|---|---|---|",
    ]
    for pkg in sorted(by_pkg):
        pkg_rows = by_pkg[pkg]
        marked = sum(1 for row in pkg_rows if row["added_comments"] > 0)
        lines.append(
            f"| {pkg} | {len(pkg_rows)} | {marked} | "
            f"{sum(r['added_comments'] for r in pkg_rows)} | "
            f"{sum(r['javadoc_blocks'] for r in pkg_rows)} |"
        )
    lines.append(f"| 合计 | {len(base_rows)} | {total_files} | {total_comments} | "
                 f"{sum(r['javadoc_blocks'] for r in base_rows)} |")
    lines += [
        "",
        "## 按类明细（原始类）",
        "",
        "| 类 | 包 | 新增注释行 | Javadoc 块 |",
        "|---|---|---|---|",
    ]
    for row in sorted(base_rows, key=lambda r: (-r["added_comments"], r["path"])):
        name = Path(row["path"]).stem
        lines.append(
            f"| {name} | {row['package']} | {row['added_comments']} | "
            f"{row['javadoc_blocks']} |"
        )
    lines += ["", "## 维护新增类（不计入标注统计）", "",
              "| 类 | 包 | 新增代码行 | 新增注释行 |",
              "|---|---|---|---|"]
    for row in sorted(new_rows, key=lambda r: r["path"]):
        name = Path(row["path"]).stem
        lines.append(
            f"| {name} | {row['package']} | {row['added_code']} | "
            f"{row['added_comments']} |"
        )
    lines += ["", "## 口径说明", "",
              "- 新增注释行：注释文字行数，不含空行；许可证头不计（基线已有）。",
              "- 新增代码行：维护功能新增的代码行，不属于标注统计。",
              "- 已标注类数：相对基线至少新增 1 行注释的原始类。",
              "- 新增类单独列出，避免把维护代码的注释算进标注工作量。", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="master")
    parser.add_argument("--out", default="docs/analysis/annotation_stats.md")
    parser.add_argument("--csv", default="docs/analysis/annotation_stats.csv")
    args = parser.parse_args()

    rows = collect(args.base)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render(rows, args.base), encoding="utf-8")

    with Path(args.csv).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    base_rows = [row for row in rows if row["in_base"]]
    marked = sum(1 for row in base_rows if row["added_comments"] > 0)
    print(f"base_classes={len(base_rows)} marked={marked} "
          f"comment_lines={sum(r['added_comments'] for r in base_rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
