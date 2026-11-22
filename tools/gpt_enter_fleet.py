# -*- coding: utf-8 -*-
"""Enter the first fleet on chatgpt.2233.ai and report the resulting page."""
from playwright.sync_api import sync_playwright

LOG = r"D:\AI\Workspace\Projects\mipnote-oss\logs\gpt_after_enter.txt"
SHOT = r"D:\AI\Workspace\Projects\mipnote-oss\logs\gpt_after_enter.png"

with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp("http://127.0.0.1:9333")
    ctx = browser.contexts[0]
    page = next(pg for pg in ctx.pages if "chatgpt.2233.ai/list" in pg.url)
    page.get_by_text("进入", exact=True).first.click()
    page.wait_for_timeout(5000)
    with open(LOG, "w", encoding="utf-8") as f:
        f.write(f"URL: {page.url}\nTITLE: {page.title()}\n\n")
        f.write(page.inner_text("body")[:4000])
    page.screenshot(path=SHOT, full_page=False)
    print("URL:", page.url)
    print("TITLE:", page.title())
