#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在本机 SonarQube 上扫描「原始代码」与「维护后代码」两个项目并导出 measures。

前置：SonarQube 已在 localhost:9000 运行（初始账号 admin/admin），
SonarScanner CLI 在 D:/AI/Tools/sonar-scanner-8.5.0.16571-windows-x64。
token 在脚本内存中生成，不打印、不落盘。

用法：
    python tools/scan_sonar.py --base D:/AI/Temp/mipnote-base
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

SONAR = "http://localhost:9000"
SCANNER = Path("D:/AI/Tools/sonar-scanner-8.5.0.16571-windows-x64/bin/sonar-scanner.bat")
JAVA_HOME = "C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot"
REPO_ROOT = Path(__file__).resolve().parents[1]
OUT = REPO_ROOT / "docs" / "analysis" / "sonar"

METRICS = ",".join([
    "files", "classes", "functions", "ncloc", "comment_lines",
    "comment_lines_density", "complexity", "cognitive_complexity",
    "duplicated_lines_density", "bugs", "vulnerabilities", "code_smells",
    "sqale_index", "reliability_rating", "security_rating", "sqale_rating",
])


def request(method: str, path: str, token: str | None = None, body: bytes | None = None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Basic " + base64.b64encode(
            f"{token}:".encode()).decode()
    else:
        headers["Authorization"] = "Basic " + base64.b64encode(b"admin:admin").decode()
    req = urllib.request.Request(SONAR + path, method=method, headers=headers, data=body)
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def generate_token() -> str:
    name = f"mipnote-{date.today().isoformat()}-{int(time.time()) % 100000}"
    data = request("POST", f"/api/user_tokens/generate?name={name}")
    return data["token"]


def wait_ce(project_key: str, timeout: int = 300) -> None:
    """等 CE 分析结束。注意 /api/ce/component 的 current 在任务完成后仍保留
    最后一条记录（status=SUCCESS），必须按 status 判断而不是判空。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        data = request("GET", f"/api/ce/component?component={project_key}")
        tasks = list(data.get("queue") or [])
        if data.get("current"):
            tasks.append(data["current"])
        if not any(t.get("status") in ("PENDING", "IN_PROGRESS") for t in tasks):
            return
        time.sleep(5)
    raise SystemExit(f"CE timeout for {project_key}")


def scan(path: Path, token: str) -> None:
    env = dict(os.environ, JAVA_HOME=JAVA_HOME, SONAR_TOKEN=token)
    print(f"scanning {path} ...")
    result = subprocess.run(
        [str(SCANNER), f"-Dsonar.host.url={SONAR}", f"-Dsonar.token={token}"],
        cwd=str(path), env=env,
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    tail = "\n".join((result.stdout or "").splitlines()[-6:])
    print(tail)
    if result.returncode != 0:
        print((result.stderr or "")[-800:])
        raise SystemExit(f"scanner failed: {result.returncode}")


def measures(project_key: str) -> dict:
    q = urllib.parse.urlencode({"component": project_key, "metricKeys": METRICS})
    data = request("GET", f"/api/measures/component?{q}")
    return {m["metric"]: m.get("value") for m in data["component"].get("measures", [])}


def issue_facets(project_key: str) -> dict:
    q = urllib.parse.urlencode({"componentKeys": project_key, "facets": "rules,types",
                                "resolved": "false", "ps": "1"})
    data = request("GET", f"/api/issues/search?{q}")
    out = {"total": data.get("total"), "rules": {}, "types": {}}
    for facet in data.get("facets", []):
        bucket = out["rules" if facet["property"] == "rules" else "types"]
        for item in facet.get("values", []):
            bucket[item["val"]] = item["count"]
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="D:/AI/Temp/mipnote-base")
    parser.add_argument("--skip-base", action="store_true")
    parser.add_argument("--skip-after", action="store_true")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    token = generate_token()

    if not args.skip_base:
        scan(Path(args.base), token)
        wait_ce("micode-notes-base")
    if not args.skip_after:
        scan(REPO_ROOT, token)
        wait_ce("micode-notes")

    results = {}
    for key, label in (("micode-notes-base", "measures_base"),
                       ("micode-notes", "measures_after")):
        m = measures(key)
        i = issue_facets(key)
        results[key] = {"measures": m, "issues": i}
        (OUT / f"{label}.json").write_text(
            json.dumps(results[key], ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        print(key, "measures:", m)
        print(key, "issues:", i["total"], i["rules"])

    base = results.get("micode-notes-base", {}).get("measures", {})
    after = results.get("micode-notes", {}).get("measures", {})
    if base and after:
        lines = ["# SonarQube 扫描摘要", "",
                 f"> 扫描时间 {date.today().isoformat()}；规则集 Sonar way（Java）。", "",
                 "| 指标 | 原始代码 | 维护后 |", "|---|---|---|"]
        for key in [k for k in METRICS.split(",") if k not in ("files", "classes", "functions")]:
            lines.append(f"| {key} | {base.get(key)} | {after.get(key)} |")
        lines.append(f"| files | {base.get('files')} | {after.get('files')} |")
        lines.append(f"| classes | {base.get('classes')} | {after.get('classes')} |")
        lines.append(f"| functions | {base.get('functions')} | {after.get('functions')} |")
        lines += ["", "## 问题明细", ""]
        for key, label in (("micode-notes-base", "原始代码"), ("micode-notes", "维护后")):
            info = results[key]["issues"]
            lines.append(f"- {label}：{info['total']} 个问题；规则分布 {info['rules']}")
        (OUT / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("wrote", OUT / "summary.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
