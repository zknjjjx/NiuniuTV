"""CineNest 设置（全家共用，存 /data/settings.json）
- interfaces: [{"name", "url", "on"}]，影视源接口列表，启用的会合并
- 管理密码：首次设置只允许局域网（不经 Cloudflare）
- 接口解析：CineNest(cmstv)、TVBox、MoonTV/LunaTV(api_site)、纯文本（名称,地址 每行一条）
"""
import hashlib, hmac, json, os, re, secrets, time
from urllib.parse import urlparse

DATA = os.environ.get("DATA_DIR", "/data")
CFG = os.path.join(DATA, "settings.json")
CACHE = os.path.join(DATA, "iface_cache.json")
PUBLIC_IFACE = "https://raw.githubusercontent.com/zknjjjx/tvbox/main/cmstv.json"


def load():
    d = {"interfaces": [], "gh_proxy": "", "pw_hash": "", "pw_salt": "", "secret": ""}
    try:
        d.update(json.load(open(CFG, encoding="utf-8")))
    except Exception:
        pass
    return d


def save(d):
    os.makedirs(DATA, exist_ok=True)
    tmp = CFG + ".tmp"
    json.dump(d, open(tmp, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    os.chmod(tmp, 0o600)
    os.replace(tmp, CFG)


def interfaces(d=None):
    return [i for i in (d or load()).get("interfaces", []) if i.get("url")]


# ---------- 密码与会话 ----------
def _hash(pw, salt):
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), 120000).hex()


def set_password(d, pw):
    d["pw_salt"] = secrets.token_hex(16)
    d["pw_hash"] = _hash(pw, d["pw_salt"])
    d["secret"] = secrets.token_hex(32)


def check_password(d, pw):
    return bool(d.get("pw_hash")) and hmac.compare_digest(_hash(pw, d["pw_salt"]), d["pw_hash"])


def make_session(d):
    exp = str(int(time.time()) + 90 * 86400)
    return exp + "." + hmac.new(bytes.fromhex(d["secret"]), exp.encode(), "sha256").hexdigest()


def check_session(d, tok):
    try:
        exp, sig = (tok or "").split(".", 1)
        if int(exp) < time.time() or not d.get("secret"):
            return False
        return hmac.compare_digest(sig, hmac.new(bytes.fromhex(d["secret"]), exp.encode(), "sha256").hexdigest())
    except Exception:
        return False


# ---------- 接口解析 ----------
API_RE = re.compile(r"https?://[^\s\"',]+?/api\.php/provide/vod", re.I)


def _site(name, api, adult=False):
    api = (api or "").strip()
    if not api.startswith("http"):
        return None
    api = api.split("?")[0].rstrip("/")
    return {"name": (name or "").strip()[:24] or (urlparse(api).hostname or api), "api": api, "adult": bool(adult)}


def parse(text):
    """返回 [{name, api, adult}]，认得几种常见格式"""
    out = []
    try:
        j = json.loads(text)
    except Exception:
        j = None
    if isinstance(j, dict):
        if isinstance(j.get("api_site"), dict):  # MoonTV / LunaTV
            for v in j["api_site"].values():
                if isinstance(v, dict):
                    out.append(_site(v.get("name"), v.get("api"), v.get("is_adult")))
        for v in j.get("sites") or []:  # cmstv 或 TVBox
            if not isinstance(v, dict):
                continue
            api = v.get("api") or ""
            if "type" in v and v.get("type") not in (0, 1, "0", "1"):
                continue  # TVBox 爬虫型(type 3)不支持
            if isinstance(api, str) and ("provide/vod" in api or v.get("type") in (1, "1") or "type" not in v):
                out.append(_site(v.get("name"), api, v.get("adult")))
    elif isinstance(j, list):
        for v in j:
            if isinstance(v, dict):
                out.append(_site(v.get("name"), v.get("api") or v.get("url"), v.get("adult")))
    else:
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in re.split(r"[,，|\t]", line, 1)]
            api = parts[-1]
            out.append(_site(parts[0] if len(parts) > 1 else "", api))
        if not any(out):
            out = [_site("", m.group(0)) for m in API_RE.finditer(text)]
    return [s for s in out if s]


def norm_proxy(p):
    p = (p or "").strip()
    if not p:
        return ""
    if not re.match(r"https?://", p):
        p = "https://" + p
    return p.rstrip("/") + "/"


def mirrors(url, proxy=""):
    """GitHub 地址在国内常连不上：先走用户设的 GitHub 代理，再试原址、ghfast、jsDelivr"""
    urls = [url]
    m = re.match(r"https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)", url)
    if not m:
        m2 = re.match(r"https://github\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^/]+)/(.+)", url)
        if m2:
            m = m2
            urls.append(f"https://raw.githubusercontent.com/{m.group(1)}/{m.group(2)}/{m.group(3)}/{m.group(4)}")
    if m:
        raw = f"https://raw.githubusercontent.com/{m.group(1)}/{m.group(2)}/{m.group(3)}/{m.group(4)}"
        urls += [f"https://ghfast.top/{raw}", f"https://cdn.jsdelivr.net/gh/{m.group(1)}/{m.group(2)}@{m.group(3)}/{m.group(4)}"]
        p = norm_proxy(proxy)
        if p:
            urls.insert(0, p + raw)
    elif re.match(r"https://(gist\.)?github(usercontent)?\.com/", url) and norm_proxy(proxy):
        urls.insert(0, norm_proxy(proxy) + url)
    return list(dict.fromkeys(urls))


def cache_get(url):
    try:
        return json.load(open(CACHE, encoding="utf-8")).get(url)
    except Exception:
        return None


def cache_put(url, sites):
    try:
        c = json.load(open(CACHE, encoding="utf-8"))
    except Exception:
        c = {}
    c[url] = {"sites": sites, "at": time.strftime("%Y-%m-%d %H:%M:%S")}
    json.dump(c, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
