"""CI：对已启动的 exe 内置服务截图"""
import os
from playwright.sync_api import sync_playwright
U = "http://127.0.0.1:38990/"
os.makedirs("shots", exist_ok=True)
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1360, "height": 820})
    for n, h in (("1-home", ""), ("2-tv", "#/more/tv/tv/tv_domestic/国产剧"), ("3-search", "#/search/繁花")):
        pg.goto(U + h); pg.wait_for_timeout(6000); pg.screenshot(path=f"shots/win-{n}.png")
    b.close()
