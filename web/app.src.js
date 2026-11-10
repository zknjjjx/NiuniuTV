/* 妞妞影视 · 电视版界面（遥控器操作）
 * 数据：豆瓣（海报/简介/演员）+ 采集站（片源）。原生层 NN 负责跨域请求、测速、播放。 */
"use strict";
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => Array.prototype.slice.call(el.querySelectorAll(s));
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const APP = typeof window.NN !== "undefined";
// 手机版：同一套界面，改成触屏操作、原生滚动
const MOB = APP ? !!(NN.phone && NN.phone()) : /[?&]m=1/.test(location.search);

/* ---------------- 基础：请求 / 测速 ---------------- */
let seq = 0;
const cbs = {};
window.__nnCb = (id, code, body, ms) => { const c = cbs[id]; if (c) { delete cbs[id]; c({ code, body, ms }); } };
function req(url, headers, timeout) {
  timeout = timeout || 10000;
  if (APP) return new Promise(r => { const id = "r" + (++seq); cbs[id] = r; NN.fetch(id, url, headers ? JSON.stringify(headers) : "", timeout); });
  const t0 = Date.now();  // 电脑浏览器调试用
  return fetch(url).then(r => r.text().then(b => ({ code: r.status, body: b, ms: Date.now() - t0 }))).catch(() => ({ code: 0, body: "", ms: 0 }));
}
async function getJSON(url, headers, timeout) {
  const r = await req(url, headers, timeout);
  if (r.code < 200 || r.code >= 300) throw new Error("http " + r.code);
  const d = JSON.parse(r.body.replace(/^\uFEFF/, ""));
  d.__ms = r.ms;
  return d;
}
// 自动选源：分辨率优先（1080 以上 > 720/未知 > 更低），同档按速度；速度不够流畅的降一档
const MB = 1048576;
function srcTier(s) {
  const h = s.h || 0, b = s.bps || 0;
  if (b < 0) return 0;
  if (b === 0) return 1;                 // 未测
  let r = h >= 1000 ? 3 : (h >= 700 || h === 0) ? 2 : 1;
  // 有 5 路预下载，1080 单路 0.2 MB/s 就排在 720 前；嫌连接多的域名只能单路，要 0.9 才行，否则排到 720 后
  if (r === 3 && b < (isCrowd(s) ? 0.9 : 0.2) * MB) r = 1;
  if (r === 2 && b < 0.15 * MB) r = 1;    // 720 有 5 路帮拉，单路 0.15 就够
  return r + 1;                          // 4=1080 3=720流畅 2=其余能通
}
let CROWD = {};
function loadCrowd() { try { const o = JSON.parse((APP && NN.cfg && NN.cfg("crowd")) || "{}"), t = Date.now(); CROWD = {}; Object.keys(o).forEach(k => { if (t - o[k] < 7 * 864e5) CROWD[k] = 1; }); } catch (e) { CROWD = {}; } }
function isCrowd(s) { const m = /^https?:\/\/([^\/:?#]+)/i.exec((s.eps && s.eps[0] && s.eps[0].url) || ""); return !!(m && CROWD[m[1].toLowerCase()]); }
function srcScore(s) { return srcTier(s) * 1e10 + (s.bps > 0 ? s.bps : s.bps == null ? -((s.ms || 0) + (s.rank || 0) * 30) : 0); }
function probe(url) {
  if (!APP) return Promise.resolve({ bps: 0 });
  return new Promise(r => { const id = "p" + (++seq); cbs[id] = x => { try { r(JSON.parse(x.body)); } catch (e) { r({ bps: -1 }); } }; NN.probe(id, url, 8000, 524288); });
}
const store = {
  get(k, d) { try { const v = localStorage.getItem("nn:" + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem("nn:" + k, JSON.stringify(v)); } catch (e) {} },
};
const cfg = {
  get(k, d) { if (APP && NN.cfg) { const v = NN.cfg(k); return v === "" || v == null ? d : v; } return store.get(k, d); },
  set(k, v) { if (APP && NN.setCfg) NN.setCfg(k, v || ""); else store.set(k, v); },
};
function toast(msg) { const t = $("#toast"); t.textContent = msg; t.classList.add("on"); clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.remove("on"), 2600); }

/* ---------------- 豆瓣 ---------------- */
const DB = "https://m.douban.com/rexxar/api/v2/";
const DBH = { "Referer": "https://m.douban.com/", "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148" };
const memo = {};
function cached(key, ttl, fn) {
  const c = memo[key];
  if (c && Date.now() - c.t < ttl) return c.p;
  const p = fn();
  memo[key] = { t: Date.now(), p };
  p.catch(() => { delete memo[key]; });
  return p;
}
const dbGet = path => cached("db:" + path, 30 * 60e3, () => getJSON(DB + path, DBH, 10000));
const picOf = i => { const p = i.pic || {}; return p.normal || p.large || i.cover_url || (i.cover && i.cover.url) || ""; };
const dbItem = i => ({ id: i.id, title: i.title, pic: picOf(i), rate: (i.rating && i.rating.value) || "", sub: i.card_subtitle || "", ep: i.episodes_info || "", type: i.type === "tv" ? "tv" : "movie", year: i.year || "" });
async function dbList(kind, cat, type, start, count) {
  const path = cat === "rec" ? `${kind}/recommend?start=${start}&count=${count}&tags=${encodeURIComponent(type)}`
    : `subject/recent_hot/${kind}?category=${encodeURIComponent(cat)}&type=${encodeURIComponent(type)}&start=${start}&limit=${count}`;
  const d = await dbGet(path);
  return { items: (d.items || []).filter(i => (i.type === "movie" || i.type === "tv") && i.id).map(dbItem), total: d.total || 0 };
}
async function dbDetail(kind, id) {
  const [d, c] = await Promise.all([dbGet(`${kind}/${id}`), dbGet(`${kind}/${id}/credits`).catch(() => ({}))]);
  let cast = [];
  (c.items || []).forEach(x => cast.push({ name: x.name, role: x.simple_character || x.character || "", avatar: (x.avatar && (x.avatar.normal || x.avatar.large)) || "" }));
  if (!cast.length) cast = (d.directors || []).map(a => ({ name: a.name, role: "导演", avatar: "" })).concat((d.actors || []).map(a => ({ name: a.name, role: "演员", avatar: "" })));
  return {
    id: String(id), type: kind, title: d.title, original: d.original_title || "", year: d.year || "",
    pic: (d.pic && (d.pic.large || d.pic.normal)) || "", rate: (d.rating && d.rating.value) || "",
    genres: d.genres || [], countries: d.countries || [], durations: d.durations || [], episodes: d.episodes_count || 0,
    intro: (d.intro || "").trim(), sub: d.card_subtitle || "", cast: cast.slice(0, 24),
    directors: (d.directors || []).map(x => x.name), actors: (d.actors || []).map(x => x.name),
  };
}
async function dbRecs(kind, id) {
  const d = await dbGet(`${kind}/${id}/recommendations?count=16`).catch(() => []);
  return (Array.isArray(d) ? d : []).filter(i => i.type === "movie" || i.type === "tv").map(dbItem);
}
async function dbSearch(q) {
  const d = await dbGet(`search/movie?q=${encodeURIComponent(q)}&count=30&start=0`);
  return (d.items || []).filter(x => (x.target_type === "movie" || x.target_type === "tv") && x.target && x.target.id)
    .map(x => { const t = x.target; return { id: t.id, title: t.title, pic: t.cover_url || "", rate: (t.rating && t.rating.value) || "", sub: t.card_subtitle || t.year || "", type: x.target_type, year: t.year || "" }; });
}
const short = s => (s || "").split(" / ").slice(0, 3).join(" · ");

/* ---------------- 片源：默认用 zknjjjx/tvbox 的 cmstv.json，多条加速线路谁快用谁 ---------------- */
const DEF_SRC = "https://raw.githubusercontent.com/zknjjjx/tvbox/main/cmstv.json";
function mirrors(url) {
  const m = url.match(/^https:\/\/(?:raw\.githubusercontent\.com\/([^/]+)\/([^/]+)\/([^/]+)\/(.+)|cdn\.jsdelivr\.net\/gh\/([^/@]+)\/([^/@]+)@([^/]+)\/(.+))$/);
  if (!m) return [url];
  const [u, r, b, p] = m[1] ? [m[1], m[2], m[3], m[4]] : [m[5], m[6], m[7], m[8]];
  const raw = `https://raw.githubusercontent.com/${u}/${r}/${b}/${p}`;
  return [`https://ghfast.top/${raw}`, `https://cdn.jsdelivr.net/gh/${u}/${r}@${b}/${p}`, `https://fastly.jsdelivr.net/gh/${u}/${r}@${b}/${p}`,
    `https://testingcf.jsdelivr.net/gh/${u}/${r}@${b}/${p}`, `https://gh-proxy.com/${raw}`, raw];
}
function anyOf(ps) {
  return new Promise((res, rej) => { let n = ps.length; if (!n) rej(new Error("none")); ps.forEach(p => p.then(res, () => { if (--n === 0) rej(new Error("all failed")); })); });
}
const BAD = /成人|福利|伦理|色|情色|AV|av|91|里番|写真|直播/;
function parseSites(d) {
  const arr = Array.isArray(d) ? d : (d.sites || d.list || []);
  const out = [], seen = {};
  arr.forEach(s => {
    if (!s || typeof s.api !== "string" || !/^https?:\/\//.test(s.api)) return;
    if (s.type !== undefined && s.type !== 1) return;
    if (s.adult || BAD.test(s.name || "")) return;
    const api = s.api.replace(/\/+$/, "");
    if (seen[api]) return; seen[api] = 1;
    out.push({ name: String(s.name || api).replace(/┃.*$/, "").trim(), api });
  });
  return out;
}
const SRC = { list: [], from: "", t: 0 };
async function loadSources(force) {
  const custom = cfg.get("srcUrl", "") || DEF_SRC;
  const cache = store.get("src", null);
  if (!force && cache && cache.url === custom && cache.list.length && Date.now() - cache.t < 12 * 3600e3) { Object.assign(SRC, cache); return SRC; }
  try {
    const urls = mirrors(custom);
    const d = await anyOf(urls.map(u => getJSON(u, null, 12000).then(j => { const l = parseSites(j); if (!l.length) throw new Error("empty"); return { l, u }; })));
    Object.assign(SRC, { list: d.l, from: d.u, t: Date.now(), url: custom });
    store.set("src", SRC);
  } catch (e) {
    if (cache && cache.list.length) Object.assign(SRC, cache);
    else {  // 网络都不通时，用安装包里自带的快照
      try {
        const txt = await new Promise((res, rej) => { const x = new XMLHttpRequest(); x.open("GET", "sources.json"); x.onload = () => res(x.responseText); x.onerror = rej; x.send(); });
        Object.assign(SRC, { list: parseSites(JSON.parse(txt)), from: "内置快照", t: 0, url: custom });
      } catch (e2) {}
    }
  }
  try { if (APP && NN.setCfg) NN.setCfg("srcState", JSON.stringify({ n: SRC.list.length, from: SRC.from })); } catch (e) {}
  return SRC;
}

/* 片名规范化，跨站匹配 */
const CN = { "一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10 };
function canon(t) {
  t = String(t || "").replace(/[\s·:：,，.。!！?？'"“”‘’()（）\[\]【】\-_—]/g, "").toLowerCase();
  t = t.replace(/第([一二三四五六七八九十])季/g, (_, c) => `第${CN[c]}季`).replace(/season(\d+)/g, "第$1季");
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
      if (item.indexOf("$") >= 0) { name = item.slice(0, item.indexOf("$")); link = item.slice(item.indexOf("$") + 1); } else { name = `第${idx + 1}集`; link = item; }
      link = link.trim();
      if (/^https?:\/\//.test(link)) eps.push({ name: name.trim() || `第${idx + 1}集`, url: link });
    });
    if (eps.length) groups.push({ from: froms[i] || `线路${i + 1}`, eps, m3u8: eps.some(e => e.url.indexOf(".m3u8") >= 0) });
  });
  return groups.filter(g => g.m3u8);
}
/* 枪版（TC/TS/CAM、抢先版）：影院偷拍，画质差，直接不要 */
const CAM = /枪版|槍版|抢先|搶先|抢鲜|尝鲜版|偷拍|(^|[^a-z])(hd)?(tc|ts|cam|hdcam|telesync|telecine)([^a-z]|$)/i;
// 只看电影（≤3 集）：剧集、综艺的「抢先版」是会员提前看，画质正常
const isCam = (v, eps) => eps.length <= 3 && ([v.vod_remarks, v.vod_version, v.vod_play_note].some(x => x && CAM.test(String(x))) || eps.some(e => CAM.test(e.name)));
/* 在所有采集站里按片名找片源，找到一个回调一个 */
function resolve(name, year, onFound, onCam) {
  const want = canon(name);
  let alive = true, idx = 0, running = 0;
  const sites = SRC.list.slice();
  return {
    cancel() { alive = false; },
    done: new Promise(done => {
      if (!sites.length) return done();
      const next = () => {
        if (!alive) return done();
        if (idx >= sites.length && running === 0) return done();
        while (running < 20 && idx < sites.length) {
          const s = sites[idx], rank = idx++;
          running++;
          const t0 = Date.now();
          getJSON(`${s.api}/?ac=videolist&wd=${encodeURIComponent(name)}`, null, 6000).then(d => {
            if (!alive) return;
            for (const v of d.list || []) {
              if (canon(v.vod_name) !== want) continue;
              const vy = String(v.vod_year || "");
              if (year && /^\d{4}$/.test(vy) && Math.abs(+vy - +year) > 1) continue;
              const g = parseEpisodes(v);
              if (!g.length) continue;
              if (isCam(v, g[0].eps)) { if (onCam) onCam(s.name); continue; }
              onFound({ site: s.name, api: s.api, eps: g[0].eps, remarks: v.vod_remarks || "", ms: Date.now() - t0, rank, bps: null });
              break;
            }
          }).catch(() => {}).then(() => { running--; next(); });
        }
      };
      next();
    }),
  };
}

/* ---------------- 焦点导航（遥控器方向键） ---------------- */
const F = {
  cur: null,
  scope() { return $("#layer").classList.contains("on") ? $("#layer") : document; },
  hold: null,  // 菜单预览中：页面加载时不抢焦点
  set(el, quiet) {
    if (!el) return;
    if (MOB) { F.prev = F.cur; F.cur = el; return; }
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
    if (el.tagName === "INPUT") el.focus(); else if (document.activeElement && document.activeElement.tagName === "INPUT") document.activeElement.blur();
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
    if (el.closest(".epvp")) { F.revealEp(el); return; }
    // 横排：平移轨道，让焦点卡片尽量靠左第二个位置
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
    // 竖向：滚动整页
    const sc = el.closest(".scroll");
    if (sc && !el.closest("#top")) {
      const pageH = $("#page").clientHeight;
      const anchor = el.closest(".row,.hero,.dt,.eps-wrap,.grid-item,.chips,.sec") || el;
      let top = 0, n = anchor;
      while (n && n !== sc) { top += n.offsetTop; n = n.offsetParent; }
      if (anchor.classList.contains("grid-item") || anchor === el) { top = 0; n = el; while (n && n !== sc) { top += n.offsetTop; n = n.offsetParent; } }
      const h = anchor.offsetHeight;
      let y = sc._y || 0;
      const topPad = sc.dataset.pad ? parseFloat(sc.dataset.pad) * rem() : 6 * rem();
      if (anchor.classList.contains("hero") || anchor.classList.contains("dt") || top < topPad) y = 0;
      else if (top - y < topPad) y = top - topPad;
      else if (top + h - y > pageH - 2 * rem()) y = top + h - pageH + 2 * rem();
      y = Math.max(0, Math.min(y, Math.max(0, sc.scrollHeight - pageH + 2 * rem())));
      if (sc.dataset.lock === "row") y = Math.max(0, top - topPad);
      // 最后一个可选项：滚到底，把它下面不能选中的说明文字也露出来
      else if ($$(".f", sc).pop() === el) y = Math.max(y, sc.scrollHeight - pageH + 2 * rem());
      const rows = $$(".row,.grid-row", sc), tops = rows.map(r => [r.offsetTop, r.offsetHeight]), at = anchor.offsetTop, lock = sc.dataset.lock === "row";
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
  visible(el) { return el.offsetParent !== null; },
  move(dir) {
    const cur = F.cur && document.contains(F.cur) ? F.cur : null;
    let all = $$(".f", F.scope());
    if (!cur) { F.set(all.filter(F.visible)[0]); return; }
    if (dir === "up" || dir === "down") {
      const seen = new Set(), g0 = cur.closest(".track");
      all = all.filter(el => {
        const t = el.closest(".track");
        if (!t || t === g0) return true;
        if (seen.has(t)) return false;
        seen.add(t);
        return true;
      }).map(el => { const t = el.closest(".track"); return t && t !== cur.closest(".track") && t._last && document.contains(t._last) ? t._last : el; });
    }
    all = all.filter(F.visible);
    const r0 = cur.getBoundingClientRect();
    const cx = r0.left + r0.width / 2, cy = r0.top + r0.height / 2;
    const g0 = cur.closest("[data-g]");
    if ((dir === "left" || dir === "right") && g0 && g0.classList.contains("track")) {
      const items = $$(".f", g0), i = items.indexOf(cur), nx = items[i + (dir === "left" ? -1 : 1)];
      if (nx) { F.set(nx); return; }
      if (!document.querySelector(".epcol")) return;
    }
    // 详情页：选集栏 ←→ 播放/完整简介，固定落点，不按几何乱跳
    if ((dir === "left" || dir === "right") && $(".epcol") && $("#epsSec").style.display !== "none") {
      const acts = $(".dtwrap .acts"), eps = $(".epcol .eps");
      if (dir === "left" && cur.closest(".epcol")) { F.set((acts._last && document.contains(acts._last)) ? acts._last : $("#bPlay")); return; }
      if (dir === "right" && cur.closest(".dtwrap") && !$$(".f", g0 || document).some(x => x !== cur && x.getBoundingClientRect().left > r0.right - 2 && Math.abs(x.getBoundingClientRect().top - r0.top) < r0.height)) {
        F.set((eps._last && document.contains(eps._last)) ? eps._last : ($(".ep.cur", eps) || $(".ep", eps))); return;
      }
    }
    let best = null, bs = 1e12;
    all.forEach(el => {
      if (el === cur) return;
      const r = el.getBoundingClientRect();
      const x = r.left + r.width / 2, y = r.top + r.height / 2;
      let main, cross;
      if (dir === "left") { if (r.right > r0.left + 2 && x >= cx) return; main = cx - x; cross = Math.abs(y - cy); }
      else if (dir === "right") { if (r.left < r0.right - 2 && x <= cx) return; main = x - cx; cross = Math.abs(y - cy); }
      else if (dir === "up") { if (r.bottom > r0.top + 2 && y >= cy) return; main = cy - y; cross = Math.abs(x - cx); }
      else { if (r.top < r0.bottom - 2 && y <= cy) return; main = y - cy; cross = Math.abs(x - cx); }
      if (main <= 0) return;
      const horiz = dir === "left" || dir === "right";
      // 左右只在同一组里走
      if (horiz && g0 && el.closest("[data-g]") !== g0 && !F.cross) return;
      const s = main + cross * (horiz ? 4 : 1.2);
      if (s < bs) { bs = s; best = el; }
    });
    if (!best && (dir === "left" || dir === "right") && !F.cross && $(".epcol,.skb")) { F.cross = true; F.move(dir); F.cross = false; return; }
    if (!best) return;
    if (!(dir === "left" || dir === "right")) {
      const g = best.closest("[data-g]");
      if (g && g !== g0 && g._last && document.contains(g._last) && F.visible(g._last)) best = g._last;
    }
    F.set(best);
  },
};
let remPx = 20;
const rem = () => remPx;
function fit() {
  if (MOB) { remPx = Math.min(window.innerWidth, 640) / 24; document.documentElement.style.fontSize = remPx + "px"; const t = $("#top"); if (t) $("#page").style.top = t.offsetHeight + "px"; return; }
  remPx = window.innerWidth / 74; document.documentElement.style.fontSize = remPx + "px"; }

/* 背景氛围图 */
let bgI = 0, bgUrl = "";
function clearBg() { bgUrl = ""; $$("#bg .bgl").forEach(l => l.classList.remove("on")); document.body.classList.remove("poster"); }
function setBg(url) {
  document.body.classList.add("poster");
  if (!url || url === bgUrl) return;
  bgUrl = url;
  const ls = $$("#bg .bgl"), img = new Image();
  img.onload = () => { if (bgUrl !== url) return; bgI ^= 1; ls[bgI].style.backgroundImage = `url("${img.src}")`; ls[bgI].classList.add("on"); ls[bgI ^ 1].classList.remove("on"); };
  img.src = url.replace(/\/[a-z]_ratio_poster\//, "/l_ratio_poster/");
}

/* ---------------- 路由 ---------------- */
const TABS = [["home", "首页"], ["movie", "电影"], ["tv", "剧集"], ["anime", "动漫"], ["show", "综艺"], ["search", "搜索"], ["history", "观看记录"], ["settings", "设置"]];
const stack = [];
let route = null, routeTok = 0;
function go(name, arg, replace) {
  if (route && !replace) stack.push({ name: route.name, arg: route.arg, focus: F.cur, y: ($(".scroll") || {})._y, sy: $("#page").scrollTop });
  route = { name, arg };
  render(name, arg);
}
function render(name, arg, restore) {
  routeTok++;
  if (resolving) { resolving.cancel(); resolving = null; }
  const tk = name === "cat" ? arg : name;
  $$("#tabs .tab").forEach(t => t.classList.toggle("on", t.dataset.k === tk));
  $("#top").classList.remove("hide");
  if (name !== "detail") clearBg();
  const fn = PAGES[name];
  if (MOB) {
    const pg = $("#page"); pg._more = null; pg.scrollTop = 0;
    if (restore && restore.sy) [80, 400, 1200].forEach(t => setTimeout(() => { if (route && route.name === name) pg.scrollTop = restore.sy; }, t));
    const tab = $("#tabs .tab.on"); if (tab && tab.scrollIntoView) { const tb = $("#tabs"); tb.scrollLeft = Math.max(0, tab.offsetLeft - tb.clientWidth / 2 + tab.offsetWidth / 2); }
  }
  if (!F.hold) { if (F.cur) F.cur.classList.remove("focus"); F.cur = null; }
  fn(routeTok, arg, restore);
}
window.nnBack = function () {
  if ($("#layer").classList.contains("on")) { closeLayer(); return true; }
  if (MOB) {
    const inp = document.activeElement; if (inp && inp.tagName === "INPUT") { inp.blur(); return true; }
    if (stack.length) { const p = stack.pop(); route = { name: p.name, arg: p.arg }; render(p.name, p.arg, p); return true; }
    if (route && route.name !== "home") { route = { name: "home" }; render("home"); return true; }
    return false;
  }
  if (stack.length) {
    const p = stack.pop();
    route = { name: p.name, arg: p.arg };
    render(p.name, p.arg, p);
    return true;
  }
  const cur = F.cur && document.contains(F.cur) ? F.cur : null;
  const toTop = () => { const sc = $(".scroll"); if (sc) { sc._y = 0; sc.style.transform = "translateY(0)"; $$(".row", sc).forEach(r => r.classList.remove("past")); } $("#top").classList.remove("hide"); };
  // 片子 → 二级菜单
  const chip = $("#page .chip.on");
  if (cur && chip && !cur.closest("#top") && !cur.classList.contains("chip")) { toTop(); F.set(chip, true); return true; }
  // 二级菜单/内容 → 一级菜单
  const tabOn = $("#tabs .tab.on");
  if (cur && !cur.closest("#top") && tabOn) { toTop(); F.set(tabOn, true); return true; }
  // 一级菜单 → 首页
  const homeTab = $('#tabs .tab[data-k="home"]');
  if (route && route.name !== "home") { F.hold = homeTab; F.set(homeTab, true); stack.length = 0; route = { name: "home" }; render("home"); return true; }
  if (F.cur !== homeTab) { toTop(); F.set(homeTab, true); return true; }
  return false;
};
window.nnRemote = async function (act, v) {  // 手机/电脑通过局域网发来的指令
  if (act === "src") { toast("手机已修改片源，正在加载…"); await loadSources(true); toast(`片源：${SRC.list.length} 个`); if (route && route.name === "settings") render("settings"); }
  else if (act === "search") { F.hold = null; stack.length = 0; route = { name: "search", arg: v }; render("search", v); toast("收到搜索：" + v); }
  else if (act === "toast") { toast(v); }
  else if (act === "hist") { toast("观看记录已清除"); if (route && (route.name === "settings" || route.name === "history" || route.name === "home")) render(route.name); }
};
window.nnState = () => JSON.stringify({ n: SRC.list.length, from: SRC.from });
window.nnResume = function () {
  if (!route) return;
  if (route.name === "home") refreshHistory();
  else if (route.name === "history") render("history");
  else if (route.name === "detail" && window.__refreshDetail) window.__refreshDetail();
};

/* ---------------- 组件 ---------------- */
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
  $$(".card", root).forEach(c => {
    if (c._bound) return;
    c._bound = 1;
    c._ok = () => openDetail({ id: c.dataset.id, type: c.dataset.type, title: c.dataset.title, year: c.dataset.year, pic: c.dataset.pic });
    if (onFocus) c._onfocus = () => onFocus(c);
  });
}
function openDetail(it) { go("detail", it); }
function skel(n) { let s = ""; for (let i = 0; i < n; i++) s += `<div class="card"><div class="pic skel"></div><div class="t">&nbsp;</div></div>`; return s; }
function rowHTML(id, title, inner, small) { return `<section class="row" id="${id}"><h3>${esc(title)}${small ? ` <small>${esc(small)}</small>` : ""}</h3><div class="vp"><div class="track" data-g="${id}">${inner}</div></div></section>`; }

function openLayer(html, focusSel) {
  const l = $("#layer");
  l._prev = F.cur;
  l.innerHTML = html;
  l.classList.add("on");
  F.set($(focusSel || ".f", l) || $(".f", l));
}
function closeLayer() {
  const l = $("#layer");
  if (l._onclose) { const f = l._onclose; l._onclose = null; f(); }
  l.classList.remove("on");
  l.innerHTML = "";
  if (l._prev && document.contains(l._prev)) F.set(l._prev, true);
}

/* ---------------- 页面 ---------------- */
const HOME_ROWS = [
  ["热门电影", "movie", "热门", "全部"], ["热播剧集", "tv", "tv", "tv"], ["国产剧", "tv", "tv", "tv_domestic"], ["热门综艺", "tv", "show", "show"],
  ["热门动漫", "tv", "tv", "tv_animation"], ["豆瓣高分", "movie", "豆瓣高分", "全部"], ["欧美剧", "tv", "tv", "tv_american"], ["韩剧", "tv", "tv", "tv_korean"],
  ["日剧", "tv", "tv", "tv_japanese"], ["华语电影", "movie", "热门", "华语"], ["纪录片", "tv", "tv", "tv_documentary"],
];
const CHIPS = {
  movie: [["热门", "movie", "热门", "全部"], ["最新", "movie", "最新", "全部"], ["高分", "movie", "豆瓣高分", "全部"], ["冷门佳片", "movie", "冷门佳片", "全部"], ["华语", "movie", "热门", "华语"], ["欧美", "movie", "热门", "欧美"], ["韩国", "movie", "热门", "韩国"], ["日本", "movie", "热门", "日本"]],
  tv: [["综合", "tv", "tv", "tv"], ["国产剧", "tv", "tv", "tv_domestic"], ["欧美剧", "tv", "tv", "tv_american"], ["韩剧", "tv", "tv", "tv_korean"], ["日剧", "tv", "tv", "tv_japanese"], ["港剧", "tv", "rec", "电视剧,香港"], ["纪录片", "tv", "tv", "tv_documentary"]],
  anime: [["热播", "tv", "tv", "tv_animation"], ["日漫", "tv", "rec", "动画,日本"], ["国漫", "tv", "rec", "动画,中国大陆"], ["欧美动画", "tv", "rec", "动画,美国"], ["动画电影", "movie", "rec", "动画"]],
  show: [["综合", "tv", "show", "show"], ["国内", "tv", "show", "show_domestic"], ["国外", "tv", "show", "show_foreign"], ["真人秀", "tv", "rec", "综艺,真人秀"], ["脱口秀", "tv", "rec", "综艺,脱口秀"], ["音乐", "tv", "rec", "综艺,音乐"]],
};
const histAll = () => { try { return APP ? JSON.parse(NN.history()) : store.get("hist", []); } catch (e) { return []; } };
const histKey = it => (it.id ? `${it.type || "movie"}:${it.id}` : `t:${canon(it.title)}`);
const fmtT = ms => { const s = Math.floor((ms || 0) / 1000); return s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}` : `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`; };
function histCard(h, cls) {
  const prog = h.dur ? Math.min(100, h.pos / h.dur * 100) : 0;
  return card({ id: h.id, type: h.type, title: h.title, pic: h.pic, year: h.year }, { prog, cls, sub: h.eps > 1 ? `看到 ${h.epName}` : `看到 ${fmtT(h.pos)}` });
}
function refreshHistory() {
  const box = $("#rowHist .track");
  if (box) { const h = histAll().slice(0, 16); box.innerHTML = h.map(histCard).join(""); bindCards(box); }
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
    const fill = d => {
      if (!$(".hero") || $("h1", hero).textContent !== it.title) return;
      $(".meta", hero).innerHTML = [d.rate ? `<span class="rate">豆瓣 ${esc(d.rate)}</span>` : "", d.year ? `<span>${esc(d.year)}</span>` : "",
        d.countries && d.countries.length ? `<span>${esc(d.countries.slice(0, 2).join(" / "))}</span>` : "",
        d.genres && d.genres.length ? `<span class="pill">${esc(d.genres.slice(0, 3).join(" · "))}</span>` : "",
        d.episodes ? `<span class="pill">${d.episodes} 集</span>` : ""].join("");
      $(".intro", hero).textContent = d.intro || "";
    };
    $(".meta", hero).innerHTML = ""; $(".intro", hero).textContent = "";
    if (it.id) dbDetail(it.type === "tv" ? "tv" : "movie", it.id).then(fill).catch(() => {});
  }, 260);
}

const PAGES = {
  home(tok, _, restore) {
    $("#page").innerHTML = `<div class="scroll home" data-lock="row" data-pad="6.2"><div style="height:6.2rem"></div>
      ${HOME_ROWS.map((r, i) => rowHTML("row" + i, r[0], skel(8))).join("")}</div>`;
    bindCards($("#page"));
    let focused = false;
    const focusFirst = () => { if (focused || tok !== routeTok) return; focused = true; if (bootFocus) { bootFocus = false; F.set($('#tabs .tab[data-k="home"]'), true); if (window.nnSplashGo) window.nnSplashGo(firstPics()); return; } if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus); else F.set($("#page .card.f")); };
    const loadRow = i => {
      const r = HOME_ROWS[i];
      return dbList(r[1], r[2], r[3], 0, 24).then(d => {
        if (tok !== routeTok) return;
        const t = $(`#row${i} .track`);
        t.innerHTML = d.items.length ? d.items.map(it => card(Object.assign(it, { type: r[1] === "tv" ? "tv" : it.type }), { sub: short(it.sub).split(" · ")[0] || it.ep })).join("") : `<div class="empty">暂时拿不到豆瓣数据</div>`;
        bindCards(t);
        if (i === 0) focusFirst();
      }).catch(() => { const t = $(`#row${i} .track`); if (t) t.innerHTML = `<div class="empty">豆瓣数据加载失败，稍后再试</div>`; });
    };
    // 第一行先出；其余几行等启动动画放完再加载，免得动画时解码几十张海报卡顿
    const firstPics = () => $$("#row0 .card .pic").slice(0, 8).map(e => (/url\('([^']+)'\)/.exec(e.getAttribute("style") || "") || [])[1]).filter(Boolean);
    loadRow(0).then(() => afterSplash(() => { for (let i = 1; i < HOME_ROWS.length; i++) loadRow(i); }));
  },

  cat(tok, key, restore) {
    const chips = CHIPS[key], name = { movie: "电影", tv: "剧集", anime: "动漫", show: "综艺" }[key];
    let ci = restore ? store.get("chip:" + key, 0) : 0;   // 从一级菜单进来总是第一个；从详情页返回才恢复原来的
    if (!chips[ci]) ci = 0;
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec cathead"><h2>${name}</h2><span id="cnt" style="display:none"></span>
      <div class="chips" data-g="chips">${chips.map((c, i) => `<div class="chip f${i === ci ? " on" : ""}" data-i="${i}">${c[0]}</div>`).join("")}</div></div>
      <div class="grid" id="grid">${skel(14)}</div></div>`;
    const grid = $("#grid");
    let start = 0, busy = false, end = false, my = 0;
    const load = async first => {
      if (busy || end) return;
      busy = true;
      const c = chips[ci], me = ++my;
      try {
        const d = await dbList(c[1], c[2], c[3], start, 28);
        if (tok !== routeTok || me !== my) return;
        if (first) grid.innerHTML = "";
        grid.insertAdjacentHTML("beforeend", d.items.map(it => card(Object.assign(it, { type: c[1] === "tv" ? "tv" : it.type }), { sub: short(it.sub).split(" · ")[0] || it.ep, cls: "grid-item" })).join(""));
        bindCards(grid, el => { const items = $$(".card", grid); if (items.indexOf(el) > items.length - 15) load(); });
        start += d.items.length;
        end = !d.items.length || (d.total && start >= d.total);
        $("#cnt").textContent = "";
        if (first && !d.items.length) grid.innerHTML = `<div class="empty">暂无内容</div>`;
      } catch (e) { if (first) grid.innerHTML = `<div class="empty">豆瓣数据加载失败</div>`; }
      busy = false;
    };
    const pick = ch => {
      if (+ch.dataset.i === ci) return;
      ci = +ch.dataset.i; store.set("chip:" + key, ci);
      $$(".chip").forEach(x => x.classList.toggle("on", x === ch));
      start = 0; end = false; busy = false; my++; grid.innerHTML = skel(14); load(true);
    };
    $$(".chip", $("#page")).forEach(ch => {
      ch._onfocus = () => { clearTimeout(pick._t); pick._t = setTimeout(() => { if (F.cur === ch) pick(ch); }, 280); };
      ch._ok = () => { clearTimeout(pick._t); pick(ch); const c = $("#grid .card.f"); if (c) F.set(c); };
    });
    F.set($(".chip.on"));
    $("#page")._more = () => load();
    load(true);
  },

  async detail(tok, it, restore) {
    const kind = it.type === "tv" ? "tv" : "movie";
    setBg(it.pic);
    $("#page").innerHTML = `<div class="dtwrap"><section class="dt"><div class="poster" style="background-image:url('${esc(it.pic)}')"></div>
      <div class="info"><h1>${esc(it.title)}</h1><div class="orig"></div><div class="meta"></div><div class="crew"></div><div class="intro"></div>
      <div class="acts" data-g="acts"><div class="btn pri f" id="bPlay">${ICON_PLAY}<span>播放</span></div><div class="btn f" id="bIntro">${ICON_INFO}<span>完整简介</span></div></div>
      <div class="srcline"><i class="lamp wait"></i><span id="srcSt">正在全网找片源…</span></div></div></section>
      <div id="castRow"></div></div>
      <aside class="epcol" id="epsSec" style="display:none"><h3>选集 <small id="epsInfo"></small></h3><div class="epvp"><div class="eps" data-g="eps"></div></div></aside>`;
    F.set($("#bPlay"));
    if (MOB) { const dt = $(".dt"); [".crew", ".intro", ".acts", ".srcline"].forEach(s => dt.appendChild($(s, dt))); $(".dtwrap").insertBefore($("#epsSec"), $("#castRow")); }
    const key = histKey({ id: it.id, type: kind, title: it.title });
    const st = { srcs: [], done: false, probing: 0, probed: 0, cam: 0 };
    let meta = { title: it.title, year: it.year, pic: it.pic };
    const hist = () => histAll().find(h => h.key === key);
    const playLabel = () => {
      const h = hist();
      $("#bPlay span").textContent = h ? (h.eps > 1 ? `继续 ${h.epName}` : `继续播放 ${fmtT(h.pos)}`) : "播放";
    };
    playLabel();
    const status = () => {
      const ok = st.srcs.filter(s => s.bps > 0).sort((a, b) => b.bps - a.bps);
      const lamp = $(".srcline .lamp"), el = $("#srcSt");
      if (!el) return;
      if (!st.srcs.length) { lamp.className = "lamp " + (st.done ? "bad" : "wait"); el.textContent = st.done ? (st.cam ? `只有枪版（${st.cam} 个），已过滤，等出了高清再来` : "没找到这部片的片源") : "正在全网找片源…"; return; }
      lamp.className = "lamp " + (ok.length ? "ok" : "wait");
      el.textContent = `找到 ${st.srcs.length} 个片源${st.done ? "" : "（还在找）"}${st.cam ? `，已滤掉 ${st.cam} 个枪版` : ""}` + (ok.length ? ` · 最快「${ok[0].site}」${(ok[0].bps / 1048576).toFixed(1)} MB/s` : st.probing ? " · 测速中" : "");
    };
    // 后台测速：拿到片源就测，电视同时测 6 个、手机 3 个（原生层排队）
    const probeQ = [];
    const pump = () => {
      while (st.probing < (MOB ? 3 : 6) && probeQ.length) {
        const s = probeQ.shift();
        st.probing++;
        probe(s.eps[0].url).then(r => { s.bps = r.bps; s.h = r.h || 0; }).then(() => { st.probing--; st.probed++; status(); pump(); });
      }
    };
    let epsShown = 0;
    const showEps = () => {
      const best = st.srcs.slice().sort((a, b) => b.eps.length - a.eps.length)[0];
      if (!best || best.eps.length < 2 || best.eps.length <= epsShown || !$("#epsSec")) return;
      if (F.cur && F.cur.closest("#epsSec")) return;  // 正在选集时不重画
      epsShown = best.eps.length;
      $("#epsSec").style.display = "";
      $(".dtwrap").classList.add("haseps");
      $("#epsInfo").textContent = `共 ${best.eps.length} 集${best.remarks ? " · " + best.remarks : ""}`;
      const h = hist(), cur = h ? h.ei : -1;
      $(".eps").innerHTML = best.eps.map((e, i) => `<div class="ep f${i === cur ? " cur" : ""}" data-i="${i}">${esc(e.name)}</div>`).join("");
      $$(".ep").forEach(b => { b._ok = () => startPlay(+b.dataset.i, 0); });
      const c0 = $(".ep.cur");
      if (c0) F.revealEp(c0);
    };
    const found = s => {
      if (tok !== routeTok) return;
      st.srcs.push(s);
      probeQ.push(s);
      pump();
      status();
      showEps();
    };
    const startResolve = () => {
      if (!SRC.list.length) { st.done = true; status(); return; }
      resolving = resolve(meta.title, meta.year, found, () => { if (tok === routeTok) { st.cam++; status(); } });
      resolving.done.then(() => { if (tok === routeTok) { st.done = true; status(); } });
    };

    // 片源排序（播放与详情页预载共用同一规则，保证预载的就是要播的那个源）
    function rank(ei) {
      const need = ei + 1, maxEp = Math.max.apply(null, st.srcs.map(s => s.eps.length));
      const score = srcScore;
      loadCrowd();
      return st.srcs.slice().sort((a, b) => {
        const ha = a.eps.length >= need ? 1 : 0, hb = b.eps.length >= need ? 1 : 0;
        if (ha !== hb) return hb - ha;
        if (kind === "tv" && maxEp > 1) { const fa = a.eps.length >= maxEp * 0.9 ? 1 : 0, fb = b.eps.length >= maxEp * 0.9 ? 1 : 0; if (fa !== fb && (a.bps > 0) === (b.bps > 0)) return fb - fa; }
        return score(b) - score(a);
      });
    }
    // 详情页预载：测速有结论后（与按播放时同样的等待规则）先缓存要播那一集的开头
    if (APP && NN.prefetch) {
      const t0 = Date.now();
      const pre = () => {
        if (tok !== routeTok) return;
        const ok = st.srcs.filter(s => s.bps > 0).length, waited = Date.now() - t0;
        if (!(ok >= 3 || (ok >= 1 && waited > 6000) || (ok >= 1 && st.done && st.probing === 0 && !probeQ.length))) {
          if (waited < 30000) setTimeout(pre, 500);
          return;
        }
        const h = hist(), ei = h ? h.ei : 0, s = rank(ei)[0];
        if (!s || !(s.bps > 0)) return;
        const e = s.eps[Math.min(ei, s.eps.length - 1)];
        if (e && e.url) NN.prefetch(e.url, h && h.ei === ei ? (h.pos || 0) : 0);
      };
      setTimeout(pre, 1500);
    }
    // 选源并播放：等测速出结果（最多等 10 秒），按速度从快到慢交给播放器，失败自动换下一个
    async function startPlay(ei, pos) {
      if (tok !== routeTok) return;
      openLayer(`<div class="panel picking"><div class="spin"></div><h2>正在为你挑选最快的片源</h2><div class="st" id="pk">准备中…</div><div class="acts" style="justify-content:center;margin-top:1.4rem"><div class="btn f" id="pkC">取消</div></div></div>`);
      let cancelled = false;
      $("#layer")._onclose = () => { cancelled = true; };
      $("#pkC")._ok = closeLayer;
      const t0 = Date.now();
      while (!cancelled) {
        const ok = st.srcs.filter(s => s.bps > 0).length, el = $("#pk");
        if (el) el.textContent = `已找到 ${st.srcs.length} 个片源，测速完成 ${st.probed} 个`;
        const waited = Date.now() - t0;
        if (ok >= 3 || (ok >= 1 && waited > 2500) || (ok >= 2 && waited > 1500) || (st.done && st.probing === 0 && !probeQ.length) || waited > 10000) break;
        await sleep(250);
      }
      if (cancelled || tok !== routeTok) return;
      if (!st.srcs.length) { closeLayer(); toast("没找到可播放的片源"); return; }
      const list = rank(ei);
      closeLayer();
      const data = { key, title: meta.title, pic: meta.pic || it.pic, id: it.id || "", type: kind, year: meta.year || "", si: 0, ei, pos: pos || 0,
        sources: list.map(s => ({ site: s.site, eps: s.eps, bps: s.bps == null ? 0 : s.bps, h: s.h || 0 })) };
      if (APP) NN.play(JSON.stringify(data)); else toast(`将播放「${list[0].site}」${list[0].eps[Math.min(ei, list[0].eps.length - 1)].name}`);
    }
    $("#bPlay")._ok = () => { const h = hist(); startPlay(h ? h.ei : 0, h ? h.pos : 0); };
    $("#bIntro")._ok = () => openLayer(`<div class="panel"><h2>${esc(meta.title)}</h2><p>${esc(meta.intro || "暂无简介")}</p><div class="acts" style="margin-top:1.6rem"><div class="btn f" id="iClose">关闭</div></div></div>`, "#iClose");
    // 弹层要在 openLayer 之后才有关闭按钮，这里补上它的 OK 动作
    { const o = $("#bIntro")._ok; $("#bIntro")._ok = () => { o(); const c = $("#iClose"); if (c) c._ok = closeLayer; }; }
    $("#layer")._dummy = 0;
    window.__refreshDetail = () => { playLabel(); epsShown = 0; showEps(); };

    await loadSources();
    if (tok !== routeTok) return;
    if (!it.id) { startResolve(); return; }
    startResolve();  // 先用列表里的片名搜，豆瓣详情回来后不再重复
    try {
      const d = await dbDetail(kind, it.id);
      if (tok !== routeTok) return;
      meta = Object.assign(meta, d, { pic: d.pic || it.pic });
      setBg(d.pic);
      $(".poster").style.backgroundImage = `url('${d.pic}')`;
      $(".dt h1").textContent = d.title;
      $(".orig").textContent = [d.original && d.original !== d.title ? d.original : "", d.year].filter(Boolean).join(" · ");
      $(".dt .meta").innerHTML = [d.rate ? `<span class="rate">豆瓣 ${esc(d.rate)}</span>` : "", d.countries.length ? `<span>${esc(d.countries.slice(0, 3).join(" / "))}</span>` : "",
        d.genres.length ? `<span class="pill">${esc(d.genres.join(" · "))}</span>` : "", d.episodes ? `<span class="pill">${d.episodes} 集</span>` : "",
        d.durations.length ? `<span class="pill">${esc(d.durations[0])}</span>` : ""].join("");
      $(".crew").textContent = [d.directors.length ? "导演：" + d.directors.slice(0, 2).join(" / ") : "", d.actors.length ? "主演：" + d.actors.slice(0, 5).join(" / ") : ""].filter(Boolean).join("　　");
      $(".dt .intro").textContent = d.intro || "暂无简介";
      if (d.cast.length) {
        $("#castRow").innerHTML = rowHTML("rowCast", "演职员", d.cast.map(c => `<div class="person f"><div class="av"${c.avatar ? ` style="background-image:url('${esc(c.avatar)}')"` : ""}>${c.avatar ? "" : esc((c.name || "?")[0])}</div><b>${esc(c.name)}</b><small>${esc(c.role)}</small></div>`).join(""));
        $$("#rowCast .person").forEach((p, i) => { p._ok = () => go("search", d.cast[i].name); });
      }
      if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus);
    } catch (e) {
      $(".dt .intro").textContent = "豆瓣详情加载失败";
    }
  },

  search(tok, q) {
    // 仿 TVBox：左侧字母键盘，按拼音首字母搜（爱奇艺联想接口支持首字母），中文/语音走豆瓣
    const KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".split("");
    if (MOB) $("#page").innerHTML = `<div class="scroll srch"><div class="sbox">${ICON_SEARCH}<input id="q" type="search" placeholder="片名、演员，或拼音首字母如 XCLF" enterkeyhint="search"></div>
      <div class="pagehead sec"><h2 id="sh">大家都在看</h2><span id="ss"></span></div><div id="sres"></div></div>`;
    else $("#page").innerHTML = `<div class="skb"><div class="sq" id="sq"><span id="sqt"></span><i>拼音首字母，如 XCLF</i></div>
      <div class="keys" data-g="kb">${KEYS.map(k => `<div class="key f" data-k="${k}">${k}</div>`).join("")}
      <div class="key f wide" data-k="clr">清空</div><div class="key f wide" data-k="del">删除</div><div class="key f wide" data-k="ime">输入法</div></div>
      <input id="q" class="imeq" value=""></div>
      <div class="scroll srch" data-pad="6"><div class="pagehead sec"><h2 id="sh">大家都在看</h2><span id="ss"></span></div><div id="sres"></div></div>`;
    const input = $("#q");
    let text = q || "", timer = 0, my = 0;
    const show = () => { if (MOB) { if (input.value !== text) input.value = text; return; } $("#sqt").textContent = text; $("#sq").classList.toggle("has", !!text); };
    const isLetters = v => /^[a-z0-9]+$/i.test(v);
    const grid = items => `<div class="grid">${items.map(it => card(it, { sub: it.sub, cls: "grid-item" })).join("")}</div>`;
    const cidType = c => (c === 1 ? "movie" : "tv");
    const iqSuggest = async v => {
      const d = await getJSON(`https://suggest.video.iqiyi.com/?if=mobile&key=${encodeURIComponent(v)}`, null, 6000);
      // cid 0 是联想词条（首字母/全拼常命中这种，如 wdxdjsl→我的兄弟叫顺溜），没有海报，带「电视剧/电影」标签的也要
      const TAGS = { "电影": 1, "电视剧": 2, "动漫": 4, "综艺": 6 };
      const seen = {}, out = [];
      (d.data || []).forEach(x => {
        if (!x.name) return;
        let cid = x.cid;
        if (!cid) { const t = ((x.presentation_element || {}).tags || []).map(g => g.name).find(n => TAGS[n]); if (!t) return; cid = TAGS[t]; }
        if ([1, 2, 4, 6].indexOf(cid) < 0) return;
        const k = x.name.replace(/\s+/g, "");
        if (seen[k]) { const o = seen[k]; if (!o.pic && x.picture_url) o.pic = x.picture_url.replace(/^http:/, "https:"); return; }
        const cn = { 1: "电影", 2: "电视剧", 4: "动漫", 6: "综艺" }[cid];
        out.push(seen[k] = { id: "", title: x.name, pic: (x.picture_url || "").replace(/^http:/, "https:"), year: x.year || "", type: cidType(cid), sub: [x.cname || cn, x.year].filter(Boolean).join(" · ") });
      });
      const items = out.slice(0, 18);
      // 没海报的前 8 个去豆瓣按片名补海报和 id（最多等 3.5 秒）
      const fill = items.filter(it => !it.pic).slice(0, 8).map(it => dbSearch(it.title).then(r => {
        const hit = r.find(x => x.title === it.title && (!it.year || !x.year || Math.abs(+x.year - +it.year) <= 1)) || r.find(x => x.title === it.title);
        if (hit) Object.assign(it, { id: hit.id, type: hit.type, year: hit.year || it.year, pic: hit.pic || it.pic });
      }).catch(() => {}));
      if (fill.length) await Promise.race([Promise.all(fill), sleep(3500)]);
      return items;
    };
    // 联想结果没有豆瓣 id：点开时先去豆瓣按片名+年份找，找不到也能直接按片名找片源
    const bindIq = items => $$("#sres .card").forEach((c, i) => {
      const it = items[i];
      c._ok = async () => {
        if (it.id) { openDetail(it); return; }
        let hit = null;
        try {
          const r = await dbSearch(it.title);
          hit = r.find(x => x.title === it.title && (!it.year || !x.year || Math.abs(+x.year - +it.year) <= 1)) || r.find(x => x.title === it.title);
        } catch (e) {}
        openDetail(hit ? { id: hit.id, type: hit.type, title: hit.title, year: hit.year, pic: hit.pic || it.pic } : { id: "", type: it.type, title: it.title, year: it.year, pic: it.pic });
      };
    });
    const run = async () => {
      const v = text.trim(), me = ++my;
      if (!v) { hot(); return; }
      $("#sh").textContent = "搜索结果"; $("#ss").textContent = "";
      $("#sres").innerHTML = `<div class="grid">${skel(5)}</div>`;
      try {
        let items = [], iq = false;
        if (isLetters(v)) { try { items = await iqSuggest(v); iq = items.length > 0; } catch (e) {} }
        if (!items.length) items = (await dbSearch(v)).map(it => Object.assign(it, { sub: String(it.sub).split(" / ")[0] }));
        if (tok !== routeTok || me !== my) return;
        $("#ss").textContent = "";
        $("#sres").innerHTML = items.length ? grid(items) : `<div class="empty">没搜到「${esc(v)}」</div>`;
        bindCards($("#sres"));
        if (iq) bindIq(items);
      } catch (e) { if (me === my) $("#sres").innerHTML = `<div class="empty">搜索失败，稍后再试</div>`; }
    };
    const hot = () => {
      $("#sh").textContent = "大家都在看"; $("#ss").textContent = "";
      dbList("movie", "热门", "全部", 0, 15).then(d => {
        if (tok !== routeTok || text.trim()) return;
        $("#sres").innerHTML = grid(d.items.map(it => Object.assign(it, { sub: short(it.sub).split(" · ")[0] })));
        bindCards($("#sres"));
      }).catch(() => {});
    };
    const later = () => { clearTimeout(timer); timer = setTimeout(run, 450); };
    $$(".skb .key").forEach(k => {
      k._ok = () => {
        const c = k.dataset.k;
        if (c === "clr") text = "";
        else if (c === "del") text = text.slice(0, -1);
        else if (c === "ime") { input.value = text; input.focus(); return; }
        else if (text.length < 20) text += c;
        show(); later();
      };
    });
    if (!MOB) input.addEventListener("keydown", e => { if (e.keyCode === 13) { e.preventDefault(); text = input.value.trim(); input.blur(); show(); run(); F.set($(".skb .key")); } });
    if (!MOB) input.addEventListener("blur", () => { if (input.value.trim() && input.value.trim() !== text) { text = input.value.trim(); show(); run(); } });
    if (MOB) {
      input.addEventListener("input", () => { text = input.value.trim(); later(); });
      input.addEventListener("keydown", e => { if (e.keyCode === 13) { e.preventDefault(); text = input.value.trim(); input.blur(); run(); } });
      show();
      if (text) run(); else { hot(); setTimeout(() => input.focus(), 300); }
      return;
    }
    show();
    F.set($(".skb .key"));
    if (text) run(); else hot();
  },

  history(tok) {
    const list = histAll();
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>观看记录</h2><span>${list.length ? (MOB ? "长按删除" : "按菜单键删除当前这条") : ""}</span></div>
      <div class="grid">${list.length ? list.map(h => histCard(h, "grid-item")).join("") : `<div class="empty">还没有看过的片子</div>`}</div></div>`;
    bindCards($("#page"));
    $$("#page .card").forEach((c, i) => { c._menu = () => { if (APP) NN.delHistory(list[i].key); toast("已删除"); render("history"); }; });
    F.set($("#page .card") || $("#tabs .tab.on"));
  },

  async settings(tok) {
    await loadSources();
    if (tok !== routeTok) return;
    const custom = cfg.get("srcUrl", "");
    let lan = null; try { lan = APP && NN.lan ? JSON.parse(NN.lan()) : null; } catch (e) {}
    $("#page").innerHTML = `${lan && lan.url ? `<div class="lan"><b>手机 / 电脑扫码设置</b><img src="${lan.qr}"><div class="u">${esc(lan.url)}</div><small>同一局域网内用浏览器打开<br>可推送安装/升级 App、改片源、推送搜索</small></div>` : `<div class="lan"><b>局域网设置</b><small>没连上局域网，暂不可用</small></div>`}
      <div class="scroll setwrap" data-pad="6"><div class="pagehead sec"><h2>设置</h2><span>版本 ${APP ? esc(NN.version()) : "dev"}</span></div>
      <div class="setrow f" id="sRefresh"><span>刷新片源</span><small>${SRC.list.length} 个采集站 · ${esc(SRC.from.replace(/^https?:\/\//, "").slice(0, 60))}</small></div>
      <div class="setrow f" id="sUrl"><span>片源地址</span><small>${esc(custom || "默认：zknjjjx/tvbox 的 cmstv.json（自动加速）")}</small></div>
      <div class="setrow f" id="sReset"><span>恢复默认片源</span><small>清掉自定义地址</small></div>
      <div class="setrow f" id="sUpd"><span>检查更新</span><small id="sUpdS">当前 ${APP ? esc(NN.version()) : "dev"}${UPD.latest ? (hasNew() ? ` · 有新版 ${esc(UPD.latest.version)}，按 OK 升级` : " · 已是最新") : ""}</small></div>
      <div class="setrow f" id="sApk"><span>安装包残留</span><small id="sApkS">${(() => { let a = { n: 0 }; try { a = APP && NN.apkInfo ? JSON.parse(NN.apkInfo()) : a; } catch (e) {} return a.n ? `${a.n} 个 · ${a.mb} MB，按 OK 删除` : "没有残留，已全部删除"; })()}</small></div>
      <div class="setrow f" id="sClear"><span>清除观看记录</span><small>${histAll().length} 条</small></div>
      <div class="setrow info"><span>播放说明</span><small>${MOB ? "自动测速挑最快源；片源没响应或卡住几秒没数据就自动换源；播放时点屏幕出控制栏，左右滑动快进快退，双击暂停" : "自动测速挑最快源；片源没响应或卡住几秒没数据就自动换源；播放中按 ▲ 选集换源"}</small></div></div>`;
    $("#sRefresh")._ok = async () => { toast("正在刷新片源…"); await loadSources(true); toast(`片源已更新：${SRC.list.length} 个`); render("settings"); };
    $("#sUrl")._ok = () => {
      openLayer(`<div class="panel"><h2>片源地址</h2><p style="opacity:.7">支持 cmstv.json 或 TVBox 配置（只用里面的采集站）。GitHub 地址会自动走加速。</p>
        <div class="sbox f" style="margin:1.2rem 0 0;max-width:none" id="uBox"><input id="uIn" value="${esc(custom || DEF_SRC)}"></div>
        <div class="acts" style="margin-top:1.6rem"><div class="btn pri f" id="uOk">保存</div><div class="btn f" id="uNo">取消</div></div></div>`, "#uBox");
      $("#uBox")._ok = () => $("#uIn").focus();
      $("#uNo")._ok = closeLayer;
      $("#uOk")._ok = async () => { const v = $("#uIn").value.trim(); cfg.set("srcUrl", v === DEF_SRC ? "" : v); closeLayer(); toast("正在加载新片源…"); await loadSources(true); toast(`片源：${SRC.list.length} 个`); render("settings"); };
    };
    $("#sReset")._ok = async () => { cfg.set("srcUrl", ""); await loadSources(true); toast("已恢复默认片源"); render("settings"); };
    $("#sUpd")._ok = () => {
      if (hasNew()) { askUpgrade(); return; }
      $("#sUpdS").textContent = "正在检查…";
      checkUpdate().then(() => { markNew(); if (hasNew()) askUpgrade(); else { $("#sUpdS").textContent = `当前 ${NN.version()} · 已是最新`; toast("已是最新版本"); } })
        .catch(() => { $("#sUpdS").textContent = "检查失败，连不上 GitHub"; });
    };
    markNew();
    $("#sApk")._ok = () => { if (APP && NN.apkClean) NN.apkClean(); $("#sApkS").textContent = "没有残留，已全部删除"; toast("安装包已删除"); };
    $("#sClear")._ok = () => { histAll().forEach(h => APP && NN.delHistory(h.key)); toast("已清除"); render("settings"); };
    F.set($("#sRefresh"));
  },
};
let resolving = null;

/* ---------------- 在线升级：读 GitHub Release v2-latest 里的 latest.json ---------------- */
const REL = "https://github.com/zknjjjx/NiuniuTV/releases/download/v2-latest/";
const UPD_JSON = MOB ? "latest-m.json" : "latest.json";
const relMirrors = f => [`https://gh-proxy.com/${REL}${f}`, `https://9797.cc.cd/${REL}${f}`, `https://ghfast.top/${REL}${f}`, REL + f];
const verNum = v => String(v || "0").split(".").reduce((a, x) => a * 1000 + (parseInt(x, 10) || 0), 0);
const UPD = { latest: null, checking: null };
function checkUpdate() {
  if (UPD.checking) return UPD.checking;
  UPD.checking = anyOf(relMirrors(UPD_JSON + "?t=" + Date.now()).map(u => getJSON(u, null, 12000).then(j => { if (!j || !j.version || !j.apk) throw new Error("bad"); return j; })))
    .then(j => { UPD.latest = j; UPD.checking = null; return j; }, e => { UPD.checking = null; throw e; });
  return UPD.checking;
}
const hasNew = () => APP && UPD.latest && verNum(UPD.latest.version) > verNum(NN.version());
function askUpgrade() {
  const j = UPD.latest;
  openLayer(`<div class="panel"><h2>发现新版本 ${esc(j.version)}</h2><p style="opacity:.75">当前 ${esc(NN.version())}${j.notes ? "<br>" + esc(j.notes) : ""}</p>
    <p id="upMsg" style="opacity:.6;min-height:1.6em"></p>
    <div class="acts" style="margin-top:1.2rem"><div class="btn pri f" id="upOk">立即升级</div><div class="btn f" id="upNo">以后再说</div></div></div>`, "#upOk");
  $("#upNo")._ok = closeLayer;
  $("#upOk")._ok = () => {
    if (UPD.busy) return;
    UPD.busy = true; $("#upMsg").textContent = "正在下载…";
    NN.upgrade(JSON.stringify(relMirrors(j.apk)));
  };
}
window.nnUpg = x => {
  const m = $("#upMsg");
  if (typeof x === "number") { if (m) m.textContent = x >= 100 ? "下载完成，请在弹出的界面点“安装”" : `正在下载 ${x}%`; if (x >= 100) UPD.busy = false; }
  else if (/^正在/.test(x)) { if (m) m.textContent = x; }
  else { UPD.busy = false; if (m) m.textContent = "升级失败：" + x; else toast("升级失败：" + x); }
};
function markNew() {
  const t = $('#tabs .tab[data-k="settings"]'), on = hasNew();
  if (t) t.classList.toggle("dot", on);
  const r = $("#sUpd"); if (r) r.classList.toggle("dot", on);
}
function autoUpdate() {
  if (!APP) return;
  // 不弹窗：有新版只在「设置」标签和「检查更新」行上亮红点，升不升级由用户决定
  setTimeout(() => checkUpdate().then(markNew).catch(() => {}), 8000);
}

/* ---------------- 按键 ---------------- */
document.addEventListener("keydown", e => {
  const k = e.keyCode;
  const inInput = document.activeElement && document.activeElement.tagName === "INPUT";
  if (inInput && (k === 37 || k === 39)) return;  // 输入框里左右移动光标
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
    if (ri >= 0 && ring.length > 1 && (dir === "right" ? ri === ring.length - 1 : ri === 0)) {  // 一级、二级菜单到头了再按：绕到另一头
      F.set(ring[dir === "right" ? 0 : ring.length - 1]);
    } else if (dir === "up" && before && before.classList.contains("chip") && before.closest("#page")) {  // 二级菜单按上：回一级菜单
      F.set($("#tabs .tab.on") || $("#tabs .tab"), true);
    } else if (dir === "down" && before && before.classList.contains("chip") && before.closest("#page") && $("#grid .card.f")) {  // 二级菜单按下：进片子
      clearTimeout(before._t); F.set($("#grid .card.f"));
    } else if (dir === "down" && before && before.closest("#tabs")) {  // 一级菜单往下：二级菜单（分类页）或第一行片子
      const tgt = $("#page .chip.on") || $("#page .card.f") || $("#page .f");
      if (tgt && F.visible(tgt)) { F.hold = null; F.set(tgt); } else F.move(dir);
    } else F.move(dir);
    F.user = false;
    // 最上面再按上：到顶部菜单
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
window.nnMenu = () => { if (F.cur && F.cur._menu) F.cur._menu(); };

/* ---------------- 启动 ---------------- */
// 启动动画：首页内容就绪（或最多等 3 秒）后，狗狗从屏幕中央缩到左上角 logo 的位置
let splashOver = false, splashQ = [];
function afterSplash(f) { if (splashOver) f(); else splashQ.push(f); }
function splash() {
  const sp = $("#splash"), body = document.body;
  const end = () => {
    if (!splashOver) { splashOver = true; setTimeout(() => { splashQ.splice(0).forEach(f => { try { f(); } catch (e) {} }); }, 60); } document.documentElement.classList.remove("splash0"); if (sp && sp.parentNode) sp.parentNode.removeChild(sp); body.classList.remove("booting", "reveal"); try { if (APP && NN.splashDone) NN.splashDone(); } catch (e) {} };
  if (!sp || MOB) { end(); window.nnSplashGo = () => {}; return; }
  const t0 = Date.now(); let gone = false;
  const go = () => {
    if (gone) return; gone = true;
    const dog = sp.querySelector(".dog"), logo = $(".brand img");
    const a = dog.getBoundingClientRect(), b = logo.getBoundingClientRect();
    if (!a.width || !b.width) { end(); return; }
    const s = b.width / a.width, dx = b.left + b.width / 2 - (a.left + a.width / 2), dy = b.top + b.height / 2 - (a.top + a.height / 2);
    const tf = `translate3d(${dx}px,${dy}px,0) scale(${s})`;
    dog.style.webkitTransform = tf; dog.style.transform = tf;
    sp.classList.add("go");
    body.classList.add("reveal");
    setTimeout(end, 1150);
  };
  // 狗狗至少停留 0.7 秒；首行前几张海报先解码好（最多再等 1.2 秒），动画期间不再有重活
  window.nnSplashGo = pics => {
    const tw = Date.now();
    let left = (pics || []).length;
    const fire = () => setTimeout(go, Math.max(0, 700 - (Date.now() - t0)));
    if (!left) { fire(); return; }
    let done = false;
    const one = () => { if (!done && --left <= 0) { done = true; fire(); } };
    pics.forEach(u => { const im = new Image(); im.onload = im.onerror = one; im.src = u; });
    setTimeout(() => { if (!done) { done = true; fire(); } }, 1200);
  };
  setTimeout(go, 3000);
}
function clock() { const d = new Date(); $("#clock").textContent = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; }
function boot() {
  if (MOB) document.body.classList.add("mob");
  splash();
  fit();
  if (cfg.get("fx", "1") === "0") document.body.classList.add("nofx");
  window.addEventListener("resize", fit);
  $("#tabs").innerHTML = TABS.map(t => `<div class="tab f" data-k="${t[0]}">${t[1]}</div>`).join("");
  $$("#tabs .tab").forEach(t => {
    const open = hold => {
      const k = t.dataset.k, name = k in CHIPS ? "cat" : k, arg = k in CHIPS ? k : null;
      if (hold && route && route.name === name && route.arg === arg && !stack.length) return;
      stack.length = 0;
      F.hold = hold ? t : null;
      route = { name, arg };
      render(name, arg);
    };
    t._ok = () => {
      const k = t.dataset.k;
      if (MOB || !route || route.name !== (k in CHIPS ? "cat" : k) || stack.length) { open(false); return; }
      F.hold = null; const c = $("#page .f.focus") || $("#page .card.f") || $("#page .f"); if (route && route.name !== "search" && c) F.set(c); else open(false); };
    t._onfocus = () => { if (!(F.prev && F.prev.closest("#tabs"))) return; clearTimeout(open._t); open._t = setTimeout(() => { if (F.cur === t) open(true); }, 280); };
  });
  clock(); setInterval(clock, 20000);
  if (MOB) {
    fit();
    const pg = $("#page");
    pg.addEventListener("scroll", () => { if (pg._more && pg.scrollTop + pg.clientHeight > pg.scrollHeight - pg.clientHeight * 1.5) pg._more(); });
    document.addEventListener("click", e => {
      const l = $("#layer");
      if (e.target === l) { closeLayer(); return; }
      const el = e.target.closest ? e.target.closest(".f") : null;
      if (!el || el.tagName === "INPUT" || el.closest(".sbox")) return;
      if (l.classList.contains("on") && !el.closest("#layer")) return;
      F.cur = el;
      if (el._ok) el._ok();
    });
    document.addEventListener("contextmenu", e => {
      e.preventDefault();
      const el = e.target.closest ? e.target.closest(".f") : null;
      if (el && el._menu) el._menu();
    });
  }
  loadSources();
  route = { name: "home" };
  autoUpdate();
  bootFocus = true;
  render("home");
}
let bootFocus = false;
if (!String.prototype.padStart) String.prototype.padStart = function (n, c) { let s = String(this); while (s.length < n) s = c + s; return s; };
if (!Array.prototype.find) Array.prototype.find = function (f) { for (let i = 0; i < this.length; i++) if (f(this[i], i)) return this[i]; };
if (!Element.prototype.closest) Element.prototype.closest = function (s) { let el = this; while (el && el.nodeType === 1) { if (el.matches ? el.matches(s) : el.webkitMatchesSelector(s)) return el; el = el.parentElement; } return null; };
boot();
