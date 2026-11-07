"""CMS 影视聚合网页播放后端
/api/sites    有效站点（来自采集容器生成的 /data/sites.json）
/api/search   并发搜索所有站点
/api/detail   取某站某片的分集
/api/latest   某站最新更新
/m3u8         拉取 m3u8 → 去广告 → 改写为绝对地址（可选全代理）
/seg          切片/密钥代理（仅在直连失败时使用）
/img          海报代理
"""
import asyncio, json, os, re, time
from urllib.parse import urljoin, quote, urlparse
import aiohttp
from aiohttp import web

DATA = os.environ.get("DATA_DIR", "/data")
STATIC = os.environ.get("STATIC_DIR") or os.path.join(os.path.dirname(__file__), "static")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
SEARCH_TIMEOUT = float(os.environ.get("SEARCH_TIMEOUT", "5"))
_sites_cache = {"mtime": 0, "sites": []}


def sites():
    p = os.path.join(DATA, "sites.json")
    try:
        m = os.path.getmtime(p)
        if m != _sites_cache["mtime"]:
            _sites_cache["sites"] = json.load(open(p, encoding="utf-8"))
            _sites_cache["mtime"] = m
    except Exception:
        pass
    return _sites_cache["sites"]


def site_by_api(api):
    for s in sites():
        if s["api"] == api:
            return s
    return None


async def get_json(session, url, timeout):
    async with session.get(url, timeout=aiohttp.ClientTimeout(total=timeout), headers={"User-Agent": UA}) as r:
        return json.loads(await r.text(errors="replace"))


def parse_episodes(v):
    froms = (v.get("vod_play_from") or "").split("$$$")
    urls = (v.get("vod_play_url") or "").split("$$$")
    groups = []
    for i, u in enumerate(urls):
        eps = []
        for idx, item in enumerate(u.split("#")):
            if not item.strip():
                continue
            if "$" in item:
                name, link = item.split("$", 1)
            else:
                name, link = f"第{idx+1}集", item
            link = link.strip()
            if link.startswith("http"):
                eps.append({"name": name.strip() or f"第{idx+1}集", "url": link})
        if eps:
            groups.append({"from": froms[i] if i < len(froms) else f"线路{i+1}", "eps": eps,
                           "m3u8": any(".m3u8" in e["url"] for e in eps)})
    groups.sort(key=lambda g: not g["m3u8"])
    return [g for g in groups if g["m3u8"]] or groups


def brief(v, s):
    return {"id": v.get("vod_id"), "name": v.get("vod_name", ""), "pic": v.get("vod_pic", ""),
            "remarks": v.get("vod_remarks", ""), "year": v.get("vod_year", ""),
            "type": v.get("type_name", ""), "area": v.get("vod_area", ""),
            "site": s["name"], "api": s["api"]}


async def api_sites(req):
    st = {}
    try:
        st = json.load(open(os.path.join(DATA, "status.json"), encoding="utf-8"))
    except Exception:
        pass
    return web.json_response({"sites": sites(), "status": st})


async def api_refresh(req):
    if not _authed(req):
        return web.json_response({"ok": False, "msg": "请先在设置里输入密码"}, status=401)
    open(os.path.join(DATA, "trigger"), "w").write(str(time.time()))
    return web.json_response({"ok": True})


async def api_search(req):
    wd = req.query.get("wd", "").strip()
    only = req.query.get("api")
    if not wd:
        return web.json_response({"list": []})
    session = req.app["session"]
    targets = [s for s in sites() if not only or s["api"] == only]

    async def one(s):
        try:
            d = await get_json(session, f"{s['api']}/?ac=videolist&wd={quote(wd)}", SEARCH_TIMEOUT)
            return [brief(v, s) for v in (d.get("list") or [])]
        except Exception:
            return []
    res = await asyncio.gather(*[one(s) for s in targets])
    items = [x for r in res for x in r]
    # 按片名+年份聚合，多个站点作为多条线路
    groups = {}
    for it in items:
        k = re.sub(r"\s+", "", it["name"]) + "|" + str(it["year"])
        g = groups.setdefault(k, {**it, "sources": []})
        g["sources"].append({"site": it["site"], "api": it["api"], "id": it["id"], "remarks": it["remarks"]})
        if not g["pic"] and it["pic"]:
            g["pic"] = it["pic"]
    out = list(groups.values())
    exact = lambda g: (g["name"] != wd, wd not in g["name"], -len(g["sources"]))
    out.sort(key=exact)
    return web.json_response({"list": out, "sites": len(targets)})


async def api_detail(req):
    api, vid = req.query.get("api", ""), req.query.get("id", "")
    s = site_by_api(api)
    if not s:
        return web.json_response({"error": "未知站点"}, status=400)
    try:
        d = await get_json(req.app["session"], f"{api}/?ac=videolist&ids={quote(vid)}", 10)
        v = (d.get("list") or [None])[0]
        if not v:
            return web.json_response({"error": "未找到"}, status=404)
        info = brief(v, s)
        info.update({"actor": v.get("vod_actor", ""), "director": v.get("vod_director", ""),
                     "content": re.sub(r"<[^>]+>", "", v.get("vod_content") or v.get("vod_blurb") or "").strip(),
                     "groups": parse_episodes(v)})
        return web.json_response(info)
    except Exception as e:
        return web.json_response({"error": f"获取失败: {type(e).__name__}"}, status=502)


_cls_cache = {}


async def get_classes(session, api):
    s = site_by_api(api)
    if s and s.get("classes"):
        return s["classes"]  # 采集容器每天统计好的（已剔除空分类）
    c = _cls_cache.get(api)
    if c and time.time() - c[0] < 3600:
        return c[1]
    d = await get_json(session, f"{api}/?ac=list", 8)
    cls = [{"id": str(x.get("type_id")), "pid": str(x.get("type_pid", 0) or 0), "name": x.get("type_name"), "n": -1} for x in d.get("class") or []]
    _cls_cache[api] = (time.time(), cls)
    return cls


