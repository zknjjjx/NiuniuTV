/* 妞妞影视 · 电视版界面（遥控器操作）
 * 数据：豆瓣（海报/简介/演员）+ 采集站（片源）。原生层 NN 负责跨域请求、测速、播放。 */
"use strict";
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => Array.prototype.slice.call(el.querySelectorAll(s));
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const APP = typeof window.NN !== "undefined";

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
/* 在所有采集站里按片名找片源，找到一个回调一个 */
function resolve(name, year, onFound) {
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
  reveal(el) {
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
      if (sc.dataset.lock === "row") {
        y = Math.max(0, top - topPad);
        $$(".row", sc).forEach(r => r.classList.toggle("past", r.offsetTop < anchor.offsetTop));
      }
      sc._y = y;
      sc.style.transform = `translateY(${-y}px)`;
      $("#top").classList.toggle("hide", y > 2 * rem() && !!sc.dataset.hidetop);
      if (sc._onScroll) sc._onScroll(el);
    }
  },
  visible(el) { return el.offsetParent !== null; },
  move(dir) {
    const cur = F.cur && document.contains(F.cur) ? F.cur : null;
    const all = $$(".f", F.scope()).filter(F.visible);
    if (!cur) { F.set(all[0]); return; }
    const r0 = cur.getBoundingClientRect();
    const cx = r0.left + r0.width / 2, cy = r0.top + r0.height / 2;
    const g0 = cur.closest("[data-g]");
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
      if (horiz && g0 && el.closest("[data-g]") !== g0) return;
      const s = main + cross * (horiz ? 4 : 1.2);
      if (s < bs) { bs = s; best = el; }
    });
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
function fit() { remPx = window.innerWidth / 74; document.documentElement.style.fontSize = remPx + "px"; }

