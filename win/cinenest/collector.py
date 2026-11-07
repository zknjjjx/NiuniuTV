"""读取用户设置的影视源接口，合并去重写出 /data/sites.json（不测速、不删源）
- 接口在首页「设置」里填（/data/settings.json），可多个，启用的按顺序合并；/data/extra.txt 可手动补充
- 顺序沿用接口内顺序（公共接口已按人气排好）；播放时前端实时测速，自动跳过连不上的源
- 输出 /data/sites.json 和 /data/status.json
- 每天 RUN_AT(默认 04:00 Asia/Shanghai) 跑一次；/data/trigger 文件出现时立即重跑（保存设置时会触发）
"""
import asyncio, json, os, re, time, datetime, random
from urllib.parse import urlparse
import aiohttp

DATA = os.environ.get("DATA_DIR", "/data")
RUN_AT = os.environ.get("RUN_AT", "04:00")
TIMEOUT = float(os.environ.get("CHECK_TIMEOUT", "15"))
ALLOW_ADULT = os.environ.get("ALLOW_ADULT", "0") == "1"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

API_RE = re.compile(r"https?://[A-Za-z0-9._-]+(?::\d+)?(?:/[A-Za-z0-9._-]+)*?/api\.php/provide/vod", re.I)
NAME_RE = re.compile(r"""["']?name["']?\s*[:=]\s*["']([^"'\n]{1,30})["']""")
ADULT_WORDS = ["伦理", "福利", "成人", "写真", "三级", "里番", "AV", "无码", "有码", "SM", "色情", "18+", "国产自拍", "强奸", "乱伦", "制服"]


def log(*a):
    print(datetime.datetime.now().strftime("%m-%d %H:%M:%S"), *a, flush=True)


def norm(api):
    api = api.strip().rstrip("/")
    return api


def key_of(api):
    p = urlparse(api)
    return (p.hostname or "").lower().removeprefix("www.") + p.path.lower()


async def fetch_text(session, url, timeout=15):
    async with session.get(url, timeout=aiohttp.ClientTimeout(total=timeout), headers={"User-Agent": UA}) as r:
        return await r.text(errors="replace")


async def gather_candidates(session):
    import conf
    cands, up_stats = {}, {}
    cfg = conf.load()
    for it in conf.interfaces(cfg):
        if it.get("on") is False:
            continue
        url = it["url"].strip()
        got = None
        for u in conf.mirrors(url, cfg.get("gh_proxy")):
            try:
                text = await fetch_text(session, u, 12)
                got = conf.parse(text)
                if got:
                    conf.cache_put(url, got)
                    break
            except Exception:
                continue
        if not got:
            c = conf.cache_get(url)
            got = (c or {}).get("sites") or []
            up_stats[it.get("name") or url] = f"拉取失败，用缓存 {len(got)}" if got else "拉取失败"
        else:
            up_stats[it.get("name") or url] = len(got)
        for s in got:
            if s.get("adult") and not ALLOW_ADULT:
                continue
            cands.setdefault(key_of(s["api"]), {"api": norm(s["api"]), "name": s["name"]})
    try:
        for line in open(os.path.join(DATA, "extra.txt"), encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(",", 1)
            api = parts[-1].strip(); name = parts[0].strip() if len(parts) > 1 else ""
            cands[key_of(api)] = {"api": norm(api), "name": name or urlparse(api).hostname}
    except FileNotFoundError:
        pass
    log("候选接口", len(cands), up_stats)
    return list(cands.values()), up_stats


async def run_once():
    """不测速、不过滤：按接口顺序（接口内已按人气排好）合并去重后直接写出，播放时由前端测速并跳过不通的源"""
    started = time.time()
    conn = aiohttp.TCPConnector(ssl=False, limit=10)
    async with aiohttp.ClientSession(connector=conn) as session:
        cands, up_stats = await gather_candidates(session)
    used, out = set(), []
    for s in cands:
        name = s["name"] or urlparse(s["api"]).hostname
        if name in used:
            name = f'{name}·{(urlparse(s["api"]).hostname or "").removeprefix("www.").split(".")[0]}'
        used.add(name)
        out.append({"name": name, "api": s["api"]})
    os.makedirs(DATA, exist_ok=True)
    tmp = os.path.join(DATA, "sites.json.tmp")
    json.dump(out, open(tmp, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    os.replace(tmp, os.path.join(DATA, "sites.json"))
    status = {"last_run": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
              "candidates": len(out), "interfaces": len(up_stats),
              "seconds": round(time.time() - started, 1), "upstreams": up_stats}
    json.dump(status, open(os.path.join(DATA, "status.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    log(f"完成：{len(out)} 个源，用时 {status['seconds']}s")


def seconds_until(hhmm):
    now = datetime.datetime.now()
    h, m = map(int, hhmm.split(":"))
    t = now.replace(hour=h, minute=m, second=0, microsecond=0)
    if t <= now:
        t += datetime.timedelta(days=1)
    return (t - now).total_seconds()


async def main():
    trigger = os.path.join(DATA, "trigger")
    sj = os.path.join(DATA, "sites.json")
    cfg = os.path.join(DATA, "settings.json")
    fresh = os.path.exists(sj) and time.time() - os.path.getmtime(sj) < 20 * 3600
    if fresh and os.path.exists(cfg) and os.path.getmtime(cfg) > os.path.getmtime(sj):
        fresh = False
    if fresh and not os.path.exists(trigger):
        log("已有近期检测结果，跳过启动时检测")
    else:
        await run_once()
    while True:
        wait = seconds_until(RUN_AT)
        log(f"下次自动检测：{RUN_AT}（{int(wait/3600)} 小时后）")
        while wait > 0:
            if os.path.exists(trigger):
                break
            await asyncio.sleep(min(3, wait)); wait -= 3
        if os.path.exists(trigger):
            try: os.remove(trigger)
            except Exception: pass
            log("收到手动刷新")
        try:
            await run_once()
        except Exception as e:
            log("运行出错", e)


if __name__ == "__main__":
    asyncio.run(main())
