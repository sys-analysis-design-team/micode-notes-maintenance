#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计「标注覆盖的代码行数」：每个新增注释块下方紧邻的声明（类/方法/字段）
实际覆盖的代码行，按类汇总。

用法：
    python tools/count_annotation_coverage.py [--base master]

口径：
- 注释块 = diff 中相邻的新增注释行（//、/*、*、*/）。
- 覆盖范围 = 注释块之后的第一行代码开始，方法/类按大括号配对到结束，
  字段按分号结束；同一行被多个注释块覆盖只算一次。
- 只统计 master 基线中存在的原始类。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

SRC_PREFIX = "src/net/micode/notes/"


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


def is_comment(text: str) -> bool:
    stripped = text.strip()
    return bool(stripped) and (
        stripped.startswith("//")
        or stripped.startswith("/*")
        or stripped.startswith("*")
        or stripped.startswith("*/")
    )


def package_of(path: str) -> str:
    rest = path[len(SRC_PREFIX):] if path.startswith(SRC_PREFIX) else ""
    return rest.split("/", 1)[0] if "/" in rest else "根"


def added_comment_lines(base: str, path: str) -> tuple[set[int], int]:
    """返回（当前文件中的新增注释行号集合, 注释行数）。行号从 1 开始。"""
    diff = git("diff", "-U0", base, "--", path)
    lines: set[int] = set()
    new_line = 0
    for raw in diff.splitlines():
        if raw.startswith("@@"):
            match = re.search(r"\+(\d+)(?:,(\d+))?", raw)
            if not match:
                continue
            new_line = int(match.group(1))
            continue
        if raw.startswith("+++") or raw.startswith("---"):
            continue
        if raw.startswith("+"):
            text = raw[1:]
            if is_comment(text):
                lines.add(new_line)
            new_line += 1
        elif raw.startswith(" "):
            new_line += 1
    return lines, len(lines)


def brace_end(lines: list[str], start: int) -> int:
    """从 start（0 基）开始，按大括号配对返回声明结束行（0 基）。"""
    depth = 0
    opened = False
    in_block = False
    i = start
    while i < len(lines):
        line = lines[i]
        j = 0
        while j < len(line):
            ch = line[j]
            if in_block:
                if line.startswith("*/", j):
                    in_block = False
                    j += 2
                    continue
                j += 1
                continue
            if line.startswith("//", j):
                break
            if line.startswith("/*", j):
                in_block = True
                j += 2
                continue
            if ch == '"' or ch == "'":
                quote = ch
                j += 1
                while j < len(line):
                    if line[j] == "\\":
                        j += 2
                        continue
                    if line[j] == quote:
                        break
                    j += 1
            elif ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth == 0:
                    return i
            elif ch == ";" and depth == 0 and not opened:
                return i
            j += 1
        i += 1
    return len(lines) - 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="master")
    args = parser.parse_args()

    base_files = {
        line for line in git("ls-tree", "-r", "--name-only", args.base, "--", "src").splitlines()
        if line.endswith(".java")
    }
    current = [line for line in git("ls-files", "src").splitlines() if line.endswith(".java")]

    rows: list[dict] = []
    for path in current:
        if path not in base_files:
            continue
        comment_lines, count = added_comment_lines(args.base, path)
        if not count:
            continue
        file_lines = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
        covered: set[int] = set()
        for line_no in sorted(comment_lines):
            idx = line_no  # 0 基：注释行的下一行
            # 跳过空行、其他注释、注解，找到声明起点
            while idx < len(file_lines):
                stripped = file_lines[idx].strip()
                if not stripped or is_comment(stripped) or stripped.startswith("@"):
                    idx += 1
                    continue
                break
            if idx >= len(file_lines):
                continue
            decl = file_lines[idx]
            if re.search(r"\b(class|interface|enum)\b", decl):
                # 类级注释只覆盖类声明本身（到左大括号），不把整个类体算作标注
                end = idx
                while end < len(file_lines) and "{" not in file_lines[end]:
                    end += 1
                if end >= len(file_lines):
                    end = idx
            else:
                end = brace_end(file_lines, idx)
            for k in range(idx, min(end, len(file_lines) - 1) + 1):
                covered.add(k)
        rows.append(
            {
                "path": path,
                "package": package_of(path),
                "class": Path(path).stem,
                "comment_lines": count,
                "covered_lines": len(covered),
            }
        )

    by_pkg: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        by_pkg[row["package"]].append(row)

    out = [
        "# 标注覆盖统计",
        "",
        f"> 基线：`{args.base}`；覆盖行 = 注释块后紧邻声明的代码行（去重）。",
        "",
        "| 包 | 已标注类数 | 注释行 | 覆盖代码行 |",
        "|---|---|---|---|",
    ]
    for pkg in sorted(by_pkg):
        pkg_rows = by_pkg[pkg]
        out.append(
            f"| {pkg} | {len(pkg_rows)} | "
            f"{sum(r['comment_lines'] for r in pkg_rows)} | "
            f"{sum(r['covered_lines'] for r in pkg_rows)} |"
        )
    out.append(
        f"| 合计 | {len(rows)} | {sum(r['comment_lines'] for r in rows)} | "
        f"{sum(r['covered_lines'] for r in rows)} |"
    )
    out += ["", "## 按类明细", "", "| 类 | 包 | 注释行 | 覆盖代码行 |", "|---|---|---|---|"]
    for row in sorted(rows, key=lambda r: (-r["covered_lines"], r["path"])):
        out.append(
            f"| {row['class']} | {row['package']} | {row['comment_lines']} | {row['covered_lines']} |"
        )

    Path("docs/analysis/annotation_coverage.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"classes={len(rows)} comment_lines={sum(r['comment_lines'] for r in rows)} "
          f"covered={sum(r['covered_lines'] for r in rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