async def api_latest(req):
    api = req.query.get("api") or (sites()[0]["api"] if sites() else "")
    s = site_by_api(api)
    if not s:
        return web.json_response({"list": [], "classes": []})
    pg = req.query.get("pg", "1"); t = req.query.get("t", "")
    session = req.app["session"]
    try:
        classes = []
        try:
            classes = await get_classes(session, api)
        except Exception:
            pass
        url = f"{api}/?ac=videolist&pg={quote(pg)}" + (f"&t={quote(t)}" if t else "")
        d = await get_json(session, url, 10)
        items, pagecount = d.get("list") or [], int(d.get("pagecount") or 1)
        children = [c["id"] for c in classes if c["pid"] == t] if t else []
        if t and not items and children:
            # 大类本身没挂视频：合并其子分类
            async def one(cid):
                try:
                    return await get_json(session, f"{api}/?ac=videolist&pg={quote(pg)}&t={cid}", 10)
                except Exception:
                    return {}
            kids = sorted([c for c in classes if c["pid"] == t], key=lambda c: -c.get("n", -1))[:8]
            sem = asyncio.Semaphore(4)
            async def lim(cid):
                async with sem:
                    return await one(cid)
            res = await asyncio.gather(*[lim(c["id"]) for c in kids])
            items = [v for r in res for v in (r.get("list") or [])]
            items.sort(key=lambda v: v.get("vod_time") or "", reverse=True)
            pagecount = max([int(r.get("pagecount") or 1) for r in res if r] or [1])
        return web.json_response({"list": [brief(v, s) for v in items], "classes": classes,
                                  "pagecount": pagecount, "site": s["name"], "api": api})
    except Exception as e:
        return web.json_response({"list": [], "classes": [], "error": type(e).__name__})


# ---------------- m3u8 去广告 ----------------
def filter_ads(lines):
    """按 #EXT-X-DISCONTINUITY 分段。广告段特征：切片目录与正片主目录不同，或是夹在中间的短段(<=45s)。"""
    header, segs, cur, groups = [], [], None, []
    group = []
    pending = []
    for ln in lines:
        if ln.startswith("#EXT-X-DISCONTINUITY") and not ln.startswith("#EXT-X-DISCONTINUITY-SEQUENCE"):
            if group:
                groups.append(group); group = []
            continue
        if not segs and not group and not groups and (ln.startswith("#EXTM3U") or ln.startswith("#EXT-X-VERSION")
                                                     or ln.startswith("#EXT-X-TARGETDURATION") or ln.startswith("#EXT-X-MEDIA-SEQUENCE")
                                                     or ln.startswith("#EXT-X-PLAYLIST-TYPE") or ln.startswith("#EXT-X-ALLOW-CACHE")):
            header.append(ln); continue
        if ln.startswith("#EXT-X-ENDLIST"):
            continue
        pending.append(ln)
        if ln and not ln.startswith("#"):
            dur = 0.0
            for p in pending:
                if p.startswith("#EXTINF:"):
                    try: dur = float(p[8:].split(",")[0])
                    except Exception: pass
            group.append({"lines": pending, "uri": ln, "dur": dur}); pending = []
    if group:
        groups.append(group)
    if len(groups) <= 1:
        return lines, 0
    def dirname(u):
        p = urlparse(u).path
        return p.rsplit("/", 1)[0] if "/" in p else ""
    counts = {}
    for g in groups:
        for sg in g:
            d = dirname(sg["uri"]); counts[d] = counts.get(d, 0) + 1
    main_dir = max(counts, key=counts.get)
    total = sum(sg["dur"] for g in groups for sg in g)
    kept, removed, carry = [], 0.0, []
    gdurs = [sum(sg["dur"] for sg in g) for g in groups]
    longest = max(gdurs) if gdurs else 0
    # 同一组切片在列表里重复出现（同一段广告插多次）
    sig = [tuple(sg["uri"].rsplit("/", 1)[-1] for sg in g[:3]) for g in groups]
    repeated = {x for x in sig if sig.count(x) > 1}
    shorts = [i for i, d in enumerate(gdurs) if d <= 60 and d < longest * 0.1]
    for i, g in enumerate(groups):
        gdur = gdurs[i]
        main_ratio = sum(1 for sg in g if dirname(sg["uri"]) == main_dir) / len(g)
        nb = [gdurs[j] for j in (i - 1, i + 1) if 0 <= j < len(groups)]
        # 短段夹在长正片段之间/片头片尾，且全片有多处这样的短段 → 插播广告（同目录、文件名同格式也能识别）
        short_ad = (i in shorts and any(x >= 120 for x in nb) and (len(shorts) >= 2 or (0 < i < len(groups) - 1)))
        is_ad = main_ratio < 0.5 or sig[i] in repeated and gdur <= 90 or short_ad
        if is_ad and removed + gdur < total * 0.3:
            removed += gdur
            carry += [l for sg in g for l in sg["lines"] if l.startswith("#EXT-X-KEY") or l.startswith("#EXT-X-MAP")]
            continue
        if carry:
            first = g[0]["lines"]
            if not any(l.startswith("#EXT-X-KEY") for l in first):
                g[0]["lines"] = [l for l in carry if l.startswith("#EXT-X-KEY")][-1:] + first
            carry = []
        kept.append(g)
    out = list(header)
    for j, g in enumerate(kept):
        if j > 0:
            out.append("#EXT-X-DISCONTINUITY")
        for sg in g:
            out.extend(sg["lines"])
    out.append("#EXT-X-ENDLIST")
    return out, removed


def is_https(req):
    """页面经 HTTPS（如 Cloudflare 隧道）访问时为 True：此时 http:// 资源会被浏览器当作混合内容拦截。"""
    if req.secure:
        return True
    if req.headers.get("X-Forwarded-Proto", "").lower().startswith("https"):
        return True
    return '"https"' in req.headers.get("Cf-Visitor", "")