/* 背景氛围图 */
let bgI = 0, bgUrl = "";
function setBg(url) {
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
  if (route && !replace) stack.push({ name: route.name, arg: route.arg, focus: F.cur, y: ($(".scroll") || {})._y });
  route = { name, arg };
  render(name, arg);
}
function render(name, arg, restore) {
  routeTok++;
  if (resolving) { resolving.cancel(); resolving = null; }
  const tk = name === "cat" ? arg : name;
  $$("#tabs .tab").forEach(t => t.classList.toggle("on", t.dataset.k === tk));
  $("#top").classList.remove("hide");
  const fn = PAGES[name];
  if (!F.hold) { if (F.cur) F.cur.classList.remove("focus"); F.cur = null; }
  fn(routeTok, arg, restore);
}
window.nnBack = function () {
  if ($("#layer").classList.contains("on")) { closeLayer(); return true; }
  if (stack.length) {
    const p = stack.pop();
    route = { name: p.name, arg: p.arg };
    render(p.name, p.arg, p);
    return true;
  }
  if (route && route.name !== "home") { go("home", null, true); return true; }
  const homeTab = $('#tabs .tab[data-k="home"]');
  if (F.cur !== homeTab) {  // 首页：先回到顶部，再按一次才提示退出
    const sc = $(".scroll");
    if (sc) { sc._y = 0; sc.style.transform = "translateY(0)"; $$(".row", sc).forEach(r => r.classList.remove("past")); }
    F.set(homeTab, true);
    return true;
  }
  return false;
};
window.nnRemote = async function (act, v) {  // 手机/电脑通过局域网发来的指令
  if (act === "src") { toast("手机已修改片源，正在加载…"); await loadSources(true); toast(`片源：${SRC.list.length} 个`); if (route && route.name === "settings") render("settings"); }
  else if (act === "search") { F.hold = null; stack.length = 0; route = { name: "search", arg: v }; render("search", v); toast("收到搜索：" + v); }
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
  if (box) { const h = histAll().slice(0, 16); box.innerHTML = h.map(histCard).join(""); bindCards(box, heroFocus); }
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
    $("#page").innerHTML = `<section class="hero"><h1>妞妞影视</h1><div class="meta"></div><div class="intro">正在加载今日热门…</div><div class="hint">↑ 顶部菜单　·　OK 查看详情　·　返回键退出</div></section>
      <div class="scroll home" data-lock="row" data-pad="20.5"><div style="height:20.5rem"></div>
      ${HOME_ROWS.map((r, i) => rowHTML("row" + i, r[0], skel(8))).join("")}</div>`;
    bindCards($("#page"), heroFocus);
    let focused = false;
    const focusFirst = () => { if (focused || tok !== routeTok) return; focused = true; if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus); else F.set($("#page .card.f")); };
    const loadRow = i => {
      const r = HOME_ROWS[i];
      return dbList(r[1], r[2], r[3], 0, 24).then(d => {
        if (tok !== routeTok) return;
        const t = $(`#row${i} .track`);
        t.innerHTML = d.items.length ? d.items.map(it => card(Object.assign(it, { type: r[1] === "tv" ? "tv" : it.type }), { sub: short(it.sub).split(" · ")[0] || it.ep })).join("") : `<div class="empty">暂时拿不到豆瓣数据</div>`;
        bindCards(t, heroFocus);
        if (i === 0) { const fc = $("#page .card.f"); if (fc) heroFocus(fc); focusFirst(); }
      }).catch(() => { const t = $(`#row${i} .track`); if (t) t.innerHTML = `<div class="empty">豆瓣数据加载失败，稍后再试</div>`; });
    };
    loadRow(0).then(() => { for (let i = 1; i < HOME_ROWS.length; i++) loadRow(i); });
  },

  cat(tok, key) {
    const chips = CHIPS[key], name = { movie: "电影", tv: "剧集", anime: "动漫", show: "综艺" }[key];
    let ci = store.get("chip:" + key, 0);
    if (!chips[ci]) ci = 0;
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>${name}</h2><span id="cnt"></span></div>
      <div class="chips sec" data-g="chips">${chips.map((c, i) => `<div class="chip f${i === ci ? " on" : ""}" data-i="${i}">${c[0]}</div>`).join("")}</div>
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
        bindCards(grid, el => { setBg(el.dataset.pic); const items = $$(".card", grid); if (items.indexOf(el) > items.length - 15) load(); });
        start += d.items.length;
        end = !d.items.length || (d.total && start >= d.total);
        $("#cnt").textContent = d.total ? `共 ${d.total} 部` : "";
        if (first && !d.items.length) grid.innerHTML = `<div class="empty">暂无内容</div>`;
      } catch (e) { if (first) grid.innerHTML = `<div class="empty">豆瓣数据加载失败</div>`; }
      busy = false;
    };
    $$(".chip", $("#page")).forEach(ch => {
      ch._ok = () => {
        ci = +ch.dataset.i; store.set("chip:" + key, ci);
        $$(".chip").forEach(x => x.classList.toggle("on", x === ch));
        start = 0; end = false; busy = false; grid.innerHTML = skel(14); load(true);
      };
    });
    F.set($(".chip.on"));
    load(true);
  },

  async detail(tok, it, restore) {
    const kind = it.type === "tv" ? "tv" : "movie";
    setBg(it.pic);
    $("#page").innerHTML = `<div class="scroll" data-hidetop="1" data-pad="6"><section class="dt"><div class="poster" style="background-image:url('${esc(it.pic)}')"></div>
      <div class="info"><h1>${esc(it.title)}</h1><div class="orig"></div><div class="meta"></div><div class="crew"></div><div class="intro"></div>
      <div class="acts" data-g="acts"><div class="btn pri f" id="bPlay">${ICON_PLAY}<span>播放</span></div><div class="btn f" id="bEps" style="display:none">${ICON_LIST}<span>选集</span></div><div class="btn f" id="bIntro">${ICON_INFO}<span>简介</span></div></div>
      <div class="srcline"><i class="lamp wait"></i><span id="srcSt">正在全网找片源…</span></div></div></section>
      <section class="row eps-wrap sec" id="epsSec" style="display:none"><h3>选集 <small id="epsInfo"></small></h3><div class="ranges" data-g="ranges"></div><div class="eps" data-g="eps"></div></section>
      <div id="castRow"></div><div id="recRow"></div></div>`;
    F.set($("#bPlay"));
    const key = histKey({ id: it.id, type: kind, title: it.title });
    const st = { srcs: [], done: false, probing: 0, probed: 0 };
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
      if (!st.srcs.length) { lamp.className = "lamp " + (st.done ? "bad" : "wait"); el.textContent = st.done ? "没找到这部片的片源" : "正在全网找片源…"; return; }
      lamp.className = "lamp " + (ok.length ? "ok" : "wait");
      el.textContent = `找到 ${st.srcs.length} 个片源${st.done ? "" : "（还在找）"}` + (ok.length ? ` · 最快「${ok[0].site}」${(ok[0].bps / 1048576).toFixed(1)} MB/s` : st.probing ? " · 测速中" : "");
    };
    // 后台测速：拿到片源就测，3 路并发（原生层排队）
    const probeQ = [];
    const pump = () => {
      while (st.probing < 3 && probeQ.length) {
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
      $("#bEps").style.display = "";
      $("#epsSec").style.display = "";
      $("#epsInfo").textContent = `共 ${best.eps.length} 集${best.remarks ? " · " + best.remarks : ""}`;
      const h = hist(), cur = h ? h.ei : -1;
      const per = 30, n = best.eps.length, pages = Math.ceil(n / per);
      const drawPage = p => {
        $(".eps").innerHTML = best.eps.slice(p * per, p * per + per).map((e, i) => `<div class="ep f${p * per + i === cur ? " cur" : ""}" data-i="${p * per + i}">${esc(e.name)}</div>`).join("");
        $$(".ep").forEach(b => { b._ok = () => startPlay(+b.dataset.i, 0); });
      };
      $(".ranges").innerHTML = pages > 1 ? Array.from({ length: pages }, (_, p) => `<div class="chip f${p === Math.floor(Math.max(cur, 0) / per) ? " on" : ""}" data-p="${p}">${p * per + 1}-${Math.min(n, p * per + per)}</div>`).join("") : "";
      $$(".ranges .chip").forEach(c => { c._onfocus = () => { $$(".ranges .chip").forEach(x => x.classList.toggle("on", x === c)); drawPage(+c.dataset.p); }; });
      drawPage(Math.floor(Math.max(cur, 0) / per));
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
      resolving = resolve(meta.title, meta.year, found);
      resolving.done.then(() => { if (tok === routeTok) { st.done = true; status(); } });
    };

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
      const need = ei + 1, maxEp = Math.max.apply(null, st.srcs.map(s => s.eps.length));
      const score = s => (s.bps > 0 ? 3e9 + s.bps : s.bps == null ? 1e9 - s.ms - s.rank * 30 : 0);
      const list = st.srcs.slice().sort((a, b) => {
        const ha = a.eps.length >= need ? 1 : 0, hb = b.eps.length >= need ? 1 : 0;
        if (ha !== hb) return hb - ha;
        if (kind === "tv" && maxEp > 1) { const fa = a.eps.length >= maxEp * 0.9 ? 1 : 0, fb = b.eps.length >= maxEp * 0.9 ? 1 : 0; if (fa !== fb && (a.bps > 0) === (b.bps > 0)) return fb - fa; }
        return score(b) - score(a);
      });
      closeLayer();
      const data = { key, title: meta.title, pic: meta.pic || it.pic, id: it.id || "", type: kind, year: meta.year || "", si: 0, ei, pos: pos || 0,
        sources: list.map(s => ({ site: s.site, eps: s.eps, bps: s.bps == null ? 0 : s.bps })) };
      if (APP) NN.play(JSON.stringify(data)); else toast(`将播放「${list[0].site}」${list[0].eps[Math.min(ei, list[0].eps.length - 1)].name}`);
    }
    $("#bPlay")._ok = () => { const h = hist(); startPlay(h ? h.ei : 0, h ? h.pos : 0); };
    $("#bEps")._ok = () => F.set($(".ep.cur") || $(".ep"));
    $("#bIntro")._ok = () => openLayer(`<div class="panel"><h2>${esc(meta.title)}</h2><p>${esc(meta.intro || "暂无简介")}</p><div class="acts" style="margin-top:1.6rem"><div class="btn f">关闭</div></div></div>`);
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
      const recs = await dbRecs(kind, it.id);
      if (tok !== routeTok || !recs.length) return;
      $("#recRow").innerHTML = rowHTML("rowRec", "相关推荐", recs.map(r => card(r, { sub: short(r.sub).split(" · ")[0] })).join(""));
      bindCards($("#recRow"));
      if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus);
    } catch (e) {
      $(".dt .intro").textContent = "豆瓣详情加载失败";
    }
  },

  search(tok, q) {
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>搜索</h2><span>按 OK 弹出输入法，支持语音输入</span></div>
      <div class="sbox f sec" id="sbox" data-g="sb">${ICON_SEARCH}<input id="q" placeholder="片名 / 演员 / 导演" value="${esc(q || "")}"></div>
      <div id="sres"></div></div>`;
    const input = $("#q"), box = $("#sbox");
    let timer = 0, my = 0;
    const run = async () => {
      const v = input.value.trim(), me = ++my;
      if (!v) { hot(); return; }
      $("#sres").innerHTML = `<div class="grid">${skel(7)}</div>`;
      try {
        const items = await dbSearch(v);
        if (tok !== routeTok || me !== my) return;
        $("#sres").innerHTML = items.length ? `<div class="grid">${items.map(it => card(it, { sub: String(it.sub).split(" / ")[0], cls: "grid-item" })).join("")}</div>`
          : `<div class="empty">豆瓣没搜到「${esc(v)}」，换个说法试试</div>`;
        bindCards($("#sres"), el => setBg(el.dataset.pic));
      } catch (e) { $("#sres").innerHTML = `<div class="empty">搜索失败，稍后再试</div>`; }
    };
    const hot = () => {
      dbList("movie", "热门", "全部", 0, 14).then(d => {
        if (tok !== routeTok || input.value.trim()) return;
        $("#sres").innerHTML = `<div class="row" style="padding-top:.6rem"><h3>大家都在看</h3></div><div class="grid">${d.items.map(it => card(it, { sub: short(it.sub).split(" · ")[0], cls: "grid-item" })).join("")}</div>`;
        bindCards($("#sres"), el => setBg(el.dataset.pic));
      }).catch(() => {});
    };
    input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(run, 700); });
    input.addEventListener("keydown", e => { if (e.keyCode === 13) { e.preventDefault(); input.blur(); run(); const c = $("#sres .card"); if (c) setTimeout(() => F.set(c), 400); } });
    box._ok = () => { input.focus(); };
    F.set(box);
    if (q) run(); else hot();
  },

  history(tok) {
    const list = histAll();
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>观看记录</h2><span>${list.length ? "按菜单键删除当前这条" : ""}</span></div>
      <div class="grid">${list.length ? list.map(h => histCard(h, "grid-item")).join("") : `<div class="empty">还没有看过的片子</div>`}</div></div>`;
    bindCards($("#page"), el => setBg(el.dataset.pic));
    $$("#page .card").forEach((c, i) => { c._menu = () => { if (APP) NN.delHistory(list[i].key); toast("已删除"); render("history"); }; });
    F.set($("#page .card") || $("#tabs .tab.on"));
  },

  async settings(tok) {
    await loadSources();
    if (tok !== routeTok) return;
    const custom = cfg.get("srcUrl", "");
    let lan = null; try { lan = APP && NN.lan ? JSON.parse(NN.lan()) : null; } catch (e) {}
    $("#page").innerHTML = `${lan && lan.url ? `<div class="lan"><b>手机 / 电脑扫码设置</b><img src="${lan.qr}"><div class="u">${esc(lan.url)}</div><small>同一局域网内用浏览器打开<br>可改片源、推送搜索、清记录</small></div>` : `<div class="lan"><b>局域网设置</b><small>没连上局域网，暂不可用</small></div>`}
      <div class="scroll setwrap" data-pad="6"><div class="pagehead sec"><h2>设置</h2><span>版本 ${APP ? esc(NN.version()) : "dev"}</span></div>
      <div class="setrow f" id="sRefresh"><span>刷新片源</span><small>${SRC.list.length} 个采集站 · ${esc(SRC.from.replace(/^https?:\/\//, "").slice(0, 60))}</small></div>
      <div class="setrow f" id="sUrl"><span>片源地址</span><small>${esc(custom || "默认：zknjjjx/tvbox 的 cmstv.json（自动加速）")}</small></div>
      <div class="setrow f" id="sReset"><span>恢复默认片源</span><small>清掉自定义地址</small></div>
      <div class="setrow f" id="sFx"><span>背景动效</span><small>${cfg.get("fx", "1") === "0" ? "已关闭（更省性能）" : "已开启 · 老电视卡顿可关闭"}</small></div>
      <div class="setrow f" id="sClear"><span>清除观看记录</span><small>${histAll().length} 条</small></div>
      <div class="setrow" style="opacity:.6"><span>播放说明</span><small>自动测速挑最快源；起播 12 秒没画面或卡住 20 秒自动换源；播放中按 ▲ 选集换源</small></div></div>`;
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
    $("#sFx")._ok = () => { const on = cfg.get("fx", "1") === "0"; cfg.set("fx", on ? "1" : "0"); document.body.classList.toggle("nofx", !on); toast(on ? "背景动效已开启" : "背景动效已关闭"); render("settings"); F.set($("#sFx")); };
    $("#sClear")._ok = () => { histAll().forEach(h => APP && NN.delHistory(h.key)); toast("已清除"); render("settings"); };
    F.set($("#sRefresh"));
  },
};
let resolving = null;

