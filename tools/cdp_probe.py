# -*- coding: utf-8 -*-
"""Read-only probe of the CDP browser used by browserctl chat."""
from playwright.sync_api import sync_playwright

OUT = r"D:\AI\Workspace\Projects\mipnote-oss\logs\gpt_page.png"

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9333")
    for ctx in browser.contexts:
        for i, page in enumerate(ctx.pages):
            print(f"[{i}] {page.url} | {page.title()}")
    ctx = browser.contexts[0]
    pages = [pg for pg in ctx.pages if "chatgpt.2233.ai" in pg.url]
    if not pages:
        raise SystemExit("no chat page")
    page = pages[0]
    body = page.inner_text("body")
    with open(r"D:\AI\Workspace\Projects\mipnote-oss\logs\gpt_page.txt", "w", encoding="utf-8") as f:
        f.write(body)
    page.screenshot(path=OUT, full_page=False)
    print("body chars:", len(body), "saved", OUT)