async def m3u8(req):
    url = req.query.get("url", "")
    secure = is_https(req)
    # 局域网（http）访问时，切片一律经本机中转：家里设备走透明代理时，境外片源 CDN 常对代理出口 403 或很慢，
    # 而本机容器是直连家宽。经隧道（https）访问的外网设备仍让切片直连，不占家里上行。
    full = req.query.get("proxy") == "1" or (not secure and req.query.get("direct") != "1")
    noad = req.query.get("noad", "1") == "1"
    if not url.startswith("http"):
        return web.Response(status=400, text="bad url")
    session = req.app["session"]
    try:
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=12), headers={"User-Agent": UA}) as r:
            text = await r.text(errors="replace")
            base = str(r.url)
    except Exception as e:
        return web.Response(status=502, text=str(e))
    if "#EXTM3U" not in text:
        return web.Response(status=502, text="not m3u8")
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    is_master = any(l.startswith("#EXT-X-STREAM-INF") for l in lines)
    out = []
    for i, ln in enumerate(lines):
        if ln.startswith("#"):
            if 'URI="' in ln:
                ln = re.sub(r'URI="([^"]+)"', lambda m: f'URI="{wrap(urljoin(base, m.group(1)), full, True, secure)}"', ln)
            out.append(ln)
        else:
            absu = urljoin(base, ln)
            if is_master or absu.split("?")[0].endswith(".m3u8"):
                out.append(f"/m3u8?url={quote(absu, safe='')}" + ("&proxy=1" if full else "") + ("" if noad else "&noad=0"))
            else:
                out.append(wrap(absu, full, False, secure))
    removed = 0
    if not is_master and noad:
        out, removed = filter_ads(out)
    return web.Response(text="\n".join(out) + "\n", content_type="application/vnd.apple.mpegurl",
                        headers={"X-Ads-Removed": str(int(removed)), "Cache-Control": "no-cache"})


def wrap(u, full, is_key, secure=False):
    # HTTPS 页面里 http:// 切片会被拦截，只能经本机中转；https:// 切片仍然直连，不占家里上行带宽
    mixed = secure and u.startswith("http://")
    return f"/seg?url={quote(u, safe='')}" if (full or is_key or mixed) else u


async def seg(req):
    url = req.query.get("url", "")
    if not url.startswith("http"):
        return web.Response(status=400)
    session = req.app["session"]
    hdrs = {"User-Agent": UA}
    if "Range" in req.headers:
        hdrs["Range"] = req.headers["Range"]
    try:
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=60, sock_read=30), headers=hdrs) as r:
            resp = web.StreamResponse(status=r.status, headers={"Content-Type": r.headers.get("Content-Type", "video/mp2t"),
                                                               "Cache-Control": "max-age=3600"})
            if r.headers.get("Content-Length"):
                resp.content_length = int(r.headers["Content-Length"])
            await resp.prepare(req)
            async for chunk in r.content.iter_chunked(65536):
                await resp.write(chunk)
            await resp.write_eof()
            return resp
    except Exception as e:
        return web.Response(status=502, text=str(e))


async def img(req):
    url = req.query.get("url", "")
    if not url.startswith("http"):
        return web.Response(status=400)
    try:
        async with req.app["session"].get(url, timeout=aiohttp.ClientTimeout(total=10),
                                          headers={"User-Agent": UA, "Referer": "https://movie.douban.com/" if "doubanio" in url else f"{urlparse(url).scheme}://{urlparse(url).netloc}/"}) as r:
            body = await r.read()
            return web.Response(body=body, content_type=r.headers.get("Content-Type", "image/jpeg").split(";")[0],
                                headers={"Cache-Control": "max-age=604800"})
    except Exception:
        return web.Response(status=502)



# ---------------- 豆瓣 ----------------
DB_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
DB = "https://m.douban.com/rexxar/api/v2/"
_cache = {}


async def cached(key, ttl, coro_fn):
    c = _cache.get(key)
    if c and time.time() - c[0] < ttl:
        return c[1]
    v = await coro_fn()
    _cache[key] = (time.time(), v)
    if len(_cache) > 2000:
        for k in sorted(_cache, key=lambda k: _cache[k][0])[:500]:
            _cache.pop(k, None)
    return v


async def db_get(session, path):
    async with session.get(DB + path, timeout=aiohttp.ClientTimeout(total=10),
                           headers={"User-Agent": DB_UA, "Referer": "https://m.douban.com/"}) as r:
        if r.status != 200:
            raise RuntimeError(f"douban {r.status}")
        return json.loads(await r.text())


def db_item(i):
    pic = (i.get("pic") or {}).get("normal") or (i.get("pic") or {}).get("large") or i.get("cover_url") or ""
    r = (i.get("rating") or {}).get("value") or 0
    return {"id": i.get("id"), "title": i.get("title"), "pic": pic, "rate": r or "",
            "sub": i.get("card_subtitle", ""), "ep": i.get("episodes_info", ""),
            "type": "tv" if i.get("type") == "tv" else "movie"}


async def api_db_list(req):
    kind = "tv" if req.query.get("kind") == "tv" else "movie"
    cat, typ = req.query.get("category", "热门"), req.query.get("type", "全部")
    start, limit = int(req.query.get("start", 0)), min(int(req.query.get("limit", 20)), 50)
    if cat == "rec":  # 按豆瓣标签筛选：type 为逗号分隔的标签
        path = f"{kind}/recommend?start={start}&count={limit}&tags={quote(typ)}"
    else:
        path = f"subject/recent_hot/{kind}?category={quote(cat)}&type={quote(typ)}&start={start}&limit={limit}"
    try:
        d = await cached("dbl:" + path, 1800, lambda: db_get(req.app["session"], path))
        items = [db_item(i) for i in d.get("items") or [] if i.get("type") in ("movie", "tv") and i.get("id")]
        return web.json_response({"items": items, "total": d.get("total", 0)})
    except Exception as e:
        return web.json_response({"items": [], "error": type(e).__name__})


async def api_db_detail(req):
    kind = "tv" if req.query.get("type") == "tv" else "movie"
    did = re.sub(r"\D", "", req.query.get("id", ""))
    session = req.app["session"]
    async def load():
        d, c = await asyncio.gather(db_get(session, f"{kind}/{did}"), db_get(session, f"{kind}/{did}/credits"), return_exceptions=True)
        if isinstance(d, Exception):
            raise d
        cast = []
        if not isinstance(c, Exception):
            for x in c.get("items") or []:
                cast.append({"name": x.get("name"), "role": x.get("simple_character") or x.get("character") or "",
                             "avatar": (x.get("avatar") or {}).get("normal") or ""})
        if not cast:
            cast = [{"name": a.get("name"), "role": "演员", "avatar": ""} for a in d.get("actors") or []]
        return {"id": did, "type": kind, "title": d.get("title"), "original": d.get("original_title", ""),
                "year": d.get("year", ""), "pic": (d.get("pic") or {}).get("large") or (d.get("pic") or {}).get("normal") or "",
                "rate": (d.get("rating") or {}).get("value") or "", "votes": (d.get("rating") or {}).get("count") or 0,
                "genres": d.get("genres") or [], "countries": d.get("countries") or [], "durations": d.get("durations") or [],
                "episodes": d.get("episodes_count") or 0, "pubdate": (d.get("pubdate") or [""])[0] if d.get("pubdate") else "",
                "intro": d.get("intro", ""), "sub": d.get("card_subtitle", ""), "cast": cast[:20]}
    try:
        return web.json_response(await cached(f"dbd:{kind}:{did}", 6 * 3600, load))
    except Exception as e:
        return web.json_response({"error": type(e).__name__}, status=502)



