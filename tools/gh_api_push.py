#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 GitHub Git Data API 把本地一个提交的改动推到远端分支（git push 被网络重置时的兜底）。

做法：读本地提交的变动文件 → 逐个建 blob（删除的置空）→ 带 base_tree 建 tree
→ 在远端分支顶端建等价 commit → PATCH ref。鉴权复用 gh CLI 的 token，不打印。

用法：
    python tools/gh_api_push.py --repo owner/name [--branch develop] [--commit HEAD]

安全校验：本地提交的 parent 必须等于远端分支当前顶端，否则拒绝（防止覆盖他人提交）。
注意：API 生成的 commit 的 author/committer 时间与本地不同，SHA 也就不同；
远程与本地内容一致但历史分叉，网络恢复后需要 `git fetch` + `git reset --hard origin/develop`
（或等价操作）重新对齐。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

API = "https://api.github.com"
GH = os.environ.get("GH_BIN", r"D:\AI\Tools\gh\bin\gh.exe")
REPO_ROOT = Path(__file__).resolve().parents[1]


def gh_token() -> str:
    out = subprocess.run([GH, "auth", "token"], capture_output=True, check=True)
    return out.stdout.decode("utf-8", "replace").strip()


def api(token: str, method: str, path: str, body=None, allow=(200, 201)):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        API + path, data=data, method=method,
        headers={
            "Authorization": f"token {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "notes-api-push",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        if e.code in allow:
            try:
                return json.loads(e.read().decode("utf-8") or "{}")
            except json.JSONDecodeError:
                return {}
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: "
                           f"{e.read().decode('utf-8', 'replace')[:300]}")


def git(*args: str) -> str:
    # core.quotepath=false：中文文件名按原样输出，否则会被转义成 \345\260\217 找不到文件
    result = subprocess.run(["git", "-c", "core.quotepath=false", *args],
                            capture_output=True, cwd=REPO_ROOT)
    if result.returncode != 0:
        sys.stderr.write(result.stderr.decode("utf-8", "replace"))
        raise SystemExit(result.returncode)
    return result.stdout.decode("utf-8", "replace")


def changed_files(commit: str) -> list[tuple[str, str, str]]:
    """返回 [(状态, 路径, 旧路径)]；R/C 拆成删除旧路径 + 新增新路径。"""
    out = git("diff-tree", "-r", "-M", "--name-status", "--no-commit-id", commit)
    entries: list[tuple[str, str, str]] = []
    for line in out.splitlines():
        parts = line.split("\t")
        status = parts[0][0]
        if status in ("R", "C"):
            entries.append(("D", parts[1], parts[1]))
            entries.append(("A", parts[2], parts[2]))
        elif status == "D":
            entries.append(("D", parts[1], parts[1]))
        else:
            entries.append(("A", parts[1], parts[1]))
    return entries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="owner/name")
    parser.add_argument("--branch", default="develop")
    parser.add_argument("--commit", default="HEAD")
    parser.add_argument("--message", default=None)
    args = parser.parse_args()

    token = gh_token()
    local_sha = git("rev-parse", args.commit).strip()
    message = args.message or git("log", "-1", "--format=%B", local_sha).rstrip()
    parent = git("rev-parse", f"{local_sha}^").strip()

    head = api(token, "GET", f"/repos/{args.repo}/git/ref/heads/{args.branch}")
    remote_sha = head["object"]["sha"]
    print(f"local={local_sha[:8]} parent={parent[:8]} remote={remote_sha[:8]}")
    if remote_sha == local_sha:
        print("ALREADY PUSHED")
        return 0
    if parent != remote_sha:
        # 允许「内容一致但历史分叉」：之前走过 API 通道推送时，远端 head 与本地
        # parent 的树相同、只是 SHA 不同。树一致即视为已同步，继续以远端 head 为 parent。
        remote_tree = api(token, "GET",
                          f"/repos/{args.repo}/git/commits/{remote_sha}")["tree"]["sha"]
        local_parent_tree = git("rev-parse", f"{parent}^{{tree}}").strip()
        if local_parent_tree != remote_tree:
            print("REFUSE: remote head moved and tree differs (fetch and rebase first)")
            return 2
        print("NOTE: local parent tree == remote head tree; continuing on remote head")

    base_tree = api(token, "GET", f"/repos/{args.repo}/git/commits/{remote_sha}")["tree"]["sha"]

    tree_items = []
    for status, path, _old in changed_files(local_sha):
        if status == "D":
            tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            print(f"DELETED {path}")
            continue
        full = REPO_ROOT / path
        content = base64.b64encode(full.read_bytes()).decode("ascii")
        blob = api(token, "POST", f"/repos/{args.repo}/git/blobs",
                   {"content": content, "encoding": "base64"})
        tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print(f"BLOB {path} {blob['sha'][:8]}")

    tree = api(token, "POST", f"/repos/{args.repo}/git/trees",
               {"base_tree": base_tree, "tree": tree_items})
    commit = api(token, "POST", f"/repos/{args.repo}/git/commits",
                 {"message": message, "tree": tree["sha"], "parents": [remote_sha]})
    api(token, "PATCH", f"/repos/{args.repo}/git/refs/heads/{args.branch}",
        {"sha": commit["sha"]})
    print(f"OK commit={commit['sha']}")
    print(f"URL https://github.com/{args.repo}/commit/{commit['sha']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
