"use strict";
var __async = (__this, __arguments, generator) => {
  return new Promise((resolve2, reject) => {
    var fulfilled = (value) => {
      try {
        step(generator.next(value));
      } catch (e) {
        reject(e);
      }
    };
    var rejected = (value) => {
      try {
        step(generator.throw(value));
      } catch (e) {
        reject(e);
      }
    };
    var step = (x) => x.done ? resolve2(x.value) : Promise.resolve(x.value).then(fulfilled, rejected);
    step((generator = generator.apply(__this, __arguments)).next());
  });
};
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => Array.prototype.slice.call(el.querySelectorAll(s));
const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const APP = typeof window.NN !== "undefined";
const MOB = APP ? !!(NN.phone && NN.phone()) : /[?&]m=1/.test(location.search);
let seq = 0;
const cbs = {};
window.__nnCb = (id, code, body, ms) => {
  const c = cbs[id];
  if (c) {
    delete cbs[id];
    c({ code, body, ms });
  }
};
function req(url, headers, timeout) {
  timeout = timeout || 1e4;
  if (APP) return new Promise((r) => {
    const id = "r" + ++seq;
    cbs[id] = r;
    NN.fetch(id, url, headers ? JSON.stringify(headers) : "", timeout);
  });
  const t0 = Date.now();
  return fetch(url).then((r) => r.text().then((b) => ({ code: r.status, body: b, ms: Date.now() - t0 }))).catch(() => ({ code: 0, body: "", ms: 0 }));
}
function getJSON(url, headers, timeout) {
  return __async(this, null, function* () {
    const r = yield req(url, headers, timeout);
    if (r.code < 200 || r.code >= 300) throw new Error("http " + r.code);
    const d = JSON.parse(r.body.replace(/^\uFEFF/, ""));
    d.__ms = r.ms;
    return d;
  });
}
const MB = 1048576;
function srcTier(s) {
  const h = s.h || 0, b = s.bps || 0;
  if (b < 0) return 0;
  if (b === 0) return 1;
  let r = h >= 1e3 ? 3 : h >= 700 || h === 0 ? 2 : 1;
  if (r === 3 && b < (isCrowd(s) ? 0.9 : 0.2) * MB) r = 1;
  if (r === 2 && b < 0.15 * MB) r = 1;
  return r + 1;
}
let CROWD = {};
function loadCrowd() {
  try {
    const o = JSON.parse(APP && NN.cfg && NN.cfg("crowd") || "{}"), t = Date.now();
    CROWD = {};
    Object.keys(o).forEach((k) => {
      if (t - o[k] < 7 * 864e5) CROWD[k] = 1;
    });
  } catch (e) {
    CROWD = {};
  }
}
function isCrowd(s) {
  const m = /^https?:\/\/([^\/:?#]+)/i.exec(s.eps && s.eps[0] && s.eps[0].url || "");
  return !!(m && CROWD[m[1].toLowerCase()]);
}
function srcScore(s) {
  return srcTier(s) * 1e10 + (s.bps > 0 ? s.bps : s.bps == null ? -((s.ms || 0) + (s.rank || 0) * 30) : 0);
}
function probe(url) {
  if (!APP) return Promise.resolve({ bps: 0 });
  return new Promise((r) => {
    const id = "p" + ++seq;
    cbs[id] = (x) => {
      try {
        r(JSON.parse(x.body));
      } catch (e) {
        r({ bps: -1 });
      }
    };
    NN.probe(id, url, 8e3, 524288);
  });
}
const store = {
  get(k, d) {
    try {
      const v = localStorage.getItem("nn:" + k);
      return v == null ? d : JSON.parse(v);
    } catch (e) {
      return d;
    }
  },
  set(k, v) {
    try {
      localStorage.setItem("nn:" + k, JSON.stringify(v));
    } catch (e) {
    }
  }
};
const cfg = {
  get(k, d) {
    if (APP && NN.cfg) {
      const v = NN.cfg(k);
      return v === "" || v == null ? d : v;
    }
    return store.get(k, d);
  },
  set(k, v) {
    if (APP && NN.setCfg) NN.setCfg(k, v || "");
    else store.set(k, v);
  }
};
function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.add("on");
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.remove("on"), 2600);
}
const DB = "https://m.douban.com/rexxar/api/v2/";
const DBH = { "Referer": "https://m.douban.com/", "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148" };
const memo = {};
function cached(key, ttl, fn) {
  const c = memo[key];
  if (c && Date.now() - c.t < ttl) return c.p;
  const p = fn();
  memo[key] = { t: Date.now(), p };
  p.catch(() => {
    delete memo[key];
  });
  return p;
}
const dbGet = (path) => cached("db:" + path, 30 * 6e4, () => getJSON(DB + path, DBH, 1e4));
const picOf = (i) => {
  const p = i.pic || {};
  return p.normal || p.large || i.cover_url || i.cover && i.cover.url || "";
};
const dbItem = (i) => ({ id: i.id, title: i.title, pic: picOf(i), rate: i.rating && i.rating.value || "", sub: i.card_subtitle || "", ep: i.episodes_info || "", type: i.type === "tv" ? "tv" : "movie", year: i.year || "" });
function dbList(kind, cat, type, start, count) {
  return __async(this, null, function* () {
    const path = cat === "rec" ? `${kind}/recommend?start=${start}&count=${count}&tags=${encodeURIComponent(type)}` : `subject/recent_hot/${kind}?category=${encodeURIComponent(cat)}&type=${encodeURIComponent(type)}&start=${start}&limit=${count}`;
    const d = yield dbGet(path);
    return { items: (d.items || []).filter((i) => (i.type === "movie" || i.type === "tv") && i.id).map(dbItem), total: d.total || 0 };
  });
}
function dbDetail(kind, id) {
  return __async(this, null, function* () {
    const [d, c] = yield Promise.all([dbGet(`${kind}/${id}`), dbGet(`${kind}/${id}/credits`).catch(() => ({}))]);
    let cast = [];
    (c.items || []).forEach((x) => cast.push({ name: x.name, role: x.simple_character || x.character || "", avatar: x.avatar && (x.avatar.normal || x.avatar.large) || "" }));
    if (!cast.length) cast = (d.directors || []).map((a) => ({ name: a.name, role: "\u5BFC\u6F14", avatar: "" })).concat((d.actors || []).map((a) => ({ name: a.name, role: "\u6F14\u5458", avatar: "" })));
    return {
      id: String(id),
      type: kind,
      title: d.title,
      original: d.original_title || "",
      year: d.year || "",
      pic: d.pic && (d.pic.large || d.pic.normal) || "",
      rate: d.rating && d.rating.value || "",
      genres: d.genres || [],
      countries: d.countries || [],
      durations: d.durations || [],
      episodes: d.episodes_count || 0,
      intro: (d.intro || "").trim(),
      sub: d.card_subtitle || "",
      cast: cast.slice(0, 24),
      directors: (d.directors || []).map((x) => x.name),
      actors: (d.actors || []).map((x) => x.name)
    };
  });
}
function dbRecs(kind, id) {
  return __async(this, null, function* () {
    const d = yield dbGet(`${kind}/${id}/recommendations?count=16`).catch(() => []);
    return (Array.isArray(d) ? d : []).filter((i) => i.type === "movie" || i.type === "tv").map(dbItem);
  });
}
function dbSearch(q) {
  return __async(this, null, function* () {
    const d = yield dbGet(`search/movie?q=${encodeURIComponent(q)}&count=30&start=0`);
    return (d.items || []).filter((x) => (x.target_type === "movie" || x.target_type === "tv") && x.target && x.target.id).map((x) => {
      const t = x.target;
      return { id: t.id, title: t.title, pic: t.cover_url || "", rate: t.rating && t.rating.value || "", sub: t.card_subtitle || t.year || "", type: x.target_type, year: t.year || "" };
    });
  });
}
const short = (s) => (s || "").split(" / ").slice(0, 3).join(" \xB7 ");
const DEF_SRC = "https://raw.githubusercontent.com/zknjjjx/tvbox/main/cmstv.json";
function mirrors(url) {
  const m = url.match(/^https:\/\/(?:raw\.githubusercontent\.com\/([^/]+)\/([^/]+)\/([^/]+)\/(.+)|cdn\.jsdelivr\.net\/gh\/([^/@]+)\/([^/@]+)@([^/]+)\/(.+))$/);
  if (!m) return [url];
  const [u, r, b, p] = m[1] ? [m[1], m[2], m[3], m[4]] : [m[5], m[6], m[7], m[8]];
  const raw = `https://raw.githubusercontent.com/${u}/${r}/${b}/${p}`;
  return [
    `https://ghfast.top/${raw}`,
    `https://cdn.jsdelivr.net/gh/${u}/${r}@${b}/${p}`,
    `https://fastly.jsdelivr.net/gh/${u}/${r}@${b}/${p}`,
    `https://testingcf.jsdelivr.net/gh/${u}/${r}@${b}/${p}`,
    `https://gh-proxy.com/${raw}`,
    raw
  ];
}
function anyOf(ps) {
  return new Promise((res, rej) => {
    let n = ps.length;
    if (!n) rej(new Error("none"));
    ps.forEach((p) => p.then(res, () => {
      if (--n === 0) rej(new Error("all failed"));
    }));
  });
}
const BAD = /成人|福利|伦理|色|情色|AV|av|91|里番|写真|直播/;
function parseSites(d) {
  const arr = Array.isArray(d) ? d : d.sites || d.list || [];
  const out = [], seen = {};
  arr.forEach((s) => {
    if (!s || typeof s.api !== "string" || !/^https?:\/\//.test(s.api)) return;
    if (s.type !== void 0 && s.type !== 1) return;
    if (s.adult || BAD.test(s.name || "")) return;
    const api = s.api.replace(/\/+$/, "");
    if (seen[api]) return;
    seen[api] = 1;
    out.push({ name: String(s.name || api).replace(/┃.*$/, "").trim(), api });
  });
  return out;
}
const SRC = { list: [], from: "", t: 0 };
function loadSources(force) {
  return __async(this, null, function* () {
    const custom = cfg.get("srcUrl", "") || DEF_SRC;
    const cache = store.get("src", null);
    if (!force && cache && cache.url === custom && cache.list.length && Date.now() - cache.t < 12 * 36e5) {
      Object.assign(SRC, cache);
      return SRC;
    }
    try {
      const urls = mirrors(custom);
      const d = yield anyOf(urls.map((u) => getJSON(u, null, 12e3).then((j) => {
        const l = parseSites(j);
        if (!l.length) throw new Error("empty");
        return { l, u };
      })));
      Object.assign(SRC, { list: d.l, from: d.u, t: Date.now(), url: custom });
      store.set("src", SRC);
    } catch (e) {
      if (cache && cache.list.length) Object.assign(SRC, cache);
      else {
        try {
          const txt = yield new Promise((res, rej) => {
            const x = new XMLHttpRequest();
            x.open("GET", "sources.json");
            x.onload = () => res(x.responseText);
            x.onerror = rej;
            x.send();
          });
          Object.assign(SRC, { list: parseSites(JSON.parse(txt)), from: "\u5185\u7F6E\u5FEB\u7167", t: 0, url: custom });
        } catch (e2) {
        }
      }
    }
    try {
      if (APP && NN.setCfg) NN.setCfg("srcState", JSON.stringify({ n: SRC.list.length, from: SRC.from }));
    } catch (e) {
    }
    return SRC;
  });
}
const BOX_PRESETS = [
  { name: "\u996D\u592A\u786C", urls: ["http://www.\u996D\u592A\u786C.com/tv", "http://www.\u996D\u592A\u786C.net/tv", "http://fty.888484.xyz/tv", "http://fty.xxooo.cf/tv"] },
  { name: "\u80A5\u732B", urls: ["http://\u80A5\u732B.com/", "http://\u80A5\u732B.net/", "http://hello.\u80A5\u732B.com/", "https://6296.kstore.vip/fm.gif"] },
  { name: "\u738B\u4E8C\u5C0F", urls: ["http://tvbox.\u738B\u4E8C\u5C0F\u653E\u725B\u5A03.top/", "https://9280.kstore.vip/newwex.json", "http://tv.999888987.xyz/"] },
  { name: "\u8001\u5218\u5907", urls: ["https://raw.liucn.cc/box/m.json"] },
  { name: "\u9AD8\u5929\u6D41\u4E91", urls: ["https://raw.githubusercontent.com/gaotianliuyun/gao/master/js.json"] },
  { name: "\u5357\u98CE", urls: ["https://raw.githubusercontent.com/yoursmile66/TVBox/main/XC.json"] },
  { name: "\u4FCA\u4F6C", urls: ["http://home.jundie.top:81/top98.json"] },
  { name: "\u6478\u9C7C\u513F", urls: ["http://\u6211\u4E0D\u662F.\u6478\u9C7C\u513F.com", "http://\u6211\u4E0D\u662F.\u6478\u9C7C\u513F.top", "http://fish.y456y.com"] },
  { name: "\u83DC\u59AE\u4E1D", urls: ["https://tv.\u83DC\u59AE\u4E1D.top"] },
  { name: "\u5C0F\u76D2\u5B50", urls: ["http://xhztv.top/xhz", "http://xhztv.top/4k.json"] }
];
const BOX_DEF = "\u996D\u592A\u786C";
const BOX = { sites: [], cms: [], state: "", ready: null };
const BOXPAR = (() => {
  try {
    if (APP && NN.boxLow && NN.boxLow()) return 3;
  } catch (e) {
  }
  return MOB ? 4 : 6;
})();
function nnCall(fn, args) {
  return new Promise((r) => {
    const id = "x" + ++seq;
    cbs[id] = r;
    NN[fn].apply(NN, [id].concat(args));
  });
}
function boxUrls(p) {
  let urls = [];
  p.urls.forEach((u) => {
    urls = urls.concat(/githubusercontent|jsdelivr/.test(u) ? mirrors(u) : [u]);
  });
  return urls;
}
function boxLoad(force) {
  if (!APP || !NN.boxLoad) return Promise.resolve(BOX);
  const sel = cfg.get("boxSel", BOX_DEF);
  const plan = [];
  if (sel === "off") plan.push(["", [""]]);
  else {
    if (sel === "custom") plan.push(["\u81EA\u5B9A\u4E49", [cfg.get("boxUrl", "")]]);
    const first = BOX_PRESETS.find((x) => x.name === sel);
    if (first) plan.push([first.name, boxUrls(first)]);
    BOX_PRESETS.forEach((p) => {
      if (p !== first) plan.push([p.name, boxUrls(p)]);
    });
  }
  BOX.state = "\u52A0\u8F7D\u4E2D\u2026";
  BOX.using = "";
  BOX.ready = (() => __async(this, null, function* () {
    for (let k = 0; k < plan.length; k++) {
      let ok = false;
      for (let i = 0; i < plan[k][1].length; i++) {
        const x = yield nnCall("boxLoad", [plan[k][1][i], !!force]);
        let r = {};
        try {
          r = JSON.parse(x.body);
        } catch (e) {
        }
        BOX.state = r.state || "\u52A0\u8F7D\u5931\u8D25";
        if (!plan[k][1][i]) {
          ok = true;
          break;
        }
        if (r.ok) {
          let n = 0;
          try {
            n = JSON.parse(NN.boxSites()).length + JSON.parse(NN.boxCms()).length;
          } catch (e) {
          }
          if (n) {
            ok = true;
            break;
          }
          BOX.state = "\u63A5\u53E3\u91CC\u6CA1\u6709\u53EF\u7528\u7684\u6E90";
        }
      }
      if (ok) {
        BOX.using = plan[k][0];
        break;
      }
    }
    try {
      BOX.sites = JSON.parse(NN.boxSites());
      BOX.cms = parseSites(JSON.parse(NN.boxCms()));
    } catch (e) {
      BOX.sites = [];
      BOX.cms = [];
    }
    return BOX;
  }))();
  return BOX.ready;
}
const PANFLAG = /夸克|阿里|UC|百度|115|天翼|迅雷|XL|磁力|网盘|云盘|p2p|quark|ali|pan|baidu|xunlei|thunder|magnet/i;
const PANID = /^(magnet:|ed2k:|thunder:|tvbox-xg:|xg:|ftp:)|pan\.|quark\.cn|aliyundrive|alipan|115\.com|123pan|cloud\.189|pan\.baidu|drive\.uc/i;
function boxOne(site, name, year, want, onFound, onCam, alive) {
  return __async(this, null, function* () {
    const t0 = Date.now();
    const r = yield nnCall("boxSearch", [site.key, name, 9e3]);
    if (!alive()) return;
    let d = null;
    try {
      d = JSON.parse(r.body);
    } catch (e) {
      return;
    }
    const nm = (v2) => canon(String(v2.vod_name || "").replace(/[\[【(（][^\]】)）]*[\]】)）]/g, ""));
    const yok = (v2) => {
      const vy = String(v2.vod_year || "");
      return !year || !/^\d{4}$/.test(vy) || Math.abs(+vy - +year) <= 1;
    };
    const hit = (d && d.list || []).find((v2) => (canon(v2.vod_name) === want || nm(v2) === want) && yok(v2));
    if (!hit) return;
    let v = hit;
    if (!v.vod_play_url) {
      const x = yield nnCall("boxDetail", [site.key, String(hit.vod_id), 9e3]);
      if (!alive()) return;
      try {
        v = (JSON.parse(x.body).list || [])[0];
      } catch (e) {
        return;
      }
    }
    if (!v || !v.vod_play_url || !yok(v)) return;
    const froms = String(v.vod_play_from || "").split("$$$"), urls = String(v.vod_play_url || "").split("$$$");
    let n = 0;
    urls.forEach((u, i) => {
      if (n >= 3) return;
      const flag = froms[i] || `\u7EBF\u8DEF${i + 1}`;
      if (PANFLAG.test(flag)) return;
      const eps = [];
      u.split("#").forEach((item, idx) => {
        if (!item.trim()) return;
        const k = item.indexOf("$"), en = (k >= 0 ? item.slice(0, k) : "").trim() || `\u7B2C${idx + 1}\u96C6`, id = (k >= 0 ? item.slice(k + 1) : item).trim();
        if (!id || PANID.test(id)) return;
        eps.push({ name: en, url: NN.boxUrl(site.key, flag, id) });
      });
      if (!eps.length) return;
      if (isCam(v, eps)) {
        if (onCam) onCam(site.name);
        return;
      }
      n++;
      onFound({ site: urls.length > 1 ? `${site.name}\xB7${flag}` : site.name, api: `box:${site.key}:${flag}`, eps, remarks: v.vod_remarks || "", ms: Date.now() - t0, rank: 300, bps: null, box: 1 });
    });
  });
}
const CN = { "\u4E00": 1, "\u4E8C": 2, "\u4E09": 3, "\u56DB": 4, "\u4E94": 5, "\u516D": 6, "\u4E03": 7, "\u516B": 8, "\u4E5D": 9, "\u5341": 10 };
function canon(t) {
  t = String(t || "").replace(/[\s·:：,，.。!！?？'"“”‘’()（）\[\]【】\-_—]/g, "").toLowerCase();
  t = t.replace(/第([一二三四五六七八九十])季/g, (_, c) => `\u7B2C${CN[c]}\u5B63`).replace(/season(\d+)/g, "\u7B2C$1\u5B63");
  return t.replace(/第1季$/, "");
}
function parseEpisodes(v) {
  const froms = (v.vod_play_from || "").split("$$$"), urls = (v.vod_play_url || "").split("$$$");
  const groups = [];
  urls.forEach((u, i) => {
    const eps = [];
    u.split("#").forEach((item, idx) => {
      if (!item.trim()) return;
      let name, link;
      if (item.indexOf("$") >= 0) {
        name = item.slice(0, item.indexOf("$"));
        link = item.slice(item.indexOf("$") + 1);
      } else {
        name = `\u7B2C${idx + 1}\u96C6`;
        link = item;
      }
      link = link.trim();
      if (/^https?:\/\//.test(link)) eps.push({ name: name.trim() || `\u7B2C${idx + 1}\u96C6`, url: link });
    });
    if (eps.length) groups.push({ from: froms[i] || `\u7EBF\u8DEF${i + 1}`, eps, m3u8: eps.some((e) => e.url.indexOf(".m3u8") >= 0) });
  });
  return groups.filter((g) => g.m3u8);
}
const CAM = /枪版|槍版|抢先|搶先|抢鲜|尝鲜版|偷拍|(^|[^a-z])(hd)?(tc|ts|cam|hdcam|telesync|telecine)([^a-z]|$)/i;
const isCam = (v, eps) => eps.length <= 3 && ([v.vod_remarks, v.vod_version, v.vod_play_note].some((x) => x && CAM.test(String(x))) || eps.some((e) => CAM.test(e.name)));
const CMS_OFF = true;
function resolve(name, year, onFound, onCam) {
  const want = canon(name);
  let alive = true, idx = 0, running = 0;
  const have = {};
  SRC.list.forEach((s) => {
    have[s.api] = 1;
  });
  const sites = CMS_OFF ? [] : SRC.list.concat(BOX.cms.filter((s) => !have[s.api]));
  const boxDone = APP && NN.boxSearch ? (BOX.ready || Promise.resolve(BOX)).then(() => new Promise((done) => {
    const list = BOX.sites.slice();
    let bi = 0, br = 0;
    const next = () => {
      if (!alive || bi >= list.length && br === 0) return done();
      while (br < BOXPAR && bi < list.length) {
        const s = list[bi++];
        br++;
        boxOne(s, name, year, want, onFound, onCam, () => alive).catch(() => {
        }).then(() => {
          br--;
          next();
        });
      }
    };
    next();
  })) : Promise.resolve();
  return {
    cancel() {
      alive = false;
    },
    done: new Promise((done) => {
      const fin = () => boxDone.then(done, done);
      if (!sites.length) return fin();
      const next = () => {
        if (!alive) return done();
        if (idx >= sites.length && running === 0) return fin();
        while (running < 20 && idx < sites.length) {
          const s = sites[idx], rank = idx++;
          running++;
          const t0 = Date.now();
          getJSON(`${s.api}/?ac=videolist&wd=${encodeURIComponent(name)}`, null, 6e3).then((d) => {
            if (!alive) return;
            for (const v of d.list || []) {
              if (canon(v.vod_name) !== want) continue;
              const vy = String(v.vod_year || "");
              if (year && /^\d{4}$/.test(vy) && Math.abs(+vy - +year) > 1) continue;
              const g = parseEpisodes(v);
              if (!g.length) continue;
              if (isCam(v, g[0].eps)) {
                if (onCam) onCam(s.name);
                continue;
              }
              onFound({ site: s.name, api: s.api, eps: g[0].eps, remarks: v.vod_remarks || "", ms: Date.now() - t0, rank, bps: null });
              break;
            }
          }).catch(() => {
          }).then(() => {
            running--;
            next();
          });
        }
      };
      next();
    })
  };
}
const F = {
  cur: null,
  scope() {
    return $("#layer").classList.contains("on") ? $("#layer") : document;
  },
  hold: null,
  // 菜单预览中：页面加载时不抢焦点
  set(el, quiet) {
    if (!el) return;
    if (MOB) {
      F.prev = F.cur;
      F.cur = el;
      return;
    }
    if (F.hold && !F.user && !el.closest("#top") && !el.closest("#layer")) return;
    if (F.hold && !el.closest("#top")) F.hold = null;
    if (F.cur) F.cur.classList.remove("focus");
    F.prev = F.cur;
    F.cur = el;
    el.classList.add("focus");
    const g = el.closest("[data-g]");
    if (g) g._last = el;
    F.reveal(el);
    if (!quiet && el._onfocus) el._onfocus();
    if (el.closest("#top")) $("#top").classList.remove("hide");
    if (el.tagName === "INPUT") el.focus();
    else if (document.activeElement && document.activeElement.tagName === "INPUT") document.activeElement.blur();
  },
  revealEp(el) {
    const vp = el.closest(".epvp"), list = vp && vp.firstChild;
    if (!list) return;
    const H = vp.clientHeight, t = el.offsetTop, h = el.offsetHeight;
    let y = list._y || 0;
    if (t - y < h * 1.2) y = t - h * 1.2;
    else if (t + h - y > H - h * 1.2) y = t + h - H + h * 1.2;
    y = Math.max(0, Math.min(y, list.scrollHeight - H + h * 0.3));
    if (list.scrollHeight <= H) y = 0;
    list._y = y;
    list.style.transform = `translate3d(0,${-y}px,0)`;
  },
  reveal(el) {
    if (el.closest(".epvp")) {
      F.revealEp(el);
      return;
    }
    const track = el.closest(".track");
    if (track) {
      const vp = track.parentElement, vw = vp.clientWidth;
      const items = $$(".f", track), i = items.indexOf(el);
      const w = el.offsetWidth + parseFloat(getComputedStyle(el).marginRight || 0);
      let x = Math.max(0, (i - 1) * w);
      const max = Math.max(0, track.scrollWidth - (vw - 6.4 * rem()));
      x = Math.min(x, max + 2 * rem());
      if (track.scrollWidth <= vw - 3 * rem()) x = 0;
      track.style.transform = `translateX(${-x}px)`;
    }
    const sc = el.closest(".scroll");
    if (sc && !el.closest("#top")) {
      const pageH = $("#page").clientHeight;
      const anchor = el.closest(".row,.hero,.dt,.eps-wrap,.grid-item,.chips,.sec") || el;
      let top = 0, n = anchor;
      while (n && n !== sc) {
        top += n.offsetTop;
        n = n.offsetParent;
      }
      if (anchor.classList.contains("grid-item") || anchor === el) {
        top = 0;
        n = el;
        while (n && n !== sc) {
          top += n.offsetTop;
          n = n.offsetParent;
        }
      }
      const h = anchor.offsetHeight;
      let y = sc._y || 0;
      const topPad = sc.dataset.pad ? parseFloat(sc.dataset.pad) * rem() : 6 * rem();
      if (anchor.classList.contains("hero") || anchor.classList.contains("dt") || top < topPad) y = 0;
      else if (top - y < topPad) y = top - topPad;
      else if (top + h - y > pageH - 2 * rem()) y = top + h - pageH + 2 * rem();
      y = Math.max(0, Math.min(y, Math.max(0, sc.scrollHeight - pageH + 2 * rem())));
      if (sc.dataset.lock === "row") y = Math.max(0, top - topPad);
      else if ($$(".f", sc).pop() === el) y = Math.max(y, sc.scrollHeight - pageH + 2 * rem());
      const rows = $$(".row,.grid-row", sc), tops = rows.map((r) => [r.offsetTop, r.offsetHeight]), at = anchor.offsetTop, lock = sc.dataset.lock === "row";
      rows.forEach((r, i) => {
        const t = tops[i][0], b = t + tops[i][1];
        r.classList.toggle("past", lock && t < at);
        r.classList.toggle("far", b < y - pageH * 0.6 || t > y + pageH * 1.6);
      });
      sc._y = y;
      sc.style.transform = `translateY(${-y}px)`;
      $("#top").classList.toggle("hide", y > 2 * rem() && !!sc.dataset.hidetop);
      if (sc._onScroll) sc._onScroll(el);
    }
  },
  visible(el) {
    return el.offsetParent !== null;
  },
  move(dir) {
    const cur = F.cur && document.contains(F.cur) ? F.cur : null;
    let all = $$(".f", F.scope());
    if (!cur) {
      F.set(all.filter(F.visible)[0]);
      return;
    }
    if (dir === "up" || dir === "down") {
      const seen = /* @__PURE__ */ new Set(), g02 = cur.closest(".track");
      all = all.filter((el) => {
        const t = el.closest(".track");
        if (!t || t === g02) return true;
        if (seen.has(t)) return false;
        seen.add(t);
        return true;
      }).map((el) => {
        const t = el.closest(".track");
        return t && t !== cur.closest(".track") && t._last && document.contains(t._last) ? t._last : el;
      });
    }
    all = all.filter(F.visible);
    const r0 = cur.getBoundingClientRect();
    const cx = r0.left + r0.width / 2, cy = r0.top + r0.height / 2;
    const g0 = cur.closest("[data-g]");
    if ((dir === "left" || dir === "right") && g0 && g0.classList.contains("track")) {
      const items = $$(".f", g0), i = items.indexOf(cur), nx = items[i + (dir === "left" ? -1 : 1)];
      if (nx) {
        F.set(nx);
        return;
      }
      if (!document.querySelector(".epcol")) return;
    }
    if ((dir === "left" || dir === "right") && $(".epcol") && $("#epsSec").style.display !== "none") {
      const acts = $(".dtwrap .acts"), eps = $(".epcol .eps");
      if (dir === "left" && cur.closest(".epcol")) {
        F.set(acts._last && document.contains(acts._last) ? acts._last : $("#bPlay"));
        return;
      }
      if (dir === "right" && cur.closest(".dtwrap") && !$$(".f", g0 || document).some((x) => x !== cur && x.getBoundingClientRect().left > r0.right - 2 && Math.abs(x.getBoundingClientRect().top - r0.top) < r0.height)) {
        F.set(eps._last && document.contains(eps._last) ? eps._last : $(".ep.cur", eps) || $(".ep", eps));
        return;
      }
    }
    let best = null, bs = 1e12;
    all.forEach((el) => {
      if (el === cur) return;
      const r = el.getBoundingClientRect();
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      let main, cross;
      if (dir === "left") {
        if (r.right > r0.left + 2 && x >= cx) return;
        main = cx - x;
        cross = Math.abs(y - cy);
      } else if (dir === "right") {
        if (r.left < r0.right - 2 && x <= cx) return;
        main = x - cx;
        cross = Math.abs(y - cy);
      } else if (dir === "up") {
        if (r.bottom > r0.top + 2 && y >= cy) return;
        main = cy - y;
        cross = Math.abs(x - cx);
      } else {
        if (r.top < r0.bottom - 2 && y <= cy) return;
        main = y - cy;
        cross = Math.abs(x - cx);
      }
      if (main <= 0) return;
      const horiz = dir === "left" || dir === "right";
      if (horiz && g0 && el.closest("[data-g]") !== g0 && !F.cross) return;
      const s = main + cross * (horiz ? 4 : 1.2);
      if (s < bs) {
        bs = s;
        best = el;
      }
    });
    if (!best && (dir === "left" || dir === "right") && !F.cross && $(".epcol,.skb")) {
      F.cross = true;
      F.move(dir);
      F.cross = false;
      return;
    }
    if (!best) return;
    if (!(dir === "left" || dir === "right")) {
      const g = best.closest("[data-g]");
      if (g && g !== g0 && g._last && document.contains(g._last) && F.visible(g._last)) best = g._last;
    }
    F.set(best);
  }
};
let remPx = 20;
const rem = () => remPx;
function fit() {
  if (MOB) {
    remPx = Math.min(window.innerWidth, 640) / 24;
    document.documentElement.style.fontSize = remPx + "px";
    const t = $("#top");
    if (t) $("#page").style.top = t.offsetHeight + "px";
    return;
  }
  remPx = window.innerWidth / 74;
  document.documentElement.style.fontSize = remPx + "px";
}
let bgI = 0, bgUrl = "";
function clearBg() {
  bgUrl = "";
  $$("#bg .bgl").forEach((l) => l.classList.remove("on"));
  document.body.classList.remove("poster");
}
function setBg(url) {
  document.body.classList.add("poster");
  if (!url || url === bgUrl) return;
  bgUrl = url;
  const ls = $$("#bg .bgl"), img = new Image();
  img.onload = () => {
    if (bgUrl !== url) return;
    bgI ^= 1;
    ls[bgI].style.backgroundImage = `url("${img.src}")`;
    ls[bgI].classList.add("on");
    ls[bgI ^ 1].classList.remove("on");
  };
  img.src = url.replace(/\/[a-z]_ratio_poster\//, "/l_ratio_poster/");
}
const TABS = [["home", "\u9996\u9875"], ["movie", "\u7535\u5F71"], ["tv", "\u5267\u96C6"], ["anime", "\u52A8\u6F2B"], ["show", "\u7EFC\u827A"], ["search", "\u641C\u7D22"], ["history", "\u89C2\u770B\u8BB0\u5F55"], ["settings", "\u8BBE\u7F6E"]];
const stack = [];
let route = null, routeTok = 0;
function go(name, arg, replace) {
  if (route && !replace) stack.push({ name: route.name, arg: route.arg, focus: F.cur, y: ($(".scroll") || {})._y, sy: $("#page").scrollTop });
  route = { name, arg };
  render(name, arg);
}
function render(name, arg, restore) {
  routeTok++;
  if (resolving) {
    resolving.cancel();
    resolving = null;
  }
  const tk = name === "cat" ? arg : name;
  $$("#tabs .tab").forEach((t) => t.classList.toggle("on", t.dataset.k === tk));
  $("#top").classList.remove("hide");
  if (name !== "detail") clearBg();
  const fn = PAGES[name];
  if (MOB) {
    const pg = $("#page");
    pg._more = null;
    pg.scrollTop = 0;
    if (restore && restore.sy) [80, 400, 1200].forEach((t) => setTimeout(() => {
      if (route && route.name === name) pg.scrollTop = restore.sy;
    }, t));
    const tab = $("#tabs .tab.on");
    if (tab && tab.scrollIntoView) {
      const tb = $("#tabs");
      tb.scrollLeft = Math.max(0, tab.offsetLeft - tb.clientWidth / 2 + tab.offsetWidth / 2);
    }
  }
  if (!F.hold) {
    if (F.cur) F.cur.classList.remove("focus");
    F.cur = null;
  }
  fn(routeTok, arg, restore);
}
window.nnBack = function() {
  if ($("#layer").classList.contains("on")) {
    closeLayer();
    return true;
  }
  if (MOB) {
    const inp = document.activeElement;
    if (inp && inp.tagName === "INPUT") {
      inp.blur();
      return true;
    }
    if (stack.length) {
      const p = stack.pop();
      route = { name: p.name, arg: p.arg };
      render(p.name, p.arg, p);
      return true;
    }
    if (route && route.name !== "home") {
      route = { name: "home" };
      render("home");
      return true;
    }
    return false;
  }
  if (stack.length) {
    const p = stack.pop();
    route = { name: p.name, arg: p.arg };
    render(p.name, p.arg, p);
    return true;
  }
  const cur = F.cur && document.contains(F.cur) ? F.cur : null;
  const toTop = () => {
    const sc = $(".scroll");
    if (sc) {
      sc._y = 0;
      sc.style.transform = "translateY(0)";
      $$(".row", sc).forEach((r) => r.classList.remove("past"));
    }
    $("#top").classList.remove("hide");
  };
  const chip = $("#page .chip.on");
  if (cur && chip && !cur.closest("#top") && !cur.classList.contains("chip")) {
    toTop();
    F.set(chip, true);
    return true;
  }
  const tabOn = $("#tabs .tab.on");
  if (cur && !cur.closest("#top") && tabOn) {
    toTop();
    F.set(tabOn, true);
    return true;
  }
  const homeTab = $('#tabs .tab[data-k="home"]');
  if (route && route.name !== "home") {
    F.hold = homeTab;
    F.set(homeTab, true);
    stack.length = 0;
    route = { name: "home" };
    render("home");
    return true;
  }
  if (F.cur !== homeTab) {
    toTop();
    F.set(homeTab, true);
    return true;
  }
  return false;
};
window.nnRemote = function(act, v) {
  return __async(this, null, function* () {
    if (act === "src") {
      toast("\u624B\u673A\u5DF2\u4FEE\u6539\u7247\u6E90\uFF0C\u6B63\u5728\u52A0\u8F7D\u2026");
      yield loadSources(true);
      toast(`\u7247\u6E90\uFF1A${SRC.list.length} \u4E2A`);
      if (route && route.name === "settings") render("settings");
    } else if (act === "search") {
      F.hold = null;
      stack.length = 0;
      route = { name: "search", arg: v };
      render("search", v);
      toast("\u6536\u5230\u641C\u7D22\uFF1A" + v);
    } else if (act === "toast") {
      toast(v);
    } else if (act === "hist") {
      toast("\u89C2\u770B\u8BB0\u5F55\u5DF2\u6E05\u9664");
      if (route && (route.name === "settings" || route.name === "history" || route.name === "home")) render(route.name);
    }
  });
};
window.nnState = () => JSON.stringify({ n: SRC.list.length, from: SRC.from });
window.nnResume = function() {
  if (!route) return;
  if (route.name === "home") refreshHistory();
  else if (route.name === "history") render("history");
  else if (route.name === "detail" && window.__refreshDetail) window.__refreshDetail();
};
const ICON_PLAY = `<svg viewBox="0 0 24 24"><path d="M8 5.14v13.72a1 1 0 0 0 1.5.86l11.2-6.86a1 1 0 0 0 0-1.72L9.5 4.28A1 1 0 0 0 8 5.14z"/></svg>`;
const ICON_LIST = `<svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z"/></svg>`;
const ICON_INFO = `<svg viewBox="0 0 24 24"><path d="M11 7h2v2h-2zm0 4h2v6h-2zm1-9a10 10 0 1 0 0 20 10 10 0 0 0 0-20z"/></svg>`;
const ICON_SEARCH = `<svg viewBox="0 0 24 24"><path d="M10 2a8 8 0 0 1 6.32 12.9l5.39 5.4-1.41 1.4-5.4-5.39A8 8 0 1 1 10 2zm0 2a6 6 0 1 0 0 12 6 6 0 0 0 0-12z"/></svg>`;
function card(it, extra) {
  extra = extra || {};
  const prog = extra.prog ? `<div class="prog" style="width:${extra.prog}%"></div>` : "";
  return `<div class="card f${extra.cls ? " " + extra.cls : ""}" data-id="${esc(it.id || "")}" data-type="${esc(it.type || "movie")}" data-title="${esc(it.title)}" data-year="${esc(it.year || "")}" data-pic="${esc(it.pic || "")}">
    <div class="pic" style="background-image:url('${esc(it.pic || "")}')">${it.rate ? `<span class="badge">${esc(it.rate)}</span>` : ""}${extra.sub || it.ep ? `<span class="sub">${esc(extra.sub || it.ep)}</span>` : ""}${prog}</div>
    <div class="t">${esc(it.title)}</div></div>`;
}
function bindCards(root, onFocus) {
  $$(".card", root).forEach((c) => {
    if (c._bound) return;
    c._bound = 1;
    c._ok = () => openDetail({ id: c.dataset.id, type: c.dataset.type, title: c.dataset.title, year: c.dataset.year, pic: c.dataset.pic });
    if (onFocus) c._onfocus = () => onFocus(c);
  });
}
function openDetail(it) {
  go("detail", it);
}
function skel(n) {
  let s = "";
  for (let i = 0; i < n; i++) s += `<div class="card"><div class="pic skel"></div><div class="t">&nbsp;</div></div>`;
  return s;
}
function rowHTML(id, title, inner, small) {
  return `<section class="row" id="${id}"><h3>${esc(title)}${small ? ` <small>${esc(small)}</small>` : ""}</h3><div class="vp"><div class="track" data-g="${id}">${inner}</div></div></section>`;
}
function openLayer(html, focusSel) {
  const l = $("#layer");
  l._prev = F.cur;
  l.innerHTML = html;
  l.classList.add("on");
  F.set($(focusSel || ".f", l) || $(".f", l));
}
function closeLayer() {
  const l = $("#layer");
  if (l._onclose) {
    const f = l._onclose;
    l._onclose = null;
    f();
  }
  l.classList.remove("on");
  l.innerHTML = "";
  if (l._prev && document.contains(l._prev)) F.set(l._prev, true);
}
const HOME_ROWS = [
  ["\u70ED\u95E8\u7535\u5F71", "movie", "\u70ED\u95E8", "\u5168\u90E8"],
  ["\u70ED\u64AD\u5267\u96C6", "tv", "tv", "tv"],
  ["\u56FD\u4EA7\u5267", "tv", "tv", "tv_domestic"],
  ["\u70ED\u95E8\u7EFC\u827A", "tv", "show", "show"],
  ["\u70ED\u95E8\u52A8\u6F2B", "tv", "tv", "tv_animation"],
  ["\u8C46\u74E3\u9AD8\u5206", "movie", "\u8C46\u74E3\u9AD8\u5206", "\u5168\u90E8"],
  ["\u6B27\u7F8E\u5267", "tv", "tv", "tv_american"],
  ["\u97E9\u5267", "tv", "tv", "tv_korean"],
  ["\u65E5\u5267", "tv", "tv", "tv_japanese"],
  ["\u534E\u8BED\u7535\u5F71", "movie", "\u70ED\u95E8", "\u534E\u8BED"],
  ["\u7EAA\u5F55\u7247", "tv", "tv", "tv_documentary"]
];
const CHIPS = {
  movie: [["\u70ED\u95E8", "movie", "\u70ED\u95E8", "\u5168\u90E8"], ["\u6700\u65B0", "movie", "\u6700\u65B0", "\u5168\u90E8"], ["\u9AD8\u5206", "movie", "\u8C46\u74E3\u9AD8\u5206", "\u5168\u90E8"], ["\u51B7\u95E8\u4F73\u7247", "movie", "\u51B7\u95E8\u4F73\u7247", "\u5168\u90E8"], ["\u534E\u8BED", "movie", "\u70ED\u95E8", "\u534E\u8BED"], ["\u6B27\u7F8E", "movie", "\u70ED\u95E8", "\u6B27\u7F8E"], ["\u97E9\u56FD", "movie", "\u70ED\u95E8", "\u97E9\u56FD"], ["\u65E5\u672C", "movie", "\u70ED\u95E8", "\u65E5\u672C"]],
  tv: [["\u7EFC\u5408", "tv", "tv", "tv"], ["\u56FD\u4EA7\u5267", "tv", "tv", "tv_domestic"], ["\u6B27\u7F8E\u5267", "tv", "tv", "tv_american"], ["\u97E9\u5267", "tv", "tv", "tv_korean"], ["\u65E5\u5267", "tv", "tv", "tv_japanese"], ["\u6E2F\u5267", "tv", "rec", "\u7535\u89C6\u5267,\u9999\u6E2F"], ["\u7EAA\u5F55\u7247", "tv", "tv", "tv_documentary"]],
  anime: [["\u70ED\u64AD", "tv", "tv", "tv_animation"], ["\u65E5\u6F2B", "tv", "rec", "\u52A8\u753B,\u65E5\u672C"], ["\u56FD\u6F2B", "tv", "rec", "\u52A8\u753B,\u4E2D\u56FD\u5927\u9646"], ["\u6B27\u7F8E\u52A8\u753B", "tv", "rec", "\u52A8\u753B,\u7F8E\u56FD"], ["\u52A8\u753B\u7535\u5F71", "movie", "rec", "\u52A8\u753B"]],
  show: [["\u7EFC\u5408", "tv", "show", "show"], ["\u56FD\u5185", "tv", "show", "show_domestic"], ["\u56FD\u5916", "tv", "show", "show_foreign"], ["\u771F\u4EBA\u79C0", "tv", "rec", "\u7EFC\u827A,\u771F\u4EBA\u79C0"], ["\u8131\u53E3\u79C0", "tv", "rec", "\u7EFC\u827A,\u8131\u53E3\u79C0"], ["\u97F3\u4E50", "tv", "rec", "\u7EFC\u827A,\u97F3\u4E50"]]
};
const histAll = () => {
  try {
    return APP ? JSON.parse(NN.history()) : store.get("hist", []);
  } catch (e) {
    return [];
  }
};
const histKey = (it) => it.id ? `${it.type || "movie"}:${it.id}` : `t:${canon(it.title)}`;
const fmtT = (ms) => {
  const s = Math.floor((ms || 0) / 1e3);
  return s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}` : `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
};
function histCard(h, cls) {
  const prog = h.dur ? Math.min(100, h.pos / h.dur * 100) : 0;
  return card({ id: h.id, type: h.type, title: h.title, pic: h.pic, year: h.year }, { prog, cls, sub: h.eps > 1 ? `\u770B\u5230 ${h.epName}` : `\u770B\u5230 ${fmtT(h.pos)}` });
}
function refreshHistory() {
  const box = $("#rowHist .track");
  if (box) {
    const h = histAll().slice(0, 16);
    box.innerHTML = h.map(histCard).join("");
    bindCards(box);
  }
}
const heroInfo = {};
let heroTimer = 0;
function heroFocus(c) {
  const it = { id: c.dataset.id, type: c.dataset.type, title: c.dataset.title, pic: c.dataset.pic };
  const hero = $(".hero");
  if (!hero) return;
  clearTimeout(heroTimer);
  heroTimer = setTimeout(() => {
    setBg(it.pic || "");
    $("h1", hero).textContent = it.title;
    const fill = (d) => {
      if (!$(".hero") || $("h1", hero).textContent !== it.title) return;
      $(".meta", hero).innerHTML = [
        d.rate ? `<span class="rate">\u8C46\u74E3 ${esc(d.rate)}</span>` : "",
        d.year ? `<span>${esc(d.year)}</span>` : "",
        d.countries && d.countries.length ? `<span>${esc(d.countries.slice(0, 2).join(" / "))}</span>` : "",
        d.genres && d.genres.length ? `<span class="pill">${esc(d.genres.slice(0, 3).join(" \xB7 "))}</span>` : "",
        d.episodes ? `<span class="pill">${d.episodes} \u96C6</span>` : ""
      ].join("");
      $(".intro", hero).textContent = d.intro || "";
    };
    $(".meta", hero).innerHTML = "";
    $(".intro", hero).textContent = "";
    if (it.id) dbDetail(it.type === "tv" ? "tv" : "movie", it.id).then(fill).catch(() => {
    });
  }, 260);
}
const PAGES = {
  home(tok, _, restore) {
    $("#page").innerHTML = `<div class="scroll home" data-lock="row" data-pad="6.2"><div style="height:6.2rem"></div>
      ${HOME_ROWS.map((r, i) => rowHTML("row" + i, r[0], skel(8))).join("")}</div>`;
    bindCards($("#page"));
    let focused = false;
    const focusFirst = () => {
      if (focused || tok !== routeTok) return;
      focused = true;
      if (bootFocus) {
        bootFocus = false;
        F.set($('#tabs .tab[data-k="home"]'), true);
        if (window.nnSplashGo) window.nnSplashGo(firstPics());
        return;
      }
      if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus);
      else F.set($("#page .card.f"));
    };
    const loadRow = (i) => {
      const r = HOME_ROWS[i];
      return dbList(r[1], r[2], r[3], 0, 24).then((d) => {
        if (tok !== routeTok) return;
        const t = $(`#row${i} .track`);
        t.innerHTML = d.items.length ? d.items.map((it) => card(Object.assign(it, { type: r[1] === "tv" ? "tv" : it.type }), { sub: short(it.sub).split(" \xB7 ")[0] || it.ep })).join("") : `<div class="empty">\u6682\u65F6\u62FF\u4E0D\u5230\u8C46\u74E3\u6570\u636E</div>`;
        bindCards(t);
        if (i === 0) focusFirst();
      }).catch(() => {
        const t = $(`#row${i} .track`);
        if (t) t.innerHTML = `<div class="empty">\u8C46\u74E3\u6570\u636E\u52A0\u8F7D\u5931\u8D25\uFF0C\u7A0D\u540E\u518D\u8BD5</div>`;
      });
    };
    const firstPics = () => $$("#row0 .card .pic").slice(0, 8).map((e) => (/url\('([^']+)'\)/.exec(e.getAttribute("style") || "") || [])[1]).filter(Boolean);
    loadRow(0).then(() => afterSplash(() => {
      for (let i = 1; i < HOME_ROWS.length; i++) loadRow(i);
    }));
  },
  cat(tok, key, restore) {
    const chips = CHIPS[key], name = { movie: "\u7535\u5F71", tv: "\u5267\u96C6", anime: "\u52A8\u6F2B", show: "\u7EFC\u827A" }[key];
    let ci = restore ? store.get("chip:" + key, 0) : 0;
    if (!chips[ci]) ci = 0;
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec cathead"><h2>${name}</h2><span id="cnt" style="display:none"></span>
      <div class="chips" data-g="chips">${chips.map((c, i) => `<div class="chip f${i === ci ? " on" : ""}" data-i="${i}">${c[0]}</div>`).join("")}</div></div>
      <div class="grid" id="grid">${skel(14)}</div></div>`;
    const grid = $("#grid");
    let start = 0, busy = false, end = false, my = 0;
    const load = (first) => __async(this, null, function* () {
      if (busy || end) return;
      busy = true;
      const c = chips[ci], me = ++my;
      try {
        const d = yield dbList(c[1], c[2], c[3], start, 28);
        if (tok !== routeTok || me !== my) return;
        if (first) grid.innerHTML = "";
        grid.insertAdjacentHTML("beforeend", d.items.map((it) => card(Object.assign(it, { type: c[1] === "tv" ? "tv" : it.type }), { sub: short(it.sub).split(" \xB7 ")[0] || it.ep, cls: "grid-item" })).join(""));
        bindCards(grid, (el) => {
          const items = $$(".card", grid);
          if (items.indexOf(el) > items.length - 15) load();
        });
        start += d.items.length;
        end = !d.items.length || d.total && start >= d.total;
        $("#cnt").textContent = "";
        if (first && !d.items.length) grid.innerHTML = `<div class="empty">\u6682\u65E0\u5185\u5BB9</div>`;
      } catch (e) {
        if (first) grid.innerHTML = `<div class="empty">\u8C46\u74E3\u6570\u636E\u52A0\u8F7D\u5931\u8D25</div>`;
      }
      busy = false;
    });
    const pick = (ch) => {
      if (+ch.dataset.i === ci) return;
      ci = +ch.dataset.i;
      store.set("chip:" + key, ci);
      $$(".chip").forEach((x) => x.classList.toggle("on", x === ch));
      start = 0;
      end = false;
      busy = false;
      my++;
      grid.innerHTML = skel(14);
      load(true);
    };
    $$(".chip", $("#page")).forEach((ch) => {
      ch._onfocus = () => {
        clearTimeout(pick._t);
        pick._t = setTimeout(() => {
          if (F.cur === ch) pick(ch);
        }, 280);
      };
      ch._ok = () => {
        clearTimeout(pick._t);
        pick(ch);
        const c = $("#grid .card.f");
        if (c) F.set(c);
      };
    });
    F.set($(".chip.on"));
    $("#page")._more = () => load();
    load(true);
  },
  detail(tok, it, restore) {
    return __async(this, null, function* () {
      const kind = it.type === "tv" ? "tv" : "movie";
      setBg(it.pic);
      $("#page").innerHTML = `<div class="dtwrap"><section class="dt"><div class="poster" style="background-image:url('${esc(it.pic)}')"></div>
      <div class="info"><h1>${esc(it.title)}</h1><div class="orig"></div><div class="meta"></div><div class="crew"></div><div class="intro"></div>
      <div class="acts" data-g="acts"><div class="btn pri f" id="bPlay">${ICON_PLAY}<span>\u64AD\u653E</span></div><div class="btn f" id="bIntro">${ICON_INFO}<span>\u5B8C\u6574\u7B80\u4ECB</span></div></div>
      <div class="srcline"><i class="lamp wait"></i><span id="srcSt">\u6B63\u5728\u5168\u7F51\u627E\u7247\u6E90\u2026</span></div></div></section>
      <div id="castRow"></div></div>
      <aside class="epcol" id="epsSec" style="display:none"><h3>\u9009\u96C6 <small id="epsInfo"></small></h3><div class="epvp"><div class="eps" data-g="eps"></div></div></aside>`;
      F.set($("#bPlay"));
      if (MOB) {
        const dt = $(".dt");
        [".crew", ".intro", ".acts", ".srcline"].forEach((s) => dt.appendChild($(s, dt)));
        $(".dtwrap").insertBefore($("#epsSec"), $("#castRow"));
      }
      const key = histKey({ id: it.id, type: kind, title: it.title });
      const st = { srcs: [], done: false, probing: 0, probed: 0, cam: 0 };
      let meta = { title: it.title, year: it.year, pic: it.pic };
      const hist = () => histAll().find((h) => h.key === key);
      const playLabel = () => {
        const h = hist();
        $("#bPlay span").textContent = h ? h.eps > 1 ? `\u7EE7\u7EED ${h.epName}` : `\u7EE7\u7EED\u64AD\u653E ${fmtT(h.pos)}` : "\u64AD\u653E";
      };
      playLabel();
      const status = () => {
        const ok = st.srcs.filter((s) => s.bps > 0).sort((a, b) => b.bps - a.bps);
        const lamp = $(".srcline .lamp"), el = $("#srcSt");
        if (!el) return;
        if (!st.srcs.length) {
          lamp.className = "lamp " + (st.done ? "bad" : "wait");
          el.textContent = st.done ? st.cam ? `\u53EA\u6709\u67AA\u7248\uFF08${st.cam} \u4E2A\uFF09\uFF0C\u5DF2\u8FC7\u6EE4\uFF0C\u7B49\u51FA\u4E86\u9AD8\u6E05\u518D\u6765` : "\u6CA1\u627E\u5230\u8FD9\u90E8\u7247\u7684\u7247\u6E90" : "\u6B63\u5728\u5168\u7F51\u627E\u7247\u6E90\u2026";
          return;
        }
        lamp.className = "lamp " + (ok.length ? "ok" : "wait");
        el.textContent = `\u627E\u5230 ${st.srcs.length} \u4E2A\u7247\u6E90${st.done ? "" : "\uFF08\u8FD8\u5728\u627E\uFF09"}${st.cam ? `\uFF0C\u5DF2\u6EE4\u6389 ${st.cam} \u4E2A\u67AA\u7248` : ""}` + (ok.length ? ` \xB7 \u6700\u5FEB\u300C${ok[0].site}\u300D${(ok[0].bps / 1048576).toFixed(1)} MB/s` : st.probing ? " \xB7 \u6D4B\u901F\u4E2D" : "");
      };
      const probeQ = [];
      const pump = () => {
        while (st.probing < (MOB ? 3 : 6) && probeQ.length) {
          const s = probeQ.shift();
          st.probing++;
          probe(s.eps[0].url).then((r) => {
            s.bps = r.bps;
            s.h = r.h || 0;
            s.err = r.err || "";
          }).then(() => {
            st.probing--;
            st.probed++;
            status();
            pump();
          });
        }
      };
      let epsShown = 0;
      const showEps = () => {
        const best = st.srcs.slice().sort((a, b) => b.eps.length - a.eps.length)[0];
        if (!best || best.eps.length < 2 || best.eps.length <= epsShown || !$("#epsSec")) return;
        if (F.cur && F.cur.closest("#epsSec")) return;
        epsShown = best.eps.length;
        $("#epsSec").style.display = "";
        $(".dtwrap").classList.add("haseps");
        $("#epsInfo").textContent = `\u5171 ${best.eps.length} \u96C6${best.remarks ? " \xB7 " + best.remarks : ""}`;
        const h = hist(), cur = h ? h.ei : -1;
        $(".eps").innerHTML = best.eps.map((e, i) => `<div class="ep f${i === cur ? " cur" : ""}" data-i="${i}">${esc(e.name)}</div>`).join("");
        $$(".ep").forEach((b) => {
          b._ok = () => startPlay(+b.dataset.i, 0);
        });
        const c0 = $(".ep.cur");
        if (c0) F.revealEp(c0);
      };
      const found = (s) => {
        if (tok !== routeTok) return;
        st.srcs.push(s);
        probeQ.push(s);
        pump();
        status();
        showEps();
      };
      const startResolve = () => {
        if (!SRC.list.length) {
          st.done = true;
          status();
          return;
        }
        resolving = resolve(meta.title, meta.year, found, () => {
          if (tok === routeTok) {
            st.cam++;
            status();
          }
        });
        resolving.done.then(() => {
          if (tok === routeTok) {
            st.done = true;
            status();
          }
        });
      };
      function rank(ei) {
        const need = ei + 1, maxEp = Math.max.apply(null, st.srcs.map((s) => s.eps.length));
        const score = srcScore;
        loadCrowd();
        return st.srcs.slice().sort((a, b) => {
          const ha = a.eps.length >= need ? 1 : 0, hb = b.eps.length >= need ? 1 : 0;
          if (ha !== hb) return hb - ha;
          if (kind === "tv" && maxEp > 1) {
            const fa = a.eps.length >= maxEp * 0.9 ? 1 : 0, fb = b.eps.length >= maxEp * 0.9 ? 1 : 0;
            if (fa !== fb && a.bps > 0 === b.bps > 0) return fb - fa;
          }
          return score(b) - score(a);
        });
      }
      if (APP && NN.prefetch) {
        const t0 = Date.now();
        const pre = () => {
          if (tok !== routeTok) return;
          const ok = st.srcs.filter((s2) => s2.bps > 0).length, waited = Date.now() - t0;
          if (!(ok >= 3 || ok >= 1 && waited > 6e3 || ok >= 1 && st.done && st.probing === 0 && !probeQ.length)) {
            if (waited < 3e4) setTimeout(pre, 500);
            return;
          }
          const h = hist(), ei = h ? h.ei : 0, s = rank(ei)[0];
          if (!s || !(s.bps > 0)) return;
          const e = s.eps[Math.min(ei, s.eps.length - 1)];
          if (e && e.url) NN.prefetch(e.url, h && h.ei === ei ? h.pos || 0 : 0);
        };
        setTimeout(pre, 1500);
      }
      function startPlay(ei, pos) {
        return __async(this, null, function* () {
          if (tok !== routeTok) return;
          openLayer(`<div class="panel picking"><div class="spin"></div><h2>\u6B63\u5728\u4E3A\u4F60\u6311\u9009\u6700\u5FEB\u7684\u7247\u6E90</h2><div class="st" id="pk">\u51C6\u5907\u4E2D\u2026</div><div class="acts" style="justify-content:center;margin-top:1.4rem"><div class="btn f" id="pkC">\u53D6\u6D88</div></div></div>`);
          let cancelled = false;
          $("#layer")._onclose = () => {
            cancelled = true;
          };
          $("#pkC")._ok = closeLayer;
          const t0 = Date.now();
          while (!cancelled) {
            const ok = st.srcs.filter((s) => s.bps > 0).length, el = $("#pk");
            if (el) el.textContent = `\u5DF2\u627E\u5230 ${st.srcs.length} \u4E2A\u7247\u6E90\uFF0C\u6D4B\u901F\u5B8C\u6210 ${st.probed} \u4E2A`;
            const waited = Date.now() - t0;
            if (ok >= 3 || ok >= 1 && waited > 2500 || ok >= 2 && waited > 1500 || st.done && st.probing === 0 && !probeQ.length || waited > 1e4) break;
            yield sleep(250);
          }
          if (cancelled || tok !== routeTok) return;
          if (!st.srcs.length) {
            closeLayer();
            toast("\u6CA1\u627E\u5230\u53EF\u64AD\u653E\u7684\u7247\u6E90");
            return;
          }
          const list = rank(ei);
          closeLayer();
          const data = {
            key,
            title: meta.title,
            pic: meta.pic || it.pic,
            id: it.id || "",
            type: kind,
            year: meta.year || "",
            si: 0,
            ei,
            pos: pos || 0,
            sources: list.map((s) => ({ site: s.site, eps: s.eps, bps: s.bps == null ? 0 : s.bps, h: s.h || 0, err: s.err || "" }))
          };
          if (APP) NN.play(JSON.stringify(data));
          else toast(`\u5C06\u64AD\u653E\u300C${list[0].site}\u300D${list[0].eps[Math.min(ei, list[0].eps.length - 1)].name}`);
        });
      }
      $("#bPlay")._ok = () => {
        const h = hist();
        startPlay(h ? h.ei : 0, h ? h.pos : 0);
      };
      $("#bIntro")._ok = () => openLayer(`<div class="panel"><h2>${esc(meta.title)}</h2><p>${esc(meta.intro || "\u6682\u65E0\u7B80\u4ECB")}</p><div class="acts" style="margin-top:1.6rem"><div class="btn f" id="iClose">\u5173\u95ED</div></div></div>`, "#iClose");
      {
        const o = $("#bIntro")._ok;
        $("#bIntro")._ok = () => {
          o();
          const c = $("#iClose");
          if (c) c._ok = closeLayer;
        };
      }
      $("#layer")._dummy = 0;
      window.__refreshDetail = () => {
        playLabel();
        epsShown = 0;
        showEps();
      };
      yield loadSources();
      if (tok !== routeTok) return;
      if (!it.id) {
        startResolve();
        return;
      }
      startResolve();
      try {
        const d = yield dbDetail(kind, it.id);
        if (tok !== routeTok) return;
        meta = Object.assign(meta, d, { pic: d.pic || it.pic });
        setBg(d.pic);
        $(".poster").style.backgroundImage = `url('${d.pic}')`;
        $(".dt h1").textContent = d.title;
        $(".orig").textContent = [d.original && d.original !== d.title ? d.original : "", d.year].filter(Boolean).join(" \xB7 ");
        $(".dt .meta").innerHTML = [
          d.rate ? `<span class="rate">\u8C46\u74E3 ${esc(d.rate)}</span>` : "",
          d.countries.length ? `<span>${esc(d.countries.slice(0, 3).join(" / "))}</span>` : "",
          d.genres.length ? `<span class="pill">${esc(d.genres.join(" \xB7 "))}</span>` : "",
          d.episodes ? `<span class="pill">${d.episodes} \u96C6</span>` : "",
          d.durations.length ? `<span class="pill">${esc(d.durations[0])}</span>` : ""
        ].join("");
        $(".crew").textContent = [d.directors.length ? "\u5BFC\u6F14\uFF1A" + d.directors.slice(0, 2).join(" / ") : "", d.actors.length ? "\u4E3B\u6F14\uFF1A" + d.actors.slice(0, 5).join(" / ") : ""].filter(Boolean).join("\u3000\u3000");
        $(".dt .intro").textContent = d.intro || "\u6682\u65E0\u7B80\u4ECB";
        if (d.cast.length) {
          $("#castRow").innerHTML = rowHTML("rowCast", "\u6F14\u804C\u5458", d.cast.map((c) => `<div class="person f"><div class="av"${c.avatar ? ` style="background-image:url('${esc(c.avatar)}')"` : ""}>${c.avatar ? "" : esc((c.name || "?")[0])}</div><b>${esc(c.name)}</b><small>${esc(c.role)}</small></div>`).join(""));
          $$("#rowCast .person").forEach((p, i) => {
            p._ok = () => go("search", d.cast[i].name);
          });
        }
        if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus);
      } catch (e) {
        $(".dt .intro").textContent = "\u8C46\u74E3\u8BE6\u60C5\u52A0\u8F7D\u5931\u8D25";
      }
    });
  },
  search(tok, q) {
    const KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".split("");
    if (MOB) $("#page").innerHTML = `<div class="scroll srch"><div class="sbox">${ICON_SEARCH}<input id="q" type="search" placeholder="\u7247\u540D\u3001\u6F14\u5458\uFF0C\u6216\u62FC\u97F3\u9996\u5B57\u6BCD\u5982 XCLF" enterkeyhint="search"></div>
      <div class="pagehead sec"><h2 id="sh">\u5927\u5BB6\u90FD\u5728\u770B</h2><span id="ss"></span></div><div id="sres"></div></div>`;
    else $("#page").innerHTML = `<div class="skb"><div class="sq" id="sq"><span id="sqt"></span><i>\u62FC\u97F3\u9996\u5B57\u6BCD\uFF0C\u5982 XCLF</i></div>
      <div class="keys" data-g="kb">${KEYS.map((k) => `<div class="key f" data-k="${k}">${k}</div>`).join("")}
      <div class="key f wide" data-k="clr">\u6E05\u7A7A</div><div class="key f wide" data-k="del">\u5220\u9664</div><div class="key f wide" data-k="ime">\u8F93\u5165\u6CD5</div></div>
      <input id="q" class="imeq" value=""></div>
      <div class="scroll srch" data-pad="6"><div class="pagehead sec"><h2 id="sh">\u5927\u5BB6\u90FD\u5728\u770B</h2><span id="ss"></span></div><div id="sres"></div></div>`;
    const input = $("#q");
    let text = q || "", timer = 0, my = 0;
    const show = () => {
      if (MOB) {
        if (input.value !== text) input.value = text;
        return;
      }
      $("#sqt").textContent = text;
      $("#sq").classList.toggle("has", !!text);
    };
    const isLetters = (v) => /^[a-z0-9]+$/i.test(v);
    const grid = (items) => `<div class="grid">${items.map((it) => card(it, { sub: it.sub, cls: "grid-item" })).join("")}</div>`;
    const cidType = (c) => c === 1 ? "movie" : "tv";
    const iqSuggest = (v) => __async(this, null, function* () {
      const d = yield getJSON(`https://suggest.video.iqiyi.com/?if=mobile&key=${encodeURIComponent(v)}`, null, 6e3);
      const TAGS = { "\u7535\u5F71": 1, "\u7535\u89C6\u5267": 2, "\u52A8\u6F2B": 4, "\u7EFC\u827A": 6 };
      const seen = {}, out = [];
      (d.data || []).forEach((x) => {
        if (!x.name) return;
        let cid = x.cid;
        if (!cid) {
          const t = ((x.presentation_element || {}).tags || []).map((g) => g.name).find((n) => TAGS[n]);
          if (!t) return;
          cid = TAGS[t];
        }
        if ([1, 2, 4, 6].indexOf(cid) < 0) return;
        const k = x.name.replace(/\s+/g, "");
        if (seen[k]) {
          const o = seen[k];
          if (!o.pic && x.picture_url) o.pic = x.picture_url.replace(/^http:/, "https:");
          return;
        }
        const cn = { 1: "\u7535\u5F71", 2: "\u7535\u89C6\u5267", 4: "\u52A8\u6F2B", 6: "\u7EFC\u827A" }[cid];
        out.push(seen[k] = { id: "", title: x.name, pic: (x.picture_url || "").replace(/^http:/, "https:"), year: x.year || "", type: cidType(cid), sub: [x.cname || cn, x.year].filter(Boolean).join(" \xB7 ") });
      });
      const items = out.slice(0, 18);
      const fill = items.filter((it) => !it.pic).slice(0, 8).map((it) => dbSearch(it.title).then((r) => {
        const hit = r.find((x) => x.title === it.title && (!it.year || !x.year || Math.abs(+x.year - +it.year) <= 1)) || r.find((x) => x.title === it.title);
        if (hit) Object.assign(it, { id: hit.id, type: hit.type, year: hit.year || it.year, pic: hit.pic || it.pic });
      }).catch(() => {
      }));
      if (fill.length) yield Promise.race([Promise.all(fill), sleep(3500)]);
      return items;
    });
    const bindIq = (items) => $$("#sres .card").forEach((c, i) => {
      const it = items[i];
      c._ok = () => __async(this, null, function* () {
        if (it.id) {
          openDetail(it);
          return;
        }
        let hit = null;
        try {
          const r = yield dbSearch(it.title);
          hit = r.find((x) => x.title === it.title && (!it.year || !x.year || Math.abs(+x.year - +it.year) <= 1)) || r.find((x) => x.title === it.title);
        } catch (e) {
        }
        openDetail(hit ? { id: hit.id, type: hit.type, title: hit.title, year: hit.year, pic: hit.pic || it.pic } : { id: "", type: it.type, title: it.title, year: it.year, pic: it.pic });
      });
    });
    const run = () => __async(this, null, function* () {
      const v = text.trim(), me = ++my;
      if (!v) {
        hot();
        return;
      }
      $("#sh").textContent = "\u641C\u7D22\u7ED3\u679C";
      $("#ss").textContent = "";
      $("#sres").innerHTML = `<div class="grid">${skel(5)}</div>`;
      try {
        let items = [], iq = false;
        if (isLetters(v)) {
          try {
            items = yield iqSuggest(v);
            iq = items.length > 0;
          } catch (e) {
          }
        }
        if (!items.length) items = (yield dbSearch(v)).map((it) => Object.assign(it, { sub: String(it.sub).split(" / ")[0] }));
        if (tok !== routeTok || me !== my) return;
        $("#ss").textContent = "";
        $("#sres").innerHTML = items.length ? grid(items) : `<div class="empty">\u6CA1\u641C\u5230\u300C${esc(v)}\u300D</div>`;
        bindCards($("#sres"));
        if (iq) bindIq(items);
      } catch (e) {
        if (me === my) $("#sres").innerHTML = `<div class="empty">\u641C\u7D22\u5931\u8D25\uFF0C\u7A0D\u540E\u518D\u8BD5</div>`;
      }
    });
    const hot = () => {
      $("#sh").textContent = "\u5927\u5BB6\u90FD\u5728\u770B";
      $("#ss").textContent = "";
      dbList("movie", "\u70ED\u95E8", "\u5168\u90E8", 0, 15).then((d) => {
        if (tok !== routeTok || text.trim()) return;
        $("#sres").innerHTML = grid(d.items.map((it) => Object.assign(it, { sub: short(it.sub).split(" \xB7 ")[0] })));
        bindCards($("#sres"));
      }).catch(() => {
      });
    };
    const later = () => {
      clearTimeout(timer);
      timer = setTimeout(run, 450);
    };
    $$(".skb .key").forEach((k) => {
      k._ok = () => {
        const c = k.dataset.k;
        if (c === "clr") text = "";
        else if (c === "del") text = text.slice(0, -1);
        else if (c === "ime") {
          input.value = text;
          input.focus();
          return;
        } else if (text.length < 20) text += c;
        show();
        later();
      };
    });
    if (!MOB) input.addEventListener("keydown", (e) => {
      if (e.keyCode === 13) {
        e.preventDefault();
        text = input.value.trim();
        input.blur();
        show();
        run();
        F.set($(".skb .key"));
      }
    });
    if (!MOB) input.addEventListener("blur", () => {
      if (input.value.trim() && input.value.trim() !== text) {
        text = input.value.trim();
        show();
        run();
      }
    });
    if (MOB) {
      input.addEventListener("input", () => {
        text = input.value.trim();
        later();
      });
      input.addEventListener("keydown", (e) => {
        if (e.keyCode === 13) {
          e.preventDefault();
          text = input.value.trim();
          input.blur();
          run();
        }
      });
      show();
      if (text) run();
      else {
        hot();
        setTimeout(() => input.focus(), 300);
      }
      return;
    }
    show();
    F.set($(".skb .key"));
    if (text) run();
    else hot();
  },
  history(tok) {
    const list = histAll();
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>\u89C2\u770B\u8BB0\u5F55</h2><span>${list.length ? MOB ? "\u957F\u6309\u5220\u9664" : "\u6309\u83DC\u5355\u952E\u5220\u9664\u5F53\u524D\u8FD9\u6761" : ""}</span></div>
      <div class="grid">${list.length ? list.map((h) => histCard(h, "grid-item")).join("") : `<div class="empty">\u8FD8\u6CA1\u6709\u770B\u8FC7\u7684\u7247\u5B50</div>`}</div></div>`;
    bindCards($("#page"));
    $$("#page .card").forEach((c, i) => {
      c._menu = () => {
        if (APP) NN.delHistory(list[i].key);
        toast("\u5DF2\u5220\u9664");
        render("history");
      };
    });
    F.set($("#page .card") || $("#tabs .tab.on"));
  },
  settings(tok) {
    return __async(this, null, function* () {
      yield loadSources();
      if (tok !== routeTok) return;
      const custom = cfg.get("srcUrl", "");
      let lan = null;
      try {
        lan = APP && NN.lan ? JSON.parse(NN.lan()) : null;
      } catch (e) {
      }
      $("#page").innerHTML = `${lan && lan.url ? `<div class="lan"><b>\u624B\u673A / \u7535\u8111\u626B\u7801\u8BBE\u7F6E</b><img src="${lan.qr}"><div class="u">${esc(lan.url)}</div><small>\u540C\u4E00\u5C40\u57DF\u7F51\u5185\u7528\u6D4F\u89C8\u5668\u6253\u5F00<br>\u53EF\u63A8\u9001\u5B89\u88C5/\u5347\u7EA7 App\u3001\u6539\u7247\u6E90\u3001\u63A8\u9001\u641C\u7D22</small></div>` : `<div class="lan"><b>\u5C40\u57DF\u7F51\u8BBE\u7F6E</b><small>\u6CA1\u8FDE\u4E0A\u5C40\u57DF\u7F51\uFF0C\u6682\u4E0D\u53EF\u7528</small></div>`}
      <div class="scroll setwrap" data-pad="6"><div class="pagehead sec"><h2>\u8BBE\u7F6E</h2><span>\u7248\u672C ${APP ? esc(NN.version()) : "dev"}</span></div>
      <div class="setrow f" id="sRefresh"><span>\u5237\u65B0\u7247\u6E90</span><small>${CMS_OFF ? "\u6D4B\u8BD5\u7248\u5DF2\u505C\u7528\u91C7\u96C6\u7AD9 \xB7 " : ""}${SRC.list.length} \u4E2A\u91C7\u96C6\u7AD9 \xB7 ${esc(SRC.from.replace(/^https?:\/\//, "").slice(0, 60))}</small></div>
      <div class="setrow f" id="sUrl"><span>\u7247\u6E90\u5730\u5740</span><small>${esc(custom || "\u9ED8\u8BA4\uFF1Azknjjjx/tvbox \u7684 cmstv.json\uFF08\u81EA\u52A8\u52A0\u901F\uFF09")}</small></div>
      <div class="setrow f" id="sReset"><span>\u6062\u590D\u9ED8\u8BA4\u7247\u6E90</span><small>\u6E05\u6389\u81EA\u5B9A\u4E49\u5730\u5740</small></div>
      <div class="setrow f" id="sBox"><span>TVBox \u63A5\u53E3</span><small id="sBoxS">${esc(boxLabel())}</small></div>
      <div class="setrow f" id="sUpd"><span>\u68C0\u67E5\u66F4\u65B0</span><small id="sUpdS">\u5F53\u524D ${APP ? esc(NN.version()) : "dev"}${UPD.latest ? hasNew() ? ` \xB7 \u6709\u65B0\u7248 ${esc(UPD.latest.version)}\uFF0C\u6309 OK \u5347\u7EA7` : " \xB7 \u5DF2\u662F\u6700\u65B0" : ""}</small></div>
      <div class="setrow f" id="sApk"><span>\u5B89\u88C5\u5305\u6B8B\u7559</span><small id="sApkS">${(() => {
        let a = { n: 0 };
        try {
          a = APP && NN.apkInfo ? JSON.parse(NN.apkInfo()) : a;
        } catch (e) {
        }
        return a.n ? `${a.n} \u4E2A \xB7 ${a.mb} MB\uFF0C\u6309 OK \u5220\u9664` : "\u6CA1\u6709\u6B8B\u7559\uFF0C\u5DF2\u5168\u90E8\u5220\u9664";
      })()}</small></div>
      <div class="setrow f" id="sClear"><span>\u6E05\u9664\u89C2\u770B\u8BB0\u5F55</span><small>${histAll().length} \u6761</small></div>
      <div class="setrow info"><span>\u64AD\u653E\u8BF4\u660E</span><small>${MOB ? "\u81EA\u52A8\u6D4B\u901F\u6311\u6700\u5FEB\u6E90\uFF1B\u7247\u6E90\u6CA1\u54CD\u5E94\u6216\u5361\u4F4F\u51E0\u79D2\u6CA1\u6570\u636E\u5C31\u81EA\u52A8\u6362\u6E90\uFF1B\u64AD\u653E\u65F6\u70B9\u5C4F\u5E55\u51FA\u63A7\u5236\u680F\uFF0C\u5DE6\u53F3\u6ED1\u52A8\u5FEB\u8FDB\u5FEB\u9000\uFF0C\u53CC\u51FB\u6682\u505C" : "\u81EA\u52A8\u6D4B\u901F\u6311\u6700\u5FEB\u6E90\uFF1B\u7247\u6E90\u6CA1\u54CD\u5E94\u6216\u5361\u4F4F\u51E0\u79D2\u6CA1\u6570\u636E\u5C31\u81EA\u52A8\u6362\u6E90\uFF1B\u64AD\u653E\u4E2D\u6309 \u25B2 \u9009\u96C6\u6362\u6E90"}</small></div></div>`;
      $("#sRefresh")._ok = () => __async(this, null, function* () {
        toast("\u6B63\u5728\u5237\u65B0\u7247\u6E90\u2026");
        yield loadSources(true);
        toast(`\u7247\u6E90\u5DF2\u66F4\u65B0\uFF1A${SRC.list.length} \u4E2A`);
        render("settings");
      });
      $("#sUrl")._ok = () => {
        openLayer(`<div class="panel"><h2>\u7247\u6E90\u5730\u5740</h2><p style="opacity:.7">\u652F\u6301 cmstv.json \u6216 TVBox \u914D\u7F6E\uFF08\u53EA\u7528\u91CC\u9762\u7684\u91C7\u96C6\u7AD9\uFF09\u3002GitHub \u5730\u5740\u4F1A\u81EA\u52A8\u8D70\u52A0\u901F\u3002</p>
        <div class="sbox f" style="margin:1.2rem 0 0;max-width:none" id="uBox"><input id="uIn" value="${esc(custom || DEF_SRC)}"></div>
        <div class="acts" style="margin-top:1.6rem"><div class="btn pri f" id="uOk">\u4FDD\u5B58</div><div class="btn f" id="uNo">\u53D6\u6D88</div></div></div>`, "#uBox");
        $("#uBox")._ok = () => $("#uIn").focus();
        $("#uNo")._ok = closeLayer;
        $("#uOk")._ok = () => __async(this, null, function* () {
          const v = $("#uIn").value.trim();
          cfg.set("srcUrl", v === DEF_SRC ? "" : v);
          closeLayer();
          toast("\u6B63\u5728\u52A0\u8F7D\u65B0\u7247\u6E90\u2026");
          yield loadSources(true);
          toast(`\u7247\u6E90\uFF1A${SRC.list.length} \u4E2A`);
          render("settings");
        });
      };
      $("#sBox")._ok = () => {
        const sel = cfg.get("boxSel", BOX_DEF), opts = BOX_PRESETS.map((p) => [p.name, p.name]).concat([["custom", "\u81EA\u5B9A\u4E49\u63A5\u53E3"], ["off", "\u4E0D\u7528"]]);
        openLayer(`<div class="panel"><h2>TVBox \u63A5\u53E3</h2><p style="opacity:.7">\u4E00\u6B21\u7528\u4E00\u4E2A\u63A5\u53E3\uFF0C\u9009\u597D\u5C31\u884C\u3002\u91CC\u9762\u7684\u722C\u866B\u6E90\u548C\u91C7\u96C6\u7AD9\u4E00\u8D77\u641C\u7D22\u3001\u6D4B\u901F\u3001\u81EA\u52A8\u6311\u6700\u5FEB\u7684\uFF1B\u5B98\u6E90\u7528\u63A5\u53E3\u81EA\u5E26\u7684\u89E3\u6790\u3002\u7F51\u76D8\u3001\u626B\u7801\u3001\u76F4\u64AD\u7C7B\u4E0D\u7528\u3002</p>
        <div class="acts boxopts" style="flex-wrap:wrap;margin-top:1.2rem">${opts.map((o) => `<div class="btn f${o[0] === sel ? " pri" : ""}" data-o="${esc(o[0])}">${esc(o[1])}</div>`).join("")}</div></div>`, `.boxopts .btn[data-o="${sel}"]`);
        const apply = (v) => __async(this, null, function* () {
          cfg.set("boxSel", v);
          closeLayer();
          toast(v === "off" ? "\u5DF2\u505C\u7528\u722C\u866B\u6E90" : "\u6B63\u5728\u52A0\u8F7D\u63A5\u53E3\u2026");
          yield boxLoad(true);
          toast(v === "off" ? "\u5DF2\u505C\u7528\u722C\u866B\u6E90" : `TVBox \u63A5\u53E3\uFF1A${BOX.state}`);
          render("settings");
        });
        $$(".boxopts .btn").forEach((b) => {
          b._ok = () => {
            if (b.dataset.o !== "custom") {
              apply(b.dataset.o);
              return;
            }
            openLayer(`<div class="panel"><h2>\u81EA\u5B9A\u4E49 TVBox \u63A5\u53E3</h2><p style="opacity:.7">\u586B TVBox \u914D\u7F6E\u5730\u5740\uFF08\u5982\u8001\u5218\u5907\u3001\u738B\u4E8C\u5C0F\u3001\u80A5\u732B\uFF09\uFF0C\u652F\u6301\u591A\u4ED3\u3001\u52A0\u5BC6\u548C\u56FE\u7247\u4F2A\u88C5\u3002</p>
          <div class="sbox f" style="margin:1.2rem 0 0;max-width:none" id="bBox"><input id="bIn" value="${esc(cfg.get("boxUrl", ""))}"></div>
          <div class="acts" style="margin-top:1.6rem"><div class="btn pri f" id="bOk">\u4FDD\u5B58</div><div class="btn f" id="bNo">\u53D6\u6D88</div></div></div>`, "#bBox");
            $("#bBox")._ok = () => $("#bIn").focus();
            $("#bNo")._ok = closeLayer;
            $("#bOk")._ok = () => {
              const u = $("#bIn").value.trim();
              if (!u) {
                toast("\u5730\u5740\u4E0D\u80FD\u4E3A\u7A7A");
                return;
              }
              cfg.set("boxUrl", u);
              apply("custom");
            };
          };
        });
      };
      $("#sReset")._ok = () => __async(this, null, function* () {
        cfg.set("srcUrl", "");
        yield loadSources(true);
        toast("\u5DF2\u6062\u590D\u9ED8\u8BA4\u7247\u6E90");
        render("settings");
      });
      $("#sUpd")._ok = () => {
        if (hasNew()) {
          askUpgrade();
          return;
        }
        $("#sUpdS").textContent = "\u6B63\u5728\u68C0\u67E5\u2026";
        checkUpdate().then(() => {
          markNew();
          if (hasNew()) askUpgrade();
          else {
            $("#sUpdS").textContent = `\u5F53\u524D ${NN.version()} \xB7 \u5DF2\u662F\u6700\u65B0`;
            toast("\u5DF2\u662F\u6700\u65B0\u7248\u672C");
          }
        }).catch(() => {
          $("#sUpdS").textContent = "\u68C0\u67E5\u5931\u8D25\uFF0C\u8FDE\u4E0D\u4E0A GitHub";
        });
      };
      markNew();
      $("#sApk")._ok = () => {
        if (APP && NN.apkClean) NN.apkClean();
        $("#sApkS").textContent = "\u6CA1\u6709\u6B8B\u7559\uFF0C\u5DF2\u5168\u90E8\u5220\u9664";
        toast("\u5B89\u88C5\u5305\u5DF2\u5220\u9664");
      };
      $("#sClear")._ok = () => {
        histAll().forEach((h) => APP && NN.delHistory(h.key));
        toast("\u5DF2\u6E05\u9664");
        render("settings");
      };
      F.set($("#sRefresh"));
    });
  }
};
let resolving = null;
function boxLabel() {
  const sel = cfg.get("boxSel", BOX_DEF);
  if (sel === "off") return "\u4E0D\u7528\u722C\u866B\u6E90";
  let nm = sel === "custom" ? cfg.get("boxUrl", "\u81EA\u5B9A\u4E49").replace(/^https?:\/\//, "").slice(0, 40) : sel;
  if (BOX.using && BOX.using !== (sel === "custom" ? "\u81EA\u5B9A\u4E49" : sel)) nm += ` \u6253\u4E0D\u5F00\uFF0C\u6682\u7528 ${BOX.using}`;
  return `${nm} \xB7 ${BOX.state || "\u672A\u52A0\u8F7D"}${BOX.sites.length ? "\uFF08" + ["jar", "js", "py"].map((k) => [k, BOX.sites.filter((s) => s.kind === k).length]).filter((x) => x[1]).map((x) => `${x[0] === "jar" ? "Java" : x[0] === "js" ? "JS" : "Python"} ${x[1]}`).join("\u3001") + "\uFF09" : ""}`;
}
const REL = "https://github.com/zknjjjx/NiuniuTV/releases/download/v2-latest/";
const UPD_JSON = MOB ? "latest-m.json" : "latest.json";
const relMirrors = (f) => [`https://gh-proxy.com/${REL}${f}`, `https://9797.cc.cd/${REL}${f}`, `https://ghfast.top/${REL}${f}`, REL + f];
const verNum = (v) => String(v || "0").split(".").reduce((a, x) => a * 1e3 + (parseInt(x, 10) || 0), 0);
const UPD = { latest: null, checking: null };
function checkUpdate() {
  if (UPD.checking) return UPD.checking;
  UPD.checking = anyOf(relMirrors(UPD_JSON + "?t=" + Date.now()).map((u) => getJSON(u, null, 12e3).then((j) => {
    if (!j || !j.version || !j.apk) throw new Error("bad");
    return j;
  }))).then((j) => {
    UPD.latest = j;
    UPD.checking = null;
    return j;
  }, (e) => {
    UPD.checking = null;
    throw e;
  });
  return UPD.checking;
}
const hasNew = () => APP && UPD.latest && verNum(UPD.latest.version) > verNum(NN.version());
function askUpgrade() {
  const j = UPD.latest;
  openLayer(`<div class="panel"><h2>\u53D1\u73B0\u65B0\u7248\u672C ${esc(j.version)}</h2><p style="opacity:.75">\u5F53\u524D ${esc(NN.version())}${j.notes ? "<br>" + esc(j.notes) : ""}</p>
    <p id="upMsg" style="opacity:.6;min-height:1.6em"></p>
    <div class="acts" style="margin-top:1.2rem"><div class="btn pri f" id="upOk">\u7ACB\u5373\u5347\u7EA7</div><div class="btn f" id="upNo">\u4EE5\u540E\u518D\u8BF4</div></div></div>`, "#upOk");
  $("#upNo")._ok = closeLayer;
  $("#upOk")._ok = () => {
    if (UPD.busy) return;
    UPD.busy = true;
    $("#upMsg").textContent = "\u6B63\u5728\u4E0B\u8F7D\u2026";
    NN.upgrade(JSON.stringify(relMirrors(j.apk)));
  };
}
window.nnUpg = (x) => {
  const m = $("#upMsg");
  if (typeof x === "number") {
    if (m) m.textContent = x >= 100 ? "\u4E0B\u8F7D\u5B8C\u6210\uFF0C\u8BF7\u5728\u5F39\u51FA\u7684\u754C\u9762\u70B9\u201C\u5B89\u88C5\u201D" : `\u6B63\u5728\u4E0B\u8F7D ${x}%`;
    if (x >= 100) UPD.busy = false;
  } else if (/^正在/.test(x)) {
    if (m) m.textContent = x;
  } else {
    UPD.busy = false;
    if (m) m.textContent = "\u5347\u7EA7\u5931\u8D25\uFF1A" + x;
    else toast("\u5347\u7EA7\u5931\u8D25\uFF1A" + x);
  }
};
function markNew() {
  const t = $('#tabs .tab[data-k="settings"]'), on = hasNew();
  if (t) t.classList.toggle("dot", on);
  const r = $("#sUpd");
  if (r) r.classList.toggle("dot", on);
}
function autoUpdate() {
  if (!APP) return;
  setTimeout(() => checkUpdate().then(markNew).catch(() => {
  }), 8e3);
}
document.addEventListener("keydown", (e) => {
  const k = e.keyCode;
  const inInput = document.activeElement && document.activeElement.tagName === "INPUT";
  if (inInput && (k === 37 || k === 39)) return;
  if (k === 37 || k === 38 || k === 39 || k === 40) {
    e.preventDefault();
    const now = Date.now();
    if (e.repeat && now - (F.lastKey || 0) < 110) return;
    F.lastKey = now;
    if (inInput) document.activeElement.blur();
    const dir = { 37: "left", 38: "up", 39: "right", 40: "down" }[k];
    const before = F.cur;
    F.user = true;
    const ring = (dir === "left" || dir === "right") && before && (before.closest("#tabs") ? $$("#tabs .tab") : before.classList.contains("chip") && before.closest("#page .chips") ? $$(".chip", before.closest(".chips")) : null);
    const ri = ring ? ring.indexOf(before) : -1;
    if (ri >= 0 && ring.length > 1 && (dir === "right" ? ri === ring.length - 1 : ri === 0)) {
      F.set(ring[dir === "right" ? 0 : ring.length - 1]);
    } else if (dir === "up" && before && before.classList.contains("chip") && before.closest("#page")) {
      F.set($("#tabs .tab.on") || $("#tabs .tab"), true);
    } else if (dir === "down" && before && before.classList.contains("chip") && before.closest("#page") && $("#grid .card.f")) {
      clearTimeout(before._t);
      F.set($("#grid .card.f"));
    } else if (dir === "down" && before && before.closest("#tabs")) {
      const tgt = $("#page .chip.on") || $("#page .card.f") || $("#page .f");
      if (tgt && F.visible(tgt)) {
        F.hold = null;
        F.set(tgt);
      } else F.move(dir);
    } else F.move(dir);
    F.user = false;
    if (dir === "up" && F.cur === before && !$("#layer").classList.contains("on") && !(before && before.closest("#tabs"))) F.set($("#tabs .tab.on") || $("#tabs .tab"));
  } else if (k === 13 || k === 23 || k === 66) {
    if (inInput) return;
    e.preventDefault();
    if (F.cur && F.cur._ok) F.cur._ok();
  } else if (k === 82 || k === 93) {
    e.preventDefault();
    window.nnMenu();
  }
});
window.nnMenu = () => {
  if (F.cur && F.cur._menu) F.cur._menu();
};
let splashOver = false, splashQ = [];
function afterSplash(f) {
  if (splashOver) f();
  else splashQ.push(f);
}
function splash() {
  const sp = $("#splash"), body = document.body;
  const end = () => {
    if (!splashOver) {
      splashOver = true;
      setTimeout(() => {
        splashQ.splice(0).forEach((f) => {
          try {
            f();
          } catch (e) {
          }
        });
      }, 60);
    }
    document.documentElement.classList.remove("splash0");
    if (sp && sp.parentNode) sp.parentNode.removeChild(sp);
    body.classList.remove("booting", "reveal");
    try {
      if (APP && NN.splashDone) NN.splashDone();
    } catch (e) {
    }
  };
  if (!sp || MOB) {
    end();
    window.nnSplashGo = () => {
    };
    return;
  }
  const t0 = Date.now();
  let gone = false;
  const go2 = () => {
    if (gone) return;
    gone = true;
    const dog = sp.querySelector(".dog"), logo = $(".brand img");
    const a = dog.getBoundingClientRect(), b = logo.getBoundingClientRect();
    if (!a.width || !b.width) {
      end();
      return;
    }
    const s = b.width / a.width, dx = b.left + b.width / 2 - (a.left + a.width / 2), dy = b.top + b.height / 2 - (a.top + a.height / 2);
    const tf = `translate3d(${dx}px,${dy}px,0) scale(${s})`;
    dog.style.webkitTransform = tf;
    dog.style.transform = tf;
    sp.classList.add("go");
    body.classList.add("reveal");
    setTimeout(end, 1150);
  };
  window.nnSplashGo = (pics) => {
    const tw = Date.now();
    let left = (pics || []).length;
    const fire = () => setTimeout(go2, Math.max(0, 700 - (Date.now() - t0)));
    if (!left) {
      fire();
      return;
    }
    let done = false;
    const one = () => {
      if (!done && --left <= 0) {
        done = true;
        fire();
      }
    };
    pics.forEach((u) => {
      const im = new Image();
      im.onload = im.onerror = one;
      im.src = u;
    });
    setTimeout(() => {
      if (!done) {
        done = true;
        fire();
      }
    }, 1200);
  };
  setTimeout(go2, 3e3);
}
function clock() {
  const d = /* @__PURE__ */ new Date();
  $("#clock").textContent = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
function boot() {
  if (MOB) document.body.classList.add("mob");
  splash();
  fit();
  if (cfg.get("fx", "1") === "0") document.body.classList.add("nofx");
  window.addEventListener("resize", fit);
  $("#tabs").innerHTML = TABS.map((t) => `<div class="tab f" data-k="${t[0]}">${t[1]}</div>`).join("");
  $$("#tabs .tab").forEach((t) => {
    const open = (hold) => {
      const k = t.dataset.k, name = k in CHIPS ? "cat" : k, arg = k in CHIPS ? k : null;
      if (hold && route && route.name === name && route.arg === arg && !stack.length) return;
      stack.length = 0;
      F.hold = hold ? t : null;
      route = { name, arg };
      render(name, arg);
    };
    t._ok = () => {
      const k = t.dataset.k;
      if (MOB || !route || route.name !== (k in CHIPS ? "cat" : k) || stack.length) {
        open(false);
        return;
      }
      F.hold = null;
      const c = $("#page .f.focus") || $("#page .card.f") || $("#page .f");
      if (route && route.name !== "search" && c) F.set(c);
      else open(false);
    };
    t._onfocus = () => {
      if (!(F.prev && F.prev.closest("#tabs"))) return;
      clearTimeout(open._t);
      open._t = setTimeout(() => {
        if (F.cur === t) open(true);
      }, 280);
    };
  });
  clock();
  setInterval(clock, 2e4);
  if (MOB) {
    fit();
    const pg = $("#page");
    pg.addEventListener("scroll", () => {
      if (pg._more && pg.scrollTop + pg.clientHeight > pg.scrollHeight - pg.clientHeight * 1.5) pg._more();
    });
    document.addEventListener("click", (e) => {
      const l = $("#layer");
      if (e.target === l) {
        closeLayer();
        return;
      }
      const el = e.target.closest ? e.target.closest(".f") : null;
      if (!el || el.tagName === "INPUT" || el.closest(".sbox")) return;
      if (l.classList.contains("on") && !el.closest("#layer")) return;
      F.cur = el;
      if (el._ok) el._ok();
    });
    document.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      const el = e.target.closest ? e.target.closest(".f") : null;
      if (el && el._menu) el._menu();
    });
  }
  loadSources();
  boxLoad();
  route = { name: "home" };
  autoUpdate();
  bootFocus = true;
  render("home");
}
let bootFocus = false;
if (!String.prototype.padStart) String.prototype.padStart = function(n, c) {
  let s = String(this);
  while (s.length < n) s = c + s;
  return s;
};
if (!Array.prototype.find) Array.prototype.find = function(f) {
  for (let i = 0; i < this.length; i++) if (f(this[i], i)) return this[i];
};
if (!Element.prototype.closest) Element.prototype.closest = function(s) {
  let el = this;
  while (el && el.nodeType === 1) {
    if (el.matches ? el.matches(s) : el.webkitMatchesSelector(s)) return el;
    el = el.parentElement;
  }
  return null;
};
boot();