# ---------------- 详情页：系列作品 + 相关推荐 ----------------
CNS = "一二三四五六七八九十"


def cn_int(n):
    if n.isdigit():
        return int(n)
    if not n or any(c not in CNS for c in n):
        return 0
    if n == "十":
        return 10
    if n.startswith("十"):
        return 10 + CNS.index(n[1]) + 1
    if "十" in n:
        a, _, b = n.partition("十")
        return (CNS.index(a) + 1) * 10 + (CNS.index(b) + 1 if b else 0)
    return CNS.index(n) + 1


def franchise_base(title):
    t = (title or "").strip()
    t = re.split(r"[：:]", t)[0]
    t = re.sub(r"\s*(第[" + CNS + r"\d]+[季部章]|season\s*\d+|[\d" + CNS + r"]+|前传|续集|外传|剧场版|电影版)\s*$", "", t, flags=re.I)
    return t.strip()


def poster_from(url):
    m = re.search(r"/(p\d+)\.(jpg|webp|png)", url or "")
    return f"https://img3.doubanio.com/view/photo/s_ratio_poster/public/{m.group(1)}.jpg" if m else (url or "")


async def api_db_related(req):
    kind = "tv" if req.query.get("type") == "tv" else "movie"
    did = re.sub(r"\D", "", req.query.get("id", ""))
    title = req.query.get("title", "")
    session = req.app["session"]
    SEA = re.compile(r"\s*(第[" + CNS + r"\d]+[季部])\s*$")
    async def load():
        full = SEA.sub("", title).strip()          # 去掉“第X季”
        base = franchise_base(title)               # 系列名（冒号前、去序号）
        cf, cb, me = canon(full), canon(base), canon(title)
        qs = []
        if len(cb) >= 2:
            qs += [f"search/movie?q={quote(base)}&count=20&start=0", f"search/movie?q={quote(base)}&count=20&start=20"]
        if full != base and len(cf) >= 2:
            qs += [f"search/movie?q={quote(full)}&count=20&start=0"]
        fell = []
        async def search(q):
            try:
                return await cached("dbs:" + q, 6 * 3600, lambda: db_get(session, q))
            except Exception:
                fell.append(q)
                # rexxar 搜索被限流时，退回网页版联想接口（无国家信息，结果较少）
                kw = re.search(r"q=([^&]+)", q).group(1)
                if "start=20" in q:
                    return {"items": []}
                async def sug():
                    async with session.get(f"https://movie.douban.com/j/subject_suggest?q={kw}", timeout=aiohttp.ClientTimeout(total=8),
                                           headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
                                                    "Referer": "https://movie.douban.com/"}) as r:
                        if r.status != 200:
                            raise RuntimeError("suggest")
                        arr = json.loads(await r.text())
                    return {"items": [{"target_type": "tv" if x.get("episode") else "movie",
                                       "target": {"id": x.get("id"), "title": x.get("title"), "cover_url": x.get("img"),
                                                  "year": x.get("year", ""), "rating": {}, "card_subtitle": ""}} for x in arr]}
                return await cached("dbsg:" + kw, 6 * 3600, sug)
        res = await asyncio.gather(*[search(q) for q in qs],
                                   cached(f"dbraw:{kind}:{did}", 6 * 3600, lambda: db_get(session, f"{kind}/{did}")),
                                   db_get(session, f"{kind}/{did}/recommendations?count=16"), return_exceptions=True)
        rc, me_d, srs = res[-1], res[-2], res[:-2]
        my_c = set(me_d.get("countries") or []) if isinstance(me_d, dict) else set()
        my_g = set(me_d.get("genres") or []) if isinstance(me_d, dict) else set()
        sib, fr, seen = [], [], {did}
        for sr in srs:
            if isinstance(sr, Exception):
                continue
            for it in sr.get("items") or []:
                t = it.get("target") or {}
                if it.get("target_type") not in ("movie", "tv") or not t.get("id") or t["id"] in seen:
                    continue
                ct = canon(t.get("title"))
                if not ct.startswith(cb) or ct == me:
                    continue
                raw = (t.get("title") or "").strip()
                rest = raw[len(base):] if raw.startswith(base) else None
                if rest is None or (rest and not re.match(r"[\s:：·\-—(（" + CNS + r"第]|\d{1,2}(?!\d)|前传|续集|外传|剧场版|电影版|Ⅱ|Ⅲ|II", rest)):
                    continue  # “重生之门”“重生2000”这类只是前缀相同
                # 同名但不相干的作品（如外国片也译作“重生”）：要求国家一致，短片名还要类型有交集
                parts = [x.strip() for x in (t.get("card_subtitle") or "").split(" / ")]
                tc = set(parts[0].split()) if parts else set()
                tg = set(parts[1].split()) if len(parts) > 1 else set()
                if my_c and tc and not (my_c & tc):
                    continue
                if len(cb) <= 3 and my_g and tg and not (my_g & tg):
                    continue
                seen.add(t["id"])
                x = {"id": t["id"], "title": t.get("title"), "pic": poster_from(t.get("cover_url")),
                     "rate": (t.get("rating") or {}).get("value") or "", "year": t.get("year", ""),
                     "type": it.get("target_type"), "sub": t.get("year", "")}
                m = SEA.search(t.get("title") or "")
                same = canon(SEA.sub("", t.get("title") or "")) == cf and (m or x["rate"])
                (sib if same else fr).append((x, cn_int(re.sub(r"[第季部\s]", "", m.group(1))) if m else 0))
        sib.sort(key=lambda p: (p[1] or 1, str(p[0]["year"])))
        fr.sort(key=lambda p: (not p[0]["rate"], str(p[0]["year"] or "9999"), p[1]))
        series = [p[0] for p in sib] + [p[0] for p in fr]
        recs = []
        if isinstance(rc, list):
            for i in rc:
                if i.get("id") in seen or i.get("type") not in ("movie", "tv"):
                    continue
                seen.add(i.get("id"))
                x = db_item(i); x["sub"] = (i.get("card_subtitle") or "").split(" / ")[0]
                recs.append(x)
        series = series[:14]
        partial = bool(fell) or any(isinstance(x, Exception) for x in srs) or isinstance(rc, Exception)
        return {"series": series, "recs": recs[:max(6, 12 - len(series))][:10], "partial": partial}
    try:
        key = f"dbr6:{kind}:{did}"
        r = await cached(key, 6 * 3600, load)
        if r.get("partial"):
            _cache.pop(key, None)  # 豆瓣限流等失败不缓存，下次再试
        return web.json_response(r)
    except Exception as e:
        return web.json_response({"series": [], "recs": [], "error": type(e).__name__})

