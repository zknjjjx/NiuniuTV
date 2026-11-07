"""CI 里用桌面 Chromium 模拟电视（1920x1080），假装原生层 NN，截几张界面图检查。"""
import concurrent.futures, asyncio, json, time, urllib.request, ssl, os, pathlib
from playwright.async_api import async_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/web"
OUT = pathlib.Path("shots"); OUT.mkdir(exist_ok=True)
ctx = ssl.create_default_context(); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130 Safari/537.36"

def http(url, headers, timeout):
    t0 = time.time()
    try:
        h = {"User-Agent": UA}; h.update(headers or {})
        r = urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout / 1000, context=ctx)
        return {"code": r.status, "body": r.read().decode("utf-8", "replace"), "ms": int((time.time() - t0) * 1000)}
    except urllib.error.HTTPError as e:
        return {"code": e.code, "body": "", "ms": 0}
    except Exception as e:
        return {"code": 0, "body": type(e).__name__, "ms": 0}

INIT = """
window.NN = {
  fetch(id, url, h, t) { window.pyFetch(url, h ? JSON.parse(h) : {}, t || 10000).then(r => window.__nnCb(id, r.code, r.body, r.ms)); },
  probe(id, url) { window.pyFetch(url, {}, 6000).then(r => window.__nnCb(id, 200, JSON.stringify({bps: r.code === 200 ? 1500000 + Math.random() * 3e6 : -1}), 0)); },
  play(j) { window.__played = JSON.parse(j); },
  history() { return JSON.stringify([]); }, lan() { return JSON.stringify({url: "http://192.168.2.50:9978", qr: "logo.png"}); }, delHistory() {}, version() { return "ci"; }, toast() {}, exit() {},
};
"""