/* ---------------- 按键 ---------------- */
document.addEventListener("keydown", e => {
  const k = e.keyCode;
  const inInput = document.activeElement && document.activeElement.tagName === "INPUT";
  if (inInput && (k === 37 || k === 39)) return;  // 输入框里左右移动光标
  if (k === 37 || k === 38 || k === 39 || k === 40) {
    e.preventDefault();
    if (inInput) document.activeElement.blur();
    const dir = { 37: "left", 38: "up", 39: "right", 40: "down" }[k];
    const before = F.cur;
    F.user = true;
    if (dir === "down" && before && before.closest("#tabs")) {  // 菜单往下：直接进片子
      const tgt = $("#grid .card.f") || $("#page .card.f") || null;
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
function clock() { const d = new Date(); $("#clock").textContent = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; }
function boot() {
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
      if (!route || route.name !== (k in CHIPS ? "cat" : k) || stack.length) { open(false); return; }
      F.hold = null; const c = $("#page .f.focus") || $("#page .card.f") || $("#page .f"); if (route && route.name !== "search" && c) F.set(c); else open(false); };
    t._onfocus = () => { if (!(F.prev && F.prev.closest("#tabs"))) return; clearTimeout(open._t); open._t = setTimeout(() => { if (F.cur === t) open(true); }, 280); };
  });
  clock(); setInterval(clock, 20000);
  loadSources();
  route = { name: "home" };
  render("home");
}
if (!String.prototype.padStart) String.prototype.padStart = function (n, c) { let s = String(this); while (s.length < n) s = c + s; return s; };
if (!Array.prototype.find) Array.prototype.find = function (f) { for (let i = 0; i < this.length; i++) if (f(this[i], i)) return this[i]; };
if (!Element.prototype.closest) Element.prototype.closest = function (s) { let el = this; while (el && el.nodeType === 1) { if (el.matches ? el.matches(s) : el.webkitMatchesSelector(s)) return el; el = el.parentElement; } return null; };
boot();