# ---------------- 按片名自动找源 ----------------
def norm_title(t):
    t = re.sub(r"[\s·:：,，.。!！?？'\"“”‘’()（）\[\]【】\-_—]", "", t or "").lower()
    t = t.replace("第一季", "") if False else t
    return t


CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}


def canon(t):
    t = norm_title(t)
    t = re.sub(r"第([一二三四五六七八九十])季", lambda m: f"第{CN_NUM[m.group(1)]}季", t)
    t = re.sub(r"season(\d+)", r"第\1季", t)
    return re.sub(r"第1季$", "", t)


_resolving = {}


async def resolve_all(session, name, year):
    want = canon(name)
    lat = {s["api"]: i for i, s in enumerate(sites())}
    async def one(s):
        t0 = time.time()
        try:
            d = await get_json(session, f"{s['api']}/?ac=videolist&wd={quote(name)}", SEARCH_TIMEOUT)
        except Exception:
            return None
        best = None
        for v in d.get("list") or []:
            if canon(v.get("vod_name")) != want:
                continue
            vy = str(v.get("vod_year") or "")
            if year and vy.isdigit() and abs(int(vy) - int(year)) > 1:
                continue
            groups = parse_episodes(v)
            groups = [g for g in groups if g["m3u8"]]
            if not groups:
                continue
            best = {"site": s["name"], "api": s["api"], "id": v.get("vod_id"), "ms": int((time.time() - t0) * 1000),
                    "rank": lat.get(s["api"], 99), "remarks": v.get("vod_remarks", ""), "groups": groups[:2],
                    "info": {"pic": v.get("vod_pic", ""), "year": vy, "area": v.get("vod_area", ""),
                             "type": v.get("type_name", ""), "actor": v.get("vod_actor", ""),
                             "director": v.get("vod_director", ""),
                             "content": re.sub(r"<[^>]+>", "", v.get("vod_content") or v.get("vod_blurb") or "").strip()}}
            break
        return best
    return [asyncio.ensure_future(one(s)) for s in sites()]


def order_sources(found):
    # 集数多的优先（更新更全），再按响应速度
    maxep = max([len(f["groups"][0]["eps"]) for f in found] or [0])
    return sorted(found, key=lambda f: (len(f["groups"][0]["eps"]) < maxep, f["ms"] + f["rank"] * 30))


def client_geo(req):
    """经 Cloudflare 访问时，访客出口所在国家（CF-IPCountry）。不是 CN 说明设备开着代理/在海外，
    这时不少片源 CDN（只认国内 IP）会拒绝直连。局域网访问返回空。"""
    return (req.headers.get("CF-IPCountry") or "").upper()


# ---------- 学习：公网访客播放各片源的效果 ----------
# 前端经公网（Cloudflare）播放时上报：直连成败，以及每次播放的起播耗时和卡顿占比。
# 按访客出口分两类学习：px（出口不在国内，设备开着代理/在海外）、cn（国内出口）。局域网访问不计。
# 1) 可达性（仅 px）：直连失败明显多 → lock=1（需中转，排最后、一开始就中转）；成功明显多 → lock=0。
# 2) 流畅度（px、cn 各自，只统计直连）：q = 卡顿占比×100 + 起播秒数，越小越流畅，用于排序。
# 中转会占家里上行（带宽小），所以能直连且流畅的线路永远优先，中转只做兜底。
LEARN_FILE = os.path.join(DATA, "geolearn.json")
LEARN_HALF = 14 * 86400
_learn = {"d": None, "dirty": False, "saved": 0}


def _learn_db():
    if _learn["d"] is None:
        try:
            d = json.load(open(LEARN_FILE, encoding="utf-8"))
        except Exception:
            d = {}
        if "lock" not in d:          # 旧格式（v28）：顶层就是各片源的成败计数
            d = {"lock": d, "q": {}}
        d.setdefault("q", {})
        d.setdefault("res", {})
        _learn["d"] = d
    return _learn["d"]


def _k(t, now):
    return 0.5 ** ((now - t) / LEARN_HALF)


def learn_verdict(api):
    e = _learn_db()["lock"].get(api)
    if not e:
        return None
    k = _k(e.get("t", time.time()), time.time())
    ok, fail = e.get("ok", 0) * k, e.get("fail", 0) * k
    if fail >= 1.8 and fail > ok * 2:
        return 1
    if ok >= 1.8 and ok > fail * 2:
        return 0
    return None


def learn_quality(bucket, api):
    e = _learn_db()["q"].get(bucket, {}).get(api)
    if not e or e.get("n", 0) * _k(e.get("t", 0), time.time()) < 1.5:
        return None
    return round(e["sr"] * 100 + e["ttff"] / 1000, 2)


def learn_res(api):
    e = _learn_db()["res"].get(api)
    return e["h"] if e else None


def _bucket(geo):
    if not geo or geo == "XX":
        return None
    return "cn" if geo == "CN" else "px"


def _learn_save(force=False):
    if not _learn["dirty"] or (not force and time.time() - _learn["saved"] < 60):
        return
    try:
        tmp = LEARN_FILE + ".tmp"
        json.dump(_learn["d"], open(tmp, "w", encoding="utf-8"), ensure_ascii=False)
        os.replace(tmp, LEARN_FILE)
        _learn["dirty"], _learn["saved"] = False, time.time()
    except Exception:
        pass


