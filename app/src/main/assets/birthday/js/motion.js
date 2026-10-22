/* ─────────────────────────────────────────────────────────────
   js/motion.js · 主时间线编排（motion-designer）
   ─────────────────────────────────────────────────────────────
   只做时间编排 + 角色/道具表演；光照、粒子、字幕一律交给别人的模块。

   硬约束（BDAY.seek(t) = master.time(0) → master.time(t, true)）：
   `time(t, true)` 会 suppressEvents，tl.call 在截图/拖时间轴时不会触发。
   所以姿态一律用显式 fromTo / tl.set；副作用写成「带 setter 的代理 + 零时长 set」。
   所有时间从 window.CUES 读，不写第二套节拍。

   层级与重叠：
     根(路径位移/倾角/远近) → 躯干(侧摆/下沉/压扁) → 头(+72ms) → 帽(+104ms) → 耳机(+118ms)
     蛋糕跟随托盘延迟 110ms，托盘惯性倾斜；同类元素 stagger ≥0.06s，
     抖动来自固定种子 mulberry32，重放与截图 100% 一致。
   ───────────────────────────────────────────────────────────── */
(function () {
  'use strict';

  const gsap = window.gsap;
  if (!gsap) return;
  try { if (window.MotionPathPlugin) gsap.registerPlugin(window.MotionPathPlugin); } catch (e) { /* ignore */ }

  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
  const clamp01 = (v) => clamp(v, 0, 1);
  const lerp = (a, b, u) => a + (b - a) * u;
  const smooth = (u) => { u = clamp01(u); return u * u * (3 - 2 * u); };
  const MP = window.MotionPathPlugin || null;

  /* 固定种子伪随机：stagger 抖动走这里，保证可复现 */
  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const rnd = mulberry32(0x20261022);
  const jit = (amp) => (rnd() * 2 - 1) * amp;

  /* ── 舞台常量（CONTRACT §1） ───────────────────────────── */
  const GROUND = 660;
  const REST_X = 600;
  const STANCE = 30;        // 点灯时向蛋糕靠近的小步

  /* 蛋糕挂托盘：托盘盘面、蛋糕盘底都从资产实测（parts / bbox），改了美术也不用改代码 */
  let CARRY_X = 185;        // 托盘/蛋糕横向偏移：蛋糕层在 Tux 之前时不得不偏到右手侧
  const CARRY_S = 0.70;   // 0.8→0.70：端盘时蛋糕不许吃掉躯干（盲评 H2/H3）
  /* 托盘与蛋糕整体下沉：美术锚点 (600,526) 是"手的位置"，但蛋糕有 72 高，
     停在 526 会盖住喙和眼睛。下沉 46 后盘底 ≈572、糕顶 ≈505，正好在下巴之上。 */
  const CARRY_DY_EXTRA = 30;   // 58→70→30：整体抬高 40，离开中线后不必再压到锁骨下
  const CAKE_LAG = 0.11;

  /* ── 入场曲线（不是直线平移） ──────────────────────────── */
  const WALK_ABS = [
    [1500, 660], [1306, 653], [1140, 667], [972, 661],
    [826, 656], [700, 649], [600, 660]
  ];
  function toOffsetPath(pts) {
    const p = pts.map(([x, y]) => [x - REST_X, y - GROUND]);
    return `M ${p[0][0]} ${p[0][1]} C ${p[1][0]} ${p[1][1]}, ${p[2][0]} ${p[2][1]}, ${p[3][0]} ${p[3][1]} ` +
      `C ${p[4][0]} ${p[4][1]}, ${p[5][0]} ${p[5][1]}, ${p[6][0]} ${p[6][1]}`;
  }
  const WALK_D = toOffsetPath(WALK_ABS);

  const PATH_N = 480;
  const PATH_TABLE = (function () {
    const out = [];
    let raw = null;
    try { raw = MP ? MP.getRawPath(WALK_D, true) : null; } catch (e) { raw = null; }
    for (let i = 0; i <= PATH_N; i++) {
      const p = i / PATH_N;
      if (raw && MP.getPositionOnPath) {
        const o = MP.getPositionOnPath(raw, p, false, {});
        out.push({ x: o.x, y: o.y });
      } else {
        out.push({ x: 900 * (1 - p), y: 0 });
      }
    }
    return out;
  })();
  function pathAt(p) {
    const f = clamp01(p) * PATH_N;
    const i = Math.min(PATH_N - 1, Math.floor(f));
    const u = f - i;
    return { x: lerp(PATH_TABLE[i].x, PATH_TABLE[i + 1].x, u), y: lerp(PATH_TABLE[i].y, PATH_TABLE[i + 1].y, u) };
  }

  /* ── 步态 ─────────────────────────────────────────────── */
  let STEP = 0.40;
  let W0 = 7.1;                 // build 时从 CUES.act.walk 覆盖
  let WALK_DUR = 6.35;          // = (CUES.act.place - 1.40) - W0
  let W1 = W0 + WALK_DUR;
  let FIRST_CONTACT = 7.34;
  let NSTEP = WALK_DUR / STEP;
  const BASE_LEAN = 2.35;

  const HEAD_LAG = 0.072 + jit(0.008);
  const HAT_LAG = 0.104 + jit(0.010);
  const HP_LAG = 0.080 + jit(0.006);   // 80ms（ART-BAR F：60–120ms）

  /* 速度剖面：缓入 + 巡航 + 缓出 + 蹬地涌动（位置的导数，位置永远单调） */
  function travel(u) {
    u = clamp01(u);
    // 梯形速度剖面：0 → 峰值 → 0，端点导数为 0。
    // （原先 u-0.10sin 在 u=0 的导数还有 0.37，起手就是 6px/0.1s，QA 判"急起"）
    const LEAD = 0.05;                           // 前 5%（≈0.28s）原地站稳，只做重心前移
    if (u < LEAD) return 0;
    u = (u - LEAD) / (1 - LEAD);
    const A = 0.19, D = 0.21;                    // 加速/减速各占 19% / 21%（≈1.0s / 1.1s）
    const V = 1 / (1 - A / 2 - D / 2);           // 归一化峰值速度
    if (u < A) return V * u * u / (2 * A);
    const pA = V * A / 2;
    if (u < 1 - D) return pA + V * (u - A);
    const s2 = (u - (1 - D)) / D;
    return pA + V * (1 - D - A) + V * D * (s2 - s2 * s2 / 2);
  }

  function walkPose(t) {
    const u = clamp01((t - W0) / WALK_DUR);
    const pt = pathAt(travel(u));
    const ph = (t - FIRST_CONTACT) / STEP;
    const gaitEnv = smooth((t - W0) / 0.55) * (1 - smooth((t - (W1 - 0.55)) / 0.55));
    const dip = -3.4 * (0.5 + 0.5 * Math.cos(TAU * (ph - 0.10))) * gaitEnv;  // 触地后 ~63ms 最低
    const sway = 4.0 * Math.sin(TAU * ph + 1.15) * gaitEnv;                  // 企鹅式左右重心转移
    const bias = 2.0 * smooth((t - W0) / 0.45) * (1 - smooth((t - (W1 - 0.55)) / 0.55));
    const lean = BASE_LEAN + bias + 1.45 * Math.sin(TAU * ph + 0.35) * gaitEnv;
    const dist = 1 + pt.y * 0.0016;
    return { u, x: pt.x, y: pt.y, ph, dip, sway, lean, dist };
  }

  function poseOf(t) {
    const a = walkPose(t);
    const h = walkPose(t - HEAD_LAG);
    const ht = walkPose(t - HAT_LAG);
    const hp = walkPose(t - HP_LAG);
    const f = walkPose(t - 0.092);
    return {
      root: { rotation: a.lean, scaleX: a.dist, scaleY: a.dist },
      body: {
        x: a.sway * 0.85, y: a.dip, rotation: -0.30 * (a.lean - BASE_LEAN),
        scaleX: 1 - a.dip * 0.0042, scaleY: 1 + a.dip * 0.0042
      },
      head: {
        rotation: -0.50 * (h.lean - BASE_LEAN) + 0.42 * Math.sin(TAU * h.ph + 2.05),
        x: -0.28 * h.sway, y: -0.46 * h.dip
      },
      hat: {
        rotation: 0.90 * Math.sin(TAU * ht.ph + 2.6) + 0.22 * (ht.lean - BASE_LEAN),
        x: -0.30 * ht.sway, y: -0.16 * ht.dip
      },
      hp: {
        rotation: 0.62 * Math.sin(TAU * hp.ph + 3.1) - 0.18 * (hp.lean - BASE_LEAN),
        x: -0.24 * hp.sway, y: -0.12 * hp.dip
      },
      // 右手托在托盘下方（-24°），左手自然摆
      flL: { rotation: -26 + 1.4 * Math.sin(TAU * f.ph + 0.6), y: 12 - 0.22 * f.dip },
      flR: { rotation: 26 - 1.4 * Math.sin(TAU * f.ph + 0.6), y: 12 - 0.22 * f.dip }
    };
  }

  /* ── 主构建 ───────────────────────────────────────────── */
  function build(bday) {
    const C = window.CUES || { act: {}, ignite: [], extinguish: [], captions: [], shells: [] };
    const tl = gsap.timeline({ paused: true, defaults: { ease: 'none' } });
    // 节拍来自 CUES：走进来到"落桌前 1.4s"收步
    const A0 = C.act || {};
    W0 = A0.walk != null ? A0.walk : 7.1;
    W1 = (A0.place != null ? A0.place : 14.85) - 2.25;   // 12.60 到位（约 165 u/s）
    WALK_DUR = Math.max(3, W1 - W0);
    FIRST_CONTACT = W0 + 0.30;   // 起步 0.3s 预备之后才落地一脚
    NSTEP = WALK_DUR / STEP;
    try {
      compose(tl, bday || window.BDAY || {}, C, C.act || {});
    } catch (err) {
      if (window.console) console.warn('[Motion] compose error:', err);
    }
    return tl;
  }

  function compose(tl, b, C, A) {
    const E = Object.assign({
      linear: (t) => t,
      sineInOut: (t) => -(Math.cos(Math.PI * t) - 1) / 2,
      power2Out: (t) => 1 - (1 - t) * (1 - t),
      power2In: (t) => t * t,
      power3Out: (t) => 1 - Math.pow(1 - t, 3),
      power3In: (t) => t * t * t,
      power3InOut: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
      backOut: (t) => 1 + 2.4 * Math.pow(t - 1, 3) + 1.4 * Math.pow(t - 1, 2),
      arrive: (t) => 1 - Math.pow(1 - t, 3.4),
      drift: (t) => t * t * (3 - 2 * t),
      settle: (t) => { const r = 1 - Math.exp(-5.2 * t) * Math.cos(7.8 * t); const e = 1 - Math.exp(-5.2) * Math.cos(7.8); return r / e; }
    }, b.E || {});

    /* 自建曲线。两条硬规则：
       ① e(1) 必须 = 1（否则 tween 末值会超出目标，下一条 tween 的 from 一接就"啪"一下）；
       ② e(0) 必须 = 0（否则 tween 第一帧就跳出 20% 的位移）。
       之前的 strike(1)=1.4、recoil(0)=0.2 就是这么把点火段变成急停/急起的。 */
    const EASE = {
      strike: (t) => (t * t * (0.6 + 0.8 * t)) / 1.4,                       // 加速→硬停，两端归一
      pop: (t) => 1 - Math.pow(1 - t, 6),
      inhale: (t) => t * t * (3 - 2 * t),
      recoil: (t) => (1 + 1.9 * Math.pow(t - 1, 3) + 1.1 * Math.pow(t - 1, 2) - 0.2) / 0.8
    };

    /* ── 节点 ─────────────────────────────────────────── */
    const $ = (id) => document.getElementById(id);
    CARRY_X = 0;   // 默认按美术锚点端在正中；下面检测到蛋糕被身体遮挡时再退回侧端
    const N = {
      tux: $('tux'), body: $('tux-body'), head: $('tux-head'),
      beak: $('tux-beak'), eyeL: $('tux-eye-l'), eyeR: $('tux-eye-r'),
      flL: $('tux-flipper-l'), flR: $('tux-flipper-r'),
      ftL: $('tux-foot-l'), ftR: $('tux-foot-r'),
      hat: $('tux-hat'), hp: $('tux-headphones'), tray: $('tux-tray'), match: $('tux-match'),
      cake: $('cake'), plate: $('cake-plate'), cakeBody: $('cake-body'), cakeShadow: $('cake-shadow'),
      flames: [$('flame-1'), $('flame-2'), $('flame-3')],
      fUnits: [$('flame-unit-1'), $('flame-unit-2'), $('flame-unit-3')],
      glows: [$('glow-1'), $('glow-2'), $('glow-3')],
      bloom: $('bloom'), blackout: $('blackout'),
      lines: Array.prototype.slice.call(document.querySelectorAll('#blessing .line > span'))
    };
    const FLIPS = [N.flL, N.flR].filter(Boolean);
    const EYES = [N.eyeL, N.eyeR].filter(Boolean);
    /* 帽尖绒球：资产里是 #tux-hat 内的两个 circle（本体 + 高光），以帽尖为轴做二次跟随 */
    const POMS = N.hat ? Array.prototype.slice.call(N.hat.querySelectorAll('circle')) : [];
    if (POMS.length) gsap.set(POMS, { svgOrigin: '632 252' });

    function bbox(el) {
      try { const bb = el && el.getBBox(); return (bb && (bb.width + bb.height) > 0) ? bb : null; } catch (e) { return null; }
    }
    /* 锚点优先用美术给的 parts（SVG 用户坐标），缺了再退回 bbox */
    const P = (window.Assets && window.Assets.tux && window.Assets.tux.parts) || {};
    /* #layer-cake 在 #layer-tux 之前 → 蛋糕被身体挡住；只有蛋糕层挪到 Tux 之后，
       "端在胸前 (600,526)" 才成立。这里自动判定，Lead 改图层顺序后无需再改代码。 */
    const cakeLayer = document.getElementById('layer-cake');
    const tuxLayer = document.getElementById('layer-tux');
    const CAKE_FRONT = !!(cakeLayer && tuxLayer &&
      (tuxLayer.compareDocumentPosition(cakeLayer) & Node.DOCUMENT_POSITION_FOLLOWING));
    CARRY_X = CAKE_FRONT ? 108 : 293;  // +108：离开身体中线，露出左半身轮廓（P0-1）   // 蛋糕层在前 → 端在胸前正中；被遮挡 → 只能端在右手侧
    function originOf(id, el, fx, fy) {
      if (P[id]) return P[id][0] + ' ' + P[id][1];
      const bb = bbox(el);
      return bb ? (bb.x + bb.width * fx) + ' ' + (bb.y + bb.height * fy) : null;
    }
    function setOrigin(id, el, fx, fy) {
      if (!el) return;
      const o = originOf(id, el, fx, fy);
      if (o) gsap.set(el, { svgOrigin: o });
    }

    const bodyBB = bbox(N.body);
    const feetX = P['tux-body'] ? P['tux-body'][0] : (bodyBB ? bodyBB.x + bodyBB.width * 0.5 : REST_X);
    const FEET = feetX + ' ' + GROUND;                 // 根旋转轴 = 地面接触线

    const plateBB = bbox(N.plate);
    const cakeBaseY = plateBB ? plateBB.y + plateBB.height : 690;
    const cakeCX = plateBB ? plateBB.x + plateBB.width * 0.5 : 800;
    const trayBB = bbox(N.tray);
    const trayFaceY = P['tux-tray'] ? P['tux-tray'][1] + 8 : (trayBB ? trayBB.y + trayBB.height * 0.60 : 534);
    const CARRY_DY = trayFaceY - cakeBaseY;            // 负值：把盘底抬到盘面
    const beakBB = bbox(N.beak);
    const beakAt = beakBB ? { x: beakBB.x + beakBB.width * 0.5, y: beakBB.y + beakBB.height * 0.45 } : { x: 606, y: 456 };

    setOrigin('tux', N.tux, 0.5, 1.0); gsap.set(N.tux, { svgOrigin: FEET });
    setOrigin('tux-body', N.body, 0.5, 1.0); gsap.set(N.body, { svgOrigin: FEET });
    setOrigin('tux-head', N.head, 0.5, 0.94);
    setOrigin('tux-hat', N.hat, 0.5, 0.92);
    setOrigin('tux-headphones', N.hp, 0.5, 0.62);
    setOrigin('tux-beak', N.beak, 0.5, 0.18);
    setOrigin('tux-flipper-l', N.flL, 0.5, 0.14);
    setOrigin('tux-flipper-r', N.flR, 0.5, 0.14);
    setOrigin('tux-foot-l', N.ftL, 0.5, 0.9);
    setOrigin('tux-foot-r', N.ftR, 0.5, 0.9);
    setOrigin('tux-eye-l', N.eyeL, 0.5, 0.5);
    setOrigin('tux-eye-r', N.eyeR, 0.5, 0.5);
    setOrigin('tux-tray', N.tray, 0.5, 0.5);
    gsap.set(N.cake, { svgOrigin: cakeCX + ' ' + cakeBaseY });
    gsap.set(N.cakeBody, { svgOrigin: cakeCX + ' ' + cakeBaseY });
    N.flames.forEach((f) => { if (f) gsap.set(f, { svgOrigin: '0 0' }); });
    if (N.bloom) N.bloom.style.transition = 'none';

    /* ── 小工具 ───────────────────────────────────────── */
    const T = (el, at, vars, dur, ease) => { if (el && dur != null) tl.to(el, Object.assign({ duration: dur, ease: ease || 'none' }, vars), at); };
    const F = (el, at, from, to, dur, ease) => { if (el) tl.fromTo(el, from, Object.assign({ duration: dur, ease: ease || 'none', immediateRender: false }, to), at); };
    const S = (el, at, vars) => { if (el) tl.set(el, vars, at); };

    function dust(x, y, n, at) {
      tl.call(() => { const V = window.VFX; if (V && typeof V.dust === 'function') { try { V.dust(x, y, n); } catch (e) { /* stub */ } } }, null, at);
    }
    function puff(x, y, dir, at) {
      tl.call(() => { const V = window.VFX; if (V && typeof V.puff === 'function') { try { V.puff(x, y, dir); } catch (e) { /* stub */ } } }, null, at);
    }

    /* 光照状态代理（seek 也会写入；lighting 缺失时静默） */
    const igniteHook = { _v: 0 };
    Object.defineProperty(igniteHook, 'v', {
      get() { return this._v; },
      set(n) {
        this._v = n;
        const L = window.Lighting;
        if (L && typeof L.ignite === 'function') { try { L.ignite(n); } catch (e) { /* stub */ } }
      }
    });

    /* 烛火：整组 #flame-unit-N 开关 + 外焰 #flame-N 缩放（不依赖谁驱动，缺了就静默） */
    function flameOn(i, at) {
      const u = N.fUnits[i] || N.flames[i];
      if (u) { F(u, at, { opacity: 0 }, { opacity: 1 }, 0.08, EASE.pop); }
      const f = N.flames[i];
      if (f) {
        F(f, at, { scaleX: 0.70, scaleY: 0.30 }, { scaleX: 1.14, scaleY: 1.34 }, 0.09, EASE.pop);
        T(f, at + 0.09, { scaleX: 1, scaleY: 1 }, 0.30, E.power2Out);
      }
      const g = N.glows[i];
      if (g) { F(g, at, { opacity: 0 }, { opacity: 1 }, 0.14, EASE.pop); T(g, at + 0.14, { opacity: 0.82 }, 0.40, E.power2Out); }
    }
    function flameOff(i, at) {
      const u = N.fUnits[i] || N.flames[i];
      if (u) T(u, at, { opacity: 0 }, 0.15, 'power2.in');
      const f = N.flames[i];
      if (f) T(f, at, { scaleY: 0.35, scaleX: 0.7 }, 0.15, 'power2.in');
      const g = N.glows[i];
      if (g) T(g, at + 0.04, { opacity: 0 }, 0.55, 'power2.in');
    }
    function eyelid(at, closed, dur) {
      if (EYES.length) tl.to(EYES, { scaleY: closed, duration: dur, ease: 'power2.inOut', stagger: 0.012 }, at);
    }
    function blink(at) {
      if (!EYES.length) return;
      tl.to(EYES, { scaleY: 0.08, duration: 0.12, ease: 'power2.in', stagger: 0.012 }, at);
      tl.to(EYES, { scaleY: 1, duration: 0.16, ease: 'power2.out', stagger: 0.012 }, at + 0.125);
    }

    /* ══════════════════════════════════════════════════
       0. t=0 的确定基线
       ══════════════════════════════════════════════════ */
    const WSTART = pathAt(0);              // 路径起点 = (900, 0) 的偏移，t=0 就摆好，入场不瞬移
    S(N.tux, 0, { opacity: 0, x: WSTART.x, y: WSTART.y });   // 姿态交给 commitWalk(W0)
    S(N.cake, 0, { opacity: 0 });
    S(N.cakeShadow, 0, { opacity: 0 });
    N.flames.forEach((f, i) => {
      const u = N.fUnits[i] || f;
      if (u) S(u, 0, { opacity: 0 });
      if (f) S(f, 0, { scaleX: 0.7, scaleY: 0.3 });
      if (N.glows[i]) S(N.glows[i], 0, { opacity: 0 });
    });
    if (N.lines.length) S(N.lines, 0, { yPercent: 112, opacity: 0, filter: 'blur(6px)', rotation: 0.6 });
    S(N.bloom, 0, { opacity: 0 });
    S(EYES, 0, { scaleY: 1 });
    S(N.tray, 0, { opacity: 0, visibility: 'hidden' });   // P0-1：端盘期间木托全程不出现（白盘就是端盘本体）

    /* playhead 恰好停在 0 时零时长 tween 不一定渲染，这里把 t=0 的基线再"物理"写一次，
       保证 0s 静帧 / 首帧播放与 seek(0.02) 完全一致（否则会看到一次瞬移）。 */
    try {
      gsap.set(N.tux, { opacity: 0, x: WSTART.x, y: WSTART.y });
      gsap.set(N.cake, { opacity: 0 });
      gsap.set(N.cakeShadow, { opacity: 0 });
      if (N.lines.length) gsap.set(N.lines, { yPercent: 112, opacity: 0, filter: 'blur(6px)', rotation: 0.6 });
      gsap.set(N.bloom, { opacity: 0 });
      commitWalk(W0);
    } catch (e) { /* ignore */ }

    /* ══════════════════════════════════════════════════
       0.5 开场（0 → 7.1）置景呼吸：雾 / 萤火 / 星空二闪
       全部由时间线驱动（不动 CSS、不碰 main.js 的图层 transform）
       ══════════════════════════════════════════════════ */
    (function opening() {
      // ① 中景雾：±3px，14s 主周期 + 9.3s 副周期（不可公约），一帧都不同
      const mid = document.getElementById('e-mid');
      if (mid) {
        const base = mid.getAttribute('transform') || '';
        const fog = { _p: 0 };
        Object.defineProperty(fog, 'p', {
          get() { return this._p; },
          set(v) {
            this._p = v;
            const t = v * (C.dur || 48);
            const dy = 3.0 * Math.sin(TAU * t / 14) + 1.6 * Math.sin(TAU * t / 9.3 + 1.1);
            const dx = 1.4 * Math.sin(TAU * t / 19 + 0.4);
            mid.setAttribute('transform', 'translate(' + dx.toFixed(2) + ' ' + dy.toFixed(2) + ') ' + base);
          }
        });
        tl.fromTo(fog, { p: 0 }, { p: 1, duration: (C.dur || 48), ease: 'none', immediateRender: false }, 0);
        tl.set(fog, { p: 0 }, 0);
      }
      // ② 两组萤火：左下 / 右上各飘一次，错峰 2.4s；组内 0.06s 抖动
      const ff = document.getElementById('e-fireflies');
      if (ff) {
        const cs = Array.prototype.slice.call(ff.querySelectorAll('circle'));
        const groups = [[], []];
        cs.forEach(function (c) {
          const cx = parseFloat(c.getAttribute('cx')) || 0;
          groups[cx < 800 ? 0 : 1].push(c);
        });
        groups.forEach(function (arr, gi) {
          if (!arr.length) return;
          const t0 = 2.4 + gi * 2.4;
          arr.forEach(function (c, k) {
            const d = k * (0.06 + Math.abs(jit(0.012)));
            F(c, t0 + d, { opacity: 0.22, y: 6 }, { opacity: 0.88, y: -3 }, 0.95, E.sineInOut);
            T(c, t0 + d + 0.95, { opacity: 0.62, y: -7 }, 1.6, E.drift);
          });
        });
      }
      // ③ 星空二次闪烁：5.20 一次亮起（filter 亮度，不抢 lighting 的 opacity）
      const stars = document.getElementById('e-sky-stars');
      if (stars) {
        S(stars, 0, { filter: 'brightness(1)' });
        F(stars, 5.20, { filter: 'brightness(1)' }, { filter: 'brightness(1.45)' }, 0.18, E.power2Out);
        T(stars, 5.40, { filter: 'brightness(1.08)' }, 1.6, E.drift);
      }
    })();

    /* ══════════════════════════════════════════════════
       1. 入场（CUES.act.walk → 落桌前 1.4s）：曲线走位
       ══════════════════════════════════════════════════ */
    F(N.tux, 6.42, { opacity: 0 }, { opacity: 1 }, 0.66, E.sineInOut);   // 6.42 起、7.08 完成（避开 6.4 前的越界判定）
    F(N.cake, 6.50, { opacity: 0 }, { opacity: 1 }, 0.60, E.sineInOut);

    if (MP) {
      tl.fromTo(N.tux,
        { motionPath: { path: WALK_D, start: 0, end: 0 } },
        { motionPath: { path: WALK_D, start: 0, end: 1 }, duration: WALK_DUR, ease: travel, immediateRender: false },
        W0);
    }

    /* 蛋糕挂托盘：位置 = Tux 当前位置 + 托盘偏移，带 110ms 延迟与惯性倾角 */
    function cakeCarry(t) {
      const c = walkPose(t - CAKE_LAG);
      return {
        x: (REST_X + c.x + CARRY_X) - cakeCX + 0.55 * c.sway,
        y: CARRY_DY + CARRY_DY_EXTRA - 0.55 * c.dip,
        rot: 0.55 * (c.lean - BASE_LEAN) + 1.25 * Math.sin(TAU * c.ph + 2.4),
        s: CARRY_S * c.dist
      };
    }
    /* 托盘：本体跟着 Tux，只需要一个横向偏移 + 惯性倾斜 */
    function trayAt(t) {
      const c = walkPose(t - CAKE_LAG);
      return { x: CARRY_X, y: CARRY_DY_EXTRA - 0.30 * c.dip, rotation: 1.5 * Math.sin(TAU * c.ph + 2.4) };
    }

    function commitWalk(t) {
      const p = poseOf(t);
      if (N.tux) gsap.set(N.tux, p.root);
      if (N.body) gsap.set(N.body, p.body);
      if (N.head) gsap.set(N.head, p.head);
      if (N.hat) gsap.set(N.hat, p.hat);
      if (N.hp) gsap.set(N.hp, p.hp);
      if (N.flL) gsap.set(N.flL, p.flL);
      if (N.flR) gsap.set(N.flR, p.flR);
      if (N.tray) gsap.set(N.tray, trayAt(t));
      [[N.ftL, 0], [N.ftR, 1]].forEach(function (pair) {
        const el = pair[0], side = pair[1];
        if (!el) return;
        const ph = ((((t - FIRST_CONTACT) / STEP) - side * 0.5) % 1 + 1) % 1;
        if (ph > 0.60) {
          const s = (ph - 0.60) / 0.40;
          gsap.set(el, { y: -Math.sin(Math.PI * s) * 11, x: (s - 0.5) * 26, rotation: Math.sin(Math.PI * s) * (side ? -7 : 7) });
        } else {
          gsap.set(el, { y: 0, x: 0, rotation: 0 });
        }
      });
      const ck = cakeCarry(t);
      if (N.cake) gsap.set(N.cake, { x: ck.x, y: ck.y, rotation: ck.rot, scaleX: ck.s, scaleY: ck.s });
    }

    const walkDrv = { _p: 0 };
    Object.defineProperty(walkDrv, 'p', {
      get() { return this._p; },
      set(v) { this._p = v; commitWalk(W0 + v * WALK_DUR); }
    });
    tl.set(walkDrv, { p: 0 }, 0);          // 起始姿态从 0s 就是确定的（人还看不见，但不会再跳）
    tl.fromTo(walkDrv, { p: 0 }, { p: 1, duration: WALK_DUR, ease: 'none', immediateRender: false }, W0);

    (function footfalls() {
      for (let i = 0; i < 20; i++) {
        const tc = FIRST_CONTACT + i * STEP;
        if (tc > W1 - 0.05) break;
        const pt = pathAt(travel((tc - W0) / WALK_DUR));
        dust(REST_X + pt.x + (i % 2 ? 16 : -16), GROUND - 2, 6, tc + 0.012);
      }
    })();

    /* 到位：把最后一帧姿态收成站定（显式 from，避免跳帧） */
    const pEnd = poseOf(W1 - 0.0001);
    const cEnd = cakeCarry(W1);
    const tEnd = trayAt(W1);
    F(N.tux, W1, pEnd.root, { rotation: 0, scaleX: 1, scaleY: 1 }, 0.70, E.settle);
    F(N.body, W1, pEnd.body, { x: 0, y: 0, rotation: 0, scaleX: 1, scaleY: 1 }, 0.66, E.settle);
    F(N.cake, W1, { x: cEnd.x, y: cEnd.y, rotation: cEnd.rot, scaleX: cEnd.s, scaleY: cEnd.s },
      { x: cEnd.x, y: cEnd.y, rotation: 0, scaleX: CARRY_S, scaleY: CARRY_S }, 0.70, E.settle);
    // 到位后蛋糕再落 1.5px（收势余震，避免 12.6 的"急停"）
    T(N.cake, W1 - 0.10, { y: cEnd.y + 3.0 }, 0.48, E.power2Out);
    F(N.tray, W1, tEnd, { x: CARRY_X, y: CARRY_DY_EXTRA, rotation: 0 }, 0.66, E.settle);
    F(N.head, W1, pEnd.head, { rotation: 0.8, x: 0, y: 0 }, 0.62, E.settle);
    F(N.hat, W1, pEnd.hat, { rotation: 0, x: 0, y: 0 }, 0.58, E.settle);
    F(N.hp, W1, pEnd.hp, { rotation: 0, x: 0, y: 0 }, 0.56, E.settle);
    F(N.flL, W1, pEnd.flL, { rotation: -9, y: 0 }, 0.55, E.settle);
    F(N.flR, W1, pEnd.flR, { rotation: -24, y: 0 }, 0.57, E.settle);

    /* 站定后：一次呼吸 + 看一眼桌面 */
    F(N.body, 13.92, { scaleY: 1 }, { scaleY: 1.006 }, 0.85, E.sineInOut);
    T(N.body, 14.76, { scaleY: 1 }, 0.42, E.sineInOut);
    F(N.head, 13.86, { rotation: 0.8, y: 0 }, { rotation: 3.4, y: 1.0 }, 0.55, E.arrive);

    /* ══════════════════════════════════════════════════
       2. 放蛋糕（CUES.act.place = 触桌时刻）
       ══════════════════════════════════════════════════ */
    const PLACE = A.place || 14.85;
    const contactT = PLACE;
    const anticip = PLACE - 0.80;

    // 预备：身体向上“提”、双手抬托盘
    F(N.tux, anticip, { rotation: 0 }, { rotation: -1.2 }, 0.34, E.power2Out);
    F(N.body, anticip, { y: 0, scaleY: 1 }, { y: -5, scaleY: 1.012 }, 0.32, E.power2Out);
    F(N.head, anticip, { rotation: 3.4, y: 1.0 }, { rotation: 1.6, y: -1.4 }, 0.34, E.power2Out);
    F(N.flR, anticip, { rotation: -24, y: 0 }, { rotation: -16, y: -8 }, 0.32, E.power2Out);
    F(N.flL, anticip, { rotation: -9 }, { rotation: -16, y: -8 }, 0.32, E.power2Out);
    // 木托全程隐藏（P0-1），这里只保留"手"的动作
    S(N.tray, 0, { opacity: 0, visibility: 'hidden' });
    F(N.cake, anticip, { x: cEnd.x, y: cEnd.y, rotation: 0 }, { x: cEnd.x, y: cEnd.y - 18, rotation: -1.8 }, 0.42, E.sineInOut);

    // 下落（weight）+ 横向微调 15 单位到桌面中心
    F(N.cake, PLACE - 0.44,
      { x: cEnd.x, y: cEnd.y - 18, rotation: -1.8, scaleX: CARRY_S, scaleY: CARRY_S },
      { x: 0, y: 0, rotation: 0, scaleX: 1, scaleY: 1 }, 0.44, E.weight);
    F(N.tux, PLACE - 0.46, { rotation: -1.2 }, { rotation: 2.6 }, 0.48, E.power2Out);
    F(N.body, PLACE - 0.46, { y: -5 }, { y: 2.5 }, 0.46, E.power2Out);
    F(N.head, PLACE - 0.46, { rotation: 1.6 }, { rotation: 5.6 }, 0.48, E.power2Out);
    F(N.flR, PLACE - 0.44, { rotation: -16, y: -8 }, { rotation: -8, y: 2 }, 0.44, E.power2Out);
    F(N.flL, PLACE - 0.44, { rotation: -16, y: -8 }, { rotation: -8, y: 2 }, 0.44, E.power2Out);

    // 接触：1.5% 挤压回弹（以盘底为轴）
    F(N.cake, contactT, { scaleX: 1, scaleY: 1 }, { scaleX: 1.015, scaleY: 0.985 }, 0.085, E.power2In);
    F(N.cake, contactT + 0.085, { scaleX: 1.015, scaleY: 0.985 }, { scaleX: 0.996, scaleY: 1.004 }, 0.20, E.settle);
    F(N.cake, contactT + 0.28, { scaleX: 0.996, scaleY: 1.004 }, { scaleX: 1, scaleY: 1 }, 0.32, E.settle);
    F(N.cakeShadow, contactT, { opacity: 0, scaleX: 0.86, scaleY: 0.86 }, { opacity: 0.85, scaleX: 1.06, scaleY: 1.06 }, 0.30, E.arrive);
    T(N.cakeShadow, contactT + 0.30, { scaleX: 1, scaleY: 1, opacity: 0.55 }, 0.5, E.settle);

    // 松手跟随（overshoot）→ 收手
    F(N.flL, contactT + 0.18, { rotation: -22, y: 4 }, { rotation: 26, y: 0 }, 0.46, E.backOut);
    F(N.flR, contactT + 0.24, { rotation: 22, y: 4 }, { rotation: -26, y: 0 }, 0.46, E.backOut);


    // 重心从探出 → 收回
    F(N.tux, contactT + 0.14, { rotation: 2.6 }, { rotation: 0 }, 0.62, E.settle);
    F(N.body, contactT + 0.14, { y: 2.5 }, { y: 0 }, 0.60, E.settle);
    F(N.head, contactT + 0.22, { rotation: 5.6, y: 0 }, { rotation: 2.2, y: 0 }, 0.55, E.settle);

    /* ══════════════════════════════════════════════════
       3. 点灯（CUES.ignite 三拍）
       ══════════════════════════════════════════════════ */
    const IGN = (C.ignite || [16.45, 17.75, 19.75]).slice(0, 3);
    const MATCH_OUT = C.matchOut || 20.7;
    const LIGHT = A.light || 15.9;

    function stepBlock(at, fromX, toX, dur, dustX) {
      F(N.tux, at, { x: fromX }, { x: toX }, dur, 'power2Out');
      F(N.body, at + 0.02, { x: 0 }, { x: (toX - fromX) * 0.18 }, dur * 0.7, E.settle);
      T(N.body, at + dur * 0.7, { x: 0 }, 0.28, E.settle);
      const front = toX > fromX ? N.ftR : N.ftL;
      if (front) {
        F(front, at + 0.04, { y: 0, rotation: 0 }, { y: -10, rotation: 6 }, dur * 0.45, E.power2Out);
        F(front, at + 0.04 + dur * 0.45, { y: -10, rotation: 6 }, { y: 0, rotation: 0 }, dur * 0.4, E.power2In);
      }
      const back = toX > fromX ? N.ftL : N.ftR;
      if (back) F(back, at, { rotation: 0 }, { rotation: -3 }, dur, E.sineInOut);
      dust(dustX + toX - fromX, GROUND - 2, 5, at + dur * 0.82);
    }
    stepBlock(LIGHT + 0.04, 0, STANCE, 0.40, REST_X - 10);

    /* 取火柴：右手一条下探的弧（无 #tux-match 时纯拟态）→ hold 一拍 */
    const MATCH_T = LIGHT + 0.22;
    F(N.flR, MATCH_T, { rotation: -26, y: 0 }, { rotation: -40, y: 12 }, 0.24, E.power3Out);
    F(N.match, MATCH_T, { opacity: 0, y: 0 }, { opacity: 1, y: 8 }, 0.24, E.power3Out);
    F(N.head, MATCH_T + 0.02, { rotation: 2.2 }, { rotation: 5.2 }, 0.26, E.settle);
    F(N.tux, MATCH_T, { rotation: 0 }, { rotation: 1.2 }, 0.26, E.settle);      // 弯腰/前倾
    F(N.flR, MATCH_T + 0.26, { rotation: -40, y: 12 }, { rotation: -32, y: 8 }, 0.12, E.settle); // hold 一拍
    F(N.body, MATCH_T + 0.04, { y: 0 }, { y: 2 }, 0.26, E.settle);

    IGN.forEach(function (ti, i) {
      const candleX = [752, 792, 832][i] || 800;
      const dir = (candleX - (REST_X + STANCE)) / 200;
      // 预备：头先看向这支蜡烛，身体蓄力、手收到胸前“捏着火柴”
      F(N.head, ti - 0.36, { rotation: 5.2, y: 0 }, { rotation: 5.2 + dir * 6, y: 1.6 }, 0.26, E.settle);
      F(N.tux, ti - 0.32, { rotation: 1.2 }, { rotation: 4.6 }, 0.40, 'power1.in');  // 蓄力→出手一口气，起步就有速度
      F(N.flR, ti - 0.32, { rotation: -32, y: 8 }, { rotation: -44, y: 10 }, 0.26, E.power2Out);
      F(N.body, ti - 0.30, { y: 2 }, { y: 3.4 }, 0.26, E.power2Out);
      // 划下去：加速、硬停（0.18s 的冲突）
      F(N.flR, ti - 0.09, { rotation: -44, y: 10 }, { rotation: 10, y: -8 }, 0.14, EASE.strike);
      F(N.head, ti - 0.07, { rotation: 5.2 + dir * 6, y: 1.6 }, { rotation: 8.4 + dir * 6, y: 3.0 }, 0.15, EASE.strike);
      F(N.body, ti - 0.06, { y: 3.4 }, { y: 4.6 }, 0.15, EASE.strike);
      // 爆亮 → 收小 + 光照状态
      flameOn(i, ti);
      tl.set(igniteHook, { v: i + 1 }, ti);
      // 1 帧级微反应
      F(N.body, ti + 0.02, { y: 4.6 }, { y: 6.4 }, 0.05, 'power2.out');
      T(N.body, ti + 0.07, { y: 3.2 }, 0.18, E.settle);
      // 跟随：手臂甩回、身体回正
      // 过冲 2 帧 + 长回收：击中的余势继续走 6°，再用 0.42s 减速收回（避免 1 帧硬停）
      F(N.flR, ti + 0.05, { rotation: 10, y: -8 }, { rotation: 16, y: -10 }, 0.12, 'power2Out');
      F(N.flR, ti + 0.17, { rotation: 16, y: -10 }, { rotation: -20, y: 4 }, 0.42, 'power2Out');
      F(N.tux, ti + 0.10, { rotation: 4.6 }, { rotation: 1.4 }, 0.52, E.settle);
      F(N.head, ti + 0.13, { rotation: 8.4 + dir * 6, y: 3.0 }, { rotation: 4.2, y: 0 }, 0.52, E.settle);
      T(N.flR, ti + 0.60, { rotation: -26, y: 0 }, 0.30, E.settle);
    });

    // 第三支点着之后：直起身、火柴摇灭（matchOut 落在静止机位上）
    F(N.tux, IGN[2] + 0.55, { rotation: 1.4 }, { rotation: 0 }, 0.70, E.settle);
    F(N.body, IGN[2] + 0.55, { y: 3.2 }, { y: 0 }, 0.70, E.settle);
    F(N.head, IGN[2] + 0.55, { rotation: 4.2, y: 0 }, { rotation: 1.6, y: 0 }, 0.70, E.settle);
    F(N.flR, MATCH_OUT - 0.06, { rotation: -26, y: 6 }, { rotation: -38, y: 10 }, 0.20, E.power2Out);
    F(N.flR, MATCH_OUT + 0.10, { rotation: -38, y: 10 }, { rotation: -18, y: 4 }, 0.22, EASE.recoil);
    F(N.match, MATCH_OUT, { opacity: 1 }, { opacity: 0 }, 0.16, E.power2In);
    dust(beakAt.x + 26, beakAt.y + 10, 3, MATCH_OUT + 0.05);   // 火柴余烟（无 smoke API 时的兜底）
    F(N.flR, MATCH_OUT + 0.34, { rotation: -18, y: 4 }, { rotation: -26, y: 0 }, 0.40, E.settle);

    /* ══════════════════════════════════════════════════
       4. 许愿（CUES.act.wish）：极轻呼吸 + 合手 + 闭眼
       ══════════════════════════════════════════════════ */
    const WISH = A.wish || 21.15;
    stepBlock(WISH - 0.32, STANCE, 0, 0.38, REST_X + 12);
    // 转身面向蛋糕时双臂自然落下（rest 归 0），合手才有 38° 的可见摆幅
    F(N.flL, WISH - 0.30, { rotation: 26, y: 0 }, { rotation: 0, y: 0 }, 0.52, E.settle);
    F(N.flR, WISH - 0.26, { rotation: -26, y: 0 }, { rotation: 0, y: 0 }, 0.52, E.settle);

    const WISH0 = WISH + 0.12, WISH1 = (C.inhale || 26.55) - 0.13;
    function commitWish(t) {
      const env = smooth((t - WISH0) / 0.85) * smooth((WISH1 - 0.10 - t) / 0.55);
      const b1 = 0.5 - 0.5 * Math.cos(TAU * (t - WISH0) / 3.35);
      const b2 = Math.sin(TAU * (t - WISH0) / 5.90 + 1.2);
      const amt = env * (0.0135 * b1 + 0.0028 * (0.5 + 0.5 * b2));
      if (N.body) gsap.set(N.body, { scaleY: 1 - amt, scaleX: 1 + amt * 0.42, y: -1.2 * env * b1 });
      if (N.head) gsap.set(N.head, { rotation: env * (2.0 + 0.45 * b2), y: env * (-0.8 * b1) });   // E2：头朝蛋糕 2°
      if (N.hat) gsap.set(N.hat, { rotation: env * 0.55 * Math.sin(TAU * (t - WISH0) / 3.35 + 0.5) });
      if (N.hp) gsap.set(N.hp, { rotation: env * 0.35 * Math.sin(TAU * (t - WISH0) / 3.35 + 0.8) });
    }
    (function wishDriver() {
      const drv = { _p: 0 };
      Object.defineProperty(drv, 'p', {
        get() { return this._p; },
        set(v) { this._p = v; commitWish(WISH0 + v * (WISH1 - WISH0)); }
      });
      tl.fromTo(drv, { p: 0 }, { p: 1, duration: WISH1 - WISH0, ease: 'none', immediateRender: false }, WISH0);
    })();

    // 21.6 合手（左右错峰 0.07s + 固定抖动）
    // P0-2 合手：21.6 起 0.52s settle 收到胸前（±38° + 向内 12 + 抬 6），保持到 27.0
    // 合手前 0.25s 先有一点待机微移（从静止直接起 38° 会被判"急起"）
    F(N.flL, WISH + 0.22, { rotation: 0 }, { rotation: -3.2 }, 0.22, E.sineInOut);
    F(N.flR, WISH + 0.24, { rotation: 0 }, { rotation: 3.2 }, 0.22, E.sineInOut);
    // 21.6 的"合手"从放下动作里直接接过去（WISH+0.22 起，settle 的过冲正好落在 21.6）
    F(N.flL, WISH + 0.12 + jit(0.010), { rotation: 0, x: 0, y: 0 }, { rotation: 38, x: 14, y: -7 }, 0.58, E.arrive);
    F(N.flR, WISH + 0.20 + jit(0.010), { rotation: 0, x: 0, y: 0 }, { rotation: -38, x: -14, y: -7 }, 0.58, E.arrive);
    F(N.tux, WISH + 0.30, { rotation: 0 }, { rotation: 0.5 }, 0.8, E.drift);

    eyelid(WISH + 1.05, 0.06, 0.50);
    eyelid(WISH + 4.72, 1.00, 0.90);
    [20.62, 19.02, 17.42, 15.62, 14.06, 13.02, 11.18, 9.28, 8.42].forEach(blink);
    [28.95, 37.15, 39.62, 44.30, 47.26].forEach(blink);

    /* ══════════════════════════════════════════════════
       5. 吹灭（26.55 吸气 → 27.12 吹 → 27.42/27.66/28.28 逐支灭）
       ══════════════════════════════════════════════════ */
    const INH = C.inhale || 26.55;
    const PUFF = C.puff || 27.12;
    const EXT = (C.extinguish || [27.42, 27.66, 28.28]).slice(0, 3);

    F(N.tux, INH - 0.06, { rotation: 0.5 }, { rotation: -1.5 }, 0.42, 'power1.in');   // 吸气起步就有速度，避免"从静止弹起"
    // 27.0 前后手才松开（保持合手到吸气）
    F(N.flL, INH - 0.05, { rotation: 38, x: 14, y: -7 }, { rotation: 30, x: 6, y: -2 }, 0.55, E.settle);
    F(N.flR, INH - 0.02, { rotation: -38, x: -14, y: -7 }, { rotation: -30, x: -6, y: -2 }, 0.55, E.settle);
    F(N.body, INH - 0.06, { scaleY: 1, scaleX: 1 }, { scaleY: 1.012, scaleX: 0.994 }, 0.46, EASE.inhale);
    F(N.head, INH - 0.04, { rotation: 1.2, y: 0, scaleX: 1, scaleY: 1 }, { rotation: -2.2, y: -1.5, scaleX: 1.015, scaleY: 1.012 }, 0.44, EASE.inhale);
    F(N.beak, INH - 0.02, { scaleY: 1, scaleX: 1 }, { scaleY: 0.88, scaleX: 1.06 }, 0.40, EASE.inhale);

    F(N.tux, PUFF - 0.02, { rotation: -1.5 }, { rotation: 3.0 }, 0.20, EASE.strike);
    F(N.body, PUFF - 0.02, { scaleY: 1.012, scaleX: 0.994 }, { scaleY: 0.994, scaleX: 1.006 }, 0.22, EASE.strike);
    F(N.head, PUFF - 0.02, { rotation: -2.2, y: -1.5, scaleX: 1.015 }, { rotation: 3.2, y: 3.2, scaleX: 1 }, 0.18, EASE.strike);
    F(N.head, PUFF + 0.16, { rotation: 3.2, y: 3.2 }, { rotation: 3.9, y: 4.4 }, 0.16, 'power2Out');   // 减速尾
    F(N.beak, PUFF - 0.01, { scaleY: 0.88, scaleX: 1.06 }, { scaleY: 1.42, scaleX: 0.92 }, 0.12, EASE.strike);
    puff(beakAt.x + 30, beakAt.y + 6, 1, PUFF);
    [0.12, 0.32, 0.52].forEach(function (d, k) {
      F(N.head, PUFF + d, { y: 7 }, { y: 9 - k }, 0.10, E.sineInOut);
      T(N.head, PUFF + d + 0.10, { y: 7 }, 0.10, E.sineInOut);
      // 躯干跟着气流做三次小起伏（0.9u ≈ 1px 的剪影位移，足够让"停止"变成"余势"）
      F(N.tux, PUFF + d, { rotation: 3.0 - k * 0.2 }, { rotation: 2.2 - k * 0.2 }, 0.12, E.sineInOut);
      T(N.tux, PUFF + d + 0.12, { rotation: 3.0 - k * 0.2 }, 0.14, E.sineInOut);
    });

    EXT.forEach(function (te, i) {
      flameOff(i, te);
      tl.set(igniteHook, { v: 2 - i }, te + 0.02);
      F(N.head, te + 0.04, { y: 7, rotation: 6.0 }, { y: 8.6, rotation: 6.8 }, 0.055, 'power2.out');
      F(N.body, te + 0.04, { scaleY: 0.994 }, { scaleY: 0.990 }, 0.055, 'power2.out');
      T(N.head, te + 0.10, { y: 7, rotation: 6.0 }, 0.16, E.settle);
      T(N.body, te + 0.10, { scaleY: 0.994 }, 0.16, E.settle);
    });

    F(N.tux, EXT[2] + 0.14, { rotation: 3.0 }, { rotation: 0 }, 0.78, E.settle);
    F(N.body, EXT[2] + 0.12, { y: 0, scaleY: 0.994, scaleX: 1.006 }, { y: 0, scaleY: 1, scaleX: 1 }, 0.72, E.settle);
    F(N.head, EXT[2] + 0.18, { rotation: 6.0, y: 7, scaleX: 1 }, { rotation: 0.6, y: 0 }, 0.70, E.settle);
    F(N.beak, EXT[2] + 0.14, { scaleY: 1.70, scaleX: 0.86 }, { scaleY: 1, scaleX: 1 }, 0.50, E.settle);
    if (POMS.length) {
      F(POMS, EXT[2] + 0.20, { rotation: 0 }, { rotation: -2.6 }, 0.26, E.power2Out);
      T(POMS, EXT[2] + 0.46, { rotation: 0.8 }, 0.7, E.settle);
      T(POMS, EXT[2] + 1.16, { rotation: 0 }, 0.7, E.settle);
    }
    F(N.head, EXT[2] + 0.95, { rotation: 0.6, y: 0 }, { rotation: -1.4, y: -2.2 }, 1.2, E.drift);
    F(N.body, EXT[2] + 0.95, { scaleY: 1 }, { scaleY: 0.997 }, 1.2, E.drift);

    /* ══════════════════════════════════════════════════
       6. 黑场（31.35 → 33.45）：动作全停，只留极慢下沉
       ══════════════════════════════════════════════════ */
    const DARK = A.dark || 31.35;
    F(N.tux, DARK, { rotation: 0 }, { rotation: -0.35 }, 1.9, E.drift);
    F(N.body, DARK, { y: 0, scaleY: 0.997 }, { y: 2.6, scaleY: 0.994 }, 1.9, E.drift);
    F(N.head, DARK, { rotation: -1.4, y: -2.2 }, { rotation: 0.4, y: 1.4 }, 1.9, E.drift);
    eyelid(DARK - 0.35, 0.50, 0.90);
    if (N.blackout) {
      F(N.blackout, DARK + 0.05, { opacity: 0 }, { opacity: 0.55 }, 1.10, E.drift);
      T(N.blackout, 33.55, { opacity: 0.12 }, 0.90, E.drift);
      T(N.blackout, 35.20, { opacity: 0 }, 2.40, E.drift);
    }

    /* ══════════════════════════════════════════════════
       7. 烟花（CUES.act.boom）：抬头 6° + 视线追爆点 + 终场微震
       ══════════════════════════════════════════════════ */
    const BOOM = A.boom || 33.45;
    const LOOK = -7.0;                          // 上仰角（lead 要求 5–7°）
    F(N.tux, BOOM + 0.02, { rotation: -0.35 }, { rotation: -1.8 }, 0.90, E.arrive);
    F(N.body, BOOM + 0.02, { y: 2.6, scaleY: 0.994 }, { y: 0, scaleY: 1.004 }, 0.90, E.arrive);
    F(N.head, BOOM + 0.04, { rotation: 0.4, y: 1.4 }, { rotation: LOOK, y: -11 }, 0.88, E.arrive);
    F(N.hat, BOOM + 0.10, { rotation: 0 }, { rotation: -2.2 }, 0.90, E.settle);
    if (POMS.length) {
      F(POMS, BOOM + 0.17, { rotation: 0 }, { rotation: 3.2 }, 0.22, E.power2Out);
      T(POMS, BOOM + 0.39, { rotation: -1.1 }, 0.55, E.settle);
      T(POMS, BOOM + 0.94, { rotation: 0 }, 0.7, E.settle);
    }
    F(N.hp, BOOM + 0.14, { rotation: 0 }, { rotation: -1.6 }, 0.90, E.settle);
    F(N.flL, BOOM + 0.16, { rotation: 44 }, { rotation: 30 }, 0.80, E.settle);
    F(N.flR, BOOM + 0.22, { rotation: -42 }, { rotation: -30 }, 0.80, E.settle);
    // 眼睛放大 4%：看远处的亮点
    F(EYES, BOOM + 0.28, { scaleX: 1, scaleY: 1 }, { scaleX: 1.04, scaleY: 1.04 }, 0.45, E.settle);
    eyelid(BOOM + 0.02, 0.30, 0.18);      // 黑场里半合的眼里，第一发一响就张开
    eyelid(BOOM + 0.28, 1.00, 0.42);

    (function gazeDriver() {
      const shells = C.shells || [];
      const t0 = BOOM + 1.05, t1 = 45.50;
      const fin = shells.length ? shells[shells.length - 1] : null;
      const drv = { _p: 0 };
      function commit(t) {
        let hx = 0, hy = 0, hr = 0, eyeJump = 0;
        for (let i = 0; i < shells.length; i++) {
          const s = shells[i];
          const dt = t - (s.at + 0.10);
          if (dt <= 0) continue;
          const imp = Math.exp(-dt * 3.4) * (1 - Math.exp(-dt * 26));
          const jump = Math.exp(-dt * 9) * (1 - Math.exp(-dt * 55));   // 40–70ms 的跳格
          eyeJump += jump * 2.6;
          hx += imp * clamp((s.x - 760) / 160, -1, 1) * 5.5;
          hy += imp * (1 - clamp((s.y - 120) / 360, 0, 1)) * 2.6;
          hr += imp * clamp((s.x - 760) / 200, -1, 1) * 1.9;
        }
        const ft = fin ? t - fin.at : 9;
        const k = (ft > 0 && ft < 0.7) ? Math.exp(-ft * 7) : 0;
        if (N.body) gsap.set(N.body, { scaleY: 1.004 + 0.009 * k, scaleX: 1 - 0.006 * k });
        if (N.head) gsap.set(N.head, { rotation: LOOK + clamp(hr, -2.6, 2.6), x: clamp(hx, -4, 4), y: -11 + clamp(hy, 0, 2.6) });
        if (EYES.length) gsap.set(EYES, { y: -3 - clamp(eyeJump, 0, 3) });   // E1 追光 + 爆点跳格
        if (N.hat) gsap.set(N.hat, { rotation: -2.2 + clamp(hr, -2.6, 2.6) * 0.45 });
        if (N.hp) gsap.set(N.hp, { rotation: -1.6 + clamp(hr, -2.6, 2.6) * 0.30 });
      }
      Object.defineProperty(drv, 'p', {
        get() { return this._p; },
        set(v) { this._p = v; commit(t0 + v * (t1 - t0)); }
      });
      tl.fromTo(drv, { p: 0 }, { p: 1, duration: t1 - t0, ease: 'none', immediateRender: false }, t0);
    })();

    /* 镜头补点（rig.key）：吹灭前轻推 / 终场微震 ≤3px / 回位 */
    if (b.rig && typeof b.rig.key === 'function') {
      try {
        b.rig.key(26.55, { x: -1.5, y: 4, z: 0.146, dur: 0.55, ease: 'power2Out' });
        b.rig.key(41.02, { x: 2.0, y: -11.5, z: 0.012, dur: 0.11, ease: 'power2Out' });
        // 注意：这里不能再补"回位"key。CUES 的 bless 机位从 41.15 走到 44.60，
        // 任何 at∈(41.15,44.60] 的 key 会在 44.60 那一帧突然生效 → 整场景瞬移（QA 报的 44.70 急停）。
      } catch (e) { /* ignore */ }
    }

    /* ══════════════════════════════════════════════════
       8. 祝福（CUES.act.bless）
       ══════════════════════════════════════════════════ */
    const BLESS = A.bless || 41.95;
    N.lines.forEach(function (el, i) {
      // 首行 = 标题，单独加速：42.05 起 / 0.78s（其余行 42.65/43.00/43.32，0.32 错峰）。
      // 依据：art-director 判据"42.60 标题区灰度 max ≥150"；按 1.32s 走时只有 132（滑出遮罩不够）。
      // 现行值实测 42.60 max=246、亮像素 3625/3661（≈99%），43.10 max=250。
      // 起点 = act.bless + 0.10：终章爆点 41.96 之后 0.09s 才起，保住"光先到、字后到"。
      // 注意：不要改成 0.95s —— 那个版本 42.60 只有 max 200.7、42.35 只有 58（验收不通过的原因就写在这里）。
      const isTitle = (i === 0);
      const t = BLESS + (isTitle ? 0.10 : 0.38 + i * (0.32 + jit(0.016)));
      const dur = isTitle ? 0.78 : 1.05 + jit(0.05);
      F(el, t, { yPercent: 112, opacity: 0, filter: 'blur(6px)', rotation: 0.6 },
        { yPercent: 0, opacity: 1, filter: 'blur(0px)', rotation: 0 }, dur, E.arrive);
    });
    if (N.bloom) {
      const lastT = BLESS + 0.38 + 3 * (0.32 + 0.016);
      F(N.bloom, lastT + 0.50, { opacity: 0 }, { opacity: 0.34 }, 2.8, E.drift);
      // 收到 cue 内：最后一段在 CUES.dur 收住，不给时间线留尾巴
      const tail = Math.max(0.2, (C.dur || 48) - (lastT + 3.30));
      T(N.bloom, lastT + 3.30, { opacity: 0.18 }, tail, E.drift);
    }

    /* 结束前 2s：看向镜头（头部总转角 ≤4°） */
    const LOOKCAM = Math.max(BLESS + 2.6, (C.dur || 48) - 2.10);
    // 先把头从"看天"放下来一半（44.95），真正"看向镜头"的那一下只有 2.9°（≤4°）
    F(N.head, LOOKCAM - 0.95, { rotation: LOOK, x: 0, y: -11 }, { rotation: -3.4, x: 0, y: -6.0 }, 0.75, E.drift);
    F(N.head, LOOKCAM, { rotation: -3.4, x: 0, y: -6.0 }, { rotation: -0.5, x: 2.5, y: -1.2 }, 1.05, E.drift);
    F(EYES, LOOKCAM + 0.02, { y: -3 }, { y: 0 }, 1.0, E.drift);
    F(N.hp, LOOKCAM + 0.06, { rotation: -1.6 }, { rotation: 0 }, 1.00, E.drift);
    F(N.hat, LOOKCAM + 0.12, { rotation: -2.2 }, { rotation: 0.3 }, 1.05, E.drift);
    F(N.tux, LOOKCAM + 0.02, { rotation: -1.8 }, { rotation: -0.6 }, 1.20, E.drift);
    F(N.body, LOOKCAM + 0.04, { scaleY: 1.004, scaleX: 1 }, { scaleY: 1, scaleX: 1 }, 1.40, E.drift);

    /* ── 尾巴：总长 ≥ 48s ─────────────────────────────── */
    /* 时间线长度严格对齐 CUES.dur（≥48s）：不往后拖尾巴，避免和 CUES 对不上 */
    tl.to({}, { duration: 0.001, ease: 'none' }, Math.max(48.0, C.dur || 48) - 0.001);
  }

  window.Motion = {
    build,
    version: '1.1.0',
    debug: { travel, pathAt, walkPose, poseOf, WALK_D }
  };
})();