async def main():
    asyncio.get_event_loop().set_default_executor(concurrent.futures.ThreadPoolExecutor(40))
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await b.new_page(viewport={"width": 1920, "height": 1080})
        logs = []
        pg.on("console", lambda m: logs.append(m.type + ": " + m.text))
        pg.on("pageerror", lambda e: logs.append("PAGEERROR: " + str(e)))
        async def pyFetch(url, h, t):
            return await asyncio.get_event_loop().run_in_executor(None, http, url, h, t)
        await pg.expose_function("pyFetch", pyFetch)
        async def img(route):
            r = await asyncio.get_event_loop().run_in_executor(None, lambda: urllib.request.urlopen(urllib.request.Request(route.request.url, headers={"Referer": "https://movie.douban.com/", "User-Agent": UA}), timeout=15, context=ctx).read())
            await route.fulfill(body=r, content_type="image/jpeg")
        await pg.route("**/*doubanio.com/**", img)
        await pg.add_init_script(INIT)
        await pg.goto((ROOT / "index.html").as_uri())
        await pg.wait_for_timeout(250)
        await pg.screenshot(path=OUT / "0a-splash.png")
        try:
            await pg.wait_for_selector("#splash.go", state="attached", timeout=6000)
            await pg.wait_for_timeout(380)
            await pg.screenshot(path=OUT / "0b-splash-fly.png")
        except Exception as e:
            logs.append("SPLASH: " + repr(e))
        await pg.wait_for_timeout(6000)
        logs.append("SPLASHGONE: " + str(await pg.evaluate("!document.getElementById('splash')")))
        await pg.screenshot(path=OUT / "1-home.png")
        logs.append("STARTFOCUS: " + await pg.evaluate("F.cur && F.cur.textContent"))
        for k in ["ArrowDown", "ArrowRight", "ArrowRight", "ArrowDown"]:
            await pg.keyboard.press(k); await pg.wait_for_timeout(500)
        await pg.wait_for_timeout(2500)
        await pg.screenshot(path=OUT / "2-home-row2.png")
        await pg.keyboard.press("Enter"); await pg.wait_for_timeout(10000)
        await pg.screenshot(path=OUT / "3-detail.png")
        for k in ["ArrowRight", "ArrowRight"] + ["ArrowDown"] * 8:
            await pg.keyboard.press(k); await pg.wait_for_timeout(400)
        logs.append("EPFOCUS: " + await pg.evaluate("F.cur && F.cur.className + ' ' + F.cur.textContent"))
        await pg.wait_for_timeout(1500)
        await pg.screenshot(path=OUT / "4-detail-down.png")
        await pg.keyboard.press("ArrowLeft"); await pg.wait_for_timeout(400)
        logs.append("EP-LEFT: " + await pg.evaluate("F.cur && F.cur.textContent"))
        await pg.keyboard.press("ArrowRight"); await pg.wait_for_timeout(400)
        await pg.keyboard.press("ArrowRight"); await pg.wait_for_timeout(400)
        logs.append("BTN-RIGHT: " + await pg.evaluate("F.cur && F.cur.textContent"))
        await pg.evaluate("F.set(document.getElementById('bIntro'))")
        await pg.keyboard.press("Enter"); await pg.wait_for_timeout(600)
        await pg.keyboard.press("Enter"); await pg.wait_for_timeout(600)
        logs.append("INTROCLOSE: layer=" + str(await pg.evaluate("document.getElementById('layer').classList.contains('on')")))
        await pg.evaluate("F.set(document.getElementById('bPlay'))")
        await pg.keyboard.press("Enter"); await pg.wait_for_timeout(1200)
        await pg.screenshot(path=OUT / "5-picking.png")
        await pg.wait_for_timeout(12000)
        played = await pg.evaluate("window.__played ? {title: __played.title, n: __played.sources.length, first: __played.sources.slice(0,3).map(s => s.site + ' ' + s.bps)} : null")
        logs.append("PLAYED: " + json.dumps(played, ensure_ascii=False))
        await pg.evaluate("nnBack()"); await pg.wait_for_timeout(800)
        for k in ["ArrowUp", "ArrowUp", "ArrowUp", "ArrowUp", "ArrowUp", "ArrowRight"]:
            await pg.keyboard.press(k); await pg.wait_for_timeout(400)
        await pg.wait_for_timeout(5000)
        await pg.screenshot(path=OUT / "6-movie.png")
        logs.append("TABFOCUS: " + await pg.evaluate("F.cur && F.cur.textContent + ' route=' + route.name + '/' + route.arg"))
        await pg.keyboard.press("ArrowDown"); await pg.wait_for_timeout(800)
        await pg.screenshot(path=OUT / "7-movie-down.png")
        logs.append("DOWN: " + await pg.evaluate("F.cur && F.cur.className + ' ' + (F.cur.dataset.title||'')"))
        await pg.keyboard.press("ArrowUp"); await pg.wait_for_timeout(300)
        await pg.keyboard.press("ArrowUp"); await pg.wait_for_timeout(300)
        for i in range(6):
            await pg.keyboard.press("ArrowRight"); await pg.wait_for_timeout(150)
        await pg.wait_for_timeout(3000)
        await pg.screenshot(path=OUT / "8-settings.png")
        logs.append("SET: " + await pg.evaluate("F.cur && F.cur.textContent + ' route=' + route.name"))
        for i in range(9):
            await pg.keyboard.press("ArrowDown"); await pg.wait_for_timeout(250)
        await pg.wait_for_timeout(600)
        await pg.screenshot(path=OUT / "8b-settings-bottom.png")
        logs.append("SETBOTTOM: " + await pg.evaluate("F.cur && F.cur.textContent"))
        await pg.evaluate("go('search')"); await pg.wait_for_timeout(1500)
        await pg.evaluate("['X','C','L','F'].forEach(c => [...document.querySelectorAll('.skb .key')].find(k => k.dataset.k === c)._ok())")
        await pg.wait_for_timeout(6000)
        await pg.screenshot(path=OUT / "9-search.png")
        logs.append("SEARCH: " + await pg.evaluate("document.querySelector('#sqt').textContent + ' ' + document.querySelectorAll('#sres .card').length"))
        await pg.evaluate("nnBack()"); await pg.wait_for_timeout(500)
        src = await pg.evaluate("JSON.stringify({n: SRC.list.length, from: SRC.from})")
        logs.append("SRC: " + src)
        (OUT / "log.txt").write_text("\n".join(logs), encoding="utf-8")
        print("\n".join(logs[-40:]))
        await b.close()

asyncio.run(main())