def _num(v, lo, hi):
    try:
        return min(max(float(v), lo), hi)
    except Exception:
        return None


async def api_learn(req):
    return web.json_response({"ok": False, "disabled": True})  # v44 起取消自动学习：前端改为实时测速选源
    bucket = _bucket(client_geo(req))
    try:
        b = await req.json() if req.content_type == "application/json" else json.loads(await req.text())
    except Exception:
        return web.json_response({"ok": False}, status=400)
    api = str(b.get("api", ""))
    if not site_by_api(api):             # 只接受已知片源，防止乱写
        return web.json_response({"ok": False}, status=400)
    now, db = time.time(), _learn_db()
    h = _num(b.get("h"), 0, 4320)
    if h and h >= 144:                   # 分辨率与网络无关，局域网也收：按片源做指数移动平均
        e = db["res"].get(api)
        db["res"][api] = {"h": round(e["h"] * 0.7 + h * 0.3) if e else round(h), "n": min((e or {}).get("n", 0) + 1, 99), "t": now}
        _learn["dirty"] = True
        _learn_save()
        return web.json_response({"ok": True})
    if not bucket:                       # 局域网或未知出口不计
        return web.json_response({"ok": False})
    if "ok" in b and bucket == "px":     # 可达性：只学代理出口的直连成败
        e = db["lock"].get(api) or {"ok": 0, "fail": 0, "t": now}
        k = _k(e["t"], now)
        ok, fail = e["ok"] * k + (1 if b["ok"] else 0), e["fail"] * k + (0 if b["ok"] else 1)
        db["lock"][api] = {"ok": round(min(ok, 50), 3), "fail": round(min(fail, 50), 3), "t": now}
    ttff, watch, stall = _num(b.get("ttff"), 0, 60000), _num(b.get("watch"), 0, 86400), _num(b.get("stall"), 0, 86400)
    if ttff is not None and watch is not None and stall is not None and not b.get("relay") and watch + stall >= 20:
        sr = stall / (watch + stall)
        q = db["q"].setdefault(bucket, {})
        e = q.get(api)
        if e:
            n = e["n"] * _k(e["t"], now)
            a = max(0.25, 1 / (n + 1))       # 前几次快速收敛，之后按指数移动平均
            e.update(ttff=round(e["ttff"] * (1 - a) + ttff * a), sr=round(e["sr"] * (1 - a) + sr * a, 4), n=round(min(n + 1, 50), 3), t=now)
        else:
            q[api] = {"ttff": round(ttff), "sr": round(sr, 4), "n": 1, "t": now}
    _learn["dirty"] = True
    _learn_save()
    return web.json_response({"ok": True})


async def api_learned(req):
    names = {s["api"]: s["name"] for s in sites()}
    db, out = _learn_db(), {}
    apis = set(db["lock"]) | set(db["res"]) | {a for b in db["q"].values() for a in b}
    rows = []
    for api in apis:
        r = {"site": names.get(api, api), "lock": learn_verdict(api), "res": learn_res(api)}
        for bk in ("px", "cn"):
            e = db["q"].get(bk, {}).get(api)
            if e:
                r[bk] = {"q": learn_quality(bk, api), "起播ms": e["ttff"], "卡顿%": round(e["sr"] * 100, 1), "次数": round(e["n"], 1)}
        rows.append(r)
    return web.json_response(rows)


def with_learn(srcs, geo):
    return srcs  # v44 起取消自动学习
    bucket = _bucket(geo)
    if not bucket:
        return [dict(s, res=learn_res(s["api"])) for s in srcs]
    return [dict(s, res=learn_res(s["api"]), q=learn_quality(bucket, s["api"]), **({"lock": learn_verdict(s["api"])} if bucket == "px" else {})) for s in srcs]


# ---------------- 预探分辨率：先读 m3u8 主列表 RESOLUTION；没有就取第一个分片读 SPS（含 AES-128 解密），后台跑，缓存 6 小时 ----------------
from vprobe import aes_cbc_dec, ts_res
_probe = {}      # url -> (时间, 高度或 None)
_pending = {}    # url -> Task


def _eq_h(w, h):
    """把宽高折算成“几 P”：竖屏取短边，宽银幕按宽度折算"""
    return max(min(w, h), round(max(w, h) * 9 / 16))


async def _deep_probe(session, url):
    to = aiohttp.ClientTimeout(total=8)
    async def get(u, n):
        async with session.get(u, headers={"User-Agent": UA}, timeout=to, ssl=False) as r:
            if r.status != 200:
                raise ValueError(r.status)
            return await r.content.read(n)
    t = (await get(url, 65536)).decode("utf-8", "ignore")
    for _ in range(2):
        if "#EXT-X-STREAM-INF" not in t:
            break
        rs = [_eq_h(int(w), int(h)) for w, h in re.findall(r"RESOLUTION=(\d+)x(\d+)", t)]
        if rs:
            return max(rs)
        v = [l.strip() for l in t.splitlines() if l.strip() and not l.startswith("#")]
        url = urljoin(url, v[-1])
        t = (await get(url, 262144)).decode("utf-8", "ignore")
    segs = [l.strip() for l in t.splitlines() if l.strip() and not l.startswith("#")]
    if not segs:
        return None
    seq = int((re.search(r"MEDIA-SEQUENCE:(\d+)", t) or [0, 0])[1])
    d = await get(urljoin(url, segs[0]), 49152)
    km = re.search(r'#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?', t)
    if km:
        key = (await get(urljoin(url, km[1]), 64))[:16]
        iv = bytes.fromhex(km[2].zfill(32)[-32:]) if km[2] else seq.to_bytes(16, "big")
        d = await asyncio.get_running_loop().run_in_executor(None, aes_cbc_dec, key, iv, d[:16384])
    r = ts_res(d)
    return _eq_h(*r) if r and r[0] > 0 and r[1] > 0 else None


async def _probe_job(session, url):
    h = None
    try:
        h = await asyncio.wait_for(_deep_probe(session, url), 15)
    except Exception:
        pass
    _probe[url] = (time.time() if h else time.time() - 21600 + 1800, h)  # 失败 30 分钟后可重试
    _pending.pop(url, None)
    if len(_probe) > 3000:
        for k in sorted(_probe, key=lambda k: _probe[k][0])[:1000]:
            _probe.pop(k, None)


