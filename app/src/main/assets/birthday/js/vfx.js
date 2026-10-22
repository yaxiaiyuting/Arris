/* ─────────────────────────────────────────────────────────────
   js/vfx.js · 技术美术 / VFX TD  ·  v2（双 SVG 合成层）
   火焰 · 轻烟 · 吹气 · 七发烟花 · 前后景浮尘

   合成层（由 Lead 维护，见 index.html）
   ─────────────────────────────────────────────────────────────
   z3  #svg-env   天空/极光/远景/中景
   z4  #fx-back   烟花 · 轻烟 · 远浮尘 · 升空尾焰        ← 本文件主战场
   z5  #svg-scene 地面/蛋糕/Tux/近景/极景（火焰是这里面的 #flame-*）
   z6  #fx-front  吹气弧 · 近景大颗浮尘/花粉 · 落到镜头前的火星

   设计要点
   ─────────────────────────────────────────────────────────────
   1) 确定性时间寻址
      seek(t) 把 t 量化成整数步 k = round(t*60)，从 0 以 1/60 步长重放；
      反向 seek 直接 reset 再重放。随机数全部来自 mulberry32 固定种子，
      每次重放前重置 → 同一 t 的状态/像素完全一致。
      渲染里的程序化部分（火焰形变、烟、浮尘）是 t 的纯函数，不依赖历史。
   2) 火焰：每帧用 4 段三次贝塞尔生成 path d（3 个不可公约频率的脉动、
      左右不对称的腹部、会甩动的尖端、受风倾倒），外焰 + 内焰 + 蓝色小核 + 光晕。
      锚点链：#wick-i → #candle-i 本体顶中 → #flame-unit-i 底中 → 契约坐标。
   3) 轻烟：canvas 描边 + setLineDash 生长 + 上飘 + 旋转扩散，逐支错峰，
      CUES.smokeEnd 前归零。
   4) 吹气：从 #tux-beak bbox 中心出发的 3 段同心弧（双频透明度扰动），
      0.5s 扫过蜡烛；画在 front（在角色之上，读得出"从鸟喙吹过去"）。
   5) 烟花：升空(减速弧 + 彗尾抖动) → 按 kind 爆开，阻力/重力/亮度衰减，
      globalCompositeOperation='lighter'，辉光精灵离屏预生成。
   6) 预算：主星 ≤2400 / 火星 ≤900 / 同时活跃 ≤3600（池 4600，实际峰值 ~1.2k）。
   7) bday.reduced：不跑粒子循环，火焰给静态形状。
   ───────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  /* ═══════════════ 0 · 工具 ═══════════════ */
  const DT = 1 / 60;
  const W = 1600;
  let H = 900;
  const TAU = Math.PI * 2;
  const NS = 'http://www.w3.org/2000/svg';

  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const lerp = (a, b, u) => a + (b - a) * u;
  const smooth = (u) => { u = clamp(u, 0, 1); return u * u * (3 - 2 * u); };
  const easeOut = (u) => 1 - Math.pow(1 - clamp(u, 0, 1), 3);
  const f2 = (v) => Math.round(v * 100) / 100;

  function mulberry32(a) {
    a = a >>> 0;
    return function () {
      a = (a + 0x6d2b79f5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), 1 | t);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const SIM_SEED = 0x1a2b3c4d;
  let rng = mulberry32(SIM_SEED);
  const rr = (a, b) => a + (b - a) * rng();
  const resetRng = () => { rng = mulberry32(SIM_SEED); };
  const dustRng = mulberry32(0xc0ffee11);   // 独立随机流：浮尘参数

  function hex2rgb(h) {
    if (!h) return [255, 255, 255];
    h = String(h).trim();
    if (h[0] === '#') h = h.slice(1);
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    const n = parseInt(h, 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  /* ═══════════════ 1 · 预生成辉光精灵 ═══════════════ */
  const SPR = [], SPR_SOFT = [], SPR_HEX = [];
  const sprMap = new Map();

  function makeSprite(hex, soft) {
    const S = 64;
    const c = document.createElement('canvas');
    c.width = c.height = S;
    const g = c.getContext('2d');
    const [r, gg, b] = hex2rgb(hex);
    const rad = g.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2);
    if (soft) {
      rad.addColorStop(0.00, `rgba(${r},${gg},${b},0.62)`);
      rad.addColorStop(0.22, `rgba(${r},${gg},${b},0.30)`);
      rad.addColorStop(0.52, `rgba(${r},${gg},${b},0.09)`);
      rad.addColorStop(1.00, `rgba(${r},${gg},${b},0)`);
    } else {
      // 白热芯占半径 ~20%，外面才是颜色 —— 太小会变成"1px 淡线"
      rad.addColorStop(0.00, 'rgba(255,255,255,1)');
      rad.addColorStop(0.10, 'rgba(255,255,255,1)');
      rad.addColorStop(0.17, `rgba(${r},${gg},${b},0.96)`);
      rad.addColorStop(0.30, `rgba(${r},${gg},${b},0.46)`);
      rad.addColorStop(0.50, `rgba(${r},${gg},${b},0.14)`);
      rad.addColorStop(0.75, `rgba(${r},${gg},${b},0.035)`);
      rad.addColorStop(1.00, `rgba(${r},${gg},${b},0)`);
    }
    g.fillStyle = rad;
    g.fillRect(0, 0, S, S);
    return c;
  }

  function brighten(hex, k) {
    const [r, g, b] = hex2rgb(hex);
    const m = (v) => Math.round(v + (255 - v) * k);
    return 'rgb(' + m(r) + ',' + m(g) + ',' + m(b) + ')';
  }

  let SPR_HOT = 0;

  /* 爆点亮芯专用：中心纯白不透明，0.55 半径处仍 ~0.88（224/255），
     让"近白像素"有面积，同时 >240 的连通块直径可控在 ~60px */
  let CORE_SPR = null;
  function coreSprite() {
    if (CORE_SPR) return CORE_SPR;
    const S = 128, c = document.createElement('canvas');
    c.width = c.height = S;
    const g = c.getContext('2d');
    const rad = g.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2);
    rad.addColorStop(0.00, 'rgba(232,255,248,1)');
    rad.addColorStop(0.12, 'rgba(232,255,248,1)');
    rad.addColorStop(0.20, 'rgba(240,255,250,0.92)');
    rad.addColorStop(0.32, 'rgba(255,244,220,0.55)');
    rad.addColorStop(0.50, 'rgba(255,238,206,0.22)');
    rad.addColorStop(0.72, 'rgba(255,236,200,0.06)');
    rad.addColorStop(1.00, 'rgba(255,236,200,0)');
    g.fillStyle = rad;
    g.fillRect(0, 0, S, S);
    CORE_SPR = c;
    return c;
  }

  function tint(hex) {
    const key = String(hex || '#ffffff').toLowerCase();
    let i = sprMap.get(key);
    if (i === undefined) {
      i = SPR.length;
      SPR.push(makeSprite(key, false));
      SPR_SOFT.push(makeSprite(key, true));
      SPR_HEX.push(key);
      sprMap.set(key, i);
    }
    return i;
  }

  /* ═══════════════ 2 · 粒子池（SoA） ═══════════════ */
  const CAP = 4600;
  const P_x = new Float32Array(CAP), P_y = new Float32Array(CAP),
    P_vx = new Float32Array(CAP), P_vy = new Float32Array(CAP),
    P_age = new Float32Array(CAP), P_ttl = new Float32Array(CAP),
    P_sz = new Float32Array(CAP), P_drag = new Float32Array(CAP),
    P_grav = new Float32Array(CAP), P_lum = new Float32Array(CAP),
    P_tw = new Float32Array(CAP), P_tws = new Float32Array(CAP),
    P_trail = new Float32Array(CAP),
    P_split = new Float32Array(CAP), P_splA = new Float32Array(CAP),
    P_sway = new Float32Array(CAP),
    P_sx = new Float32Array(CAP), P_sy = new Float32Array(CAP),
    P_wid = new Float32Array(CAP), P_slen = new Float32Array(CAP),
    P_dec = new Float32Array(CAP);
  const P_sp = new Int16Array(CAP);
  const P_sty = new Uint8Array(CAP);   // 0 = 精灵+尾迹，1 = 沿速度的短条（ring 用）
  const P_kind = new Uint8Array(CAP);   // 0 主星 1 火星/余烬 2 尘团
  const P_splN = new Uint8Array(CAP);
  const P_alive = new Uint8Array(CAP);
  const FREE = new Int32Array(CAP);
  let freeN = 0, nSlot = 0, live = 0, peak = 0;
  let stars = 0, sparks = 0;

  const S_ = { x: 0, y: 0, vx: 0, vy: 0, ttl: 1, sz: 8, drag: 0.6, grav: 40,
    lum: 1, tw: 0, tws: 6, trail: 0.02, sp: 0, kind: 0,
    spl: 0, splA: 0, splN: 0, sway: 0, sx: 1, sy: 0, sty: 0, wid: 1.8, slen: 6, dec: 0.42 };

  function spawn() {
    if (live >= 3600) return -1;                       // 全局同时活跃
    if (S_.kind === 0 && stars >= 2400) return -1;     // 主星预算
    if (S_.kind === 1 && sparks >= 900) return -1;     // 火星预算
    let i;
    if (freeN > 0) i = FREE[--freeN];
    else if (nSlot < CAP) i = nSlot++;
    else return -1;
    P_x[i] = S_.x; P_y[i] = S_.y; P_vx[i] = S_.vx; P_vy[i] = S_.vy;
    P_age[i] = 0; P_ttl[i] = S_.ttl; P_sz[i] = S_.sz;
    P_drag[i] = S_.drag; P_grav[i] = S_.grav;
    P_lum[i] = S_.lum; P_tw[i] = S_.tw; P_tws[i] = S_.tws;
    P_trail[i] = S_.trail; P_sp[i] = S_.sp; P_kind[i] = S_.kind;
    P_split[i] = S_.spl; P_splA[i] = S_.splA; P_splN[i] = S_.splN;
    P_sway[i] = S_.sway || 0;
    P_sx[i] = S_.sx; P_sy[i] = S_.sy;
    P_sty[i] = S_.sty || 0; P_wid[i] = S_.wid || 1.8; P_slen[i] = S_.slen || 6;
    P_dec[i] = S_.dec || 0.42;
    P_alive[i] = 1;
    live++;
    if (live > peak) peak = live;
    if (S_.kind === 1) sparks++; else if (S_.kind === 0) stars++;
    return i;
  }

  function kill(i) {
    if (!P_alive[i]) return;
    P_alive[i] = 0;
    FREE[freeN++] = i;
    live--;
    if (P_kind[i] === 1) sparks--; else if (P_kind[i] === 0) stars--;
  }

  /* ═══════════════ 3 · 运行环境 ═══════════════ */
  let B = null, cue = null, svgScene = null, svgEnv = null;
  let back = null, front = null, ctxB = null, ctxF = null;
  let reduced = false, ready = false;
  let playing = false, rafId = 0, lastLoopT = -1;

  const sim = { step: 0, t: 0 };
  let evPtr = 0;
  let events = [];
  const flashes = [];
  const rockets = [];
  const fgSparks = [];              // 落到镜头前的火星（front，≤40）
  const statsInfo = { steps: 0, seekMs: 0, frameMs: 0, totalSpawns: 0, extDrop: 0, extTake: 0 };
  /* 外部触发（motion 的 dust/puff）不一定落在正确时刻：
     main.js 的 BDAY.seek 会先 master.time(0,false) 反向遍历一遍再跳到目标，
     且 time(t,true) 抑制前向回调 —— 于是外部调用总在错误的时刻到来。
     闸门规则：只有"与当前渲染时刻同步"的调用（即播放态）才接收，
     跳帧/反向遍历的调用一律丢弃，保证 seek 的确定性与 48s 零残留。 */
  const pending = [];

  const CANDLE_FALLBACK = [[772, 539], [800, 539], [828, 539]];
  const scene = {
    flame: [null, null, null],
    anchors: [{ bx: 772, by: 539 }, { bx: 800, by: 539 }, { bx: 828, by: 539 }],
    anchorSrc: ['fallback', 'fallback', 'fallback'],
    anchorCache: [null, null, null]
  };
  let anchorT = -999;

  function xf(m, x, y) { return { x: m.a * x + m.c * y + m.e, y: m.b * x + m.d * y + m.f }; }
  function safe(fn, dflt) { try { return fn(); } catch (e) { return dflt; } }

  /* src 元素局部点 → dst 元素局部点 */
  function mapPoint(srcEl, x, y, dstEl) {
    if (!srcEl || !dstEl) return null;
    const sm = safe(() => srcEl.getScreenCTM(), null);
    const dm = safe(() => dstEl.getScreenCTM(), null);
    if (!sm || !dm) return null;
    const p = xf(sm, x, y);
    const inv = safe(() => dm.inverse(), null);
    if (!inv) return null;
    const q = xf(inv, p.x, p.y);
    return isFinite(q.x) && isFinite(q.y) ? q : null;
  }

  function bboxOf(el) {
    const b = safe(() => el.getBBox(), null);
    if (b && (b.width > 0.01 || b.height > 0.01)) return b;
    return null;
  }

  /* 画布坐标：把 el 局部点映射到 #fx-back 的 1600×900 空间 */
  function elToCanvas(el, lx, ly) {
    if (!el || !back) return null;
    const m = safe(() => el.getScreenCTM(), null);
    if (!m) return null;
    const p = xf(m, lx, ly);
    const r = back.getBoundingClientRect();
    if (!r.width || !r.height) return null;
    return { x: (p.x - r.left) * (W / r.width), y: (p.y - r.top) * (H / r.height) };
  }

  /* ── 锚点链：#wick → #candle 本体顶中 → #flame-unit 底中 → 契约 ── */
  function anchorFor(i, targetEl) {
    // A. 烛芯（静态几何，可动态取）
    const wick = document.getElementById('wick-' + i);
    if (wick) {
      const b = bboxOf(wick);
      if (b) {
        const p = mapPoint(wick, b.x + b.width / 2, b.height > 0.5 ? b.y : b.y + b.height / 2, targetEl);
        if (p) return { bx: p.x, by: p.y, src: 'wick', frozen: false };
      }
    }
    // B. 蜡烛本体（#candle-i 内部除火焰/烟以外最高的那个图元）的顶中
    const candle = document.getElementById('candle-' + i);
    if (candle) {
      let best = null, bestH = 0;
      const kids = safe(() => Array.prototype.slice.call(candle.children), []);
      for (let k = 0; k < kids.length; k++) {
        const el = kids[k];
        const id = el.getAttribute('id') || '';
        if (id.indexOf('flame') === 0 || id.indexOf('smoke') === 0 || id.indexOf('glow') === 0) continue;
        const b = bboxOf(el);
        if (b && b.height > bestH) { bestH = b.height; best = { el, b }; }
      }
      if (best && bestH > 4) {
        const p = mapPoint(best.el, best.b.x + best.b.width / 2, best.b.y, targetEl);
        if (p) return { bx: p.x, by: p.y, src: 'candle', frozen: true };
      }
    }
    // C. 火焰容器（快照：之后 d 会被改写，不能每帧取）
    const unit = document.getElementById('flame-unit-' + i);
    if (unit) {
      const b = bboxOf(unit);
      if (b && b.height > 2) {
        const p = mapPoint(unit, b.x + b.width / 2, b.y + b.height, targetEl);
        if (p) return { bx: p.x, by: p.y, src: 'unit', frozen: true };
      }
    }
    // D. 契约兜底（fallback cake：烛芯顶 772/800/828, y=539）
    return { bx: CANDLE_FALLBACK[i - 1][0], by: CANDLE_FALLBACK[i - 1][1], src: 'fallback', frozen: true };
  }

  function refreshAnchors(force) {
    if (!force && Math.abs(runT - anchorT) < 0.12) return;
    anchorT = runT;
    for (let i = 0; i < 3; i++) {
      const f = scene.flame[i];
      if (!f) continue;
      const cached = scene.anchorCache[i];
      // 只有 wick 源可以动态重算；其余是快照，避免"火焰改 bbox → 锚点漂移"的回授
      if (cached && cached.frozen) { scene.anchors[i] = cached; continue; }
      const a = anchorFor(i + 1, f.outer);
      scene.anchorCache[i] = a.frozen ? a : null;
      scene.anchors[i] = a;
      scene.anchorSrc[i] = a.src;
    }
  }

  /* ── 火焰节点 ── */
  function ensureDefs() {
    if (!svgScene || document.getElementById('vfx-halo-grad')) return;
    const defs = document.createElementNS(NS, 'defs');
    defs.setAttribute('id', 'vfx-defs');
    defs.innerHTML =
      '<radialGradient id="vfx-halo-grad">' +
      '<stop offset="0" stop-color="#FFD9A0" stop-opacity=".62"/>' +
      '<stop offset=".35" stop-color="#FFB066" stop-opacity=".26"/>' +
      '<stop offset=".7" stop-color="#FF9A4D" stop-opacity=".07"/>' +
      '<stop offset="1" stop-color="#FF9A4D" stop-opacity="0"/>' +
      '</radialGradient>';
    svgScene.insertBefore(defs, svgScene.firstChild);
  }

  function ensureFlames() {
    ensureDefs();
    const layer = document.getElementById('layer-cake') || svgScene;
    for (let i = 1; i <= 3; i++) {
      let outer = document.getElementById('flame-' + i);
      let space = outer && outer.parentNode;
      if (!outer || !space) {
        space = layer;
        if (!space) continue;
        let g = document.getElementById('vfx-flame-fallback');
        if (!g) {
          g = document.createElementNS(NS, 'g');
          g.setAttribute('id', 'vfx-flame-fallback');
          layer.appendChild(g);
        }
        outer = document.createElementNS(NS, 'path');
        outer.setAttribute('id', 'flame-' + i);
        outer.setAttribute('pointer-events', 'none');
        g.appendChild(outer);
      }
      if (outer.tagName && outer.tagName.toLowerCase() !== 'path') continue;

      const fill = (outer.getAttribute('fill') || '').toLowerCase();
      if (!fill || fill === 'none') outer.setAttribute('fill', '#FFB066');

      const mk = (id, fillColor, opacity) => {
        let el = document.getElementById(id);
        if (!el) {
          el = document.createElementNS(NS, 'path');
          el.setAttribute('id', id);
          el.setAttribute('pointer-events', 'none');
          space.appendChild(el);
        }
        el.setAttribute('fill', fillColor);
        el.setAttribute('opacity', String(opacity));
        return el;
      };
      let halo = document.getElementById('vfx-flame-halo-' + i);
      if (!halo) {
        halo = document.createElementNS(NS, 'circle');
        halo.setAttribute('id', 'vfx-flame-halo-' + i);
        halo.setAttribute('pointer-events', 'none');
        space.appendChild(halo);
      }
      halo.setAttribute('fill', 'url(#vfx-halo-grad)');
      halo.setAttribute('opacity', '0.5');
      halo.setAttribute('style', 'mix-blend-mode:screen');
      const inner = mk('vfx-flame-in-' + i, '#FFE3A8', 0.95);
      const core = mk('vfx-flame-core-' + i, '#9FE4FF', 0.60);
      core.setAttribute('style', 'mix-blend-mode:screen');

      scene.flame[i - 1] = { outer, inner, core, halo, space };
      scene.anchors[i - 1] = anchorFor(i, outer);
    }
  }

  /* ═══════════════ 4 · 火焰 ═══════════════ */
  /* 火焰轮廓：9 个节点沿"茎"分布，Catmull-Rom 平滑成闭合路径。
     宽度剖面：底部窄、22% 处最宽、往上收成一条舌头 —— 不是水滴。
     弯度按 u^1.85 分布：根部几乎竖直，只有上半段被风甩出去。 */
  const FLAME_W = [[0, 0.38], [0.26, 1.00], [0.50, 0.66], [0.74, 0.30], [0.90, 0.10], [1, 0.010]];
  function flameWid(u) {
    for (let i = 1; i < FLAME_W.length; i++) {
      if (u <= FLAME_W[i][0]) {
        const a = FLAME_W[i - 1], b = FLAME_W[i];
        const k = (u - a[0]) / (b[0] - a[0] || 1);
        const e = k * k * (3 - 2 * k);
        return a[1] + (b[1] - a[1]) * e;
      }
    }
    return 0.012;
  }

  function flameD(bx, by, hh, ww, lean, t, ph) {
    const w1 = Math.sin(t * 6.1 + ph);
    const w2 = Math.sin(t * 11.3 + ph * 1.73 + 0.7);
    const w3 = Math.sin(t * 17.9 + ph * 0.41 + 2.1);
    const h = hh * (1 + 0.17 * w1 + 0.075 * w2);
    const w = ww * (1 + 0.18 * w2 + 0.06 * w3);
    const us = [0, 0.20, 0.46, 0.72, 0.90, 1];
    const bend = (u) => lean * h * 0.72 * Math.pow(u, 1.85);
    const wob = (u) => w * 0.16 * Math.sin(t * 8.3 + u * 5.4 + ph) * u;
    const pt = (u, side) => ({
      x: bx + side * w * flameWid(u) + bend(u) + wob(u),
      y: by - h * u
    });
    const R = us.map((u) => pt(u, 1));
    const L = us.map((u) => pt(u, -1));
    // 尖端稍微偏离中轴，读起来像被气流拉着走
    const tip = { x: bx + w * 0.10 * (w1 + w3) + bend(1) + wob(1) * 1.6, y: by - h };
    const pts = R.concat([tip], L.slice(0, -1).reverse());
    const n = pts.length;
    let d = `M${f2(pts[0].x)},${f2(pts[0].y)}`;
    for (let i = 0; i < n; i++) {
      const p0 = pts[(i - 1 + n) % n], p1 = pts[i], p2 = pts[(i + 1) % n], p3 = pts[(i + 2) % n];
      const c1x = p1.x + (p2.x - p0.x) / 6, c1y = p1.y + (p2.y - p0.y) / 6;
      const c2x = p2.x - (p3.x - p1.x) / 6, c2y = p2.y - (p3.y - p1.y) / 6;
      d += `C${f2(c1x)},${f2(c1y)} ${f2(c2x)},${f2(c2y)} ${f2(p2.x)},${f2(p2.y)}`;
    }
    return d + 'Z';
  }

  function candlePhase(i, t) {
    const on = cue.ignite[i], off = cue.extinguish[i];
    if (t < on) return { vis: false };
    if (t < off) return { vis: true, pop: smooth((t - on) / 0.26), die: 0 };
    const d = (t - off) / 0.17;
    if (d >= 1) return { vis: false };
    return { vis: true, pop: 1, die: smooth(1 - d) };
  }

  function gustAt(t) {
    const u = t - cue.puff;
    if (u < 0) return 0;
    const rise = smooth(u / 0.085);
    const fall = 1 - smooth((u - 0.72) / 0.55);
    return rise * clamp(fall, 0, 1) * (1 + 0.22 * Math.sin(u * 46 + 1.1));
  }

  function renderFlames(t) {
    for (let i = 0; i < 3; i++) {
      const f = scene.flame[i];
      if (!f) continue;
      const st = candlePhase(i, t);
      const nodes = [f.outer, f.inner, f.core, f.halo];
      if (!st.vis) {
        for (let k = 0; k < nodes.length; k++) nodes[k].setAttribute('display', 'none');
        continue;
      }
      for (let k = 0; k < nodes.length; k++) nodes[k].removeAttribute('display');

      const a = scene.anchors[i] || { bx: CANDLE_FALLBACK[i][0], by: CANDLE_FALLBACK[i][1] };
      const ph = i * 2.13 + i * 0.71 + 0.4;
      const gust = gustAt(t);
      const dir = frame.dir;
      const leanBase = 0.045 * Math.sin(t * 1.9 + ph * 1.3) + 0.028 * Math.sin(t * 3.37 + ph);
      const lean = leanBase + dir * gust * 1.5;
      const shrink = 1 - 0.24 * Math.min(1, gust) - 0.72 * (st.die || 0);
      const k = st.pop * clamp(shrink, 0.06, 1);

      const hBase = 30.0 * (0.80 + 0.20 * Math.sin(t * 0.9 + ph));
      const hOuter = hBase * k * (1 + 0.55 * (st.die || 0));
      const wOuter = 5.4 * (0.55 + 0.45 * st.pop) * (1 - 0.22 * Math.min(1, gust));

      f.outer.setAttribute('d', flameD(a.bx, a.by, hOuter, wOuter, lean, t, ph));
      f.inner.setAttribute('d', flameD(a.bx, a.by - hOuter * 0.07, hOuter * 0.62,
        wOuter * 0.52, lean * 0.94, t * 1.09, ph + 0.55));
      f.core.setAttribute('d', flameD(a.bx, a.by - hOuter * 0.02, hOuter * 0.24,
        wOuter * 0.36, lean * 0.55, t * 1.27, ph + 1.2));
      const hr = hOuter * (1.02 + 0.10 * Math.sin(t * 4.3 + ph));
      f.halo.setAttribute('cx', String(f2(a.bx + lean * hOuter * 0.30)));
      f.halo.setAttribute('cy', String(f2(a.by - hOuter * 0.48)));
      f.halo.setAttribute('r', String(f2(hr)));
      const flick = 0.30 + 0.10 * Math.sin(t * 13.1 + ph) + 0.06 * Math.sin(t * 23.7 + ph * 2.3);
      f.halo.setAttribute('opacity', String(f2(clamp(flick * (1 - Math.min(1, gust) * 0.35), 0.05, 0.5))));
    }
  }

  /* ═══════════════ 5 · 轻烟（canvas / fx-back） ═══════════════ */
  const SMOKE = [];   // [candle][strand] = {pts:[[dx,dy]...], L, w0, w1, born, life, color, dir}

  /* 轻烟：每支蜡烛 3 段**独立**上升的贝塞尔，不再回环。
     旧版是左右交替的 S 形（在缩略图上读成"弹簧/连续回环"）。
     现在：每段一个固定偏向（−8° / +3° / +11°）、错开 90ms、宽 7→20u、alpha .5→0。 */
  const SMOKE_LEAN = [-8, 3, 11];          // 度（Lead 指定：三股方向不同，不平行）
  const SMOKE_DELAY = [0, 0.09, 0.19];     // 段间错峰（60–120ms 量级）
  function buildSmokeStrands() {
    SMOKE.length = 0;
    for (let i = 0; i < 3; i++) {
      const list = [];
      for (let s = 0; s < 1; s++) {
        const sIdx = i;                                  // 段 index = 蜡烛 index
        const ang = (SMOKE_LEAN[sIdx] * Math.PI) / 180;
        const hgt = 158 + i * 10;                       // 竖直高度
        const lean = Math.tan(ang) * hgt;               // 顶端横移（固定偏向）
        const bow = lean * 0.18 + (i === 1 ? -1.5 : 1.5); // 只留极轻的单侧弧，避免绕回成环
        const pts = [
          [0, 0],
          [lean * 0.22 + bow * 0.25, -hgt * 0.28],
          [lean * 0.60 + bow * 0.8, -hgt * 0.62],
          [lean, -hgt]
        ];
        let L = 0;
        for (let k = 1; k < pts.length; k++) {
          L += Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]);
        }
        const born = cue.extinguish[i] + 0.04 + SMOKE_DELAY[i];
        const life = Math.min(2.25, Math.max(0.9, (cue.smokeEnd - 0.05) - born));
        list.push({
          pts, L,
          w0: 16, w1: 64,                               // 16u → 64u（中段最宽、顶端收细）
          a0: 0.22,                                      // 起点 alpha .22，向上 → 0
          born, life,
          color: i === 1 ? '#DCE6D6' : '#CFDCC8',
          dir: Math.sign(ang) || 1, s: sIdx
        });
      }
      SMOKE.push(list);
    }
  }

  /* 烟起点：直接锚 #wick-N 顶端中点（canvas 坐标，误差 <2px） */
  function smokeAnchorCanvas(i) {
    const wick = document.getElementById('wick-' + (i + 1));
    if (wick) {
      const b = safe(() => wick.getBBox(), null);
      if (b && (b.width > 0 || b.height > 0)) {
        const p = elToCanvas(wick, b.x + b.width / 2, b.y);   // 顶端
        if (p) return p;
      }
    }
    const f = scene.flame[i];
    if (f && f.outer) {
      const a = scene.anchors[i] || { bx: CANDLE_FALLBACK[i][0], by: CANDLE_FALLBACK[i][1] };
      const p = elToCanvas(f.outer, a.bx, a.by);
      if (p) return p;
    }
    const root = svgScene;
    if (root) {
      const p = elToCanvas(root, CANDLE_FALLBACK[i][0], CANDLE_FALLBACK[i][1]);
      if (p) return p;
    }
    return { x: CANDLE_FALLBACK[i][0], y: CANDLE_FALLBACK[i][1] };
  }

  /* 烟柱中心线 → 上下两条边（宽度沿路径从 w0 渐变到 w1），返回闭合点列 */
  function smokeShape(pts, ax, ay, w0, w1) {
    const n = pts.length;
    const left = [], right = [];
    for (let k = 0; k < n; k++) {
      const p = pts[k];
      const pa = pts[Math.max(0, k - 1)], pb = pts[Math.min(n - 1, k + 1)];
      let tx = pb[0] - pa[0], ty = pb[1] - pa[1];
      const tl = Math.hypot(tx, ty) || 1;
      tx /= tl; ty /= tl;
      const nx = -ty, ny = tx;
      const u = k / (n - 1);
      // 烟柱剖面：中段最宽、顶端收细，避免平顶读成「木棍」
      const bell = Math.pow(Math.sin(Math.PI * Math.min(1, u * 0.92)), 0.75);
      const w = (w0 + (w1 - w0) * bell) * 0.5;
      const cx = ax + p[0], cy = ay + p[1];
      left.push([cx + nx * w, cy + ny * w]);
      right.push([cx - nx * w, cy - ny * w]);
    }
    return { left, right };
  }

  function fillSmoke(ctx, shape, ax, ay, tipX, tipY, color, alpha) {
    ctx.beginPath();
    ctx.moveTo(shape.left[0][0], shape.left[0][1]);
    for (let k = 1; k < shape.left.length; k++) ctx.lineTo(shape.left[k][0], shape.left[k][1]);
    for (let k = shape.right.length - 1; k >= 0; k--) ctx.lineTo(shape.right[k][0], shape.right[k][1]);
    ctx.closePath();
    const g = ctx.createLinearGradient(ax, ay, tipX, tipY);
    g.addColorStop(0, color);
    g.addColorStop(0.42, color);
    g.addColorStop(1, 'rgba(207,220,200,0)');
    ctx.fillStyle = g;
    ctx.globalAlpha = alpha;
    ctx.fill();
  }

  function renderSmoke(t) {
    if (!ctxB || !SMOKE.length) return;
    ctxB.save();
    // 烟是遮光的悬浮物，不是发光体：source-over；lighter 会把它摊成一根灰条
    ctxB.globalCompositeOperation = 'source-over';
    for (let i = 0; i < 3; i++) {
      const list = SMOKE[i];
      if (!list) continue;
      const a = smokeAnchorCanvas(i);                 // 烛芯顶端（画布坐标，±2px）
      for (let s = 0; s < list.length; s++) {
        const st = list[s];
        const u = (t - st.born) / st.life;
        if (u <= 0 || u >= 1) continue;
        const grow = smooth(Math.min(1, u / 0.30));    // 前 0.6s 长成一根烟柱
        const fadeIn = smooth(Math.min(1, u / 0.13));  // 0–0.3s 渐入
        const fadeOut = Math.pow(1 - u, 1.4);
        if (fadeIn * fadeOut <= 0.006) continue;
        const drift = st.dir * 10 * u * u;             // 极轻的单侧偏移
        const rise = 0;                                 // 根部死钉在烛芯顶端
        const pts = st.pts.map((q) => [q[0] * grow, q[1] * grow]);
        const tip = pts[pts.length - 1];
        const wBase = lerp(st.w0, st.w1, Math.min(1, u * 0.9));
        // 3 段填充（无描边）：宽雾体 .08 / 中芯 .18 / 亮芯 .35
        const layers = [[2.35, 0.16], [1.45, 0.20], [0.85, 0.26]];
        for (let L = 0; L < 3; L++) {
          const shape = smokeShape(pts, a.x + drift, a.y, wBase * layers[L][0], wBase * layers[L][0] * 1.35);
          fillSmoke(ctxB, shape, a.x + drift, a.y,
            a.x + drift + tip[0], a.y + tip[1],
            st.color, fadeIn * fadeOut * layers[L][1]);
        }
      }
    }
    ctxB.restore();
    ctxB.globalAlpha = 1;
    ctxB.globalCompositeOperation = 'source-over';
  }

  /* ═══════════════ 6 · 吹气（front） ═══════════════ */
  const frame = {
    beak: { x: 600, y: 470 },
    candles: [{ x: 772, y: 519 }, { x: 800, y: 519 }, { x: 828, y: 519 }],
    dir: 1, t: -999
  };
  let beakCache = null, beakCacheT = -99;

  function beakPoint(t) {
    if (beakCache && Math.abs(t - beakCacheT) < 0.2) return beakCache;
    const el = document.getElementById('tux-beak');
    let p = null;
    if (el) {
      const b = bboxOf(el);
      if (b) p = elToCanvas(el, b.x + b.width / 2, b.y + b.height / 2);
    }
    if (!p) p = { x: 600, y: 470 };            // 契约兜底：Tux 站位 x≈600
    beakCache = p; beakCacheT = t;
    return p;
  }

  function candlePoint(i, t) {
    const f = scene.flame[i] || scene.flame[1];
    const a = scene.anchors[i] || { bx: CANDLE_FALLBACK[i][0], by: CANDLE_FALLBACK[i][1] };
    if (f && f.outer) {
      const p = elToCanvas(f.outer, a.bx, a.by);
      if (p) return p;
    }
    if (svgScene) {
      const p = elToCanvas(svgScene, CANDLE_FALLBACK[i][0], CANDLE_FALLBACK[i][1]);
      if (p) return p;
    }
    return { x: CANDLE_FALLBACK[i][0], y: CANDLE_FALLBACK[i][1] };
  }

  function prepareFrame(t) {
    if (frame.t === t) return;
    frame.t = t;
    frame.beak = beakPoint(t);
    for (let i = 0; i < 3; i++) frame.candles[i] = candlePoint(i, t);
    frame.dir = (frame.candles[1].x - frame.beak.x) >= 0 ? 1 : -1;
  }

  function renderBlow(t) {
    if (!ctxF || !cue) return;
    const u0 = t - cue.puff;
    if (u0 < -0.01 || u0 > 0.62) return;
    const o = frame.beak;
    const c1 = frame.candles[0], c3 = frame.candles[2];
    const tx = (c1.x + c3.x) / 2 + 55 * frame.dir;
    const ty = (c1.y + c3.y) / 2 - 16;
    const ddx = tx - o.x, ddy = ty - o.y;
    const len = Math.hypot(ddx, ddy) || 1;
    const nx = -ddy / len, ny = ddx / len;

    ctxF.save();
    ctxF.globalCompositeOperation = 'lighter';
    ctxF.lineCap = 'round';
    // 气流末端淡出：用线性渐变描边，而不是一根等亮的线
    const grad = ctxF.createLinearGradient(o.x, o.y, o.x + ddx, o.y + ddy);
    grad.addColorStop(0, 'rgba(226,242,222,0)');
    grad.addColorStop(0.22, 'rgba(226,242,222,0.75)');
    grad.addColorStop(0.7, 'rgba(226,242,222,0.55)');
    grad.addColorStop(1, 'rgba(226,242,222,0)');
    for (let a = 0; a < 3; a++) {
      const p = clamp((u0 - a * 0.045) / 0.5, 0, 1);
      if (p <= 0 || p >= 1) continue;
      const e = easeOut(p);
      const head = 0.14 + 0.92 * e;
      const bow = (30 - a * 8) * (1 - 0.5 * e);
      const x0 = o.x + nx * (a - 1) * 7 + (ddx / len) * 8;
      const y0 = o.y + ny * (a - 1) * 7 + (ddy / len) * 8;
      const x1 = o.x + ddx * head, y1 = o.y + ddy * head;
      const mx = (x0 + x1) / 2 + nx * bow, my = (y0 + y1) / 2 + ny * bow;
      const noise = 0.55 + 0.28 * Math.sin(t * 37 + a * 2.3) + 0.17 * Math.sin(t * 61.7 + a * 1.1);
      const alpha = Math.sin(Math.PI * Math.min(1, p * 1.02)) * 0.34 * noise;
      if (alpha <= 0.004) continue;
      ctxF.globalAlpha = clamp(alpha, 0, 0.36);
      ctxF.strokeStyle = grad;
      ctxF.lineWidth = 4.5 + a * 2.6 + e * 2.2;
      ctxF.beginPath();
      ctxF.moveTo(x0, y0);
      ctxF.quadraticCurveTo(mx, my, x1, y1);
      ctxF.stroke();
    }
    // 沿气流跑的气团：让人读到"一口气吹过去"，而不是一条线
    const puffs = 9;
    for (let k = 0; k < puffs; k++) {
      const ph = clamp(u0 / 0.5 - k * 0.055, 0, 1);
      if (ph <= 0 || ph >= 1) continue;
      const e = easeOut(ph);
      const px = o.x + ddx * (0.12 + 0.92 * e) + nx * Math.sin(k * 1.7 + t * 9) * (10 + 16 * e);
      const py = o.y + ddy * (0.12 + 0.92 * e) + ny * Math.sin(k * 2.3 + t * 11) * (8 + 12 * e);
      const sz = 10 + 26 * e;
      const al = Math.sin(Math.PI * ph) * 0.17;
      ctxF.globalAlpha = clamp(al, 0, 0.2);
      ctxF.drawImage(SPR_SOFT[tint('#E8F4E2')], px - sz, py - sz, sz * 2, sz * 2);
    }
    ctxF.restore();
    ctxF.globalAlpha = 1;
    ctxF.globalCompositeOperation = 'source-over';
  }

  /* ═══════════════ 7 · 烟花 ═══════════════ */
  const RISE = 0.70;
  const riseOf = (i) => RISE + 0.04 * i;

  function launchShell(i) {
    const sh = cue.shells[i];
    if (!sh) return;
    const rise = riseOf(i);
    const x0 = clamp(sh.x + rr(-90, 90), 120, 1480);
    const y0 = 742 + rr(-18, 46);
    rockets.push({
      i, t0: sim.t, dur: rise, x0, y0, x1: sh.x, y1: sh.y,
      ph: rr(0, TAU), c: sh.c, alive: true
    });
    for (let k = 0; k < 5; k++) {
      S_.x = x0 + rr(-6, 6); S_.y = y0 + rr(-4, 6);
      S_.vx = rr(-14, 14); S_.vy = rr(-30, -6);
      S_.ttl = rr(0.4, 0.8); S_.sz = rr(9, 16); S_.drag = 2.4; S_.grav = -6;
      S_.lum = 0.28; S_.tw = rr(0, TAU); S_.tws = 2; S_.trail = 0;
      S_.sp = tint('#B9CBB4'); S_.kind = 1;
      S_.sway = 0; S_.sx = 1; S_.sy = 0; S_.sty = 0; S_.wid = 1.8; S_.slen = 6; S_.dec = 0.42; S_.spl = 0; S_.splA = 0; S_.splN = 0;
      spawn(); statsInfo.totalSpawns++;
    }
  }

  function rocketPos(rk, t) {
    const p = clamp((t - rk.t0) / rk.dur, 0, 1);
    const e = 1 - Math.pow(1 - p, 2.15);
    const x = lerp(rk.x0, rk.x1, e) + Math.sin(p * Math.PI * 2 + rk.ph) * 2.6 * (1 - p);
    const y = lerp(rk.y0, rk.y1, e);
    return { x, y, p, e };
  }

  function stepRockets(k) {
    const t = k * DT;
    for (let i = 0; i < rockets.length; i++) {
      const rk = rockets[i];
      if (!rk.alive) continue;
      const pos = rocketPos(rk, t);
      if (pos.p >= 1) { rk.alive = false; continue; }
      if ((k + i) % 2 === 0) {
        // 尾焰横向抖动：两个不可公约频率 × 每发独立相位
        const flick = 0.55 + 0.45 * Math.sin(t * 58 + rk.ph) * Math.cos(t * 41.3 + rk.ph * 1.7);
        S_.x = pos.x + rr(-1.6, 1.6) * flick;
        S_.y = pos.y + rr(0, 5);
        S_.vx = rr(-26, 26) * flick; S_.vy = rr(10, 52);
        S_.ttl = rr(0.22, 0.5); S_.sz = rr(4, 8.5); S_.drag = 1.6; S_.grav = 55;
        S_.lum = 0.95; S_.tw = rr(0, TAU); S_.tws = 26; S_.trail = 0.018;
        S_.sp = tint(rk.c[0]); S_.kind = 1;
        S_.spl = 0; S_.splA = 0; S_.splN = 0;
        spawn(); statsInfo.totalSpawns++;
      }
    }
  }

  function boomShell(i) {
    const sh = cue.shells[i];
    const rk = rockets.find((r) => r.i === i);
    if (rk) rk.alive = false;
    burst(sh);
    // 落到镜头前的火星（front 层，≤40）：让烟花有"越过角色"的纵深
    const n = 4 + Math.floor(rng() * 3);
    for (let k = 0; k < n; k++) {
      if (fgSparks.length >= 40) break;
      fgSparks.push({
        x: sh.x + rr(-sh.r * 0.9, sh.r * 0.9),
        y: sh.y + rr(-sh.r * 0.5, sh.r * 0.8),
        vx: rr(-26, 26), vy: rr(6, 42),
        ttl: rr(1.4, 2.6), age: 0,
        sz: rr(3.2, 7.5), sp: tint(sh.c[1] || sh.c[0]),
        tw: rr(0, TAU)
      });
    }
  }

  function star(x, y, vx, vy, o) {
    S_.x = x; S_.y = y; S_.vx = vx; S_.vy = vy;
    S_.ttl = o.ttl; S_.sz = o.sz; S_.drag = o.drag; S_.grav = o.grav;
    S_.lum = o.lum; S_.tw = rr(0, TAU); S_.tws = o.tws || 9;
    S_.trail = o.trail == null ? 0.018 : o.trail;
    S_.sp = o.sp; S_.kind = o.kind == null ? 0 : o.kind;
    S_.spl = o.spl || 0; S_.splA = o.splA || 0; S_.splN = o.splN || 0;
    S_.sway = o.sway || 0;
    S_.sx = o.sx == null ? 1 : o.sx; S_.sy = o.sy == null ? 0 : o.sy;
    S_.sty = o.sty || 0; S_.wid = o.wid || 1.8; S_.slen = o.slen || 6; S_.dec = o.dec || 0.42;
    return spawn();
  }
  /* 七发。
     R  = CUES.shells[].r（美术定版尺寸参数）
     Rf = 可见半径目标 ≈ 0.53R  →  可见直径 ≈ 1.06R（r=320 → 340，r=372 → 394）
     阻力让花在 0.6s 内冲到 ~0.75Rf 然后几乎停住，再被重力慢慢拉垂。
     爆点亮芯：近白 #FFFDF4 的柔光核（直径 50–70px）→ 224–255 像素占比 0.3–1.2% */
  function burst(sh) {
    if (!sh) return;
    const x = sh.x, y = sh.y;
    const R = sh.r || 300;
    const kind0 = sh.kind || 'peony';
    // 可见半径：单发 0.585r（直径 ≈1.17r → 350–420px）；柳按椭圆 rx=.52r
    const Rf = R * (kind0 === 'willow' ? 0.52 : 0.60);
    const c = sh.c || ['#FFE9B0', '#FF6F91'];
    const sp0 = tint(c[0]);
    const sp1 = tint(c[1] || c[0]);
    const spHot = tint(brighten(c[0], 0.55));
    const spW = tint('#FFFDF4');
    const kind = kind0;
    const CORE = kind === 'finale' ? 34 : 40;      // 亮芯半径（终点：连通块 ≤68px）

    flashes.push({ t0: sim.t, x, y, r: Rf, col: c[0], dur: kind === 'finale' ? 0.95 : 1.8, lit: false, core: CORE });
    if (flashes.length > 24) flashes.shift();

    const push = (n, fn) => {
      for (let i = 0; i < n; i++) { if (live >= 3600) return; fn(i, n); }
    };
    const sph = (i, n, jitter) => {
      const u = (i + 0.5) / n;
      const ct = 1 - 2 * u;
      const st = Math.sqrt(Math.max(0, 1 - ct * ct));
      return { st, ph: i * 2.399963 + rr(-(jitter || 0.1), jitter || 0.1) };
    };

    // 起爆瞬间的一层"壳皮"：白热点直接铺在 8–30px 的薄壳上（不从一点往外挤），
    // 起爆帧就有近白面积，同时不会糊成一坨雾斑
    for (let k = 0; k < 64; k++) {
      const a0 = (k / 64) * TAU + rr(-0.06, 0.06);
      const rr0 = rr(8, 30);
      S_.x = x + Math.cos(a0) * rr0;
      S_.y = y + Math.sin(a0) * rr0 * 0.9;
      S_.vx = Math.cos(a0) * rr(30, 110);
      S_.vy = Math.sin(a0) * rr(30, 110) * 0.9;
      S_.ttl = rr(0.22, 0.45); S_.sz = rr(9, 15); S_.drag = 3.0; S_.grav = 26;
      S_.lum = 1; S_.tw = rr(0, TAU); S_.tws = 26; S_.trail = 0.008;
      S_.sp = tint('#FFFDF4'); S_.kind = 1;
      S_.sway = 0; S_.sx = 1; S_.sy = 0; S_.sty = 0;
      S_.wid = 1.8; S_.slen = 6; S_.dec = 0.42;
      S_.spl = 0; S_.splA = 0; S_.splN = 0;
      spawn(); statsInfo.totalSpawns++;
    }

    if (kind === 'peony') {
      // 粉色起手（不抢蛋糕的暖色），内圈才带一点金
      push(560, (i, n) => {
        const d = sph(i, n, 0.14);
        const v = Rf * (3.20 + rr(-0.06, 0.06));
        const sp = rng() < 0.78 ? sp0 : sp1;
        star(x, y, Math.cos(d.ph) * d.st * v, Math.sin(d.ph) * d.st * v * 0.96, {
          ttl: rr(2.2, 2.8), sz: rr(16, 24), drag: 3.20, grav: 96, lum: rr(0.85, 1),
          trail: 0.016, sp, tws: rr(7, 13)
        });
      });
      push(290, (i, n) => {
        const d = sph(i, n, 0.2);
        const v = Rf * (1.70 + rr(-0.06, 0.06));
        const sp = rng() < 0.5 ? spHot : sp0;
        star(x, y, Math.cos(d.ph) * d.st * v, Math.sin(d.ph) * d.st * v, {
          ttl: rr(1.8, 2.3), sz: rr(13, 19), drag: 3.0, grav: 74, lum: rr(0.9, 1),
          trail: 0.014, sp, tws: rr(10, 16)
        });
      });
      push(40, () => star(x + rr(-10, 10), y + rr(-10, 10), rr(-90, 90), rr(-90, 90), {
        ttl: rr(0.45, 0.8), sz: rr(6, 10), drag: 3, grav: 30, lum: 0.9,
        sp: spW, tws: 30, trail: 0.01
      }));
    } else if (kind === 'willow') {
      // 长垂尾 + 持续滴金粉（金色留给第 2 发）
      // 椭圆柳：rx = .52r、ry = .94r（1.81:1），横向摆幅 .06r，垂落为主
      const EY = 0.94 / 0.52;                       // 4.8? 见下：用 Rf 归一
      void EY;
      push(600, (i, n) => {
        const d = sph(i, n, 0.1);
        const v = Rf * rr(1.55, 1.85);
        star(x, y,
          Math.cos(d.ph) * d.st * v,
          Math.sin(d.ph) * d.st * v * 1.81 + Rf * 0.55 - rr(0, 30), {
          ttl: rr(2.4, 2.9), sz: rr(14, 21), drag: 1.45, grav: 104, lum: rr(0.62, 0.88),
          trail: 0.11, sp: rng() < 0.35 ? sp1 : sp0, tws: rr(4, 8),
          sway: R * 0.06, spl: rr(0.55, 0.8), splN: rng() < 0.45 ? 2 : 0
        });
      });
      push(60, () => star(x, y, rr(-60, 60), rr(-60, 60), {
        ttl: rr(1.4, 2.2), sz: rr(9, 15), drag: 2.4, grav: 58, lum: 0.7,
        sp: sp1, tws: 6, trail: 0.04
      }));
    } else if (kind === 'ring') {
      /* 屏幕正圆：rx = ry = 半径。破"完美圆"只许来自
         ① 8% 周长缺口 ② 半径 ±3% 抖动（每颗独立相位，不同环不同） ③ 6–10% 外散
         ④ 单点大小 0.6–1.8×、环向间距 ±25%。火星画成沿速度的短条(1.2–2.4u 宽 / 4–9u 长)。 */
      const vOut = Rf * 3.0;
      const ringRing = (radiusK, count, gapPct, spA, spB, szA, szB, ttlA, ttlB, lenVar) => {
        const gapA = rr(0, TAU), gapW = TAU * gapPct;
        push(count, (i) => {
          const a = (i / count) * TAU + rr(-0.25, 0.25) * (TAU / count);
          let da = a - gapA; da = Math.atan2(Math.sin(da), Math.cos(da));
          if (Math.abs(da) < gapW * 0.5) return;              // 缺口
          const scatter = rng() < 0.08 ? rr(1.12, 1.35) : 1;  // 6–10% 外散
          const v = vOut * radiusK * rr(0.97, 1.03) * scatter; // 半径 ±3%
          star(x, y, Math.cos(a) * v, Math.sin(a) * v, {
            ttl: rr(ttlA, ttlB),
            sz: (szA + (szB - szA) * rng()) * rr(0.6, 1.8),       // 单点大小 0.6–1.8×
            drag: 3.0, grav: 52, lum: rr(0.78, 0.95),
            trail: 0, sp: i % 14 === 0 ? spW : (rng() < 0.5 ? spA : spB),
            tws: rr(9, 15),
            sway: Rf * 0.03, sx: Math.cos(a), sy: Math.sin(a),     // 径向呼吸
            sty: 1, wid: rr(1.2, 2.2), slen: rr(6, 11) * (lenVar ? rr(0.6, 1.8) : 1)
          });
        });
      };
      ringRing(1.00, 190, 0.08, sp0, sp1, 9, 16, 1.12, 1.25, false);  // 外环
      ringRing(0.55, 110, 0.07, spHot, sp0, 5, 13, 1.05, 1.20, true);  // 内环：长度 0.6–1.8× 随机 + 独立缺口/相位

    } else if (kind === 'chrys') {
      push(470, (i, n) => {
        const d = sph(i, n, 0.12);
        const v = Rf * 3.10;
        star(x, y, Math.cos(d.ph) * d.st * v, Math.sin(d.ph) * d.st * v, {
          ttl: rr(2.1, 2.6), sz: rr(15, 22), drag: 3.10, grav: 92, lum: rr(0.65, 0.92),
          trail: 0.018, sp: sp0, tws: rr(5, 9)
        });
      });
      push(380, (i, n) => {
        const d = sph(i, n, 0.16);
        const v = Rf * 1.60;
        star(x, y, Math.cos(d.ph) * d.st * v, Math.sin(d.ph) * d.st * v, {
          ttl: rr(2.3, 2.9), sz: rr(17, 25), drag: 3.1, grav: 70, lum: rr(0.9, 1),
          trail: 0.014, sp: rng() < 0.55 ? sp1 : spHot, tws: rr(3.5, 6)
        });
      });
    } else if (kind === 'crossette') {
      push(215, (i, n) => {
        const d = sph(i, n, 0.08);
        const v = Rf * 2.7;
        star(x, y, Math.cos(d.ph) * d.st * v, Math.sin(d.ph) * d.st * v * 0.9, {
          ttl: rr(1.9, 2.2), sz: rr(14, 20), drag: 2.8, grav: 78, lum: rr(0.85, 1),
          trail: 0.018, sp: sp0, tws: rr(10, 16),
          spl: rr(0.45, 0.58), splN: 5
        });
      });
      push(30, () => star(x + rr(-8, 8), y + rr(-8, 8), rr(-70, 70), rr(-70, 70), {
        ttl: rr(0.3, 0.6), sz: rr(4, 8), drag: 3, grav: 20, lum: 0.8, sp: spW, tws: 30, trail: 0
      }));
    } else if (kind === 'double') {
      for (let d = 0; d < 2; d++) {
        const ox = d ? 46 : -40, oy = d ? -18 : 16;
        push(280, (i, n) => {
          const s2 = sph(i, n, 0.14);
          const v = Rf * (d ? 2.7 : 3.0);
          star(x + ox, y + oy, Math.cos(s2.ph) * s2.st * v, Math.sin(s2.ph) * s2.st * v * 0.92, {
            ttl: rr(1.9, 2.4), sz: rr(14, 21), drag: 3.0, grav: 82, lum: rr(0.78, 1),
            trail: 0.018, sp: d ? sp1 : sp0, tws: rr(6, 12)
          });
        });
      }
    } else if (kind === 'finale') {
      /* 居中空心双环：内半径 0.42R 以内不放任何粒子，核心 ≤60px */
      const rIn = R * 0.42;          // 151
      const rA = rIn * 1.05;         // 158.6 —— 内环
      const rB = rIn * 1.32;         // 199.4 —— 外环
      const ringAt = (rad, n, spA, spB, szA, szB, ttlA, ttlB, vOut) => {
        push(n, (i) => {
          const a = (i / n) * TAU + rr(-0.004, 0.004);
          const jr = rad * rr(0.985, 1.015);
          const v = vOut * rr(0.9, 1.1);
          const px = x + Math.cos(a) * jr, py = y + Math.sin(a) * jr * 0.94;
          star(px, py, Math.cos(a) * v, Math.sin(a) * v * 0.94, {
            ttl: rr(ttlA, ttlB), sz: rr(szA, szB), drag: 2.6, grav: 22,
            lum: 0.68 * rr(0.94, 1.0), dec: 1.6, trail: 0.016, sp: i % 11 === 0 ? spW : (i % 2 ? spA : spB),
            tws: rr(9, 16)
          });
        });
      };
      ringAt(rA, 260, sp0, spHot, 13, 19, 0.72, 0.82, 42);  // 内环（0.82s 内归零）
      ringAt(rB, 300, sp0, sp1, 15, 22, 0.74, 0.86, 26);    // 外环
      push(80, () => {             // 环上碎钻，轻微外溅但不出内圈
        const a = rr(0, TAU);
        const jr = rB * rr(1.0, 1.1);
        star(x + Math.cos(a) * jr, y + Math.sin(a) * jr * 0.94,
          Math.cos(a) * rr(20, 60), Math.sin(a) * rr(20, 60) * 0.94, {
            ttl: rr(0.5, 0.8), sz: rr(4, 8), drag: 2.4, grav: 40, lum: 0.75,
            sp: spW, tws: rr(24, 38), trail: 0.012
          });
      });
      push(90, () => {             // 外圈金色垂落（在环外，不填内部）
        const a = rr(-0.6, 0.6) + (rng() < 0.5 ? 0 : Math.PI);
        const jr = rB * rr(1.0, 1.1);
        star(x + Math.cos(a) * jr, y + Math.sin(a) * jr * 0.9,
          Math.cos(a) * rr(10, 40), rr(20, 60), {
            ttl: rr(0.7, 0.95), sz: rr(8, 14), drag: 1.4, grav: 88, lum: rr(0.5, 0.75),
            trail: 0.05, sp: sp1, tws: rr(3, 6)
          });
      });
    }
  }

  /* 二段：crossette 三向分裂 / willow 末端金色余烬 */
  function processSplits() {
    for (let i = 0; i < nSlot; i++) {
      if (!P_alive[i]) continue;
      const n = P_splN[i];
      if (!n || P_split[i] <= 0) continue;
      if (P_age[i] < P_split[i]) continue;
      P_splN[i] = 0;
      const x = P_x[i], y = P_y[i];
      const baseA = Math.atan2(P_vy[i], P_vx[i]);
      const spd = Math.hypot(P_vx[i], P_vy[i]);
      if (n >= 3) {
        // crossette：一次分裂成 5 颗，继承动量 + 一个环形偏转
        for (let k = 0; k < 6; k++) {
          const a = baseA + (k - 2.5) * 0.78 + rr(-0.1, 0.1);
          const v = spd * rr(0.5, 0.72) + rr(28, 70);
          star(x, y, Math.cos(a) * v, Math.sin(a) * v, {
            ttl: rr(0.6, 0.95), sz: rr(9, 14), drag: 1.45, grav: 56, lum: rr(0.85, 1),
            trail: 0.022, sp: tint('#FFF6E0'), tws: rr(18, 30)
          });
        }
      } else {
        // willow：持续滴金粉（每 0.42s 一颗余烬），不是一次性散花
        const a = baseA + rr(-1.2, 1.2);
        star(x, y, Math.cos(a) * rr(4, 26), Math.sin(a) * rr(2, 18), {
          ttl: rr(0.9, 1.5), sz: rr(6, 11), drag: 1.05, grav: 30, lum: rr(0.45, 0.75),
          trail: 0.018, sp: tint('#FFB066'), tws: rr(5, 12)
        });
        P_split[i] = P_age[i] + 0.42;     // 继续滴
      }
    }
  }

  /* ═══════════════ 8 · 事件 / 步进 ═══════════════ */
  function buildEvents() {
    events = [];
    const add = (t, fn, tag) => events.push({ step: Math.max(0, Math.round(t * 60)), fn, tag });
    (cue.ignite || []).forEach((t, i) => add(t, () => igniteEvent(i), 'ignite' + i));
    (cue.extinguish || []).forEach((t, i) => add(t, () => extinguishEvent(i), 'out' + i));
    if (typeof cue.puff === 'number') add(cue.puff, puffEvent, 'puff');
    (cue.shells || []).forEach((sh, i) => {
      add(sh.at, () => launchShell(i), 'launch' + i);
      add(sh.at + riseOf(i), () => boomShell(i), 'boom' + i);
    });
    events.sort((a, b) => a.step - b.step);
    evPtr = 0;
  }

  function igniteEvent(i) {
    const p = candlePoint(i, sim.t);
    for (let k = 0; k < 6; k++) {
      S_.x = p.x + rr(-3, 3); S_.y = p.y - rr(0, 10);
      S_.vx = rr(-26, 26); S_.vy = rr(-58, -16);
      S_.ttl = rr(0.3, 0.7); S_.sz = rr(3.5, 7); S_.drag = 2.2; S_.grav = -20;
      S_.lum = 1; S_.tw = rr(0, TAU); S_.tws = 24; S_.trail = 0.01;
      S_.sp = tint('#FFD79B'); S_.kind = 1;
      S_.sway = 0; S_.sx = 1; S_.sy = 0; S_.sty = 0; S_.wid = 1.8; S_.slen = 6; S_.dec = 0.42; S_.spl = 0; S_.splN = 0; S_.splA = 0;
      spawn(); statsInfo.totalSpawns++;
    }
  }

  function extinguishEvent(i) {
    const p = candlePoint(i, sim.t);
    for (let k = 0; k < 5; k++) {
      S_.x = p.x + rr(-2, 2); S_.y = p.y - rr(6, 22);
      S_.vx = rr(-14, 34); S_.vy = rr(-46, -8);
      S_.ttl = rr(0.5, 1.0); S_.sz = rr(2.5, 5.5); S_.drag = 1.5; S_.grav = 26;
      S_.lum = 0.9; S_.tw = rr(0, TAU); S_.tws = 18; S_.trail = 0.012;
      S_.sp = tint('#FF9A55'); S_.kind = 1;
      S_.sway = 0; S_.sx = 1; S_.sy = 0; S_.sty = 0; S_.wid = 1.8; S_.slen = 6; S_.dec = 0.42; S_.spl = 0; S_.splN = 0; S_.splA = 0;
      spawn(); statsInfo.totalSpawns++;
    }
  }

  function puffEvent() {
    const o = beakPoint(sim.t);
    const c = candlePoint(1, sim.t);
    if (!o || !c) return;
    for (let k = 0; k < 10; k++) {
      const u = k / 10;
      S_.x = lerp(o.x, c.x, u) + rr(-16, 16);
      S_.y = lerp(o.y, c.y - 20, u) + rr(-14, 14);
      S_.vx = rr(40, 130) * (c.x > o.x ? 1 : -1); S_.vy = rr(-26, 14);
      S_.ttl = rr(0.25, 0.55); S_.sz = rr(3, 7); S_.drag = 2.6; S_.grav = -4;
      S_.lum = 0.34; S_.tw = rr(0, TAU); S_.tws = 30; S_.trail = 0.01;
      S_.sp = tint('#E4F2E0'); S_.kind = 1;
      S_.sway = 0; S_.sx = 1; S_.sy = 0; S_.sty = 0; S_.wid = 1.8; S_.slen = 6; S_.dec = 0.42; S_.spl = 0; S_.splN = 0; S_.splA = 0;
      spawn(); statsInfo.totalSpawns++;
    }
  }

  function stepOnce() {
    const k = sim.step;
    const t = k * DT;
    while (evPtr < events.length && events[evPtr].step <= k) {
      const ev = events[evPtr++];
      safe(() => ev.fn());
    }
    for (let i = 0; i < nSlot; i++) {
      if (!P_alive[i]) continue;
      const age = P_age[i] + DT;
      if (age >= P_ttl[i]) { kill(i); continue; }
      P_age[i] = age;
      const d = 1 - P_drag[i] * DT;
      P_vx[i] *= d;
      P_vy[i] *= d;
      P_vy[i] += P_grav[i] * DT;
      P_x[i] += P_vx[i] * DT;
      P_y[i] += P_vy[i] * DT;
      if (P_sway[i] > 0) {
        // 摆动：沿 (sx,sy) 方向叠位置（等效 sin 速度积分），不破坏物理
        const a2 = P_age[i];
        const dS = P_sway[i] * (Math.sin(TAU * 0.9 * a2 + P_tw[i]) - Math.sin(TAU * 0.9 * (a2 - DT) + P_tw[i]));
        P_x[i] += P_sx[i] * dS;
        P_y[i] += P_sy[i] * dS;
      }
    }
    processSplits();
    stepRockets(k);
    // 前景火星
    for (let i = fgSparks.length - 1; i >= 0; i--) {
      const s = fgSparks[i];
      s.age += DT;
      if (s.age >= s.ttl) { fgSparks.splice(i, 1); continue; }
      s.vy += 34 * DT;
      s.vx *= 1 - 0.5 * DT;
      s.vy *= 1 - 0.5 * DT;
      s.x += s.vx * DT;
      s.y += s.vy * DT;
    }
    while (flashes.length && t - flashes[0].t0 > 1.2) flashes.shift();
    sim.step = k + 1;
    sim.t = sim.step * DT;
    if (pending.length) drainPending();
  }

  function resetSim() {
    for (let i = 0; i < nSlot; i++) P_alive[i] = 0;
    freeN = 0; nSlot = 0; live = 0; stars = 0; sparks = 0;
    sim.step = 0; sim.t = 0;
    evPtr = 0;
    rockets.length = 0;
    fgSparks.length = 0;
    flashes.length = 0;
    pending.length = 0;
    resetRng();
  }

  function stepTo(k) {
    if (k < sim.step) resetSim();
    let guard = 0;
    while (sim.step < k && guard++ < 12000) stepOnce();
    // 边界补火：seek 正好落在事件时刻时，事件也要在这一帧生效（否则迟到 16.7ms）
    while (evPtr < events.length && events[evPtr].step <= sim.step) {
      const ev = events[evPtr++];
      safe(() => ev.fn());
    }
    if (pending.length) drainPending();
  }

  /* ═══════════════ 9 · 浮尘（back 远景 / front 近景） ═══════════════ */
  const FAR_N = 92, NEAR_N = 26, POLLEN_N = 16;
  const farDust = [], nearDust = [], pollen = [];

  function buildDust() {
    farDust.length = 0; nearDust.length = 0; pollen.length = 0;
    for (let i = 0; i < FAR_N; i++) {
      farDust.push({
        x: dustRng() * W, y: 880 - dustRng() * 900,
        amp: 8 + dustRng() * 40, f1: 0.07 + dustRng() * 0.22, f2: 0.13 + dustRng() * 0.3,
        ph: dustRng() * TAU, spd: 0.012 + dustRng() * 0.04, off: dustRng(),
        sz: 1.8 + dustRng() * 3.2, a: 0.03 + dustRng() * 0.055,
        sp: dustRng() < 0.5 ? tint('#A8C4A4') : tint('#CBDCC4')
      });
    }
    for (let i = 0; i < NEAR_N; i++) {
      nearDust.push({
        x: dustRng() * W, y: 860 - dustRng() * 820,
        amp: 16 + dustRng() * 58, f1: 0.05 + dustRng() * 0.16, f2: 0.11 + dustRng() * 0.24,
        ph: dustRng() * TAU, spd: 0.01 + dustRng() * 0.03, off: dustRng(),
        sz: 3.4 + dustRng() * 6.2, a: 0.045 + dustRng() * 0.075,
        sp: dustRng() < 0.45 ? tint('#B7CDB2') : tint('#D7E3CE')
      });
    }
    for (let i = 0; i < POLLEN_N; i++) {
      pollen.push({
        bx: 620 + dustRng() * 380, by: 470 + dustRng() * 220,
        r: 18 + dustRng() * 46, f: 0.13 + dustRng() * 0.26, ph: dustRng() * TAU,
        sz: 4 + dustRng() * 7, a: 0.05 + dustRng() * 0.1, sp: tint('#FFD79B')
      });
    }
  }

  function candleLitAmount(t) {
    let n = 0;
    for (let i = 0; i < 3; i++) if (t >= cue.ignite[i] && t < cue.extinguish[i] + 0.15) n++;
    return n / 3;
  }

  function drawMote(ctx, m, t, alt) {
    const u = ((t * m.spd + m.off) % 1 + 1) % 1;
    const y = 930 - u * 1010;
    const x = m.x + Math.sin(t * m.f1 + m.ph) * m.amp + Math.sin(t * m.f2 * 1.7 + m.ph * 1.9) * m.amp * 0.35;
    const a = m.a * Math.pow(Math.sin(Math.PI * u), 0.75) * (0.72 + 0.28 * Math.sin(t * m.f2 * 2.3 + m.ph));
    if (a <= 0.003) return;
    ctx.globalAlpha = clamp(a, 0, 0.4);
    const s = m.sz * (0.85 + 0.3 * Math.sin(t * m.f1 * 3 + m.ph));
    ctx.drawImage(SPR_SOFT[m.sp], x - s, y - s, s * 2, s * 2);
  }

  function renderDustBack(t) {
    if (!ctxB) return;
    ctxB.save();
    ctxB.globalCompositeOperation = 'lighter';
    for (let i = 0; i < farDust.length; i++) drawMote(ctxB, farDust[i], t, true);
    ctxB.restore();
    ctxB.globalAlpha = 1;
    ctxB.globalCompositeOperation = 'source-over';
  }

  function renderDustFront(t) {
    if (!ctxF) return;
    ctxF.save();
    ctxF.globalCompositeOperation = 'lighter';
    for (let i = 0; i < nearDust.length; i++) drawMote(ctxF, nearDust[i], t, false);
    const lit = candleLitAmount(t);
    for (let i = 0; i < pollen.length; i++) {
      const p = pollen[i];
      const x = p.bx + Math.cos(t * p.f + p.ph) * p.r;
      const y = p.by + Math.sin(t * p.f * 1.31 + p.ph) * p.r * 0.6;
      const pulse = 0.6 + 0.4 * Math.sin(t * 1.1 + p.ph * 2.3);
      const a = p.a * pulse * (0.45 + 0.55 * lit);
      if (a <= 0.003) continue;
      ctxF.globalAlpha = clamp(a, 0, 0.3);
      const s = p.sz * (0.9 + 0.2 * Math.sin(t * 0.9 + p.ph));
      ctxF.drawImage(SPR_SOFT[p.sp], x - s, y - s, s * 2, s * 2);
    }
    // 落到镜头前的火星
    for (let i = 0; i < fgSparks.length; i++) {
      const s = fgSparks[i];
      const f = 1 - s.age / s.ttl;
      const a = Math.pow(f, 0.8) * (0.75 + 0.25 * Math.sin(t * 22 + s.tw));
      if (a <= 0.004) continue;
      ctxF.globalAlpha = clamp(a, 0, 0.9);
      const sz = s.sz * (0.7 + 0.5 * f) * 2.6;
      ctxF.drawImage(SPR_SOFT[s.sp], s.x - sz / 2, s.y - sz / 2, sz, sz);
    }
    ctxF.restore();
    ctxF.globalAlpha = 1;
    ctxF.globalCompositeOperation = 'source-over';
  }

  /* ═══════════════ 10 · 渲染 ═══════════════ */
  let runT = 0;

  function clearLayer(ctx) {
    if (!ctx) return;
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    ctx.setLineDash([]);
    ctx.lineDashOffset = 0;
    ctx.clearRect(0, 0, W, H);
  }

  function renderFlashes(t) {
    if (!ctxB || !flashes.length) return;
    const core = coreSprite();
    ctxB.save();
    ctxB.globalCompositeOperation = 'lighter';
    for (let i = 0; i < flashes.length; i++) {
      const fl = flashes[i];
      const u = (t - fl.t0) / fl.dur;
      if (u < 0 || u > 1) continue;
      const e = easeOut(u);
      // ① 挂在整朵花后面的同色柔光（半径 ≈ 1.6 × 花半径）
      const r1 = fl.r * (0.9 + 0.75 * e);
      ctxB.globalAlpha = 0.14 * Math.pow(1 - u, 1.25);
      ctxB.drawImage(SPR_SOFT[tint(fl.col)], fl.x - r1, fl.y - r1, r1 * 2, r1 * 2);
      // ② 爆心：0.15s 的刺点（r 5px，近白 #E8FFF8）+ 0.30s 的紧光圈
      const cu = clamp((t - fl.t0) / 0.30, 0, 1);
      if (cu < 1) {
        const rc = 9 + 16 * cu;
        ctxB.globalAlpha = 0.55 * Math.pow(1 - cu, 1.6);
        ctxB.drawImage(core, fl.x - rc, fl.y - rc, rc * 2, rc * 2);
        const pu = clamp((t - fl.t0) / 0.15, 0, 1);
        if (pu < 1) {
          ctxB.globalAlpha = 1 - pu;
          ctxB.drawImage(core, fl.x - 5, fl.y - 5, 10, 10);
        }
      }
      if (!fl.lit && u < 0.4) {
        fl.lit = true;
        const L = window.Lighting;
        if (L && typeof L.flash === 'function') {
          safe(() => L.flash({ x: fl.x, y: fl.y, r: fl.r * 1.5, color: fl.col, intensity: 0.9, dur: 0.55 }));
        }
      }
    }
    ctxB.restore();
  }

  function renderRockets(t) {
    if (!ctxB) return;
    ctxB.save();
    ctxB.globalCompositeOperation = 'lighter';
    for (let i = 0; i < rockets.length; i++) {
      const rk = rockets[i];
      if (!rk.alive) continue;
      const p = rocketPos(rk, t);
      const flick = 0.72 + 0.28 * Math.sin(t * 71 + rk.ph) * Math.cos(t * 39.7 + rk.ph * 1.4);
      const sp = tint(rk.c[0]);
      const s = 24 * flick;
      ctxB.globalAlpha = 0.30;
      ctxB.drawImage(SPR_SOFT[sp], p.x - s * 1.4, p.y - s * 1.4, s * 2.8, s * 2.8);
      ctxB.globalAlpha = 0.98;
      ctxB.drawImage(SPR[sp], p.x - s / 2, p.y - s / 2, s, s);
      ctxB.globalAlpha = 0.55 * flick;
      ctxB.strokeStyle = rk.c[0];
      ctxB.lineWidth = 2.8;
      ctxB.beginPath();
      ctxB.moveTo(p.x, p.y);
      ctxB.lineTo(p.x + Math.sin(p.p * 6 + rk.ph) * 3, p.y + 26 + 16 * flick);
      ctxB.stroke();
    }
    ctxB.restore();
  }

  function renderParticles(t) {
    if (!ctxB) return;
    ctxB.save();
    ctxB.globalCompositeOperation = 'lighter';
    for (let i = 0; i < nSlot; i++) {
      if (!P_alive[i]) continue;
      const age = P_age[i], ttl = P_ttl[i];
      const f = 1 - age / ttl;
      // 亮度：0.6s 内从 1 掉到 ~0.4，之后缓慢熄灭（整朵花不会同时消失）
      const head = 0.42 + 0.58 * Math.exp(-age / 0.55);
      const tw = 0.80 + 0.20 * Math.sin(t * P_tws[i] + P_tw[i] * 6.283);
      const a = P_lum[i] * head * Math.pow(f, P_dec[i]) * tw * smooth(age / 0.04);
      if (a <= 0.006) continue;
      const spd = Math.hypot(P_vx[i], P_vy[i]);
      const sp = P_sp[i];
      const sz = P_sz[i] * (0.82 + 0.30 * f);
      if (P_sty[i] === 1) {
        // 火星：沿速度方向的短条，尾部收细、亮度 1→0.35
        const ux = P_vx[i] / (spd || 1), uy = P_vy[i] / (spd || 1);
        const L = P_slen[i] * (0.65 + 0.55 * f);
        const hx = P_x[i], hy = P_y[i];
        const mx = hx - ux * L * 0.5, my = hy - uy * L * 0.5;
        const tx = hx - ux * L, ty = hy - uy * L;
        ctxB.strokeStyle = SPR_HEX[sp] || '#fff';
        ctxB.lineCap = 'round';
        ctxB.lineWidth = Math.max(1, P_wid[i]);
        ctxB.globalAlpha = clamp(a, 0, 1);
        ctxB.beginPath(); ctxB.moveTo(hx, hy); ctxB.lineTo(mx, my); ctxB.stroke();
        ctxB.lineWidth = Math.max(0.8, P_wid[i] * 0.55);
        ctxB.globalAlpha = clamp(a * 0.36, 0, 1);
        ctxB.beginPath(); ctxB.moveTo(mx, my); ctxB.lineTo(tx, ty); ctxB.stroke();
        // 一点辉光：短条是"火星"，外面还应该有一层很淡的光
        const gs = 8 + 5 * f;
        ctxB.globalAlpha = clamp(a * 0.09, 0, 0.18);
        ctxB.drawImage(SPR_SOFT[sp], hx - gs / 2, hy - gs / 2, gs, gs);
        ctxB.globalAlpha = clamp(a * 0.95, 0, 1);
        ctxB.drawImage(SPR[SPR_HOT], hx - 0.7, hy - 0.7, 1.4, 1.4);
        continue;
      }
      // ③ 每颗星下面垫一层柔光（烟花"发光"靠这层）
      if (a > 0.12 && P_sz[i] > 10) {
        const gs = sz * 2.9;
        ctxB.globalAlpha = clamp(a * 0.30, 0, 0.6);
        ctxB.drawImage(SPR_SOFT[sp], P_x[i] - gs / 2, P_y[i] - gs / 2, gs, gs);
      }
      // 白热点：保证任何尺寸下都有一颗刺眼的核
      if (a > 0.16 && P_kind[i] !== 2) {
        const hs = sz * 0.18;
        ctxB.globalAlpha = clamp(a * 0.78, 0, 1);
        ctxB.drawImage(SPR[SPR_HOT], P_x[i] - hs / 2, P_y[i] - hs / 2, hs, hs);
      }
      if (spd > 40) {
        const ux = P_vx[i] / spd, uy = P_vy[i] / spd;
        const g0 = P_grav[i];
        // 残影：2 段，小、淡，只交代方向
        const gap = Math.min(10, spd * 0.008);
        const nGhost = live > 1200 ? 1 : 2;
        for (let g = 1; g <= nGhost; g++) {
          const gs = sz * (0.62 - g * 0.14);
          const dt = (gap * g) / (spd || 1);
          ctxB.globalAlpha = clamp(a * (0.20 - g * 0.07), 0, 1);
          ctxB.drawImage(SPR[sp],
            P_x[i] - ux * gap * g - gs / 2,
            P_y[i] - uy * gap * g + 0.5 * g0 * dt * dt - gs / 2, gs, gs);
        }
        // 尾迹：三段变宽（3.5u / 1.6u / 0.6u），越往后越细越淡，带抛物线沉降
        if (P_trail[i] > 0) {
          const tl = Math.min(96, spd * P_trail[i] * 5);
          const tau = tl / (spd || 1);
          const segs = [[0.42, 3.5, 0.6], [0.74, 1.6, 0.34], [1.0, 0.6, 0.16]];
          ctxB.strokeStyle = SPR_HEX[sp] || '#fff';
          ctxB.lineCap = 'round';
          let px = P_x[i], py = P_y[i];
          for (let g = 0; g < segs.length; g++) {
            const fr = segs[g][0], wU = segs[g][1], al = segs[g][2];
            const tx = P_x[i] - ux * tl * fr;
            const ty = P_y[i] - uy * tl * fr + 0.5 * g0 * tau * tau * fr * fr;
            ctxB.globalAlpha = clamp(a * al, 0, 0.8);
            ctxB.lineWidth = Math.max(0.5, wU * (0.7 + 0.4 * f));
            ctxB.beginPath();
            ctxB.moveTo(px, py);
            ctxB.lineTo(tx, ty);
            ctxB.stroke();
            px = tx; py = ty;
          }
        }
      }
      ctxB.globalAlpha = clamp(a, 0, 1);
      ctxB.drawImage(SPR[sp], P_x[i] - sz / 2, P_y[i] - sz / 2, sz, sz);
    }
    ctxB.restore();
  }

  function render(t) {
    runT = t;
    refreshAnchors(false);
    prepareFrame(t);
    clearLayer(ctxB);
    clearLayer(ctxF);
    renderFlames(t);
    if (reduced) return;
    renderDustBack(t);
    renderSmoke(t);
    renderBlow(t);
    renderFlashes(t);
    renderRockets(t);
    renderParticles(t);
    renderDustFront(t);
  }

  /* ═══════════════ 11 · 生命周期 ═══════════════ */
  function resize() {
    H = new URLSearchParams(location.search).has('app') && innerHeight > innerWidth ? 1600 * innerHeight / innerWidth : 900;
    const r = (B && B.pixelRatio) || 1;
    [[back, ctxB], [front, ctxF]].forEach(([c, ctx]) => {
      if (!c || !ctx) return;
      const w = Math.round(W * r), h = Math.round(H * r);
      if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
      ctx.setTransform(r, 0, 0, r, 0, 0);
    });
    anchorT = -999;
    beakCacheT = -999;
    refreshAnchors(true);
  }

  function init(bday) {
    B = bday || window.BDAY;
    cue = (B && B.cue) || window.CUES || null;
    if (!cue) return;
    svgScene = document.getElementById('svg-scene') || document.getElementById('stage-svg');
    svgEnv = document.getElementById('svg-env');
    back = (B && B.fx && B.fx.back) || document.getElementById('fx-back');
    front = (B && B.fx && B.fx.front) || document.getElementById('fx-front');
    ctxB = back ? back.getContext('2d') : null;
    ctxF = front ? front.getContext('2d') : null;
    // 注：开 imageSmoothing 会把星点能量摊薄一半（环整体变暗、亮度预算失守），
    // 所以这里保持默认插值；2× 放大时才看得出的"方块感"在 100% 画幅下不可见。
    reduced = !!(B && B.reduced);
    ready = true;

    // 调色板精灵（一次性预生成，运行期只 drawImage）
    const pal = ['#FFF7E7', '#FFF6E0', '#FFE9B0', '#FFB066', '#FF9A4D', '#FF9A55', '#FFD79B',
      '#FF6F91', '#FFC46B', '#7FF0D0', '#9DB4FF', '#C79BFF', '#FFE3A8', '#9FE4FF',
      '#A8C4A4', '#CBDCC4', '#B9CBB4', '#E4F2E0', '#C8D6C4', '#DCE6D6', '#B7CDB2', '#D7E3CE'];
    (cue.shells || []).forEach((s) => (s.c || []).forEach((c) => tint(c)));
    pal.forEach((c) => tint(c));
    SPR_HOT = tint('#FFFFFF');

    ensureFlames();
    buildSmokeStrands();
    buildDust();
    buildEvents();
    resize();
    resetSim();
    render(0);
  }

  function seek(t) {
    if (!ready) return;
    const tt = clamp(Number(t) || 0, 0, cue.dur);
    runT = tt;
    refreshAnchors(false);
    if (reduced) { render(tt); statsInfo.steps = 0; return; }
    const k = Math.round(tt * 60);
    const perf = window.performance;
    const t0 = perf.now();
    stepTo(k);
    const t1 = perf.now();
    render(tt);
    const t2 = perf.now();
    statsInfo.steps = k;
    statsInfo.seekMs = t1 - t0;
    statsInfo.frameMs = t2 - t1;
  }

  function loop() {
    if (!playing) return;
    rafId = requestAnimationFrame(loop);
    const t = B && typeof B.time === 'function' ? Math.min(cue.dur, B.time()) : 0;
    if (Math.abs(t - lastLoopT) < 1e-4) return;
    lastLoopT = t;
    const want = Math.round(t * 60);
    // 掉帧保护：落后超过 8 步直接对齐，避免"越卡越慢"的正反馈
    if (want - sim.step > 8) sim.step = want - 8;
    stepTo(want);
    render(t);
  }

  function play() {
    if (!ready || reduced || playing) return;
    playing = true;
    lastLoopT = -1;
    rafId = requestAnimationFrame(loop);
  }
  function pause() {
    playing = false;
    if (rafId) cancelAnimationFrame(rafId);
    rafId = 0;
  }

  /* 外部调用闸门：错误时刻直接丢弃，正确的排到对应步 */
  function acceptExternal(fn) {
    if (!ready) return;
    const tt = (B && typeof B.time === 'function') ? B.time() : runT;
    const step = Math.round(tt * 60);
    if (Math.abs(tt - runT) > 0.35) { statsInfo.extDrop++; return; }  // 跳帧/反向遍历
    if (step < sim.step - 1) { statsInfo.extDrop++; return; }         // 已经过去
    if (step <= sim.step) { statsInfo.extTake++; fn(); return; }      // 就地
    statsInfo.extTake++;
    pending.push({ step, fn });
  }

  function drainPending() {
    if (!pending.length) return;
    for (let i = pending.length - 1; i >= 0; i--) {
      if (pending[i].step <= sim.step) {
        const fn = pending[i].fn;
        pending.splice(i, 1);
        safe(fn);
      }
    }
  }

  function puff(x, y, dir) {
    if (reduced || !ready) return;
    if (typeof x === 'number' && typeof y === 'number') {
      beakCache = { x, y };
      beakCacheT = runT;
      frame.t = -999;
    }
    acceptExternal(() => puffEvent());
  }

  function dustPuff(x, y, n) {
    if (reduced || !ready) return;
    if (typeof x !== 'number') { x = 800; y = 660; }
    const count = n == null ? 6 : clamp(n | 0, 1, 24);
    acceptExternal(() => spawnDustPuff(x, y, count));
  }

  function spawnDustPuff(x, y, count) {
    for (let k = 0; k < count; k++) {
      S_.x = x + rr(-14, 14); S_.y = y + rr(-6, 6);
      S_.vx = rr(-46, 46); S_.vy = rr(-26, -4);
      S_.ttl = rr(0.5, 1.05); S_.sz = rr(8, 18); S_.drag = 2.2; S_.grav = -8;
      S_.lum = 0.34; S_.tw = rr(0, TAU); S_.tws = 3; S_.trail = 0;
      S_.sp = tint('#C6D3BE'); S_.kind = 2;
      S_.sway = 0; S_.sx = 1; S_.sy = 0; S_.sty = 0; S_.wid = 1.8; S_.slen = 6; S_.dec = 0.42; S_.spl = 0; S_.splN = 0; S_.splA = 0;
      spawn(); statsInfo.totalSpawns++;
    }
  }

  function stats() {
    return {
      active: live + rockets.filter((r) => r.alive).length + fgSparks.length,
      peak,
      steps: statsInfo.steps,
      stars, sparks,
      fg: fgSparks.length,
      dust: FAR_N + NEAR_N + POLLEN_N,
      totalSpawns: statsInfo.totalSpawns,
      seekMs: Math.round(statsInfo.seekMs * 100) / 100,
      frameMs: Math.round(statsInfo.frameMs * 100) / 100,
      reduced
    };
  }

  function bench() {
    if (!ready) return { replayMs: 0, renderMs: 0, active: 0, peak: 0 };
    const perf = window.performance;
    resetSim();
    const t0 = perf.now();
    stepTo(Math.round(cue.dur * 60));
    const t1 = perf.now();
    render(cue.dur);
    const t2 = perf.now();
    return {
      replayMs: Math.round((t1 - t0) * 10) / 10,
      renderMs: Math.round((t2 - t1) * 10) / 10,
      active: live, peak
    };
  }

  window.VFX = {
    init, resize, seek, play, pause,
    // v3.6.3：只按当前模拟状态重绘一帧，**不 step、不 reset**。
    // 供 resize / 布局变化后刷新画布；seek 会重置模拟并重放事件（会重复触发闪光）。
    repaint: render,
    puff, burst: (c) => burst(c), dust: dustPuff,
    stats, bench,
    version: '2.0.0'
  };
})();
