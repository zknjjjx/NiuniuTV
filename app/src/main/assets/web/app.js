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
    const custom = store.get("srcUrl", "") || DEF_SRC;
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
    return SRC;
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
function resolve(name, year, onFound) {
  const want = canon(name);
  let alive = true, idx = 0, running = 0;
  const sites = SRC.list.slice();
  return {
    cancel() {
      alive = false;
    },
    done: new Promise((done) => {
      if (!sites.length) return done();
      const next = () => {
        if (!alive) return done();
        if (idx >= sites.length && running === 0) return done();
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
  set(el, quiet) {
    if (!el) return;
    if (F.cur) F.cur.classList.remove("focus");
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
  reveal(el) {
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
      if (sc.dataset.lock === "row") {
        y = Math.max(0, top - topPad);
        $$(".row", sc).forEach((r) => r.classList.toggle("past", r.offsetTop < anchor.offsetTop));
      }
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
    const all = $$(".f", F.scope()).filter(F.visible);
    if (!cur) {
      F.set(all[0]);
      return;
    }
    const r0 = cur.getBoundingClientRect();
    const cx = r0.left + r0.width / 2, cy = r0.top + r0.height / 2;
    const g0 = cur.closest("[data-g]");
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
      if (horiz && g0 && el.closest("[data-g]") !== g0) return;
      const s = main + cross * (horiz ? 4 : 1.2);
      if (s < bs) {
        bs = s;
        best = el;
      }
    });
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
  remPx = window.innerWidth / 96;
  document.documentElement.style.fontSize = remPx + "px";
}
let bgI = 0, bgUrl = "";
function setBg(url) {
  if (!url || url === bgUrl) return;
  bgUrl = url;
  const ls = $$("#bg .bgl"), img = new Image();
  img.onload = () => {
    if (bgUrl !== url) return;
    bgI ^= 1;
    ls[bgI].style.backgroundImage = `url("${url}")`;
    ls[bgI].classList.add("on");
    ls[bgI ^ 1].classList.remove("on");
  };
  img.src = url;
}
const TABS = [["home", "\u9996\u9875"], ["movie", "\u7535\u5F71"], ["tv", "\u5267\u96C6"], ["anime", "\u52A8\u6F2B"], ["show", "\u7EFC\u827A"], ["search", "\u641C\u7D22"], ["history", "\u89C2\u770B\u8BB0\u5F55"], ["settings", "\u8BBE\u7F6E"]];
const stack = [];
let route = null, routeTok = 0;
function go(name, arg, replace) {
  if (route && !replace) stack.push({ name: route.name, arg: route.arg, focus: F.cur, y: ($(".scroll") || {})._y });
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
  const fn = PAGES[name];
  if (F.cur) F.cur.classList.remove("focus");
  F.cur = null;
  fn(routeTok, arg, restore);
}
window.nnBack = function() {
  if ($("#layer").classList.contains("on")) {
    closeLayer();
    return true;
  }
  if (stack.length) {
    const p = stack.pop();
    route = { name: p.name, arg: p.arg };
    render(p.name, p.arg, p);
    return true;
  }
  if (route && route.name !== "home") {
    go("home", null, true);
    return true;
  }
  return false;
};
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
    bindCards(box, heroFocus);
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
    setBg((it.pic || "").replace("/s_ratio_poster/", "/l_ratio_poster/").replace("/m_ratio_poster/", "/l_ratio_poster/"));
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
    const hist = histAll().slice(0, 16);
    $("#page").innerHTML = `<section class="hero"><h1>\u599E\u599E\u5F71\u89C6</h1><div class="meta"></div><div class="intro">\u6B63\u5728\u52A0\u8F7D\u4ECA\u65E5\u70ED\u95E8\u2026</div><div class="hint">\u2191 \u9876\u90E8\u83DC\u5355\u3000\xB7\u3000OK \u67E5\u770B\u8BE6\u60C5\u3000\xB7\u3000\u8FD4\u56DE\u952E\u9000\u51FA</div></section>
      <div class="scroll home" data-lock="row" data-pad="29.5"><div style="height:29.5rem"></div>
      ${rowHTML("rowHist", "\u7EE7\u7EED\u89C2\u770B", hist.map(histCard).join(""))}
      ${HOME_ROWS.map((r, i) => rowHTML("row" + i, r[0], skel(8))).join("")}</div>`;
    if (!hist.length) $("#rowHist").style.display = "none";
    bindCards($("#page"), heroFocus);
    let focused = false;
    const focusFirst = () => {
      if (focused || tok !== routeTok) return;
      focused = true;
      if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus);
      else F.set($("#page .card.f"));
    };
    if (hist.length) focusFirst();
    const loadRow = (i) => {
      const r = HOME_ROWS[i];
      return dbList(r[1], r[2], r[3], 0, 24).then((d) => {
        if (tok !== routeTok) return;
        const t = $(`#row${i} .track`);
        t.innerHTML = d.items.length ? d.items.map((it) => card(Object.assign(it, { type: r[1] === "tv" ? "tv" : it.type }), { sub: short(it.sub).split(" \xB7 ")[0] || it.ep })).join("") : `<div class="empty">\u6682\u65F6\u62FF\u4E0D\u5230\u8C46\u74E3\u6570\u636E</div>`;
        bindCards(t, heroFocus);
        if (i === 0) focusFirst();
      }).catch(() => {
        const t = $(`#row${i} .track`);
        if (t) t.innerHTML = `<div class="empty">\u8C46\u74E3\u6570\u636E\u52A0\u8F7D\u5931\u8D25\uFF0C\u7A0D\u540E\u518D\u8BD5</div>`;
      });
    };
    loadRow(0).then(() => {
      for (let i = 1; i < HOME_ROWS.length; i++) loadRow(i);
    });
  },
  cat(tok, key) {
    const chips = CHIPS[key], name = { movie: "\u7535\u5F71", tv: "\u5267\u96C6", anime: "\u52A8\u6F2B", show: "\u7EFC\u827A" }[key];
    let ci = store.get("chip:" + key, 0);
    if (!chips[ci]) ci = 0;
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>${name}</h2><span id="cnt"></span></div>
      <div class="chips sec" data-g="chips">${chips.map((c, i) => `<div class="chip f${i === ci ? " on" : ""}" data-i="${i}">${c[0]}</div>`).join("")}</div>
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
          setBg(el.dataset.pic);
          const items = $$(".card", grid);
          if (items.indexOf(el) > items.length - 15) load();
        });
        start += d.items.length;
        end = !d.items.length || d.total && start >= d.total;
        $("#cnt").textContent = d.total ? `\u5171 ${d.total} \u90E8` : "";
        if (first && !d.items.length) grid.innerHTML = `<div class="empty">\u6682\u65E0\u5185\u5BB9</div>`;
      } catch (e) {
        if (first) grid.innerHTML = `<div class="empty">\u8C46\u74E3\u6570\u636E\u52A0\u8F7D\u5931\u8D25</div>`;
      }
      busy = false;
    });
    $$(".chip", $("#page")).forEach((ch) => {
      ch._ok = () => {
        ci = +ch.dataset.i;
        store.set("chip:" + key, ci);
        $$(".chip").forEach((x) => x.classList.toggle("on", x === ch));
        start = 0;
        end = false;
        busy = false;
        grid.innerHTML = skel(14);
        load(true);
      };
    });
    F.set($(".chip.on"));
    load(true);
  },
  detail(tok, it, restore) {
    return __async(this, null, function* () {
      const kind = it.type === "tv" ? "tv" : "movie";
      setBg(it.pic);
      $("#page").innerHTML = `<div class="scroll" data-hidetop="1" data-pad="6"><section class="dt"><div class="poster" style="background-image:url('${esc(it.pic)}')"></div>
      <div class="info"><h1>${esc(it.title)}</h1><div class="orig"></div><div class="meta"></div><div class="crew"></div><div class="intro"></div>
      <div class="acts" data-g="acts"><div class="btn pri f" id="bPlay">${ICON_PLAY}<span>\u64AD\u653E</span></div><div class="btn f" id="bEps" style="display:none">${ICON_LIST}<span>\u9009\u96C6</span></div><div class="btn f" id="bIntro">${ICON_INFO}<span>\u7B80\u4ECB</span></div></div>
      <div class="srcline"><i class="lamp wait"></i><span id="srcSt">\u6B63\u5728\u5168\u7F51\u627E\u7247\u6E90\u2026</span></div></div></section>
      <section class="row eps-wrap sec" id="epsSec" style="display:none"><h3>\u9009\u96C6 <small id="epsInfo"></small></h3><div class="ranges" data-g="ranges"></div><div class="eps" data-g="eps"></div></section>
      <div id="castRow"></div><div id="recRow"></div></div>`;
      F.set($("#bPlay"));
      const key = histKey({ id: it.id, type: kind, title: it.title });
      const st = { srcs: [], done: false, probing: 0, probed: 0 };
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
          el.textContent = st.done ? "\u6CA1\u627E\u5230\u8FD9\u90E8\u7247\u7684\u7247\u6E90" : "\u6B63\u5728\u5168\u7F51\u627E\u7247\u6E90\u2026";
          return;
        }
        lamp.className = "lamp " + (ok.length ? "ok" : "wait");
        el.textContent = `\u627E\u5230 ${st.srcs.length} \u4E2A\u7247\u6E90${st.done ? "" : "\uFF08\u8FD8\u5728\u627E\uFF09"}` + (ok.length ? ` \xB7 \u6700\u5FEB\u300C${ok[0].site}\u300D${(ok[0].bps / 1048576).toFixed(1)} MB/s` : st.probing ? " \xB7 \u6D4B\u901F\u4E2D" : "");
      };
      const probeQ = [];
      const pump = () => {
        while (st.probing < 3 && probeQ.length) {
          const s = probeQ.shift();
          st.probing++;
          probe(s.eps[0].url).then((r) => {
            s.bps = r.bps;
            s.h = r.h || 0;
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
        $("#bEps").style.display = "";
        $("#epsSec").style.display = "";
        $("#epsInfo").textContent = `\u5171 ${best.eps.length} \u96C6${best.remarks ? " \xB7 " + best.remarks : ""}`;
        const h = hist(), cur = h ? h.ei : -1;
        const per = 30, n = best.eps.length, pages = Math.ceil(n / per);
        const drawPage = (p) => {
          $(".eps").innerHTML = best.eps.slice(p * per, p * per + per).map((e, i) => `<div class="ep f${p * per + i === cur ? " cur" : ""}" data-i="${p * per + i}">${esc(e.name)}</div>`).join("");
          $$(".ep").forEach((b) => {
            b._ok = () => startPlay(+b.dataset.i, 0);
          });
        };
        $(".ranges").innerHTML = pages > 1 ? Array.from({ length: pages }, (_, p) => `<div class="chip f${p === Math.floor(Math.max(cur, 0) / per) ? " on" : ""}" data-p="${p}">${p * per + 1}-${Math.min(n, p * per + per)}</div>`).join("") : "";
        $$(".ranges .chip").forEach((c) => {
          c._onfocus = () => {
            $$(".ranges .chip").forEach((x) => x.classList.toggle("on", x === c));
            drawPage(+c.dataset.p);
          };
        });
        drawPage(Math.floor(Math.max(cur, 0) / per));
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
        resolving = resolve(meta.title, meta.year, found);
        resolving.done.then(() => {
          if (tok === routeTok) {
            st.done = true;
            status();
          }
        });
      };
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
          const need = ei + 1, maxEp = Math.max.apply(null, st.srcs.map((s) => s.eps.length));
          const score = (s) => s.bps > 0 ? 3e9 + s.bps : s.bps == null ? 1e9 - s.ms - s.rank * 30 : 0;
          const list = st.srcs.slice().sort((a, b) => {
            const ha = a.eps.length >= need ? 1 : 0, hb = b.eps.length >= need ? 1 : 0;
            if (ha !== hb) return hb - ha;
            if (kind === "tv" && maxEp > 1) {
              const fa = a.eps.length >= maxEp * 0.9 ? 1 : 0, fb = b.eps.length >= maxEp * 0.9 ? 1 : 0;
              if (fa !== fb && a.bps > 0 === b.bps > 0) return fb - fa;
            }
            return score(b) - score(a);
          });
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
            sources: list.map((s) => ({ site: s.site, eps: s.eps, bps: s.bps == null ? 0 : s.bps }))
          };
          if (APP) NN.play(JSON.stringify(data));
          else toast(`\u5C06\u64AD\u653E\u300C${list[0].site}\u300D${list[0].eps[Math.min(ei, list[0].eps.length - 1)].name}`);
        });
      }
      $("#bPlay")._ok = () => {
        const h = hist();
        startPlay(h ? h.ei : 0, h ? h.pos : 0);
      };
      $("#bEps")._ok = () => F.set($(".ep.cur") || $(".ep"));
      $("#bIntro")._ok = () => openLayer(`<div class="panel"><h2>${esc(meta.title)}</h2><p>${esc(meta.intro || "\u6682\u65E0\u7B80\u4ECB")}</p><div class="acts" style="margin-top:1.6rem"><div class="btn f">\u5173\u95ED</div></div></div>`);
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
        const recs = yield dbRecs(kind, it.id);
        if (tok !== routeTok || !recs.length) return;
        $("#recRow").innerHTML = rowHTML("rowRec", "\u76F8\u5173\u63A8\u8350", recs.map((r) => card(r, { sub: short(r.sub).split(" \xB7 ")[0] })).join(""));
        bindCards($("#recRow"));
        if (restore && restore.focus && document.contains(restore.focus)) F.set(restore.focus);
      } catch (e) {
        $(".dt .intro").textContent = "\u8C46\u74E3\u8BE6\u60C5\u52A0\u8F7D\u5931\u8D25";
      }
    });
  },
  search(tok, q) {
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>\u641C\u7D22</h2><span>\u6309 OK \u5F39\u51FA\u8F93\u5165\u6CD5\uFF0C\u652F\u6301\u8BED\u97F3\u8F93\u5165</span></div>
      <div class="sbox f sec" id="sbox" data-g="sb">${ICON_SEARCH}<input id="q" placeholder="\u7247\u540D / \u6F14\u5458 / \u5BFC\u6F14" value="${esc(q || "")}"></div>
      <div id="sres"></div></div>`;
    const input = $("#q"), box = $("#sbox");
    let timer = 0, my = 0;
    const run = () => __async(this, null, function* () {
      const v = input.value.trim(), me = ++my;
      if (!v) {
        hot();
        return;
      }
      $("#sres").innerHTML = `<div class="grid">${skel(7)}</div>`;
      try {
        const items = yield dbSearch(v);
        if (tok !== routeTok || me !== my) return;
        $("#sres").innerHTML = items.length ? `<div class="grid">${items.map((it) => card(it, { sub: String(it.sub).split(" / ")[0], cls: "grid-item" })).join("")}</div>` : `<div class="empty">\u8C46\u74E3\u6CA1\u641C\u5230\u300C${esc(v)}\u300D\uFF0C\u6362\u4E2A\u8BF4\u6CD5\u8BD5\u8BD5</div>`;
        bindCards($("#sres"), (el) => setBg(el.dataset.pic));
      } catch (e) {
        $("#sres").innerHTML = `<div class="empty">\u641C\u7D22\u5931\u8D25\uFF0C\u7A0D\u540E\u518D\u8BD5</div>`;
      }
    });
    const hot = () => {
      dbList("movie", "\u70ED\u95E8", "\u5168\u90E8", 0, 14).then((d) => {
        if (tok !== routeTok || input.value.trim()) return;
        $("#sres").innerHTML = `<div class="row" style="padding-top:.6rem"><h3>\u5927\u5BB6\u90FD\u5728\u770B</h3></div><div class="grid">${d.items.map((it) => card(it, { sub: short(it.sub).split(" \xB7 ")[0], cls: "grid-item" })).join("")}</div>`;
        bindCards($("#sres"), (el) => setBg(el.dataset.pic));
      }).catch(() => {
      });
    };
    input.addEventListener("input", () => {
      clearTimeout(timer);
      timer = setTimeout(run, 700);
    });
    input.addEventListener("keydown", (e) => {
      if (e.keyCode === 13) {
        e.preventDefault();
        input.blur();
        run();
        const c = $("#sres .card");
        if (c) setTimeout(() => F.set(c), 400);
      }
    });
    box._ok = () => {
      input.focus();
    };
    F.set(box);
    if (q) run();
    else hot();
  },
  history(tok) {
    const list = histAll();
    $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>\u89C2\u770B\u8BB0\u5F55</h2><span>${list.length ? "\u6309\u83DC\u5355\u952E\u5220\u9664\u5F53\u524D\u8FD9\u6761" : ""}</span></div>
      <div class="grid">${list.length ? list.map((h) => histCard(h, "grid-item")).join("") : `<div class="empty">\u8FD8\u6CA1\u6709\u770B\u8FC7\u7684\u7247\u5B50</div>`}</div></div>`;
    bindCards($("#page"), (el) => setBg(el.dataset.pic));
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
      const custom = store.get("srcUrl", "");
      $("#page").innerHTML = `<div class="scroll" data-pad="6"><div class="pagehead sec"><h2>\u8BBE\u7F6E</h2><span>\u7248\u672C ${APP ? esc(NN.version()) : "dev"}</span></div>
      <div class="setrow f" id="sRefresh"><span>\u5237\u65B0\u7247\u6E90</span><small>${SRC.list.length} \u4E2A\u91C7\u96C6\u7AD9 \xB7 ${esc(SRC.from.replace(/^https?:\/\//, "").slice(0, 60))}</small></div>
      <div class="setrow f" id="sUrl"><span>\u7247\u6E90\u5730\u5740</span><small>${esc(custom || "\u9ED8\u8BA4\uFF1Azknjjjx/tvbox \u7684 cmstv.json\uFF08\u81EA\u52A8\u52A0\u901F\uFF09")}</small></div>
      <div class="setrow f" id="sReset"><span>\u6062\u590D\u9ED8\u8BA4\u7247\u6E90</span><small>\u6E05\u6389\u81EA\u5B9A\u4E49\u5730\u5740</small></div>
      <div class="setrow f" id="sClear"><span>\u6E05\u9664\u89C2\u770B\u8BB0\u5F55</span><small>${histAll().length} \u6761</small></div>
      <div class="setrow" style="opacity:.6"><span>\u64AD\u653E\u8BF4\u660E</span><small>\u81EA\u52A8\u6D4B\u901F\u6311\u6700\u5FEB\u6E90\uFF1B\u8D77\u64AD 12 \u79D2\u6CA1\u753B\u9762\u6216\u5361\u4F4F 20 \u79D2\u81EA\u52A8\u6362\u6E90\uFF1B\u64AD\u653E\u4E2D\u6309 \u25B2 \u9009\u96C6\u6362\u6E90</small></div></div>`;
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
          store.set("srcUrl", v === DEF_SRC ? "" : v);
          closeLayer();
          toast("\u6B63\u5728\u52A0\u8F7D\u65B0\u7247\u6E90\u2026");
          yield loadSources(true);
          toast(`\u7247\u6E90\uFF1A${SRC.list.length} \u4E2A`);
          render("settings");
        });
      };
      $("#sReset")._ok = () => __async(this, null, function* () {
        store.set("srcUrl", "");
        yield loadSources(true);
        toast("\u5DF2\u6062\u590D\u9ED8\u8BA4\u7247\u6E90");
        render("settings");
      });
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
document.addEventListener("keydown", (e) => {
  const k = e.keyCode;
  const inInput = document.activeElement && document.activeElement.tagName === "INPUT";
  if (inInput && (k === 37 || k === 39)) return;
  if (k === 37 || k === 38 || k === 39 || k === 40) {
    e.preventDefault();
    if (inInput) document.activeElement.blur();
    const dir = { 37: "left", 38: "up", 39: "right", 40: "down" }[k];
    const before = F.cur;
    F.move(dir);
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
function clock() {
  const d = /* @__PURE__ */ new Date();
  $("#clock").textContent = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
function boot() {
  fit();
  window.addEventListener("resize", fit);
  $("#tabs").innerHTML = TABS.map((t) => `<div class="tab f" data-k="${t[0]}">${t[1]}</div>`).join("");
  $$("#tabs .tab").forEach((t) => {
    t._ok = () => {
      const k = t.dataset.k;
      stack.length = 0;
      route = { name: k in CHIPS ? "cat" : k, arg: k in CHIPS ? k : null };
      render(route.name, route.arg);
    };
  });
  clock();
  setInterval(clock, 2e4);
  loadSources();
  route = { name: "home" };
  render("home");
}
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