def probe_start(session, url):
    c = _probe.get(url)
    if c and time.time() - c[0] < 21600:
        return None
    if url not in _pending:
        _pending[url] = asyncio.ensure_future(_probe_job(session, url))
    return _pending[url]


def _ep1(s):
    try:
        u = s["groups"][0]["eps"][0]["url"]
        return u if re.search(r"\.m3u8", u, re.I) else None
    except Exception:
        return None


async def with_probe(session, srcs):
    urls = [_ep1(s) for s in srcs]
    jobs = [j for j in (probe_start(session, u) for u in urls if u) if j]
    if jobs:
        await asyncio.wait(jobs, timeout=1.5)
    return [dict(s, pres=_probe.get(u, (0, None))[1]) if u else s for s, u in zip(srcs, urls)]


async def api_pres(req):
    """前端补问：解析时没探完的片源，最多再等 6 秒；只认 resolve 登记过的地址"""
    try:
        urls = [u for u in (await req.json()).get("urls", [])[:40] if isinstance(u, str)]
    except Exception:
        urls = []
    jobs = [_pending[u] for u in urls if u in _pending]
    if jobs:
        await asyncio.wait(jobs, timeout=6)
    return web.json_response({u: _probe[u][1] for u in urls if u in _probe and _probe[u][1]})


async def api_resolve(req):
    name, year = req.query.get("name", "").strip(), re.sub(r"\D", "", req.query.get("year", ""))[:4]
    full = req.query.get("full") == "1"
    if not name:
        return web.json_response({"sources": []})
    key = f"{canon(name)}|{year}"
    c = _cache.get("res:" + key)
    if c and time.time() - c[0] < 1800:
        return web.json_response({"sources": with_learn(await with_probe(req.app["session"], c[1]), client_geo(req)), "done": True, "geo": client_geo(req)})
    session = req.app["session"]
    tasks = _resolving.get(key)
    if not tasks:
        tasks = await resolve_all(session, name, year)
        _resolving[key] = tasks
        async def finish():
            res = await asyncio.gather(*tasks)
            _cache["res:" + key] = (time.time(), order_sources([r for r in res if r]))
            _resolving.pop(key, None)
        asyncio.ensure_future(finish())
    if full:
        await asyncio.wait(tasks, timeout=SEARCH_TIMEOUT + 2)
    else:
        # 拿到第一个匹配后最多再等 2.5 秒，尽快开播
        deadline, first = time.time() + SEARCH_TIMEOUT + 1, None
        pending = set(tasks)
        while pending and time.time() < deadline:
            wait_for = (first + 2.5 - time.time()) if first else (deadline - time.time())
            if wait_for <= 0:
                break
            done, pending = await asyncio.wait(pending, timeout=wait_for, return_when=asyncio.FIRST_COMPLETED)
            if not first and any(t.result() for t in done):
                first = time.time()
    found = [t.result() for t in tasks if t.done() and t.result()]
    return web.json_response({"sources": with_learn(await with_probe(session, order_sources(found)), client_geo(req)), "done": all(t.done() for t in tasks), "geo": client_geo(req)})



# ---------------- 短剧：聚合各采集站的“短剧”分类 ----------------
SHORT_RE = re.compile(r"短剧|爽剧|爽文")
SHORT_BAD = re.compile(r"擦边|伦理|福利")


_short_src = [0, []]


async def short_sources(session, limit=3):
    """在前面的源里找“短剧”分类（懒加载，缓存 6 小时）"""
    if _short_src[1] and time.time() - _short_src[0] < 6 * 3600:
        return _short_src[1]
    top = sites()[:24]
    async def one(s):
        try:
            cls = await get_classes(session, s["api"])
        except Exception:
            return None
        pids = {c["pid"] for c in cls}
        cand = [c for c in cls if SHORT_RE.search(c.get("name") or "") and not SHORT_BAD.search(c.get("name") or "") and c["id"] not in pids]
        return (s, cand[0]["id"]) if cand else None
    res = [r for r in await asyncio.gather(*[one(s) for s in top]) if r]
    pref = ["360zy.com", "dyttzyapi", "lziapi", "ffzyapi"]
    rank = lambda x: next((i for i, k in enumerate(pref) if k in x[0]["api"]), 99)
    out = sorted(res, key=rank)[:limit]
    if out:
        _short_src[:] = [time.time(), out]
    return out


async def api_shorts(req):
    pg = max(1, int(req.query.get("pg", 1) or 1))
    session = req.app["session"]
    srcs = await short_sources(session)
    async def one(s, cid):
        path = f"{s['api']}/?ac=videolist&t={cid}&pg={pg}"
        try:
            return await cached("sh:" + path, 1800, lambda: get_json(session, path, 5))
        except Exception:
            return {}
    res = await asyncio.gather(*[one(s, cid) for s, cid in srcs])
    seen, items = set(), []
    for v in sorted([v for r in res for v in (r.get("list") or [])], key=lambda v: v.get("vod_time") or "", reverse=True):
        k = canon(v.get("vod_name"))
        if not k or k in seen or not v.get("vod_pic"):
            continue
        seen.add(k)
        items.append({"title": v.get("vod_name"), "pic": v.get("vod_pic"), "ep": v.get("vod_remarks", ""),
                      "year": v.get("vod_year", ""), "sub": (v.get("type_name") or "短剧").replace("短剧", "") or "短剧"})
    more = any(int(r.get("pagecount") or 1) > pg for r in res if r)
    return web.json_response({"items": items, "more": more})

import conf

CONF_COOKIE = "cn_conf"


def _is_lan(req):
    # 经 Cloudflare 隧道进来的请求一定带 CF 头；首次设置密码只允许局域网
    return not (req.headers.get("CF-Connecting-IP") or req.headers.get("CF-Ray"))


def _authed(req, d=None):
    return conf.check_session(d or conf.load(), req.cookies.get(CONF_COOKIE))


def _set_cookie(req, resp, d):
    resp.set_cookie(CONF_COOKIE, conf.make_session(d), max_age=90 * 86400, httponly=True,
                    samesite="Strict", secure=is_https(req), path="/")


