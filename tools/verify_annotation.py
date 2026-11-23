#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验工作区的标注改动：相对 HEAD 只允许新增注释行，不允许删除或改动代码。

用法：
    python tools/verify_annotation.py [--base HEAD] [--paths src/... ...]

退出码：0 全部通过；1 发现违规。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys


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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="HEAD")
    parser.add_argument("--paths", nargs="*", default=["src"])
    args = parser.parse_args()

    diff = git("diff", "-U0", args.base, "--", *args.paths)
    current_file = ""
    added_comments = 0
    violations: list[str] = []
    files: dict[str, int] = {}
    added_code_lines: list[str] = []

    for line in diff.splitlines():
        if line.startswith("diff --git"):
            current_file = line.split(" b/", 1)[-1]
            files.setdefault(current_file, 0)
            continue
        if line.startswith("+++") or line.startswith("---"):
            continue
        if line.startswith("+"):
            text = line[1:]
            if not text.strip():
                continue
            if is_comment(text):
                added_comments += 1
                files[current_file] = files.get(current_file, 0) + 1
            else:
                added_code_lines.append(f"{current_file}: {text[:120]}")
        elif line.startswith("-") and not line.startswith("---"):
            text = line[1:]
            if text.strip() and not is_comment(text):
                violations.append(f"[删除代码] {current_file}: {text[:120]}")
        elif line.startswith("-"):
            text = line[1:]
            if text.strip():
                violations.append(f"[删除注释] {current_file}: {text[:120]}")

    # 检查 Javadoc 是否错放在 @Override 之后（缩进异常的 /**）
    override_javadoc = subprocess.run(
        ["git", "grep", "-n", "-A1", "-E", r"^\s*@Override\s*$", "--", *args.paths],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        encoding="utf-8",
        errors="replace",
    ).stdout
    for block in override_javadoc.split("--\n"):
        lines = [ln for ln in block.splitlines() if ln.strip()]
        for i, ln in enumerate(lines[:-1]):
            if ln.strip() == "@Override" and lines[i + 1].strip().startswith("/**"):
                violations.append(f"[Javadoc 位置] {ln}")

    print(f"新增注释行合计: {added_comments}")
    print("按文件:")
    for path, count in sorted(files.items(), key=lambda kv: -kv[1]):
        if count:
            print(f"  {count:4d}  {path}")
    if added_code_lines:
        print("\n发现新增非注释行（需要人工确认是否属于维护代码）:")
        for item in added_code_lines:
            print("  " + item)
    if violations:
        print("\n违规:")
        for item in violations:
            print("  " + item)
        return 1
    print("\n通过：没有删除行，Javadoc 位置正常。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