async def api_conf(req):
    d = conf.load()
    ok = _authed(req, d)
    r = {"authed": ok, "has_password": bool(d.get("pw_hash")), "lan": _is_lan(req),
         "configured": bool(conf.interfaces(d)), "public": conf.PUBLIC_IFACE}
    if ok:
        r["interfaces"] = d.get("interfaces", [])
        r["gh_proxy"] = d.get("gh_proxy", "")
    return web.json_response(r)


async def api_conf_login(req):
    b = await req.json()
    pw = str(b.get("password") or "")
    d = conf.load()
    if not d.get("pw_hash"):
        if not _is_lan(req):
            return web.json_response({"ok": False, "msg": "首次设置密码请在家里局域网内打开"}, status=403)
        if len(pw) < 4:
            return web.json_response({"ok": False, "msg": "密码至少 4 位"}, status=400)
        conf.set_password(d, pw); conf.save(d)
    elif not conf.check_password(d, pw):
        await asyncio.sleep(1.5)
        return web.json_response({"ok": False, "msg": "密码不对"}, status=401)
    resp = web.json_response({"ok": True})
    _set_cookie(req, resp, d)
    return resp


async def api_conf_logout(req):
    resp = web.json_response({"ok": True})
    resp.del_cookie(CONF_COOKIE, path="/")
    return resp


async def api_conf_save(req):
    d = conf.load()
    if not _authed(req, d):
        return web.json_response({"ok": False, "msg": "请先输入密码"}, status=401)
    b = await req.json()
    if "interfaces" in b:
        out = []
        for it in b["interfaces"][:20]:
            url = str(it.get("url") or "").strip()
            if not url:
                continue
            if not re.match(r"https?://", url):
                return web.json_response({"ok": False, "msg": f"地址要以 http:// 或 https:// 开头：{url[:40]}"}, status=400)
            out.append({"name": str(it.get("name") or "").strip()[:30] or urlparse(url).hostname, "url": url[:500],
                        "on": it.get("on") is not False})
        d["interfaces"] = out
    if "gh_proxy" in b:
        d["gh_proxy"] = conf.norm_proxy(str(b["gh_proxy"] or ""))[:200]
    if b.get("new_password"):
        if len(b["new_password"]) < 4:
            return web.json_response({"ok": False, "msg": "密码至少 4 位"}, status=400)
        conf.set_password(d, b["new_password"])
    conf.save(d)
    if "interfaces" in b or "gh_proxy" in b:
        open(os.path.join(DATA, "trigger"), "w").write(str(time.time()))
    resp = web.json_response({"ok": True})
    if b.get("new_password"):
        _set_cookie(req, resp, d)
    return resp


async def api_conf_test(req):
    """试拉一个接口地址，返回解析出的源数量（不保存）"""
    if not _authed(req):
        return web.json_response({"ok": False, "msg": "请先输入密码"}, status=401)
    b = await req.json()
    url = str(b.get("url") or "").strip()
    proxy = b["gh_proxy"] if "gh_proxy" in b else conf.load().get("gh_proxy")
    for u in conf.mirrors(url, proxy):
        try:
            async with req.app["session"].get(u, timeout=aiohttp.ClientTimeout(total=10), headers={"User-Agent": UA}) as r:
                got = conf.parse(await r.text(errors="replace"))
            if got:
                return web.json_response({"ok": True, "count": len(got), "via": u if u != url else ""})
        except Exception:
            continue
    return web.json_response({"ok": False, "msg": "拉取失败或没认出影视源"})


async def index(req):
    return web.FileResponse(os.path.join(STATIC, "index.html"), headers={"Cache-Control": "no-cache"})


async def supervise_collector(app):
    """同一容器内以子进程运行采集器，退出后自动重启。"""
    import sys
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "collector.py")
    while True:
        try:
            proc = await asyncio.create_subprocess_exec(sys.executable, script)
            app["st"]["collector"] = proc
            code = await proc.wait()
            print(f"[supervisor] collector 退出 code={code}，30 秒后重启", flush=True)
        except asyncio.CancelledError:
            p = app["st"].get("collector")
            if p and p.returncode is None:
                p.terminate()
            raise
        except Exception as e:
            print("[supervisor] 启动采集器失败", e, flush=True)
        await asyncio.sleep(30)


async def on_start(app):
    app["session"] = aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=False, limit=100))
    if os.environ.get("RUN_COLLECTOR", "1") == "1":
        app["sup"] = asyncio.create_task(supervise_collector(app))


async def on_stop(app):
    _learn_save(force=True)
    t = app.get("sup")
    if t:
        t.cancel()
        try: await t
        except BaseException: pass
    await app["session"].close()


@web.middleware
async def cache_mw(req, handler):
    resp = await handler(req)
    if req.path.startswith("/static/fonts/") or req.path.endswith("hls.min.js"):
        resp.headers["Cache-Control"] = "public, max-age=31536000, immutable"
    elif req.path.startswith("/static/"):
        resp.headers["Cache-Control"] = "public, max-age=86400"
    return resp


app = web.Application(middlewares=[cache_mw])
app["st"] = {}
app.on_startup.append(on_start)
app.on_cleanup.append(on_stop)
app.router.add_get("/", index)
app.router.add_get("/api/sites", api_sites)
app.router.add_post("/api/refresh", api_refresh)
app.router.add_get("/api/search", api_search)
app.router.add_get("/api/detail", api_detail)
app.router.add_get("/api/latest", api_latest)
app.router.add_get("/m3u8", m3u8)
app.router.add_get("/seg", seg)
app.router.add_get("/img", img)
app.router.add_get("/api/db/list", api_db_list)
app.router.add_get("/api/shorts", api_shorts)
app.router.add_get("/api/db/detail", api_db_detail)
app.router.add_get("/api/db/related", api_db_related)
app.router.add_get("/api/resolve", api_resolve)
app.router.add_post("/api/learn", api_learn)
app.router.add_get("/api/learned", api_learned)
app.router.add_post("/api/pres", api_pres)
app.router.add_get("/api/conf", api_conf)
app.router.add_post("/api/conf/login", api_conf_login)
app.router.add_post("/api/conf/logout", api_conf_logout)
app.router.add_post("/api/conf/save", api_conf_save)
app.router.add_post("/api/conf/test", api_conf_test)
app.router.add_static("/static", STATIC)

if __name__ == "__main__":
    web.run_app(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8080")), access_log=None)
